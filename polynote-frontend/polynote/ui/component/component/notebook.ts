import {div, TagElement} from "../../util/tags";
import {CreateCell, NotebookMessageDispatcher} from "../messaging/dispatcher";
import {CellState, NotebookStateHandler} from "../state/notebook_state";
import {StateHandler} from "../state/state_handler";
import {CellMetadata, NotebookConfig} from "../../../data/data";
import {diffArray} from "../../../util/functions";
import {CellContainerComponent} from "./cell";
import {languages} from "monaco-editor";

export class Notebook {
    readonly el: TagElement<"div">;
    readonly cells: Record<number, {cell: CellContainerComponent, handler: StateHandler<CellState>, el: TagElement<"div">}> = {};
    readonly cellOrder: Record<number, number> = {}; // index => cell id;
    // private pendingHint?: {
    //     type: "completion" | "signature",
    //     resolve: (hint: CompletionList | (SignatureHelp | undefined)) => void,
    //     reject: () => void };

    constructor(private dispatcher: NotebookMessageDispatcher, private notebookState: NotebookStateHandler) {
        const config = new NotebookConfigComponent(notebookState.view("config"));
        this.el = div(['notebook-cells'], [config.el, this.newCellDivider()]);

        // we use views to watch for state changes we care about
        notebookState.view("cells").addObserver((oldCells, newCells) => {
            const [removed, added] = diffArray(oldCells, newCells, (o, n) => o.id === n.id);

            added.forEach(state => {
                const handler = new StateHandler(state);
                const cell = new CellContainerComponent(dispatcher, handler);
                this.cells[state.id] = {cell, handler, el: div(['cell-and-divider'], [cell.el, this.newCellDivider()])}
            });
            removed.forEach(cell => {
                this.cells[cell.id].cell.delete();
                const cellEl = this.cells[cell.id].el;
                if (cellEl) this.el.removeChild(cellEl);
                delete this.cells[cell.id];

                // clean up cell order
                const deletedIdx = Object.entries(this.cellOrder).find((idx, id) => id === cell.id);
                if (deletedIdx) {
                    const [idxStr, id] = deletedIdx;
                    let idx = parseInt(idxStr); // Object.entries casts the idx to string
                    let nextId = this.cellOrder[idx+1];
                    while (nextId) {
                        this.cellOrder[idx] = nextId;
                        idx += 1;
                        nextId = this.cellOrder[idx];
                    }
                }
            });

            newCells.forEach((cell, idx) => {
                const cellEl = this.cells[cell.id].el;
                const cellIdAtIdx = this.cellOrder[idx];
                if (cellIdAtIdx) {
                    if (cellIdAtIdx !== cell.id) {
                        // there's a different cell at this index. we need to insert this cell above the existing cell
                        const prevCellEl = this.cells[cellIdAtIdx].el;
                        // note that inserting a node that is already in the DOM will move it from its current location to here.
                        this.el.insertBefore(cellEl, prevCellEl);
                        this.cellOrder[idx] = cell.id;
                        this.cellOrder[idx + 1] = cellIdAtIdx;
                    }
                } else {
                    // index not found, must be at the end
                    this.cellOrder[idx] = cell.id;
                    this.el.appendChild(this.cells[cell.id].cell.el);
                }
                this.cells[cell.id].handler.setState(cell);
            })
        });
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
        this.dispatcher.dispatch(new CreateCell(language, content, metadata ?? new CellMetadata(), prev))
    }

    dispose() {
        this.notebookState.clearObservers();
    }
}

class NotebookConfigComponent {
    readonly el: TagElement<"div">;
    constructor(stateHandler: StateHandler<NotebookConfig>) {

    }

}

