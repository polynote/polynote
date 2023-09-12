import {div, icon, span, TagElement} from "../../tags";
import {NotebookMessageDispatcher} from "../../../messaging/dispatcher";
import {Disposable, IDisposable, MoveArrayValue, NoUpdate, setValue, StateHandler, UpdateResult} from "../../../state";
import {CellMetadata} from "../../../data/data";
import {CellContainer} from "./cell";
import {NotebookConfigEl} from "./notebookconfig";
import {VimStatus} from "./vim_status";
import {PosRange} from "../../../data/result";
import {CellState, NotebookStateHandler} from "../../../state/notebook_state";
import {NotebookScrollLocationsHandler} from "../../../state/preferences";
import {ServerStateHandler} from "../../../state/server_state";
import {Main} from "../../../main";
import {cellIdFromHash} from "./common";

type CellInfo = {cell: CellContainer, handler: StateHandler<CellState>, el: TagElement<"div">};

export class Notebook extends Disposable {
    readonly el: TagElement<"div">;
    readonly cells: Record<number, CellInfo> = {};

    constructor(private dispatcher: NotebookMessageDispatcher, private notebookState: NotebookStateHandler) {
        super()
        const path = notebookState.state.path;
        const config = new NotebookConfigEl(dispatcher, notebookState.lens("config"), notebookState.view("kernel").view("status"));
        const cellsEl = div(['notebook-cells'], [config.el, this.newCellDivider()]);

        cellsEl.addEventListener('scroll', evt => {
            NotebookScrollLocationsHandler.updateField(path, () => cellsEl.scrollTop);
        });

        this.el = div(['notebook-content'], [cellsEl]);

        let needLayout = true;
        const layoutCells = () => {
            if (!needLayout)
                return;
            // console.time("Layout cells")
            const cells = Object.values(this.cells);
            const layoutCell = cellsEl.querySelector('.code-cell .cell-input-editor');
            if (cells.length) {
                const width = layoutCell?.clientWidth || cells[0].cell.cell.editorEl.clientWidth;
                const didLayoutCells = cells.map(cellInfo => {
                    return cellInfo.cell.layout(width)
                });
                needLayout = !didLayoutCells.every(x => x);
            }
            // console.timeEnd("Layout cells")
        }

        const handleVisibility = (currentNotebook: string | undefined) => {
            if (currentNotebook === path) {
                // when this notebook becomes visible, scroll to the saved location (if present)
                const maybeScrollLocation = NotebookScrollLocationsHandler.state[path]
                if (maybeScrollLocation !== undefined) {
                    cellsEl.scrollTop = maybeScrollLocation
                }

                notebookState.loaded.then(() => layoutCells())
            } else {
                // deselect cells.
                this.notebookState.selectCell(undefined)
            }
        }
        handleVisibility(ServerStateHandler.state.currentNotebook)
        ServerStateHandler.view("currentNotebook").addObserver(handleVisibility).disposeWith(notebookState)

        const cellsHandler = notebookState.cellsHandler

        const handleAddedCells = (added: Partial<Record<number, CellState>>, cellOrderUpdate: UpdateResult<number[]>) => {

            // if no cells exist yet, we'll need to do an initial layout after adding the cells.
            const layoutAllCells = Object.keys(this.cells).length === 0;

            // layout each new cell because we're no longer initializing.
            const layoutNewCells = !layoutAllCells && cellsEl.isConnected; // if cellsEl is in the DOM it means we're done initializing.

            Object.entries(cellOrderUpdate.addedValues!).forEach(([idx, id]) => {
                const handler = cellsHandler.lens(id)
                const cell = new CellContainer(this.newCellDivider(), dispatcher, notebookState, handler, layoutNewCells);
                const el = cell.el;
                this.cells[id] = {cell, handler, el};
                const cellIdx = parseInt(idx);
                const nextCellIdAtIdx = cellOrderUpdate.newValue[cellIdx + 1];
                if (nextCellIdAtIdx !== undefined && this.cells[nextCellIdAtIdx] !== undefined) {
                    // there's a different cell at this index. we need to insert this cell above the existing cell
                    const nextCellEl = this.cells[nextCellIdAtIdx].el;
                    // note that inserting a node that is already in the DOM will move it from its current location to here.
                    cellsEl.insertBefore(el, nextCellEl);
                } else {
                    // index not found, must be at the end
                    cellsEl.appendChild(el);
                }

                if (layoutNewCells) {
                    cell.layout()
                }
            })

            if (layoutAllCells) {
                needLayout = true;
                window.requestAnimationFrame(layoutCells);
            }
        }

        const handleDeletedCells = (deletedPartial: Partial<Record<number, CellState>>, cellOrderUpdate: UpdateResult<number[]>) => {
            Object.entries(cellOrderUpdate.removedValues!).forEach(([idx, id]) => {
                const deletedCell = deletedPartial[id]!;
                const deletedIdx = parseInt(idx);
                const cellEl = this.cells[id].el!;

                const prevCellId = cellOrderUpdate.newValue[deletedIdx - 1] ?? -1;
                const undoEl = div(['undo-delete'], [
                    icon(['close-button'], 'times', 'close icon').click(evt => {
                        undoEl.parentNode!.removeChild(undoEl)
                    }),
                    span(['undo-message'], [
                        'Cell deleted. ',
                        span(['undo-link'], ['Undo']).click(evt => {
                            this.insertCell(prevCellId, deletedCell.language, deletedCell.content, deletedCell.metadata)
                            undoEl.parentNode!.removeChild(undoEl);
                        })
                    ])
                ])

                cellEl.replaceWith(undoEl)
                this.cells[id].handler.dispose()
                this.cells[id].cell.delete();
                delete this.cells[id];
            })
        }

        notebookState.addObserver((state, update) => {
            const cellOrderUpdate = update.fieldUpdates?.cellOrder
            const maybeDeletedCells = update.fieldUpdates?.cells?.removedValues
            const maybeAddedCells = update.fieldUpdates?.cells?.addedValues

            if (cellOrderUpdate && cellOrderUpdate.update instanceof MoveArrayValue) {
                const newIndex = cellOrderUpdate.update.toIndex;
                // Move the cell's element such that it's in the correct order
                // Which cell should it be before now?
                const newNextCellId = cellOrderUpdate.newValue[newIndex + 1];
                const targetCellId = cellOrderUpdate.update.movedValue;

                if (targetCellId !== undefined) {
                    const targetCell = this.cells[targetCellId]?.el;
                    if (targetCell) {
                        const newNextCell = this.cells[newNextCellId]?.el;
                        if (newNextCell) {
                            cellsEl.insertBefore(targetCell, newNextCell);
                        } else {
                            cellsEl.appendChild(targetCell);
                        }

                        if (state.activeCellId !== undefined) {
                            const activeCellId = state.activeCellId;
                            // re-select the cell, so that its available values get recomputed
                            notebookState.updateAsync(
                                s => {
                                    if (s.activeCellId !== activeCellId)
                                        return NoUpdate;
                                    return {activeCellId: setValue(undefined)}
                                 },
                                this,
                                'activeCellId'
                            ).then(() => notebookState.update(s => {
                                if (s.activeCellId !== undefined)
                                    return NoUpdate;
                                return {activeCellId: setValue(activeCellId)}
                            }, this, 'activeCellId'))
                        }
                    }
                }
            } else {
                if (maybeDeletedCells) {
                    if (cellOrderUpdate === undefined) {
                        console.error("Got deleted cells", maybeDeletedCells, "but cellOrder didn't change! This is weird. Update:", update)
                    } else {
                        handleDeletedCells(maybeDeletedCells, cellOrderUpdate)
                    }
                }
                if (maybeAddedCells) {
                    if (cellOrderUpdate === undefined) {
                        console.error("Got deleted cells", maybeDeletedCells, "but cellOrder didn't change! This is weird. Update:", update)
                    } else {
                        handleAddedCells(maybeAddedCells, cellOrderUpdate)
                    }
                }
            }
        }, "cellOrder") // in theory, this should make it so this observer only gets called when `cellOrder` changes.

        this.notebookState.view("activeCellId").addObserver(cell => {
            if (cell === undefined) {
                VimStatus.get.hide()
            }
        }).disposeWith(this)

        this.notebookState.loaded.then(() => {
            Main.get.splitView.onEndResize(width => {
                needLayout = true;
                if (ServerStateHandler.state.currentNotebook === path) {
                    layoutCells();
                }
            }).disposeWith(this);
        })

        // select cell + highlight based on the current hash
        const hash = document.location.hash;
        // the hash can (potentially) have two parts: the selected cell and selected position.
        // for example: #Cell2,6-12 would mean Cell2, positions at offset 6 to 12
        const [hashId, pos] = hash.slice(1).split(",");
        const cellId = cellIdFromHash(hashId)
        // cell might not yet be loaded, so be sure to wait for it
        this.waitForCell(cellId).then(() => {
            this.notebookState.selectCell(cellId)

            if (pos) {
                const range = PosRange.fromString(pos)
                // TODO: when should this go away? maybe when you edit the cell
                cellsHandler.updateField(cellId, () => ({
                    currentHighlight: {
                        range,
                        className: "link-highlight"
                    }
                }))
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
                if (self.parentElement === next.cell.el) {
                    acc = next.handler.state
                }
                return acc;
            }, undefined);

            const lang = prevCell?.language && prevCell.language !== "text" && prevCell.language !== "viz" ? prevCell.language : "scala"; // TODO: make this configurable

            this.insertCell(prevCell?.id ?? -1, lang, '');
        });
    }

    private insertCell(prev: number, language: string, content: string, metadata?: CellMetadata) {
        this.notebookState.insertCell("below", {id: prev, language, content, metadata: metadata ?? new CellMetadata()})
            .then(newCellId => {
                this.notebookState.selectCell(newCellId, {editing: true})
            })
    }

    /**
     * Wait for a specific cell to be loaded. Since we load cells lazily, we might get actions for certain cells
     * (e.g., highlighting them) before they have been loaded by the page.
     *
     * @returns the id of the cell, useful if you pass this Promise somewhere else.
     */
    private waitForCell(cellId: number): Promise<number> {
        // wait for the cell to appear in the state
        return new Promise(resolve => {
            const wait = this.notebookState.addObserver(state => {
                if (state.cellOrder.find(id => id === cellId)) {
                    wait.dispose();
                    requestAnimationFrame(() => {
                        resolve(cellId)
                    })
                }
            }).disposeWith(this)
        }).then((cellId: number) => {
            // wait for the cell to appear on the page
            return new Promise(resolve => {
                const interval = window.setInterval(() => {
                    const maybeCell = this.cells[cellId]?.cell
                    if (maybeCell && this.el.contains(maybeCell.el)) {
                        window.clearInterval(interval)
                        resolve(cellId)
                    }
                }, 100)
            })
        })
    }
}

