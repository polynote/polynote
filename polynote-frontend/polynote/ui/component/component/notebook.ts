import {div, icon, span, TagElement} from "../../util/tags";
import {CreateCell, NotebookMessageDispatcher, SetCellHighlight, SetSelectedCell} from "../messaging/dispatcher";
import {CellState, NotebookStateHandler} from "../state/notebook_state";
import {StateHandler} from "../state/state_handler";
import {CellMetadata} from "../../../data/data";
import {diffArray} from "../../../util/functions";
import {CellContainerComponent} from "./cell";
import {NotebookConfigComponent} from "./notebookconfig";
import {VimStatus} from "./vim_status";
import {Position} from "monaco-editor";
import {PosRange} from "../../../data/result";

export class Notebook {
    readonly el: TagElement<"div">;
    readonly cells: Record<number, {cell: CellContainerComponent, handler: StateHandler<CellState>, el: TagElement<"div">}> = {};
    cellOrder: Record<number, number> = {}; // index => cell id;
    // private pendingHint?: {
    //     type: "completion" | "signature",
    //     resolve: (hint: CompletionList | (SignatureHelp | undefined)) => void,
    //     reject: () => void };

    constructor(private dispatcher: NotebookMessageDispatcher, private notebookState: NotebookStateHandler) {
        const config = new NotebookConfigComponent(dispatcher, notebookState.view("config"), notebookState.view("kernel").view("status"));
        const cellsEl = div(['notebook-cells'], [config.el, this.newCellDivider()]);
        this.el = div(['notebook-content'], [cellsEl, VimStatus.get.el]);

        // we use views to watch for state changes we care about
        notebookState.view("cells").addObserver((newCells, oldCells) => {
            const [removed, added] = diffArray(oldCells, newCells, (o, n) => o.id === n.id);

            added.forEach(state => {
                const handler = new StateHandler(state);
                const cell = new CellContainerComponent(dispatcher, handler, notebookState.getState().path);
                this.cells[state.id] = {cell, handler, el: div(['cell-and-divider'], [cell.el, this.newCellDivider()])}
            });
            removed.forEach(cell => {
                this.cells[cell.id].cell.delete();
                const cellEl = this.cells[cell.id].el!;

                const prevCellId = this.getPreviousCellId(cell.id) ?? -1
                const undoEl = div(['undo-delete'], [
                    icon(['close-button'], 'times', 'close icon').click(evt => {
                        undoEl.parentNode!.removeChild(undoEl)
                    }),
                    span(['undo-message'], [
                        'Cell deleted. ',
                        span(['undo-link'], ['Undo']).click(evt => {
                            this.insertCell(prevCellId, cell.language, cell.content, cell.metadata)
                            undoEl.parentNode!.removeChild(undoEl);
                        })
                    ])
                ])

                cellEl.replaceWith(undoEl)
                delete this.cells[cell.id];

                // clean up cell order
                const deletedIdx = this.getCellIndex(cell.id)
                if (deletedIdx !== undefined) {
                    let idx = deletedIdx;
                    let nextId = this.cellOrder[idx + 1];
                    while (nextId !== undefined) {
                        this.cellOrder[idx] = nextId;
                        idx += 1;
                        nextId = this.cellOrder[idx + 1];
                    }
                    if (idx === Object.entries(this.cellOrder).length) {
                        delete this.cellOrder[idx]
                    }
                }
            });

            newCells.forEach((cell, idx) => {
                const cellEl = this.cells[cell.id].el;
                const cellIdAtIdx = this.cellOrder[idx];
                if (cellIdAtIdx !== undefined) {
                    if (cellIdAtIdx !== cell.id) {
                        // there's a different cell at this index. we need to insert this cell above the existing cell
                        const prevCellEl = this.cells[cellIdAtIdx].el;
                        // note that inserting a node that is already in the DOM will move it from its current location to here.
                        cellsEl.insertBefore(cellEl, prevCellEl);
                        this.cellOrder[idx] = cell.id;
                        this.cellOrder[idx + 1] = cellIdAtIdx;
                    }
                } else {
                    // index not found, must be at the end
                    this.cellOrder[idx] = cell.id;
                    cellsEl.appendChild(cellEl);
                }
                this.cells[cell.id].handler.updateState(() => cell);
            })
            this.cellOrder = newCells.reduce<Record<number, number>>((acc, next, idx) => {
                acc[idx] = next.id
                return acc
            }, {})
        });

        console.log("initial active cell ", this.notebookState.getState().activeCell)
        this.notebookState.view("activeCell").addObserver(cell => {
            console.log("activeCell = ", cell)
            if (cell === undefined) {
                VimStatus.get.hide()
            }
        })

        // select cell + highlight based on the current hash
        const hash = document.location.hash;
        // the hash can (potentially) have two parts: the selected cell and selected position.
        // for example: #Cell2,6-12 would mean Cell2, positions at offset 6 to 12
        const [hashId, pos] = hash.slice(1).split(",");
        const cellId = parseInt(hashId.slice("Cell".length))
        // cell might not yet be loaded, so be sure to wait for it
        this.waitForCell(cellId).then(() => {
            this.dispatcher.dispatch(new SetSelectedCell(cellId))

            if (pos) {
                const pr = PosRange.fromString(pos)
                this.dispatcher.dispatch(new SetCellHighlight(cellId, pr, "link-highlight"))
            }
        })
    }

    /**
     * Create a cell divider that inserts new cells at a given position
     */
    private newCellDivider() {
        return div(['new-cell-divider'], []).click((evt) => {
            const self = evt.target as TagElement<"div">;
            const prevCell = Object.values(this.cells).reduce((acc: CellState, next) => {
                if (self.previousElementSibling === next.cell.el) {
                    acc = next.handler.getState()
                }
                return acc;
            }, undefined);
            this.insertCell(prevCell?.id ?? -1, prevCell?.language ?? 'scala', '');
        });
    }

    private insertCell(prev: number, language: string, content: string, metadata?: CellMetadata) {
        this.dispatcher.insertCell("below", {id: prev, language, content, metadata: metadata ?? new CellMetadata()})
            .then(newCellId => {
                this.dispatcher.dispatch(new SetSelectedCell(newCellId))
            })
    }

    /**
     * Get the ordering index of the cell with the provided id.
     */
    private getCellIndex(cellId: number): number | undefined {
        const anchorIdxStr = Object.entries(this.cellOrder).find(([idx, id]) => id === cellId)?.[0];
        return anchorIdxStr ? parseInt(anchorIdxStr) : undefined
    }

    /**
     * Get the cell above the one with the provided id
     */
    private getPreviousCellId(anchorId: number): number | undefined {
        const anchorIdx = this.getCellIndex(anchorId)
        return anchorIdx ? this.cellOrder[anchorIdx - 1] : undefined
    }

    /**
     * Get the cell below the one with the provided id
     */
    private getNextCellId(anchorId: number): number | undefined {
        const anchorIdx = this.getCellIndex(anchorId)
        return anchorIdx ? this.cellOrder[anchorIdx + 1] : undefined
    }

    /**
     * Wait for a specific cell to be loaded. Since we load cells lazily, we might get actions for certain cells
     * (e.g., highlighting them) before they have been loaded by the page.
     *
     * Resolves during the next animation frame, which ensures that the cell has been painted, etc.
     *
     * @returns the id of the cell, useful if you pass this Promise somewhere else.
     */
    private waitForCell(cellId: number): Promise<number> {
        return new Promise(resolve => {
            const wait = this.notebookState.addObserver(state => {
                if (state.cells.find(cell => cell.id === cellId)) {
                    this.notebookState.removeObserver(wait)
                    requestAnimationFrame(() => {
                        resolve(cellId)
                    })
                }
            })
        })
    }

    dispose() {
        this.notebookState.clearObservers();
    }
}

