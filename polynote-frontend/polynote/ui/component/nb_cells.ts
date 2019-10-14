import {UIMessageTarget} from "../util/ui_event";
import {NotebookConfigUI} from "./nb_config";
import {div, span, TagElement} from "../util/tags";
import {Cell, CellContainer, CodeCell, isCellContainer, TextCell} from "./cell";
import {TaskInfo, TaskStatus} from "../../data/messages";
import {storage} from "../util/storage";
import {clientInterpreters} from "../../interpreter/client_interpreter";
import * as monaco from "monaco-editor";
import {PosRange} from "../../data/result";
import {NotebookUI} from "./notebook";
import {CurrentNotebook} from "./current_notebook";
import {NotebookConfig} from "../../data/data";

type NotebookCellsEl = TagElement<"div"> & { cellsUI: NotebookCellsUI }

export class NotebookCellsUI extends UIMessageTarget {
    private disabled: boolean;
    readonly configUI: NotebookConfigUI;
    readonly el: NotebookCellsEl;
    resizeTimeout: number;
    readonly notebookUI: NotebookUI;
    private configEl: TagElement<"div">;

    constructor(parent: NotebookUI, readonly path: string) {
        super(parent);
        this.notebookUI = parent; // TODO: get rid of this
        this.disabled = false;
        this.configUI = new NotebookConfigUI((conf: NotebookConfig) => CurrentNotebook.get.updateConfig(conf)).setParent(this);
        this.el = Object.assign(
            div(['notebook-cells'], [this.configEl = this.configUI.el, this.newCellDivider()]),
            // TODO: remove when we get to TabUI
            { cellsUI: this });  // TODO: this is hacky and bad (used for getting to this instance via the element, from the tab content area of MainUI#currentNotebook)

        window.addEventListener('resize', this.forceLayout.bind(this));
    }

    newCellDivider() {
        return div(['new-cell-divider'], []).click((evt) => {
            const self = evt.target as TagElement<"div">;
            const prevCell = this.getCellBeforeEl(self);
            if (prevCell) {
                CurrentNotebook.get.insertCell("below", prevCell.id)
            } else {
                CurrentNotebook.get.insertCell("above") // no cell above this one, so it must be at the top of the file
            }
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
        if (cell instanceof CodeCell) {
            switch (status.status) {
                case TaskStatus.Complete:
                    cell.setStatus("complete");
                    break;

                case TaskStatus.Error:
                    cell.setStatus("error");
                    break;

                case TaskStatus.Queued:
                    cell.setStatus("queued");
                    break;

                case TaskStatus.Running:
                    cell.setStatus("running");
                    break;
            }
        }
    }

    setExecutionHighlight(id: number, pos: PosRange | null) {
        const cell = this.getCell(id);
        if (cell instanceof CodeCell) {
            cell.setHighlight(pos, "currently-executing");
        }
    }

    /**
     * Iterate through cells while `cond` is true.
     *
     * @param initial   The initial element value to start with.
     * @param nextCell  How to get to the next cell or element given the previous one.
     * @param cond      Whether to continue iteration based on the current selected element.
     */
    iterCellsWhile(initial: Element | null, nextCell: (el: Element) => Element | null, cond: (el: Element) => boolean): void {
        let current = initial;
        while (current && cond(current)) {
            current = nextCell(current);
        }
    }

    iterCellsForwardWhile(cond: (el: Element) => boolean): void {
        const first = this.el.firstElementChild;
        this.iterCellsWhile(first, (el: Element) => el.nextElementSibling, cond);
    }

    forEachCell(cb: (cell: Cell) => void): void {
        this.iterCellsForwardWhile(el => {
            const cc = el as CellContainer;
            if (cc && cc.cell) cb(cc.cell);
            return true
        })
    }

    getCell(cellId: number): Cell | undefined {
        let cell = undefined;
        this.iterCellsForwardWhile(before => {
            const cc = before as CellContainer;
            if (cc && cc.cell && cc.cell.id === cellId) {
                cell = cc.cell;
                return false;
            } else return true;
        });
        return cell;
    }

    getCellBeforeEl(el: HTMLElement): Cell | undefined {
        let cell = undefined;
        this.iterCellsWhile(el.previousElementSibling, (elem: Element) => elem.previousElementSibling, elem => {
            if (isCellContainer(elem)) {
                cell = elem.cell;
                return false
            } else return true
        });
        return cell;
    }

    getCellBefore(cell: Cell): Cell | undefined {
        return this.getCellBeforeEl(cell.container)
    }

    getCellAfterEl(el: HTMLElement): Cell | undefined {
        let cell = undefined;
        this.iterCellsWhile(el.nextElementSibling, elem => elem.nextElementSibling, elem => {
            if (isCellContainer(elem)) {
                cell = elem.cell;
                return false
            } else return true
        });
        return cell;
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
            } else {
                return new CodeCell(newCellId, '', 'scala', this.path) // default new cells are scala cells
            }
        };

        const prev = el && this.getCellBeforeEl(el);
        const newCell = (cell && cell(newCellId)) || mkCell(prev);

        // it'd be nice if we could use some simpler logic here :\
        // most of this logic is to ensure that there's always a new cell divider in between each cell, above the first cell, and below the last cell.
        let anchorEl;
        if (prev) {
            anchorEl = prev.container.nextElementSibling; // we insert between the prev cell and its divider
        } else if (el && el !== this.configEl) {
            if (isCellContainer(el)) {
                anchorEl = el.nextElementSibling // if el is a cell, we insert between it and its divider.
            } else {
                anchorEl = el // if el is not a cell, it must be a divider, in which case we just want to insert above it
            }
        } else {
            anchorEl = this.configEl.nextElementSibling // if there's nothing, we insert between the config element and its divider
        }
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

            const undoEl = div(['undo-delete'], [
                span(['close-button', 'fa'], ['']).click(evt => {
                    undoEl.parentNode!.removeChild(undoEl);

                    // don't actually get rid of the cell from the UI while we still might undo it.
                    cellToDelete.dispose();
                    cellToDelete.container.innerHTML = ''
                }),
                span(['undo-message'], [
                    'Cell deleted. ',
                    span(['undo-link'], ['Undo']).click(evt => {
                        const mkCell = (nextCellId: number) => {
                            return cellToDelete.language !== "text"
                                ? new CodeCell(nextCellId, cellToDelete.content, cellToDelete.language, this.path)
                                : new TextCell(nextCellId, cellToDelete.content, this.path);
                        };

                        const prevCell = this.getCellBeforeEl(undoEl);
                        if (prevCell) {
                            CurrentNotebook.get.insertCell("below", prevCell.id, mkCell);
                        } else {
                            CurrentNotebook.get.insertCell("above", undefined, mkCell)
                        }
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

            if (cb) cb()
        }
    }

    forceLayout() {
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
        cell.setParent(this);
    }

    setCellLanguage(cell: Cell, language: string) {
        const currentCell = this.getCell(cell.id);
        if (cell !== currentCell) {
            throw {message: "Cell with that ID is not the same cell as the target cell"};
        }


        if (currentCell.language === language) {
            currentCell.focus();
            return;
        }

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
            currentCell.focus();
        }
    }
}