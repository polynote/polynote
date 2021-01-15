import {NoUpdate, StateHandler, StateView} from "./state_handler";
import {
    ClientResult, CompileErrors,
    Output,
    PosRange,
    ResultValue, RuntimeError,
    ServerErrorWithCause
} from "../data/result";
import {
    CompletionCandidate,
    HandleData,
    KernelStatusString,
    ModifyStream,
    NotebookUpdate,
    Signatures,
    TaskInfo
} from "../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../data/data";
import {ContentEdit} from "../data/content_edit";
import {EditBuffer} from "../data/edit_buffer";
import {deepEquals, diffArray, equalsByKey, mapValues} from "../util/helpers";
import * as messages from "../data/messages";
import {IgnoreServerUpdatesWrapper} from "../messaging/receiver";

export interface CellState {
    id: number,
    language: string,
    content: string,
    metadata: CellMetadata,
    comments: Record<string, CellComment>,
    output: Output[],
    results: (ResultValue | ClientResult)[],
    compileErrors: CompileErrors[],
    runtimeError: RuntimeError | undefined,
    // ephemeral states
    incomingEdits: ContentEdit[],
    outgoingEdits: ContentEdit[],
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

export type KernelSymbols = (ResultValue)[];
export type KernelInfo = Record<string, string>;
export type KernelTasks = Record<string, TaskInfo>; // taskId -> TaskInfo

export interface KernelState {
    symbols: KernelSymbols,
    status: KernelStatusString,
    info: KernelInfo,
    tasks: KernelTasks
}

export interface NotebookState {
    // basic states
    path: string,
    cells: Record<number, CellState>, // cellId -> state
    cellOrder: number[], // this is the canonical ordering of the cells.
    config: NBConfig,
    kernel: KernelState,
    // ephemeral states
    activeCellId: number | undefined,
    activeCompletion: { cellId: number, offset: number, resolve: (completion: CompletionHint) => void, reject: () => void } | undefined,
    activeSignature: { cellId: number, offset: number, resolve: (signature: SignatureHint) => void, reject: () => void } | undefined,
    activePresence: Record<number, { id: number, name: string, color: string, avatar?: string, selection?: { cellId: number, range: PosRange}}>,
    // map of handle ID to message received.
    activeStreams: Record<number, (HandleData | ModifyStream)[]>,
}

export class NotebookStateHandler extends StateHandler<NotebookState> {
    readonly cellsHandler: StateHandler<Record<number, CellState>>;
    updateHandler: NotebookUpdateHandler;
    constructor(state: NotebookState) {
        super(new StateView(state));

        this.cellsHandler = this.lens("cells")
        this.updateHandler = new NotebookUpdateHandler(this, -1, -1, new EditBuffer())

        // Update activeCellId when the active cell is deselected.
        this.view("activeCellId").addObserver(cellId => {
            if (cellId !== undefined) {
                const activeCellWatcher = this.cellsHandler.view(cellId)
                const obs = activeCellWatcher.addObserver(s => {
                    if (! s.selected) {
                        activeCellWatcher.removeObserver(obs)
                        if (this.state.activeCellId === cellId) {
                            this.update1("activeCellId", () => undefined)
                        }
                    }
                }, this)
            }
        }, this)
    }

    protected compare(s1: any, s2: any): boolean {
        return deepEquals(s1, s2)
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
        // generate the max ID here. Note that there is a possible race condition if another client is inserting a cell at the same time.
        const maxId = state.cellOrder.reduce((acc, cellId) => acc > cellId ? acc : cellId, -1)
        const cellTemplate = {cellId: maxId + 1, language: anchor!.language, content: anchor!.content ?? '', metadata: anchor!.metadata, prev: maybePrevId}
        this.updateHandler.insertCell(maxId + 1, anchor!.language, anchor.content ?? '', anchor.metadata, maybePrevId)
        // wait for the InsertCell message to go to the server and come back.
        return new Promise(resolve => {
            const cellOrder = this.view("cellOrder")
            const obs = cellOrder.addObserver((order, prev) => {
                const added = diffArray(prev, order)[1][0]
                const addedCellIdx = order.indexOf(added)

                // ensure the new cell is the one we're waiting for
                const addedCell = this.state.cells[added]
                const matches = equalsByKey(addedCell, cellTemplate, ["language", "content", "metadata"])
                if (addedCellIdx - 1 === maybePrevId && matches && addedCell.id > maxId) {
                    resolve(addedCell.id)
                    cellOrder.removeObserver(obs)
                }
            }, this)
        })
    }

    deleteCell(id?: number){
        if (id === undefined) {
            id = this.state.activeCellId;
        }
        if (id !== undefined) {
            this.updateHandler.deleteCell(id)
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
            }, this)
        })
    }
}

/**
 * Handles Notebook Updates.
 *
 * Keeps track of `globalVersion`, `localVersion` and the Edit Buffer, making sure they are updated when necessary.
 *
 * Watches the state of this notebook's cells, translating their state changes into NotebookUpdates which are then
 * observed by the dispatcher and sent to the server.
 */
export class NotebookUpdateHandler extends StateHandler<NotebookUpdate[]>{
    cellWatchers: Record<number, StateView<CellState>> = {};
    constructor(state: NotebookStateHandler, public globalVersion: number, public localVersion: number, public edits: EditBuffer) {
        super(new StateView([]))

        new IgnoreServerUpdatesWrapper(state.view("config")).addObserver((current, prev) => {
            if (! deepEquals(current.config, prev.config)) {
                this.updateConfig(current.config)
            }
        }, this)

        state.view("cellOrder").addObserver((newOrder, prevOrder) => {
            const [removed, added] = diffArray(prevOrder, newOrder)
            added.forEach(cellId => this.watchCell(cellId, new IgnoreServerUpdatesWrapper(state.cellsHandler.view(cellId))))
            removed.forEach(cellId => {
                delete this.cellWatchers[cellId]
            })
        }, this)
    }

    addUpdate(update: NotebookUpdate) {
        if (update.localVersion !== this.localVersion) {
            throw new Error(`Update Version mismatch! Update had ${update.localVersion}, but I had ${this.localVersion}`)
        }
        this.edits = this.edits.push(update.localVersion, update)
        this.update(s => [...s, update])
    }

    insertCell(cellId: number, language: string, content: string, metadata: CellMetadata, prevId: number) {
        this.localVersion++;
        const cell = new NotebookCell(cellId, language, content, [], metadata);
        const update = new messages.InsertCell(this.globalVersion, this.localVersion, cell, prevId);
        this.addUpdate(update)
    }

    deleteCell(id: number) {
        this.localVersion++;
        const update = new messages.DeleteCell(this.globalVersion, this.localVersion, id)
        this.addUpdate(update)
    }

    updateCell(id: number, changed: {edits?: ContentEdit[], metadata?: CellMetadata}) {
        this.localVersion++;
        const update = new messages.UpdateCell(this.globalVersion, this.localVersion, id, changed.edits ?? [], changed.metadata)
        this.addUpdate(update)
    }

    createComment(cellId: number, comment: CellComment): void {
        this.addUpdate(new messages.CreateComment(this.globalVersion, this.localVersion, cellId, comment));
    }

    deleteComment(cellId: number, commentId: string): void {
        console.log("deleting comment", commentId, cellId)
        this.addUpdate(new messages.DeleteComment(this.globalVersion, this.localVersion, cellId, commentId));
    }

    updateComment(cellId: number, commentId: string, range: PosRange, content: string): void {
        this.addUpdate(new messages.UpdateComment(this.globalVersion, this.localVersion, cellId, commentId, range, content));
    }

    setCellOutput(cellId: number, output: Output) {
        console.log("setting cell output!", cellId, output)
        this.addUpdate(new messages.SetCellOutput(this.globalVersion, this.localVersion, cellId, output))
    }

    updateConfig(config: NotebookConfig) {
        this.localVersion++;
        const update = new messages.UpdateConfig(this.globalVersion, this.localVersion, config);
        this.addUpdate(update);
    }

    rebaseUpdate(update: NotebookUpdate) {
        this.globalVersion = update.globalVersion
        if (update.localVersion < this.localVersion) {
            const prevUpdates = this.edits.range(update.localVersion, this.localVersion);

            // discard edits before the local version from server – it will handle rebasing at least until that point
            this.edits = this.edits.discard(update.localVersion)

            update = messages.NotebookUpdate.rebase(update, prevUpdates)
        }

        return update
    }

    private watchCell(id: number, handler: StateView<CellState>) {
        console.log("notebookupdatehandler: watching cell", id, handler)
        this.cellWatchers[id] = handler
        handler.view("output").addObserver((newOutput, oldOutput, source) => {
            const added = diffArray(oldOutput, newOutput)[1]
            added.forEach(o => {
                this.setCellOutput(id, o)
            })
        }, this)

        handler.view("language").addObserver(lang => {
            this.addUpdate(new messages.SetCellLanguage(this.globalVersion, this.localVersion, id, lang))
        }, this)

        handler.view("outgoingEdits").addObserver(edits => {
            console.log("got outgoing edits!", edits)
            if (edits.length > 0) {
                this.updateCell(id, {edits})
            }
        }, this)

        handler.view("metadata").addObserver(metadata => {
            this.updateCell(id, {metadata})
        }, this)

        handler.view("comments").addObserver((current, previous) => {
            const [removed, added] = diffArray(Object.keys(previous), Object.keys(current));

            added.forEach(commentId => this.createComment(id, current[commentId]))
            removed.forEach(commentId => this.deleteComment(id, commentId))

            Object.keys(current).filter(k => ! added.includes(k) && ! deepEquals(current[k], previous[k])).forEach(maybeChangedId => {
                const currentComment = current[maybeChangedId]
                if (! deepEquals(current[maybeChangedId], previous[maybeChangedId])) {
                    this.updateComment(id, currentComment.uuid, currentComment.range, currentComment.content)
                }
            })
        }, this)
    }

    protected compare(s1: any, s2: any): boolean {
        return deepEquals(s1, s2);
    }
}
