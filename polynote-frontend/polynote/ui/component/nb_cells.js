// BREAKOUT (nb_cells.js)
import {UIEvent, UIEventTarget} from "../util/ui_event";
import {NotebookConfigUI} from "./nb_config";
import {div} from "../util/tags";
import {Cell, CellExecutionFinished, CodeCell, TextCell} from "./cell";
import {TaskStatus} from "../../data/messages";
import * as Tinycon from "tinycon";
import {storage} from "../util/storage";
import {clientInterpreters} from "../../interpreter/client_interpreter";
import * as monaco from "monaco-editor";

export class NotebookCellsUI extends UIEventTarget {
    constructor(eventParent, path) {
        super(eventParent);
        this.disabled = false;
        this.configUI = new NotebookConfigUI().setEventParent(this);
        this.path = path;
        this.el = div(['notebook-cells'], [this.configUI.el, this.newCellDivider()]);
        // TODO: remove when we get to TabUI
        this.el.cellsUI = this;  // TODO: this is hacky and bad (using for getting to this instance via the element, from the tab content area of MainUI#currentNotebook)
        this.cells = {};
        this.cellCount = 0;
        this.queuedCells = 0;

        this.registerEventListener('resize', this.forceLayout.bind(this));
    }

    newCellDivider() {
        return div(['new-cell-divider'], []).click((evt) => {
            const nextCell = this.getCellAfterEl(evt.target);
            if (nextCell) {
                this.dispatchEvent(new UIEvent('InsertCellBefore', {cellId: nextCell.id}));
            } else { // last cell
                this.dispatchEvent(new UIEvent('InsertCellAfter', {cellId: this.getCellBeforeEl(evt.target).id}));
            }
        });
    }

    setDisabled(disabled) {
        if (disabled === this.disabled) {
            return;
        }
        this.disabled = disabled;

        for (let cellId in this.cells) {
            if (this.cells.hasOwnProperty(cellId)) {
                const cell = this.cells[cellId];
                if (cell instanceof Cell) {
                    cell.setDisabled(disabled);
                }
            }
        }
    }

    setStatus(id, status) {
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
                if (progressBar && status.progress) {
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

    setExecutionHighlight(id, pos) {
        const cell = this.getCell(id);
        if (cell instanceof CodeCell) {
            cell.setHighlight(pos, "currently-executing");
        }
    }

    firstCell() {
        return this.getCells()[0];
    }

    getCell(cellId) {
        return this.cells[cellId];
    }

    getCellBeforeEl(el) {
        let before = this.el.firstElementChild;
        let cell = undefined;
        while (before !== el) {
            if (before && before.cell) {
                cell = before.cell;
            }
            before = before.nextElementSibling;
        }
        return cell
    }

    getCellAfterEl(el) {
        let after = this.el.lastElementChild;
        let cell = undefined;
        while (after !== el) {
            if (after && after.cell) {
                cell = after.cell;
            }
            after = after.previousElementSibling;
        }
        return cell
    }

    getCells() {
        return Array.from(this.el.children)
            .map(container => container.cell)
            .filter(cell => cell);
    }

    getCodeCellIds() {
        return this.getCells().filter(cell => cell instanceof CodeCell).map(cell => cell.id);
    }

    getCodeCellIdsBefore(id) {
        const result = [];
        let child = this.el.firstElementChild;
        while (child && (!child.cell || child.cell.id !== id)) {
            if (child.cell) {
                result.push(child.cell.id);
            }
            child = child.nextElementSibling;
        }
        return result;
    }

    getMaxCellId() {
        return this.getCells().reduce((acc, cell) => {
            const id = cell.id;
            return acc > id ? acc : id
        }, -1)
    }

    forceLayout(evt) {
        if (this.resizeTimeout) {
            clearTimeout(this.resizeTimeout);
        }
        this.resizeTimeout = setTimeout(() => {
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

    addCell(cell) {
        this.cellCount++;
        this.el.appendChild(cell.container);
        this.el.appendChild(this.newCellDivider());
        this.setupCell(cell);
    }

    insertCell(cell, after) {
        let prevCell = after;
        if (after && after instanceof Cell) {
            prevCell = after.container;
        } else if ((after || after === 0) && this.getCell(after)) {
            prevCell = this.getCell(after).container;
        } else if (!after) {
            prevCell = this.configUI.el;
        }

        this.cellCount++;

        const prevCellDivider = prevCell.nextElementSibling;

        const newDivider = this.newCellDivider();
        this.el.insertBefore(cell.container, prevCellDivider);
        this.el.insertBefore(newDivider, cell.container);

        this.setupCell(cell);
    }

    removeCell(cellId) {
        const cell = this.getCell(cellId);
        if (cell) {
            const divider = cell.container.nextElementSibling;
            this.el.removeChild(cell.container);
            if (divider) {
                this.el.removeChild(divider);
            } else {
                throw ["couldn't find divider after", cell.container] // why wasn't the divider there??
            }
            delete this.cells[cellId];
            cell.dispose();
            cell.container.innerHTML = '';
        }
    }

    setupCell(cell) {
        this.cells[cell.id] = cell;
        if (cell.editor && cell.editor.layout) {
            cell.editor.layout();
        }
        cell.setEventParent(this);

        cell.nextCell = () => {
            return this.getCellAfterEl(cell.container);
        };

        cell.prevCell = () => {
            return this.getCellBeforeEl(cell.container);
        }
    }

    setCellLanguage(cell, language) {
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
            const newCell = new CodeCell(currentCell.id, currentCell.content, language, this.path);
            this.el.replaceChild(newCell.container, currentCell.container);
            currentCell.dispose();
            this.setupCell(newCell);
            newCell.editor.getModel().setValue(textContent); // use setValue in order to properly persist the change.
            const clientInterpreter = clientInterpreters[language];
            if (clientInterpreter && clientInterpreter.highlightLanguage && clientInterpreter.highlightLanguage !== language) {
                monaco.editor.setModelLanguage(newCell.editor.getModel(), clientInterpreter.highlightLanguage)
            }

            newCell.focus();
        } else if (currentCell instanceof CodeCell && language === 'text') {
            // replace code cell with a text cell
            const newCell = new TextCell(currentCell.id, currentCell.content, this.path);
            this.el.replaceChild(newCell.container, currentCell.container);
            currentCell.dispose();
            this.setupCell(newCell);
            newCell.focus();
        } else {
            // already a code cell, change the language
            const highlightLanguage = (clientInterpreters[language] && clientInterpreters[language].highlightLanguage) || language;
            monaco.editor.setModelLanguage(currentCell.editor.getModel(), highlightLanguage);
            currentCell.setLanguage(language);
        }
    }
}