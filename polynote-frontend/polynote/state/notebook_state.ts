import {
    BaseHandler,
    clearArray,
    Disposable,
    EditString,
    IDisposable,
    ImmediateDisposable,
    MoveArrayValue,
    NoUpdate,
    ObjectStateHandler,
    OptionalStateView,
    setValue,
    StateHandler,
    StateView,
    UpdatePartial,
    UpdateResult,
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
    TaskStatus
} from "../data/messages";

import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../data/data";
import {ContentEdit, diffEdits} from "../data/content_edit";
import {EditBuffer} from "../data/edit_buffer";
import {deepEquals, Deferred} from "../util/helpers";
import {notReceiver} from "../messaging/receiver";
import {ConstView, ProxyStateView} from "./state_handler";
import {ServerStateHandler} from "./server_state";


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
    presence: Record<number, CellPresenceState>;
    editing: boolean,
    error: boolean,
    running: boolean
    queued: boolean,
    currentSelection: PosRange | undefined,
    currentHighlight: { range: PosRange, className: string} | undefined
}

export type CompletionHint = { cell: number, offset: number; completions: CompletionCandidate[] }
export type SignatureHint = { cell: number, offset: number, signatures?: Signatures };
export type NBConfig = {open: boolean, config: NotebookConfig}

export type KernelSymbols = Record<string, Record<string, ResultValue>>;
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

export class NotebookStateHandler extends BaseHandler<NotebookState> {
    readonly loaded: Promise<void>;
    private lazyActiveCellView?: StateView<CellState | undefined>;

    constructor(
        parent: StateHandler<NotebookState>,
        readonly cellsHandler: StateHandler<Record<number, CellState>>,
        readonly updateHandler: NotebookUpdateHandler,
        readonly _loaded?: Promise<void>
    ) {
        super(parent);

        if (_loaded === undefined) {
            if (this.isLoading) {
                const tasksView = this.view('kernel').view('tasks');
                this.loaded = new Promise<void>(resolve => {
                    const obs = tasksView.addObserver((current, prev) => {
                        if (!current[this.state.path] || current[this.state.path].status === TaskStatus.Complete) {
                            obs.dispose();
                            resolve()
                        }
                    });
                })
            } else {
                this.loaded = Promise.resolve()
            }
        } else {
            this.loaded = _loaded;
        }

        this.loaded.then(_ => this.updateHandler.localVersion = 0)
    }

    /**
     * A (lazily-created) view which gives update on whatever the focused cell is (if a cell is focused)
     */
    get activeCellView(): OptionalStateView<CellState> {
        if (!this.lazyActiveCellView) {
            const noActiveState: StateView<CellState | undefined> = new ConstView(undefined);
            let currentState = this.state.activeCellId !== undefined ? this.cellsHandler.view(this.state.activeCellId) : noActiveState;
            const view = new ProxyStateView(currentState);
            this.lazyActiveCellView = view;

            this.observeKey("activeCellId", activeCellId => {
                if (activeCellId !== undefined && (!view.state || view.state.id !== activeCellId)) {
                    if (currentState !== noActiveState) {
                        currentState.dispose();
                    }
                    currentState = this.cellsHandler.view(activeCellId);
                    view.setParent(currentState);
                } else if (activeCellId === undefined && view.state) {
                    if (currentState !== noActiveState) {
                        currentState.dispose();
                    }
                    currentState = noActiveState;
                    view.setParent(noActiveState);
                }
            });
        }
        return this.lazyActiveCellView;
    }

    static forPath(path: string) {
        const baseHandler = new ObjectStateHandler<NotebookState>({
            path,
            cells: {},
            cellOrder: [],
            config: {open: false, config: NotebookConfig.default},
            kernel: {
                symbols: {},
                status: 'disconnected',
                info: {},
                tasks: {},
            },
            activePresence: {},
            activeCellId: undefined,
            activeCompletion: undefined,
            activeSignature: undefined,
            activeStreams: {},
        });

        const cellsHandler = baseHandler.lens("cells");
        const updateHandler = new NotebookUpdateHandler(baseHandler, cellsHandler, 0, 0, new EditBuffer())
        const handler = new NotebookStateHandler(baseHandler, cellsHandler, updateHandler);
        cellsHandler.disposeWith(handler);
        updateHandler.disposeWith(handler);
        return handler;
    }

    protected compare(s1: any, s2: any): boolean {
        return deepEquals(s1, s2)
    }

    availableValuesAt(id: number): Record<string, ResultValue> {
        return availableResultValues(this.state.kernel.symbols, this.state.cellOrder, id);
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
        this.update(state => {
            if (id !== undefined) {
                const anchorIdx = state.cellOrder.indexOf(id)
                if (options?.relative === "above")  {
                    let prevIdx = anchorIdx - 1;
                    id = state.cellOrder[prevIdx];
                    if (options?.skipHiddenCode) {
                        while (prevIdx > -1 && state.cells[id]?.metadata.hideSource) {
                            --prevIdx;
                            id = state.cellOrder[prevIdx];
                        }
                    }
                } else if (options?.relative === "below") {
                    let nextIdx = anchorIdx + 1
                    id = state.cellOrder[nextIdx];
                    if (options?.skipHiddenCode) {
                        while (nextIdx < state.cellOrder.length && state.cells[id]?.metadata.hideSource) {
                            ++nextIdx;
                            id = state.cellOrder[nextIdx];
                        }
                    }
                }
            }
            id = id ?? (selected === -1 ? 0 : selected); // if "above" or "below" don't exist, just select `selected`.
            const prev = state.activeCellId;
            const update: UpdatePartial<NotebookState> = {
                activeCellId: id,
                cells: id === undefined ? {} : {
                    [id]: {
                        editing: options?.editing ?? false
                    },
                    ...((prev === undefined && prev !== id) ? {} : {[prev]: {editing: false}})
                }
            };
            return update;
        })

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
            anchor = {id: currentCellId, language: (currentCell?.language === undefined || currentCell?.language === 'viz') ? 'scala' : currentCell.language, metadata: currentCell?.metadata ?? new CellMetadata()};
        }
        const anchorIdx = this.getCellIndex(anchor.id)!;
        const prevIdx = direction === 'above' ? anchorIdx - 1 : anchorIdx;
        const maybePrevId = state.cellOrder[prevIdx] ?? -1;
        // generate the max ID here. Note that there is a possible race condition if another client is inserting a cell at the same time.
        const maxId = state.cellOrder.reduce((acc, cellId) => acc > cellId ? acc : cellId, -1)
        const cellTemplate = {id: maxId + 1, language: anchor.language, content: anchor.content ?? '', metadata: anchor.metadata, prev: maybePrevId}

        // trigger the insert
        return this.updateHandler.insertCell(maxId + 1, anchor.language, anchor.content ?? '', anchor.metadata, maybePrevId).then(
            insert => insert.cell.id
        )
    }

    /**
     * Helper for inserting an inspection cell
     * @param result
     * @param viewType
     */
    insertInspectionCell(result: ResultValue, viewType?: string) {
        this.insertCell("below", {
            id: result.sourceCell,
            language: 'viz',
            metadata: new CellMetadata(false, false, false),
            content: JSON.stringify({type: viewType, value: result.name})
        }).then(id => this.selectCell(id))
    }

    deleteCell(id?: number, selectPrevCell: boolean = true): Promise<number | undefined> {
        if (id === undefined) {
            id = this.state.activeCellId;
        }
        if (id !== undefined) {
            const waitForDelete = new Promise<number>(resolve => {
                const cellOrder = this.view("cellOrder")
                const obs = cellOrder.addObserver(order => {
                    if (! order.includes(id!)) {
                        resolve(id!);
                        obs.dispose();
                    }
                }).disposeWith(this)
            })
            if (selectPrevCell) {
                const nextId = this.getNextCellId(id) ?? this.getPreviousCellId(id)
                waitForDelete.then(deletedId => {
                    if (deletedId !== undefined && nextId !== undefined) {
                        this.selectCell(nextId)
                    }
                })
            }
            this.updateHandler.deleteCell(id);
            return waitForDelete
        } else return Promise.resolve(undefined)
    }

    setCellLanguage(id: number, language: string, source?: any) {
        const cell = this.cellsHandler.state[id];
        this.cellsHandler.updateField(id, () => ({
            language,
            // clear a bunch of stuff if we're changing to text... hm, maybe we need to do something else when it's a a text cell...
            output: language === "text" ? clearArray() : NoUpdate,
            results: language === "text" ? clearArray() : NoUpdate,
            error: language === "text" ? false : NoUpdate,
            compileErrors: language === "text" ? clearArray() : NoUpdate,
            runtimeError: language === "text" ? setValue(undefined) : NoUpdate,
        }), source)
    }

    // wait for cell to transition to a specific state
    waitForCellChange(id: number, targetState: "queued" | "running" | "error"): Promise<void> {
        return new Promise<void>(resolve => {
            const obs = this.addObserver(state => {
                const maybeChanged = state.cells[id];
                if (maybeChanged && maybeChanged[targetState]) {
                    obs.dispose();
                    resolve();
                }
            }).disposeWith(this)
        })
    }

    get isLoading(): boolean {
        const nbLoaded = ServerStateHandler.getNotebook(this.state.path)?.loaded
        return nbLoaded === undefined || !nbLoaded || !!(this.state.kernel.tasks[this.state.path] ?? false)
    }

    fork(disposeContext?: IDisposable): NotebookStateHandler {
        const fork = new NotebookStateHandler(
            this.parent.fork(disposeContext).disposeWith(this),
            this.cellsHandler.fork(disposeContext).disposeWith(this),
            this.updateHandler,
            this.loaded
        ).disposeWith(this);

        return disposeContext ? fork.disposeWith(disposeContext) : fork;
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
    private observers: ((update: NotebookUpdate, rep?: Deferred<NotebookUpdate>) => void)[] = [];
    constructor(
        state: StateView<NotebookState>,
        cellsHandler: StateView<Record<number, CellState>>,
        public globalVersion: number,
        public localVersion: number,
        public edits: EditBuffer
    ) {
        super()

        state.view("config").view("config", notReceiver)
            .addObserver(config => this.updateConfig(config))

        state.view("cellOrder", notReceiver).addObserver((order, updateResult) => {
            if (updateResult.update instanceof MoveArrayValue) {
                const movedCell = updateResult.update.movedValue;
                const myIndex = updateResult.update.toIndex;
                const prev = order[myIndex - 1] ?? -1;
                this.moveCell(movedCell, prev);
            }
        })

        state.view("cellOrder").addObserver((newOrder, update) => {
            for (const id of Object.values(update.addedValues ?? {})) {
                this.watchCell(
                    id!,
                    cellsHandler.view(id!).filterSource(notReceiver).disposeWith(this)
                );
            }

            for (const id of Object.values(update.removedValues ?? {})) {
                if (id !== undefined && this.cellWatchers[id]) {
                    this.cellWatchers[id].tryDispose();
                    delete this.cellWatchers[id];
                }
            }

        })

        this.onDispose.then(() => this.observers.splice(0, this.observers.length))
    }

    addUpdate(update: NotebookUpdate, rep?: Deferred<NotebookUpdate>) {
        if (update.localVersion !== this.localVersion) {
            throw new Error(`Update Version mismatch! Update had ${update.localVersion}, but I had ${this.localVersion}`)
        }
        this.edits = this.edits.push(update.localVersion, update);
        this.observers.forEach(obs => obs(update, rep));
    }

    requestUpdate<T extends NotebookUpdate>(update: T): Promise<T> {
        const rep = new Deferred<T>()
        this.addUpdate(update, rep);
        return rep;
    }

    insertCell(cellId: number, language: string, content: string, metadata: CellMetadata, prevId: number) {
        this.localVersion++;
        const cell = new NotebookCell(cellId, language, content, [], metadata);
        const update = new messages.InsertCell(this.globalVersion, this.localVersion, cell, prevId);
        return this.requestUpdate(update)
    }

    deleteCell(id: number) {
        this.localVersion++;
        const update = new messages.DeleteCell(this.globalVersion, this.localVersion, id)
        return this.requestUpdate(update)
    }

    moveCell(id: number, after: number) {
        this.localVersion++;
        const update = new messages.MoveCell(this.globalVersion, this.localVersion, id, after);
        this.addUpdate(update);
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
            update = this.edits.rebaseThrough(update, this.localVersion);

            // discard edits before the local version from server – it will handle rebasing at least until that point
            this.edits = this.edits.discard(update.localVersion)
        }

        return update
    }

    private watchCell(id: number, handler: StateView<CellState>) {
        this.cellWatchers[id] = handler
        handler.view("output").addObserver((newOutput, updateResult) => {
            Object.values(updateResult.addedValues ?? {}).forEach(o => {
                this.setCellOutput(id, o)
            })
        }).disposeWith(this)

        handler.view("results", notReceiver).addObserver((newResults) => {
            if (newResults[0] && newResults[0] instanceof ClientResult) {
                newResults[0].toOutput().then(
                    o => this.addUpdate(new messages.SetCellOutput(this.globalVersion, this.localVersion, id, o))
                )
            }
        }).disposeWith(this)

        handler.view("language").addObserver(lang => {
            this.addUpdate(new messages.SetCellLanguage(this.globalVersion, this.localVersion, id, lang))
        }).disposeWith(this)

        handler.view("content").addObserver((content, updateResult) => {
            if (updateResult.update instanceof EditString) {
                this.updateCell(id, {edits: updateResult.update.edits})
            } else if (updateResult.oldValue !== undefined) {
                this.updateCell(id, {edits: diffEdits(updateResult.oldValue, content)})
            } else {
                // the only updates possible should be EditString or SetValue, so this is a defect
                console.error("Unexpected update to cell content", updateResult)
                throw new Error("Unexpected update to cell content")
            }
        }).disposeWith(this)

        handler.view("metadata").addObserver(metadata => {
            this.updateCell(id, {metadata})
        }).disposeWith(this)

        const existingComments: Set<string> = new Set(Object.keys(handler.state.comments))
        handler.view("comments").addObserver((current, updateResult) => {
            UpdateResult.addedOrChangedKeys(updateResult).forEach(commentId => {
                if (existingComments.has(commentId)) {
                    const currentComment = current[commentId];
                    this.updateComment(id, commentId, currentComment.range, currentComment.content)
                } else {
                    existingComments.add(commentId);
                    this.createComment(id, current[commentId]);
                }
            })

            Object.keys(updateResult.removedValues ?? {}).forEach(commentId => this.deleteComment(id, commentId))
        }).disposeWith(this)
    }

    addObserver(fn: (update: NotebookUpdate, rep?: Deferred<NotebookUpdate>) => void): IDisposable {
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

/**
 * Helper function for extracting the result values available to a particular cell from the symbols available in the
 * kernel
 */
export function availableResultValues(symbols: KernelSymbols, cellOrder: number[], id?: number): Record<string, ResultValue> {
    const availableCells = Object.keys(symbols);
    // first, make sure to add any predef cells (they don't appear in cellOrder)
    const cellsInScope = availableCells.filter(id => id.startsWith('-'));
    const cellIdx = id !== undefined ? cellOrder.indexOf(id) : cellOrder.length - 1;

    if (cellIdx >= 0) {
        cellsInScope.push(...cellOrder.slice(0, cellIdx).map(id => id.toString()));
    }

    return cellsInScope.reduce<Record<string, ResultValue>>((acc, next) => {
        Object.values(symbols[next] || {})
            .forEach((result: ResultValue) => acc[result.name] = result);
        return acc;
    }, {});
}
