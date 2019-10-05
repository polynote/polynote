import {UIEventTarget} from "../util/ui_event";
import {NotebookConfigUI} from "./nb_config";
import {div, span, TagElement} from "../util/tags";
import {Cell, CellContainer, CellExecutionFinished, CodeCell, TextCell} from "./cell";
import {TaskInfo, TaskStatus} from "../../data/messages";
import * as Tinycon from "tinycon";
import {storage} from "../util/storage";
import {clientInterpreters} from "../../interpreter/client_interpreter";
import * as monaco from "monaco-editor";
import {PosRange} from "../../data/result";
import {NotebookUI} from "./notebook";
import {CurrentNotebook} from "./current_notebook";

type NotebookCellsEl = TagElement<"div"> & { cellsUI: NotebookCellsUI }

export class NotebookCellsUI extends UIEventTarget {
    private disabled: boolean;
    readonly configUI: NotebookConfigUI;
    readonly el: NotebookCellsEl;
    private queuedCells: number;
    resizeTimeout: number;
    readonly notebookUI: NotebookUI;
    private configEl: TagElement<"div">;

    constructor(parent: NotebookUI, readonly path: string) {
        super(parent);
        this.notebookUI = parent; // TODO: get rid of this
        this.disabled = false;
        this.configUI = new NotebookConfigUI().setEventParent(this);
        this.el = Object.assign(
            div(['notebook-cells'], [this.configEl = this.configUI.el, this.newCellDivider()]),
            // TODO: remove when we get to TabUI
            { cellsUI: this });  // TODO: this is hacky and bad (used for getting to this instance via the element, from the tab content area of MainUI#currentNotebook)
        this.queuedCells = 0;

        this.registerEventListener('resize', this.forceLayout.bind(this));
    }

    newCellDivider() {
        return div(['new-cell-divider'], []).click((evt) => {
            const self = evt.target as TagElement<"div">;
            const prevCell = this.getCellBeforeEl(self);
            CurrentNotebook.current.insertCell("below", prevCell && prevCell.id)
        });
    }

    setDisabled(disabled: boolean) {
        if (disabled === this.disabled) {
            return;
        }
        this.disabled = disabled;
        this.forEachCell(cell => cell.setDisabled(disabled));
    }

    setStatus(id: number, status: TaskInfo) {
        const cell = this.getCell(id);
        if (!cell) return;

        switch (status.status) {
            case TaskStatus.Complete:
                dispatchEvent(new CellExecutionFinished(cell.id));
                cell.container.classList.remove('running', 'queued', 'error');
                this.queuedCells -= 1;
                break;

            case TaskStatus.Error:
                cell.container.classList.remove('queued', 'running');
                cell.container.classList.add('error');
                this.queuedCells -= 1;
                break;

            case TaskStatus.Queued:
                cell.container.classList.remove('running', 'error');
                cell.container.classList.add('queued');
                this.queuedCells += 1;
                break;

            case TaskStatus.Running:
                cell.container.classList.remove('queued', 'error');
                cell.container.classList.add('running');
                const progressBar = cell.container.querySelector('.progress-bar');
                if (progressBar instanceof HTMLElement && status.progress) {
                    progressBar.style.width = (status.progress * 100 / 255).toFixed(0) + "%";
                }


        }
        if (this.queuedCells <= 0) {
            this.queuedCells = 0;
            Tinycon.setBubble(this.queuedCells);
            Tinycon.reset();
        } else {
            Tinycon.setBubble(this.queuedCells);
        }
    }

    setExecutionHighlight(id: number, pos: PosRange | null) {
        const cell = this.getCell(id);
        if (cell instanceof CodeCell) {
            cell.setHighlight(pos, "currently-executing");
        }
    }

    iterCellsForwardWhile(cond: (el: HTMLElement) => boolean): Cell | undefined {
        let before = this.el.firstElementChild as CellContainer;
        let cell = undefined;
        while (before && (cond(before) || cell === undefined)) {
            if (before && before.cell) {
                cell = before.cell;
            }
            before = before.nextElementSibling as CellContainer;
        }
        return cell
    }

    iterCellsBackwardWhile(cond: (el: HTMLElement) => boolean): Cell | undefined {
        let after = this.el.lastElementChild as CellContainer;
        let cell = undefined;
        while (after && (cond(after) || cell === undefined)) {
            if (after && after.cell) {
                cell = after.cell;
            }
            after = after.previousElementSibling as CellContainer;
        }
        return cell
    }

    forEachCell(cb: (cell: Cell) => void): void {
        this.iterCellsForwardWhile(el => {
            const cc = el as CellContainer;
            if (cc && cc.cell) cb(cc.cell);
            return true
        })
    }

    getCell(cellId: number): Cell | undefined {
        return this.iterCellsForwardWhile(before => {
            if (before) {
                const cc = before as CellContainer;
                return cc.cell && cc.cell.id === cellId;
            } else return false;
        });
    }

    getCellBeforeEl(el: HTMLElement): Cell | undefined {
        return this.iterCellsForwardWhile(before => before !== el);
    }

    getCellBefore(cell: Cell): Cell | undefined {
        return this.getCellBeforeEl(cell.container)
    }

    getCellAfterEl(el: HTMLElement): Cell | undefined {
        return this.iterCellsBackwardWhile(after => after !== el);
    }

    getCellAfter(cell: Cell): Cell | undefined {
        return this.getCellAfterEl(cell.container);
    }

    getCells(): Cell[] {
        const cells: Cell[] = [];
        this.forEachCell(cell => cells.push(cell));
        return cells;
    }

    getCodeCellIds() {
        return this.getCells().filter(cell => cell instanceof CodeCell).map(cell => cell.id);
    }

    getCodeCellIdsBefore(id: number): number[] {
        const ids: number[] = [];
        this.iterCellsForwardWhile(el => {
            const cc = el as CellContainer;
            if (cc && cc.cell) {
                if (cc.cell.id === id) {
                    return false;
                } else if (cc.cell instanceof CodeCell) {
                    ids.push(cc.cell.id);
                }
            }
            return true
        });

        return ids;
    }

    getMaxCellId() {
        return this.getCells().reduce((acc, cell) => {
            const id = cell.id;
            return acc > id ? acc : id
        }, -1)
    }

    insertCellAbove(el?: HTMLElement, cell?: (nextCellId: number) => Cell): Cell {
        const newCellId = this.getMaxCellId() + 1;
        const mkCell = (oldCell?: Cell) => {
            if (oldCell instanceof CodeCell) {
                return new CodeCell(newCellId, '', oldCell.language, this.path)
            } else if (oldCell instanceof TextCell) {
                return new TextCell(newCellId, '', this.path);
            } else {
                return new CodeCell(newCellId, '', 'scala', this.path) // default new cells are scala cells
            }
        };

        const prev = el && this.getCellBeforeEl(el);
        const newCell = (cell && cell(newCellId)) || mkCell(prev);

        const anchorEl = (prev ? prev.container : this.configEl).nextElementSibling;
        this.el.insertBefore(newCell.container, anchorEl);
        this.el.insertBefore(this.newCellDivider(), newCell.container);

        this.setupCell(newCell);
        return newCell;
    }

    insertCellBelow(el?: HTMLElement, mkCell?: (nextCellId: number) => Cell): Cell {
        const nextCell = el && this.getCellAfterEl(el);
        if (nextCell) {
            return this.insertCellAbove(nextCell.container, mkCell)
        } else {  // if there are no cells under here, we need to insert above the last divider
            return this.insertCellAbove(this.el.lastElementChild as HTMLElement, mkCell)
        }
    }

    deleteCell(cellId: number, cb?: () => void) {
        const cellToDelete = this.getCell(cellId);
        if (cellToDelete) {
            const prevCell = this.getCellBefore(cellToDelete);
            const nextCell = this.getCellAfter(cellToDelete);

            const anchorEl = prevCell && prevCell.container || this.configEl; // if there is no prevCell to be found, anchor by anchorEl;
            const undoEl = div(['undo-delete'], [
                span(['close-button', 'fa'], ['']).click(evt => {
                    undoEl.parentNode!.removeChild(undoEl);
                    cellToDelete.container.innerHTML = ''
                }),
                span(['undo-message'], [
                    'Cell deleted. ',
                    span(['undo-link'], ['Undo']).click(evt => {

                        this.insertCellBelow(anchorEl, () => cellToDelete);
                        undoEl.parentNode!.removeChild(undoEl);
                    })
                ])
            ]);

            if (nextCell) {
                nextCell.focus();
            } else if (prevCell) {
                prevCell.focus();
            }

            const divider = cellToDelete.container.previousElementSibling;
            this.el.insertBefore(undoEl, cellToDelete.container);
            this.el.removeChild(cellToDelete.container);
            if (divider) {
                this.el.removeChild(divider);
            } else {
                throw new Error(`Couldn't find divider after ${cellId} !`) // why wasn't the divider there??
            }

            cellToDelete.dispose();

            if (cb) cb()
        }
    }

    forceLayout(evt: Event) {
        if (this.resizeTimeout) {
            window.clearTimeout(this.resizeTimeout);
        }
        this.resizeTimeout = window.setTimeout(() => {
            this.getCells().forEach((cell) => {
                if (cell instanceof CodeCell) {
                    cell.editor.layout();
                }
            });
            // scroll to previous position, if any
            const scrollPosition = storage.get('notebookLocations')[this.path];
            if (this.el.parentElement && (scrollPosition || scrollPosition === 0)) {
                this.el.parentElement.scrollTop = scrollPosition;
            }
        }, 333);
    }

    setupCell(cell: Cell) {
        if (cell instanceof CodeCell && cell.editor && cell.editor.layout) {
            cell.editor.layout();
        }
        cell.setEventParent(this);
    }

    setCellLanguage(cell: Cell, language: string) {
        const currentCell = this.getCell(cell.id);
        if (cell !== currentCell) {
            throw {message: "Cell with that ID is not the same cell as the target cell"};
        }


        if (currentCell.language === language)
            return;

        // TODO: should cell-specific logic be moved into the cell itself?
        if (currentCell instanceof TextCell && language !== 'text') {
            // replace text cell with a code cell
            const textContent = currentCell.container.innerText.trim(); // innerText has just the plain text without any HTML formatting
            const newCell = new CodeCell(currentCell.id, currentCell.content, language, this.path, currentCell.metadata);
            this.el.replaceChild(newCell.container, currentCell.container);
            currentCell.dispose();
            this.setupCell(newCell);
            newCell.editor.getModel()!.setValue(textContent); // use setValue in order to properly persist the change.
            const clientInterpreter = clientInterpreters[language];
            if (clientInterpreter && clientInterpreter.highlightLanguage && clientInterpreter.highlightLanguage !== language) {
                monaco.editor.setModelLanguage(newCell.editor.getModel()!, clientInterpreter.highlightLanguage)
            }

            newCell.focus();
        } else if (currentCell instanceof CodeCell && language === 'text') {
            // replace code cell with a text cell
            const newCell = new TextCell(currentCell.id, currentCell.content, this.path, currentCell.metadata);
            this.el.replaceChild(newCell.container, currentCell.container);
            currentCell.dispose();
            this.setupCell(newCell);
            newCell.focus();
        } else {
            // already a code cell, change the language
            const highlightLanguage = (clientInterpreters[language] && clientInterpreters[language].highlightLanguage) || language;
            monaco.editor.setModelLanguage((currentCell as CodeCell).editor.getModel()!, highlightLanguage);
            currentCell.setLanguage(language);
        }
    }
}