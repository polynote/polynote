import {NoUpdate, StateHandler, StateView} from "./state_handler";
import {
    ClientResult, CompileErrors,
    Output,
    PosRange,
    ResultValue, RuntimeError,
    ServerErrorWithCause
} from "../data/result";
import {CompletionCandidate, HandleData, ModifyStream, Signatures, TaskStatus} from "../data/messages";
import {CellComment, CellMetadata, NotebookConfig} from "../data/data";
import {KernelState} from "./kernel_state";
import {ContentEdit} from "../data/content_edit";
import {EditBuffer} from "../data/edit_buffer";
import {deepEquals, equalsByKey, mapValues} from "../util/helpers";
import {NotebookMessageDispatcher} from "../messaging/dispatcher";
import {availableResultValues} from "../interpreter/client_interpreter";

export type Outputs = Output[] & { clear?: boolean }
export function outputs(outputs: Output[], clear?: boolean): Outputs {
    const result = outputs as Outputs;
    if (clear !== undefined) {
        result.clear = clear;
    }
    return result;
}

export interface CellState {
    id: number,
    language: string,
    content: string,
    metadata: CellMetadata,
    comments: Record<string, CellComment>,
    output: Outputs,
    results: (ResultValue | ClientResult)[],
    compileErrors: CompileErrors[],
    runtimeError: RuntimeError | undefined,
    // ephemeral states
    pendingEdits: ContentEdit[],
    presence: {id: number, name: string, color: string, range: PosRange, avatar?: string}[];
    editing: boolean,
    selected: boolean,
    error: boolean,
    running: boolean
    queued: boolean,
    currentSelection: PosRange | undefined,
    currentHighlight: { range: PosRange, className: string} | undefined
}

export type CompletionHint = { cell: number, offset: number; completions: CompletionCandidate[] }
export type SignatureHint = { cell: number, offset: number, signatures?: Signatures };
export type NBConfig = {open: boolean, config: NotebookConfig}

export interface NotebookState {
    // basic states
    path: string,
    cells: Record<number, CellState>, // cellId -> state
    cellOrder: number[], // this is the canonical ordering of the cells.
    config: NBConfig,
    kernel: KernelState,
    // version
    // TODO: make sure the global and local versions are properly updated
    globalVersion: number,
    localVersion: number,
    editBuffer: EditBuffer,
    // ephemeral states
    activeCellId: number | undefined,
    activeCompletion: { resolve: (completion: CompletionHint) => void, reject: () => void } | undefined,
    activeSignature: { resolve: (signature: SignatureHint) => void, reject: () => void } | undefined,
    activePresence: Record<number, { id: number, name: string, color: string, avatar?: string, selection?: { cellId: number, range: PosRange}}>,
    // map of handle ID to message received.
    activeStreams: Record<number, (HandleData | ModifyStream)[]>,
    // cell changes that are waiting ack from server
    pendingCells: { added: {cellId: number, language: string, content: string, metadata: CellMetadata, prev: number}[], removed: number[]},
}

export class NotebookStateHandler extends StateHandler<NotebookState> {
    readonly cellsHandler: StateHandler<Record<number, CellState>>;
    constructor(state: NotebookState) {
        super(state);

        this.cellsHandler = this.lens("cells")
    }

    availableValuesAt(id: number, dispatcher: NotebookMessageDispatcher): Record<string, ResultValue> {
        return availableResultValues(this.state.kernel.symbols, this, dispatcher, id);
    }

    viewAvailableValuesAt(id: number, dispatcher: NotebookMessageDispatcher): StateView<Record<string, ResultValue> | undefined> {
        return this.view("kernel").mapView("symbols", symbols => availableResultValues(symbols, this, dispatcher, id));
    }

    getCellIndex(cellId: number, cellOrder: number[] = this.state.cellOrder): number | undefined {
        return cellOrder.indexOf(cellId)
    }

    getCellIdAtIndex(cellIdx: number): number | undefined {
        return this.state.cellOrder[cellIdx]
    }

    getPreviousCellId(anchorId: number, cellOrder: number[] = this.state.cellOrder): number | undefined {
        const anchorIdx = this.getCellIndex(anchorId, cellOrder)
        return anchorIdx ? cellOrder[anchorIdx - 1] : undefined
    }

    getNextCellId(anchorId: number, cellOrder: number[] = this.state.cellOrder): number | undefined {
        const anchorIdx = this.getCellIndex(anchorId, cellOrder)
        return anchorIdx ? cellOrder[anchorIdx + 1] : undefined
    }

    /**
     * Change the currently selected cell.
     *
     * @param selected     The ID of the cell to select OR the ID of the anchor cell for `relative`. If `undefined`, deselects cells.
     * @param options      Options, consisting of:
     *                     relative        If set, select the cell either above or below the one with ID specified by `selected`
     *                     skipHiddenCode  If set alongside a relative cell selection, cells with hidden code blocks should be skipped.
     *                     editing         If set, indicate that the cell is editing in addition to being selected.
     * @return             The ID of the cell that was selected, possibly undefined if nothing was selected.
     */
    selectCell(selected: number | undefined, options?: { relative?: "above" | "below", skipHiddenCode?: boolean, editing?: boolean}): number | undefined {
        let id = selected;
        if (id !== undefined) {
            const anchorIdx = this.state.cellOrder.indexOf(id)
            if (options?.relative === "above")  {
                let prevIdx = anchorIdx - 1;
                id = this.state.cellOrder[prevIdx];
                if (options?.skipHiddenCode) {
                    while (prevIdx > -1 && this.state.cells[id]?.metadata.hideSource) {
                        --prevIdx;
                        id = this.state.cellOrder[prevIdx];
                    }
                }
            } else if (options?.relative === "below") {
                let nextIdx = anchorIdx + 1
                id = this.state.cellOrder[nextIdx];
                if (options?.skipHiddenCode) {
                    while (nextIdx < this.state.cellOrder.length && this.state.cells[id]?.metadata.hideSource) {
                        ++nextIdx;
                        id = this.state.cellOrder[nextIdx];
                    }
                }
            }
        }
        id = id ?? (selected === -1 ? 0 : selected); // if "above" or "below" don't exist, just select `selected`.
        console.log("cell 6 output is", this.state.cells[6]?.output)
        this.update(s => ({
            ...s,
            activeCellId: id,
            cells: mapValues(s.cells, cell => ({
                ...cell,
                selected: cell.id === id,
                editing: cell.id === id && (options?.editing ?? false)
            }))
        }))
        return id
    }

    /**
     * Helper for inserting a cell.
     *
     * @param direction  Whether to insert below of above the anchor
     * @param anchor     The anchor. If it is undefined, the anchor is based on the currently selected cell. If none is
     *                   selected, the anchor is either the first or last cell (depending on the direction supplied).
     *                   The anchor is used to determine the location, language, and metadata to supply to the new cell.
     * @return           A Promise that resolves with the inserted cell's id.
     */
    insertCell(direction: 'above' | 'below', anchor?: {id: number, language: string, metadata: CellMetadata, content?: string}): Promise<number> {
        const state = this.state;
        let currentCellId = state.activeCellId;
        if (anchor === undefined) {
            if (currentCellId === undefined) {
                if (direction === 'above') {
                    currentCellId = state.cellOrder[0];
                } else {
                    currentCellId = state.cellOrder[state.cellOrder.length - 1];
                }
            }
            const currentCell = state.cells[currentCellId];
            anchor = {id: currentCellId, language: currentCell.language, metadata: currentCell.metadata};
        }
        const anchorIdx = this.getCellIndex(anchor.id)!;
        const prevIdx = direction === 'above' ? anchorIdx - 1 : anchorIdx;
        const maybePrevId = state.cellOrder[prevIdx] ?? -1;
        const maxId = state.cellOrder.reduce((acc, cellId) => acc > cellId ? acc : cellId, -1)
        const cellTemplate = {cellId: maxId + 1, language: anchor!.language, content: anchor!.content ?? '', metadata: anchor!.metadata, prev: maybePrevId}
        this.update1("pendingCells", pending => ({
            ...pending,
            added: [...pending.added, cellTemplate]
        }))
        return new Promise(resolve => {
            const cellOrder = this.view("cellOrder")
            cellOrder.addObserver(order => {
                const anchorCellIdx = order.indexOf(maybePrevId)
                const insertedCellId = order.slice(anchorCellIdx).find((id, idx) => {
                    const newer = id > maybePrevId;
                    const matches = equalsByKey(this.state.cells[id], cellTemplate, ["language", "content", "metadata"]);
                    return newer && matches
                });
                if (insertedCellId !== undefined) {
                    cellOrder.dispose()
                    resolve(insertedCellId)
                }
            })
        })
    }

    deleteCell(id?: number){
        if (id === undefined) {
            id = this.state.activeCellId;
        }
        if (id !== undefined) {
            this.update1("pendingCells", pending => ({
                ...pending,
                removed: [...pending.removed, id!]
            }))
        }
    }

    setCellLanguage(id: number, language: string) {
        console.log("Setting language of cell", id, "to", language)
        this.cellsHandler.update1(id, cell => ({
            ...cell,
            language,
            // clear a bunch of stuff if we're changing to text... hm, maybe we need to do something else when it's a a text cell...
            output: language === "text" ? [] : cell.output,
            results: language === "text" ? [] : cell.results,
            error: language === "text" ? false : cell.error,
            compileErrors: language === "text" ? [] : cell.compileErrors,
            runtimeError: language === "text" ? undefined : cell.runtimeError,
        }))
    }

    // wait for cell to transition to a specific state
    waitForCellChange(id: number, targetState: "queued" | "running" | "error"): Promise<undefined> {
        return new Promise(resolve => {
            const obs = this.addObserver(state => {
                const maybeChanged = state.cells[id];
                if (maybeChanged && maybeChanged[targetState]) {
                    this.removeObserver(obs)
                    resolve()
                }
            })
        })
    }

    get isLoading(): boolean {
        return !!(this.state.kernel.tasks[this.state.path] ?? false)
    }

    get loaded(): Promise<void> {
        if (!this.isLoading) {
            return Promise.resolve();
        }
        return new Promise<void>(resolve => {
            const tasksView = this.view('kernel').view('tasks');
            tasksView.addObserver((current, prev) => {
                if (!current[this.state.path] || current[this.state.path].status === TaskStatus.Complete) {
                    tasksView.dispose();
                    resolve();
                }
            })
        })
    }
}
