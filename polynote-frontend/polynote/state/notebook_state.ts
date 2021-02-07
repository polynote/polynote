import {
    clearArray,
    Disposable,
    EditString,
    IDisposable,
    ImmediateDisposable,
    NoUpdate,
    ObjectStateHandler,
    setValue,
    StateHandler,
    StateView,
    UpdatePartial,
} from ".";
import {ClientResult, CompileErrors, Output, PosRange, ResultValue, RuntimeError,} from "../data/result";

import * as messages from "../data/messages";
import {
    CompletionCandidate,
    HandleData,
    KernelStatusString,
    ModifyStream,
    NotebookUpdate,
    Signatures,
    TaskInfo,
} from "../data/messages";

import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../data/data";
import {ContentEdit, diffEdits} from "../data/content_edit";
import {EditBuffer} from "../data/edit_buffer";
import {deepEquals, equalsByKey} from "../util/helpers";
import {notReceiver} from "../messaging/receiver";

export type CellPresenceState = {id: number, name: string, color: string, range: PosRange, avatar?: string};

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
    presence: CellPresenceState[];
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

export type PresenceState = { id: number, name: string, color: string, avatar?: string, selection?: { cellId: number, range: PosRange}};

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
    activePresence: Record<number, PresenceState>,
    // map of handle ID to message received.
    activeStreams: Record<number, (HandleData | ModifyStream)[]>,
}

export class NotebookStateHandler extends ObjectStateHandler<NotebookState> {
    readonly cellsHandler: StateHandler<Record<number, CellState>>;
    readonly updateHandler: NotebookUpdateHandler;
    constructor(state: NotebookState) {
        super(state);

        this.cellsHandler = this.lens("cells")
        this.updateHandler = new NotebookUpdateHandler(this, -1, 0, new EditBuffer()).disposeWith(this)

        // Update activeCellId when the active cell is deselected.
        this.view("activeCellId").addObserver(cellId => {
            if (cellId !== undefined) {
                const activeCellWatcher = this.cellsHandler.view(cellId)
                const obs = activeCellWatcher.addObserver(s => {
                    if (! s.selected) {
                        activeCellWatcher.dispose()
                        if (this.state.activeCellId === cellId) {
                            this.updateField("activeCellId", setValue(undefined))
                        }
                    }
                }).disposeWith(this)
            }
        }).disposeWith(this)
    }

    static forPath(path: string) {
        return new NotebookStateHandler({
            path,
            cells: {},
            cellOrder: [],
            config: {open: false, config: NotebookConfig.default},
            kernel: {
                symbols: [],
                status: 'disconnected',
                info: {},
                tasks: {},
            },
            activePresence: {},
            activeCellId: undefined,
            activeCompletion: undefined,
            activeSignature: undefined,
            activeStreams: {},
        })
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
        return anchorIdx !== undefined ? cellOrder[anchorIdx - 1] : undefined
    }

    getNextCellId(anchorId: number, cellOrder: number[] = this.state.cellOrder): number | undefined {
        const anchorIdx = this.getCellIndex(anchorId, cellOrder)
        return anchorIdx !== undefined ? cellOrder[anchorIdx + 1] : undefined
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
        const prev = this.state.activeCellId;
        const update: UpdatePartial<NotebookState> = {
            activeCellId: id,
            cells: id === undefined ? NoUpdate : {
                [id]: {
                    selected: true,
                    editing: options?.editing ?? false
                }
            }
        };
        if (prev !== undefined) {
            (update.cells as any)[prev] = { selected: false };
        }
        this.update(update)
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
            anchor = {id: currentCellId, language: currentCell?.language ?? 'scala', metadata: currentCell?.metadata ?? new CellMetadata()};
        }
        const anchorIdx = this.getCellIndex(anchor.id)!;
        const prevIdx = direction === 'above' ? anchorIdx - 1 : anchorIdx;
        const maybePrevId = state.cellOrder[prevIdx] ?? -1;
        // generate the max ID here. Note that there is a possible race condition if another client is inserting a cell at the same time.
        const maxId = state.cellOrder.reduce((acc, cellId) => acc > cellId ? acc : cellId, -1)
        const cellTemplate = {id: maxId + 1, language: anchor.language, content: anchor.content ?? '', metadata: anchor.metadata, prev: maybePrevId}
        // wait for the InsertCell message to go to the server and come back.
        const waitForCell = new Promise<number>(resolve => {
            const cellOrder = this.view("cellOrder")
            const obs = cellOrder.addObserver((order, update) => {
                const addedCellIdx = update.changedKeys[0] as number
                if (addedCellIdx !== undefined) {
                    // ensure the new cell is the one we're waiting for
                    const addedCell = this.state.cells[order[addedCellIdx]]
                    const matches = equalsByKey(addedCell, cellTemplate, ["language", "content", "metadata"])
                    if (addedCellIdx - 1 === prevIdx && matches && addedCell.id > maxId) {
                        resolve(addedCell.id)
                        obs.dispose()
                    }
                }
            }).disposeWith(this)
        })
        // trigger the insert
        this.updateHandler.insertCell(maxId + 1, anchor.language, anchor.content ?? '', anchor.metadata, maybePrevId)

        return waitForCell
    }

    deleteCell(id?: number): Promise<number | undefined> {
        if (id === undefined) {
            id = this.state.activeCellId;
        }
        if (id !== undefined) {
            const waitForDelete = new Promise<number>(resolve => {
                const cellOrder = this.view("cellOrder")
                const obs = cellOrder.addObserver(order => {
                    if (! order.includes(id!)) {
                        resolve(id);
                        obs.dispose();
                    }
                }).disposeWith(this)
            })
            this.updateHandler.deleteCell(id);
            return waitForDelete
        } else return Promise.resolve(undefined)
    }

    setCellLanguage(id: number, language: string) {
        const cell = this.cellsHandler.state[id];
        this.cellsHandler.updateField(id, {
            language,
            // clear a bunch of stuff if we're changing to text... hm, maybe we need to do something else when it's a a text cell...
            output: language === "text" ? clearArray() : NoUpdate,
            results: language === "text" ? clearArray() : NoUpdate,
            error: language === "text" ? false : NoUpdate,
            compileErrors: language === "text" ? clearArray() : NoUpdate,
            runtimeError: language === "text" ? setValue(undefined) : NoUpdate,
        })
    }

    // wait for cell to transition to a specific state
    waitForCellChange(id: number, targetState: "queued" | "running" | "error"): Promise<undefined> {
        return new Promise(resolve => {
            const obs = this.addObserver(state => {
                const maybeChanged = state.cells[id];
                if (maybeChanged && maybeChanged[targetState]) {
                    obs.dispose();
                    resolve();
                }
            }).disposeWith(this)
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
 *
 * TODO: can this be refactored so it's not a "broadcaster"? The dependencies seem backwards; shouldn't this just
 *       talk directly to the server message dispatcher rather than the dispatcher listening to this?
 */
export class NotebookUpdateHandler extends Disposable { // extends ObjectStateHandler<NotebookUpdate[]>{
    cellWatchers: Record<number, StateView<CellState>> = {};
    private observers: ((update: NotebookUpdate) => void)[] = [];
    constructor(state: NotebookStateHandler, public globalVersion: number, public localVersion: number, public edits: EditBuffer) {
        super()

        state.view("config").view("config", notReceiver)
            .addObserver((config, update, source) => this.updateConfig(config))
            .disposeWith(this)

        state.view("cellOrder").addObserver((newOrder, update) => {
            const added = update.changedValues(newOrder);
            const removed = update.removedValues ?? [];

            for (const idx in added) {
                if (added.hasOwnProperty(idx)) {
                    const id = added[idx];
                    this.watchCell(
                        id!,
                        state.cellsHandler.view(id!).filterSource(notReceiver).disposeWith(this)
                    );
                }
            }

            for (const idx in removed) {
                if (removed.hasOwnProperty(idx)) {
                    const id = removed[idx];
                    if (id !== undefined && this.cellWatchers[id]) {
                        this.cellWatchers[id].tryDispose();
                        delete this.cellWatchers[id];
                    }
                }
            }

        }).disposeWith(this)

        this.onDispose.then(() => this.observers.splice(0, this.observers.length))
    }

    addUpdate(update: NotebookUpdate) {
        if (update.localVersion !== this.localVersion) {
            throw new Error(`Update Version mismatch! Update had ${update.localVersion}, but I had ${this.localVersion}`)
        }
        this.edits = this.edits.push(update.localVersion, update);
        this.observers.forEach(obs => obs(update));
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
        this.addUpdate(new messages.DeleteComment(this.globalVersion, this.localVersion, cellId, commentId));
    }

    updateComment(cellId: number, commentId: string, range: PosRange, content: string): void {
        this.addUpdate(new messages.UpdateComment(this.globalVersion, this.localVersion, cellId, commentId, range, content));
    }

    setCellOutput(cellId: number, output: Output) {
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
        this.cellWatchers[id] = handler
        handler.view("output").addObserver((newOutput, update) => {
            const added = (update.changedKeys as number[]).map(idx => newOutput[idx]);
            added.forEach(o => {
                this.setCellOutput(id, o)
            })
        }).disposeWith(this)

        handler.view("language").addObserver(lang => {
            this.addUpdate(new messages.SetCellLanguage(this.globalVersion, this.localVersion, id, lang))
        }).disposeWith(this)

        handler.view("content").addObserver((content, update) => {
            if (update instanceof EditString) {
                this.updateCell(id, {edits: update.edits})
            } else if (update.oldValue !== undefined) {
                this.updateCell(id, {edits: diffEdits(update.oldValue, content)})
            } else {
                // the only updates possible should be EditString or SetValue, so this is a defect
                console.error("Unexpected update to cell content", update)
                throw new Error("Unexpected update to cell content")
            }
        }).disposeWith(this)

        handler.view("metadata").addObserver(metadata => {
            this.updateCell(id, {metadata})
        }).disposeWith(this)

        const existingComments: Set<string> = new Set(Object.keys(handler.state.comments))
        handler.view("comments").addObserver((current, update) => {
            update.changedKeys.forEach(commentId => {
                if (existingComments.has(commentId)) {
                    const currentComment = current[commentId];
                    this.updateComment(id, commentId, currentComment.range, currentComment.content)
                } else {
                    existingComments.add(commentId);
                    this.createComment(id, current[commentId]);
                }
            })

            update.removedKeys.forEach(commentId => this.deleteComment(id, commentId))
        }).disposeWith(this)
    }

    addObserver(fn: (update: NotebookUpdate) => void): IDisposable {
        this.observers.push(fn);
        return new ImmediateDisposable(() => {
            const idx = this.observers.indexOf(fn);
            if (idx >= 0) {
                this.observers.splice(idx, 1);
            }
        })
    }

    protected compare(s1: any, s2: any): boolean {
        return deepEquals(s1, s2);
    }
}
