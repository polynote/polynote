import {div, icon, span, TagElement} from "../../tags";
import {NotebookMessageDispatcher} from "../../../messaging/dispatcher";
import {CellState, NotebookStateHandler} from "../../../state/notebook_state";
import {Disposable, StateHandler} from "../../../state/state_handler";
import {CellMetadata} from "../../../data/data";
import {diffArray} from "../../../util/helpers";
import {CellContainer} from "./cell";
import {NotebookConfigEl} from "./notebookconfig";
import {VimStatus} from "./vim_status";
import {PosRange} from "../../../data/result";
import {NotebookScrollLocationsHandler} from "../../../state/preferences";
import {ServerStateHandler} from "../../../state/server_state";

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
            NotebookScrollLocationsHandler.update(locations => ({
                ...locations,
                [path]: cellsEl.scrollTop
            }))
        })
        this.el = div(['notebook-content'], [cellsEl]);

        const handleVisibility = (currentNotebook?: string, previousNotebook?: string) => {
            if (currentNotebook === path) {
                // when this notebook becomes visible, scroll to the saved location (if present)
                const maybeScrollLocation = NotebookScrollLocationsHandler.state[path]
                if (maybeScrollLocation !== undefined) {
                    cellsEl.scrollTop = maybeScrollLocation
                }

                // layout cells
                Object.values(this.cells).forEach(({cell, handler, el}) => {
                    cell.layout()
                })
            } else {
                // deselect cells.
                this.notebookState.selectCell(undefined)
            }
        }
        handleVisibility(ServerStateHandler.state.currentNotebook)
        ServerStateHandler.view("currentNotebook").addObserver((current, previous) => handleVisibility(current, previous), notebookState)

        const cellsHandler = notebookState.cellsHandler

        const handleCells = (newOrder: number[], prevOrder: number[] = []) => {
            const [removedIds, addedIds] = diffArray(prevOrder, newOrder)

            addedIds.forEach(id => {
                const handler = cellsHandler.lens(id)
                const cell = new CellContainer(dispatcher, notebookState, handler);
                const el = div(['cell-and-divider'], [cell.el, this.newCellDivider()])
                this.cells[id] = {cell, handler, el}
                const cellIdx = newOrder.indexOf(id)
                const nextCellIdAtIdx = prevOrder[cellIdx]
                if (nextCellIdAtIdx !== undefined) {
                    // there's a different cell at this index. we need to insert this cell above the existing cell
                    const nextCellEl = this.cells[nextCellIdAtIdx].el;
                    // note that inserting a node that is already in the DOM will move it from its current location to here.
                    cellsEl.insertBefore(el, nextCellEl);
                } else {
                    // index not found, must be at the end
                    cellsEl.appendChild(el);
                }
            });

            removedIds.forEach(id => {
                const deletedCell = this.cells[id].handler.state
                const cellEl = this.cells[id].el!;

                const prevCellId = notebookState.getPreviousCellId(id, prevOrder) ?? -1
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
            });
        }
        handleCells(notebookState.state.cellOrder)
        notebookState.view("cellOrder").addObserver((newOrder, prevOrder) => handleCells(newOrder, prevOrder), this);

        console.debug("initial active cell ", this.notebookState.state.activeCellId)
        this.notebookState.view("activeCellId").addObserver(cell => {
            if (cell === undefined) {
                VimStatus.get.hide()
            }
        }, this)

        // select cell + highlight based on the current hash
        const hash = document.location.hash;
        // the hash can (potentially) have two parts: the selected cell and selected position.
        // for example: #Cell2,6-12 would mean Cell2, positions at offset 6 to 12
        const [hashId, pos] = hash.slice(1).split(",");
        const cellId = parseInt(hashId.slice("Cell".length))
        // cell might not yet be loaded, so be sure to wait for it
        this.waitForCell(cellId).then(() => {
            this.notebookState.selectCell(cellId)

            if (pos) {
                const range = PosRange.fromString(pos)
                // TODO: when should this go away? maybe when you edit the cell
                cellsHandler.update1(cellId, s => ({
                    ...s,
                    currentHighlight: {range, className: "link-highlight"}
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
                if (self.previousElementSibling === next.cell.el) {
                    acc = next.handler.state
                }
                return acc;
            }, undefined);

            const lang = prevCell?.language && prevCell.language !== "text" ? prevCell.language : "scala"; // TODO: make this configurable

            this.insertCell(prevCell?.id ?? -1, lang, '');
        });
    }

    private insertCell(prev: number, language: string, content: string, metadata?: CellMetadata) {
        this.notebookState.insertCell("below", {id: prev, language, content, metadata: metadata ?? new CellMetadata()})
            .then(newCellId => {
                this.notebookState.selectCell(newCellId)
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
                    this.notebookState.removeObserver(wait)
                    requestAnimationFrame(() => {
                        resolve(cellId)
                    })
                }
            }, this)
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

