/**
 * The Dispatcher is used to handle actions initiated by the UI.
 * It knows whether an Action should be translated to a message and then sent on the socket, or if it should be
 * handled by something else.
 */
import match from "../../../util/match";
import * as messages from "../../../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../../../data/data";
import {ClientResult, Output, PosRange, ResultValue} from "../../../data/result";
import {StateHandler} from "../state/state_handler";
import {CompletionHint, NotebookState, NotebookStateHandler, SignatureHint} from "../state/notebook_state";
import {ContentEdit} from "../../../data/content_edit";
import {NotebookInfo, ServerState, ServerStateHandler} from "../state/server_state";
import {SocketStateHandler} from "../state/socket_state";
import {About} from "../component/about";
import {ValueInspector} from "../component/value_inspector";
import {arrDeleteItem, collect, equalsByKey, partition} from "../../../util/functions";
import {HandleData, ModifyStream, ReleaseHandle, TableOp} from "../../../data/messages";
import {Either} from "../../../data/types";
import {DialogModal} from "../component/modal";
import {ClientInterpreterComponent, ClientInterpreters} from "../component/interpreter/client_interpreter";
import {OpenNotebooksHandler} from "../state/storage";

export abstract class MessageDispatcher<S> {
    protected constructor(protected socket: SocketStateHandler, protected state: StateHandler<S>) {}

    abstract dispatch(action: UIAction): void
}

export class NotebookMessageDispatcher extends MessageDispatcher<NotebookState>{
    constructor(socket: SocketStateHandler, state: NotebookStateHandler, private clientInterpreter: ClientInterpreterComponent) {
        super(socket, state);
        // when the socket is opened, send a KernelStatus message to request the current status from the server.
        socket.view("status").addObserver(next => {
            if (next === "connected") {
                socket.send(new messages.KernelStatus(new messages.KernelBusyState(false, false)))
            }
        });
    }

    dispatch(action: UIAction) {
        match(action)
            .when(Reconnect, (onlyIfClosed: boolean) => {
                this.socket.reconnect(onlyIfClosed)
            })
            .when(KernelCommand, (command: string) => {
                NotebookMessageDispatcher.kernelCommand(this.socket, command)
            })
            .when(CreateComment, (cellId, comment) => {
                const state = this.state.getState()
                this.socket.send(new messages.CreateComment(state.globalVersion, state.localVersion, cellId, comment))
            })
            .when(UpdateComment, (cellId, commentId, range, content) => {
                const state = this.state.getState()
                this.socket.send(new messages.UpdateComment(state.globalVersion, state.localVersion, cellId, commentId, range, content))
            })
            .when(DeleteComment, (cellId, commentId) => {
                const state = this.state.getState()
                this.socket.send(new messages.DeleteComment(state.globalVersion, state.localVersion, cellId, commentId))
            })
            .when(SetCurrentSelection, (cellId, range) => {
                this.socket.send(new messages.CurrentSelection(cellId, range))
            })
            .when(SetCellHighlight, (cellId, range, className) => {
                this.state.updateState(state => {
                    return {
                        ...state,
                        cells: state.cells.map(cell => {
                            if (cell.id === cellId) {
                                return {
                                    ...cell,
                                    currentHighlight: {range, className}
                                }
                            } else return cell
                        })
                    }
                })
            })
            .when(SetCellOutput, (cellId, output) => {
                    if (output instanceof Output) {
                        this.state.updateState(state => {
                            this.socket.send(new messages.SetCellOutput(state.globalVersion, state.localVersion, cellId, output))
                            return {
                                ...state,
                                cells: state.cells.map(cell => {
                                    if (cell.id === cellId) {
                                        return {...cell, output: [output]}
                                    } else return cell
                                })
                            }
                        })
                    } else {
                        // ClientResults are special. The Client treats them like a Result, but the Server treats them like an Output.
                        output.toOutput().then(o => {
                            this.state.updateState(state => {
                                this.socket.send(new messages.SetCellOutput(state.globalVersion, state.localVersion, cellId, o))
                                return {
                                    ...state,
                                    cells: state.cells.map(cell => {
                                        if (cell.id === cellId) {
                                            if (cell.results.includes(output)) {
                                                return {...cell, output: [o]}
                                            } else {
                                                return {...cell, results: [...cell.results, output], output: [o]}
                                            }
                                        } else return cell
                                    })
                                }
                            })
                        })
                    }
            })
            .when(SetCellLanguage, (cellId, language) => {
                this.state.updateState(state => {
                    this.socket.send(new messages.SetCellLanguage(state.globalVersion, state.localVersion, cellId, language))
                    return {
                        ...state,
                        cells: state.cells.map(cell => {
                            if (cell.id === cellId) {
                                return {...cell, language: language}
                            } else return cell
                        })
                    }
                })
            })
            .when(CreateCell, (language, content, metadata, prev) => {
                this.state.updateState(state => {
                    let localVersion = state.localVersion + 1;
                    // generate the max ID here. Note that there is a possible race condition if another client is inserting a cell at the same time.
                    const maxId = state.cells.reduce((acc, cell) => acc > cell.id ? acc : cell.id, -1)
                    const cell = new NotebookCell(maxId + 1, language, content, [], metadata);
                    const update = new messages.InsertCell(state.globalVersion, localVersion, cell, prev);
                    this.socket.send(update);
                    return {
                        ...state,
                        editBuffer: state.editBuffer.push(state.localVersion, update)
                    }
                })
            })
            .when(UpdateCell, (cellId, edits, newContent, metadata) => {
                this.state.updateState(state => {
                    let localVersion = state.localVersion + 1;
                    const update = new messages.UpdateCell(state.globalVersion, localVersion, cellId, edits, metadata);
                    this.socket.send(update);
                    return {
                        ...state,
                        editBuffer: state.editBuffer.push(state.localVersion, update),
                        cells: state.cells.map(cell => {
                            if (cell.id === cellId) {
                                return {...cell, content: newContent ?? cell.content, metadata: metadata ?? cell.metadata}
                            } else return cell
                        })
                    }
                })
            })
            .when(DeleteCell, (cellId) => {
                this.state.updateState(state => {
                    state = {...state}
                    const update = new messages.DeleteCell(state.globalVersion, ++state.localVersion, cellId);
                    this.socket.send(update);
                    state.editBuffer = state.editBuffer.push(state.localVersion, update)
                    return state
                })
            })
            .when(UpdateConfig, conf => {
                this.state.updateState(state => {
                    state = {...state}
                    const update = new messages.UpdateConfig(state.globalVersion, ++state.localVersion, conf);
                    this.socket.send(update);
                    state.editBuffer = state.editBuffer.push(state.localVersion, update)
                    return state
                })
            })
            .when(RequestCompletions, (cellId, offset, resolve, reject) => {
                this.socket.send(new messages.CompletionsAt(cellId, offset, []));
                this.state.updateState(state => {
                    if (state.activeCompletion) {
                        state.activeCompletion.reject();
                    }
                    return {
                        ...state,
                        activeCompletion: {resolve, reject}
                    }
                })
            })
            .when(RequestSignature, (cellId, offset, resolve, reject) => {
                this.socket.send(new messages.ParametersAt(cellId, offset));
                this.state.updateState(state => {
                    if (state.activeSignature) {
                        state.activeSignature.reject();
                    }
                    return {
                        ...state,
                        activeSignature: {resolve, reject}
                    }
                })
            })
            .when(RequestNotebookVersion, version => {
                const state = this.state.getState()
                this.socket.send(new messages.NotebookVersion(state.path, version))
            })
            .when(RequestCellRun, cellIds => {
                this.state.updateState(state => {
                    // empty cellIds means run all of them!
                    if (cellIds.length === 0) {
                        cellIds = collect(state.cells, cell => cell.language !== "text" ? cell.id : undefined)
                    }

                    const [clientCells, serverCells] = partition(cellIds, id => {
                        const cell = state.cells.find(c => c.id === id)
                        if (cell) {
                            return Object.keys(ClientInterpreters).includes(cell.language)
                        } else {
                            console.warn("Run requested for cell with ID", id, "but a cell with that ID was not found in", state.cells)
                            return true // should this fail?
                        }
                    })
                    clientCells.forEach(id => this.clientInterpreter.runCell(id, this))
                    this.socket.send(new messages.RunCell(serverCells));
                    return {
                        ...state,
                        cells: state.cells.map(cell => {
                            if (cellIds.includes(cell.id)) {
                                return { ...cell, queued: true, results: [], error: false }
                            } else return cell
                        })
                    }
                })
            })
            .when(RequestCancelTasks, () => {
                const state = this.state.getState()
                this.socket.send(new messages.CancelTasks(state.path))
            })
            .when(RequestClearOutput, () => {
                this.socket.send(new messages.ClearOutput())
            })
            .when(SetSelectedCell, (selected, relative, skipHiddenCode) => {
                this.state.updateState(state => {
                    let id = selected;
                    if (relative === "above")  {
                        const anchorIdx = state.cells.findIndex(cell => cell.id === id);
                        let prevIdx = anchorIdx - 1;
                        id = state.cells[prevIdx]?.id;
                        if (skipHiddenCode) {
                            while (state.cells[prevIdx]?.metadata.hideSource) {
                                --prevIdx;
                            }
                            id = state.cells[prevIdx]?.id;
                        }
                    } else if (relative === "below") {
                        const anchorIdx = state.cells.findIndex(cell => cell.id === id);
                        let nextIdx = anchorIdx + 1
                        id = state.cells[nextIdx]?.id;
                        if (skipHiddenCode) {
                            while (state.cells[nextIdx]?.metadata.hideSource) {
                                ++nextIdx;
                            }
                            id = state.cells[nextIdx]?.id;
                        }
                    }
                    id = id ?? (selected === -1 ? 0 : selected); // if "above" or "below" don't exist, just select `selected`.
                    return {
                        ...state,
                        activeCell: state.cells.find(cell => cell.id === id),
                        cells: state.cells.map(cell => {
                            return {
                                ...cell,
                                selected: cell.id === id
                            }
                        })
                    }
                })
            })
            .when(DeselectCell, cellId => {
                this.state.updateState(state => {
                    return {
                        ...state,
                        cells: state.cells.map(cell => {
                            if (cell.id === cellId) {
                                return {
                                    ...cell,
                                    selected: false
                                }
                            } else return cell
                        }),
                        activeCell: state.activeCell?.id === cellId ? undefined : state.activeCell
                    }
                })
            })
            .when(CurrentSelection, (cellId, range) => {
                this.socket.send(new messages.CurrentSelection(cellId, range));
                this.state.updateState(state => {
                    return {
                        ...state,
                        cells: state.cells.map(cell => {
                            // todo: what is this used for?
                            return {
                                ...cell,
                                currentSelection: cell.id === cellId ? range : undefined
                            }
                        })
                    }
                })
            })
            .when(ClearCellEdits, id => {
                this.state.updateState(state => {
                    return {
                        ...state,
                        cells: state.cells.map(cell => {
                            if (cell.id === id) {
                                return {
                                    ...cell,
                                    pendingEdits: []
                                }
                            } else return cell
                        })
                    }
                })
            })
            .when(DownloadNotebook, () => {
                const path = window.location.pathname + "?download=true"
                const link = document.createElement('a');
                link.setAttribute("href", path);
                link.setAttribute("download", this.state.getState().path);
                link.click()
            })
            .when(ShowValueInspector, (result, tab) => {
                ValueInspector.get.inspect(this, this.state, result, tab)
            })
            .when(HideValueInspector, () => {
                ValueInspector.get.hide()
            })
            .when(RequestDataBatch, (handleType, handleId, size) => {
                this.socket.send(new HandleData(handleType, handleId, size, Either.right([])))
            })
            .when(ModifyDataStream, (handleId, mods) => {
                this.socket.send(new ModifyStream(handleId, mods))
            })
            .when(StopDataStream, (handleType, handleId) => {
                this.socket.send(new ReleaseHandle(handleType, handleId))
            })
            .when(CloseNotebook, (path) => {
                this.socket.close()
            })
        // TODO: add more actions! basically UIEvents that anything subscribes to should be here I think
    }

    // Helper methods
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
        const currentState = this.state.getState();
        let currentCell = currentState.activeCell;
        if (anchor === undefined) {
            if (currentCell === undefined) {
                if (direction === 'above') {
                    currentCell = currentState.cells[0];
                } else {
                    currentCell = currentState.cells[currentState.cells.length - 1];
                }
            }
            anchor = {id: currentCell.id, language: currentCell.language, metadata: currentCell.metadata};
        }
        const anchorIdx = currentState.cells.findIndex(cell => cell.id === anchor!.id);
        const prevIdx = direction === 'above' ? anchorIdx - 1 : anchorIdx;
        const maybePrev = currentState.cells[prevIdx];
        const createMsg = new CreateCell(anchor.language, anchor.content ?? '', anchor.metadata, maybePrev.id)
        this.dispatch(createMsg)
        return new Promise((resolve, reject) => {
            const obs = this.state.addObserver(state => {
                const anchorCellIdx = state.cells.findIndex(cell => cell.id === maybePrev.id)
                const didInsert = state.cells.find((cell, idx) => {
                    const below = idx > anchorCellIdx;
                    const newer = cell.id > maybePrev.id;
                    const matches = equalsByKey(cell, createMsg, ["language", "content", "metadata"]);
                    return below && newer && matches
                });
                if (didInsert !== undefined) {
                    this.state.removeObserver(obs);
                    resolve(didInsert.id)
                }
            })
        })
    }

    deleteCell(id?: number){
       if (id === undefined) {
           id = this.state.getState().activeCell?.id;
       }
       if (id !== undefined) {
           this.dispatch(new DeleteCell(id));
       }
    }

    runActiveCell() {
        const id = this.state.getState().activeCell?.id;
        if (id !== undefined) {
            this.dispatch(new RequestCellRun([id]));
        }
    }

    runToActiveCell() {
        const state = this.state.getState();
        const id = state.activeCell?.id;
        const activeIdx = state.cells.findIndex(cell => cell.id === id);
        const cellsToRun = state.cells.slice(0, activeIdx + 1).map(c => c.id);
        if (cellsToRun.length > 0) {
            this.dispatch(new RequestCellRun(cellsToRun))
        }
    }

    static kernelCommand(socket: SocketStateHandler, command: string) {
        if (command === "start") {
            socket.send(new messages.StartKernel(messages.StartKernel.NoRestart));
        } else if (command === "kill") {
            if (confirm("Kill running kernel? State will be lost.")) {
                socket.send(new messages.StartKernel(messages.StartKernel.Kill));
            }
        }
    }
}

export class ServerMessageDispatcher extends MessageDispatcher<ServerState>{
    constructor(socket: SocketStateHandler) {
        super(socket, ServerStateHandler.get);

    }

    dispatch(action: UIAction): void {
        this.state.updateState(s => {
            let newS: ServerState | undefined = undefined;
            match(action)
                .when(RequestNotebooksList, () => {
                    this.socket.send(new messages.ListNotebooks([]))
                })
                .when(LoadNotebook, (path, open) => {
                    let notebooks = s.notebooks
                    if (! s.notebooks[path])  {
                        notebooks = {...notebooks, [path]: ServerStateHandler.loadNotebook(path).loaded}
                    }
                    newS = {
                        ...s,
                        currentNotebook: open ? path : s.currentNotebook,
                        notebooks: notebooks
                    };
                    if (open && ! this.openNotebooks.includes(path)) {
                        this.openNotebooks = [...this.openNotebooks, path]
                    }
                })
                .when(CreateNotebook, (path, content) => {
                    if (path) {
                        this.socket.send(new messages.CreateNotebook(path, content))
                    } else {
                        new DialogModal('Create Notebook', 'path/to/new notebook name', 'Create').show().then(newPath => {
                            this.socket.send(new messages.CreateNotebook(newPath, content))
                        })
                    }
                })
                .when(RenameNotebook, (oldPath, newPath) => {
                    if (newPath) {
                        this.socket.send(new messages.RenameNotebook(oldPath, newPath))
                    } else {
                        new DialogModal('Rename Notebook', oldPath, 'Rename').show().then(newPath => {
                            this.socket.send(new messages.RenameNotebook(oldPath, newPath))
                        })
                    }
                })
                .when(CopyNotebook, (oldPath, newPath) => {
                    if (newPath) {
                        this.socket.send(new messages.CopyNotebook(oldPath, newPath))
                    } else {
                        new DialogModal('Copy Notebook', oldPath, 'Copy').show().then(newPath => {
                            this.socket.send(new messages.CopyNotebook(oldPath, newPath))
                        })
                    }
                })
                .when(DeleteNotebook, (path) => {
                    this.socket.send(new messages.DeleteNotebook(path))
                })
                .when(CloseNotebook, (path) => {
                    ServerStateHandler.closeNotebook(path)
                    this.openNotebooks = arrDeleteItem(this.openNotebooks, path)
                    newS = {
                        ...s,
                        notebooks: {
                            ...s.notebooks,
                            [path]: false
                        }
                    }
                })
                .when(ViewAbout, section => {
                    About.show(this, section)
                })
                .when(SetSelectedNotebook, path => {
                    newS = {
                        ...s,
                        currentNotebook: path
                    }
                })
                .when(RequestRunningKernels, () => {
                    this.socket.send(new messages.RunningKernels([]))
                })

            if (newS) return newS
            else return s
        });
    }

    loadNotebook(path: string, open?: boolean): Promise<NotebookInfo> {
        return new Promise(resolve => {
            this.dispatch(new LoadNotebook(path, open))
            const info = ServerStateHandler.getOrCreateNotebook(path)
            const loading = info.handler.addObserver(() => {
                const maybeLoaded = ServerStateHandler.getOrCreateNotebook(path)
                if (maybeLoaded.loaded && maybeLoaded.info) {
                    info.handler.removeObserver(loading);
                    resolve(maybeLoaded)
                }
            })
        })
    }

    private _openNotebooks: string[] = [];
    private get openNotebooks() {
        return this._openNotebooks;
    }
    private set openNotebooks(tabs: string[]) {
        this._openNotebooks = tabs;
        OpenNotebooksHandler.updateState(() => {
            return this._openNotebooks;
        })
    }
}

export class UIAction {
    // All empty classes are equivalent in typescript (because structural typing), so we need a dummy parameter here for typing.
    private __UIAction = undefined

    static unapply(inst: UIAction): any[] {return []}

    constructor(...args: any[]) {}
}

export class KernelCommand extends UIAction {
    constructor(readonly command: "start" | "kill") { super() }

    static unapply(inst: KernelCommand): ConstructorParameters<typeof KernelCommand> {
        return [inst.command]
    }
}

export class Reconnect extends UIAction {
    constructor(readonly onlyIfClosed: boolean) { super() }

    static unapply(inst: Reconnect): ConstructorParameters<typeof Reconnect> {
        return [inst.onlyIfClosed]
    }
}


export class CreateComment extends UIAction {
    constructor(readonly cellId: number, readonly comment: CellComment) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CreateComment): ConstructorParameters<typeof CreateComment> {
        return [inst.cellId, inst.comment];
    }
}

export class UpdateComment extends UIAction {
    constructor(readonly cellId: number, readonly commentId: string, readonly range: PosRange, readonly content: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: UpdateComment): ConstructorParameters<typeof UpdateComment> {
        return [inst.cellId, inst.commentId, inst.range, inst.content];
    }
}

export class DeleteComment extends UIAction {
    constructor(readonly cellId: number, readonly commentId: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeleteComment): ConstructorParameters<typeof DeleteComment> {
        return [inst.cellId, inst.commentId];
    }
}

export class CreateCell extends UIAction {
    constructor(readonly language: string, readonly content: string, readonly metadata: CellMetadata, readonly prev: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CreateCell): ConstructorParameters<typeof CreateCell> {
        return [inst.language, inst.content, inst.metadata, inst.prev];
    }
}

export class UpdateCell extends UIAction {
    constructor(readonly cellId: number, readonly edits: ContentEdit[], readonly newContent?: string, readonly metadata?: CellMetadata) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: UpdateCell): ConstructorParameters<typeof UpdateCell> {
        return [inst.cellId, inst.edits, inst.newContent, inst.metadata];
    }
}

export class DeleteCell extends UIAction {
    constructor(readonly cellId: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeleteCell): ConstructorParameters<typeof DeleteCell> {
        return [inst.cellId];
    }
}

export class UpdateConfig extends UIAction {
    constructor(readonly config: NotebookConfig) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: UpdateConfig): ConstructorParameters<typeof UpdateConfig> {
        return [inst.config];
    }
}

export class LoadNotebook extends UIAction {
    constructor(readonly path: string, readonly open: boolean = true) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: LoadNotebook): ConstructorParameters<typeof LoadNotebook> {
        return [inst.path, inst.open];
    }
}

export class CreateNotebook extends UIAction {
    constructor(readonly path?: string, readonly content?: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CreateNotebook): ConstructorParameters<typeof CreateNotebook> {
        return [inst.path, inst.content];
    }
}

export class RenameNotebook extends UIAction {
    constructor(readonly oldPath: string, readonly newPath?: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RenameNotebook): ConstructorParameters<typeof RenameNotebook> {
        return [inst.oldPath, inst.newPath];
    }
}

export class CopyNotebook extends UIAction {
    constructor(readonly oldPath: string, readonly newPath?: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CopyNotebook): ConstructorParameters<typeof CopyNotebook> {
        return [inst.oldPath, inst.newPath];
    }
}

export class DeleteNotebook extends UIAction {
    constructor(readonly path: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeleteNotebook): ConstructorParameters<typeof DeleteNotebook> {
        return [inst.path];
    }
}

export class CloseNotebook extends UIAction {
    constructor(readonly path: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CloseNotebook): ConstructorParameters<typeof CloseNotebook> {
        return [inst.path];
    }
}

export class SetCurrentSelection extends UIAction {
    constructor(readonly cellId: number, readonly range: PosRange) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetCurrentSelection): ConstructorParameters<typeof SetCurrentSelection> {
        return [inst.cellId, inst.range];
    }
}

export class SetCellOutput extends UIAction {
    constructor(readonly cellId: number, readonly output: (Output | ClientResult)) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetCellOutput): ConstructorParameters<typeof SetCellOutput> {
        return [inst.cellId, inst.output];
    }
}

export class SetCellLanguage extends UIAction {
    constructor(readonly cellId: number, readonly language: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetCellLanguage): ConstructorParameters<typeof SetCellLanguage> {
        return [inst.cellId, inst.language];
    }
}

export class RequestCompletions extends UIAction {
    constructor(readonly cellId: number, readonly offset: number,
                readonly resolve: (completion: CompletionHint) => void, readonly reject: () => void) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestCompletions): ConstructorParameters<typeof RequestCompletions> {
        return [inst.cellId, inst.offset, inst.resolve, inst.reject];
    }
}

export class RequestSignature extends UIAction {
    constructor(readonly cellId: number, readonly offset: number,
                readonly resolve: (value: SignatureHint) => void, readonly reject: () => void) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestSignature): ConstructorParameters<typeof RequestSignature> {
        return [inst.cellId, inst.offset, inst.resolve, inst.reject];
    }
}

export class RequestNotebookVersion extends UIAction {
    constructor(readonly version: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestNotebookVersion): ConstructorParameters<typeof RequestNotebookVersion> {
        return [inst.version];
    }
}

export class RequestCellRun extends UIAction {
    constructor(readonly cells: number[]) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestCellRun): ConstructorParameters<typeof RequestCellRun> {
        return [inst.cells];
    }
}

export class RequestCancelTasks extends UIAction {
    constructor() {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestCancelTasks): ConstructorParameters<typeof RequestCancelTasks> {
        return [];
    }
}

export class RequestClearOutput extends UIAction {
    constructor() {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestClearOutput): ConstructorParameters<typeof RequestClearOutput> {
        return [];
    }
}

export class RequestNotebooksList extends UIAction {
    constructor() {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestNotebooksList): ConstructorParameters<typeof RequestNotebooksList> {
        return [];
    }
}

export class SetSelectedCell extends UIAction {
    /**
     * Change the currently selected cell.
     *
     * @param selected        The ID of the cell to select OR the ID of the anchor cell for `relative`. If `undefined`, deselects cells.
     * @param relative        If set, select the cell either above or below the one with ID specified by `selected`
     * @param skipHiddenCode  If set alongside a relative cell selection, cells with hidden code blocks should be skipped.
     */
    constructor(readonly selected: number | undefined, readonly relative?: "above" | "below", readonly skipHiddenCode: boolean = false) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetSelectedCell): ConstructorParameters<typeof SetSelectedCell> {
        return [inst.selected, inst.relative, inst.skipHiddenCode];
    }
}

export class DeselectCell extends UIAction {
    constructor(readonly cellId: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeselectCell): ConstructorParameters<typeof DeselectCell> {
        return [inst.cellId]
    }
}

export class CurrentSelection extends UIAction {
    constructor(readonly cellId: number, readonly range: PosRange) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CurrentSelection): ConstructorParameters<typeof CurrentSelection> {
        return [inst.cellId, inst.range];
    }
}

export class SetCellHighlight extends UIAction {
    constructor(readonly cellId: number, readonly range: PosRange, readonly className: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetCellHighlight): ConstructorParameters<typeof SetCellHighlight> {
        return [inst.cellId, inst.range, inst.className];
    }
}

export class ClearCellEdits extends UIAction {
    constructor(readonly cellId: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: ClearCellEdits): ConstructorParameters<typeof ClearCellEdits> {
        return [inst.cellId];
    }
}

export class RequestRunningKernels extends UIAction {
    constructor() {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestRunningKernels): ConstructorParameters<typeof RequestRunningKernels> {
        return [];
    }
}

export class DownloadNotebook extends UIAction {
    constructor() {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DownloadNotebook): ConstructorParameters<typeof DownloadNotebook> {
        return [];
    }
}

export class SetSelectedNotebook extends UIAction {
    constructor(readonly path?: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetSelectedNotebook): ConstructorParameters<typeof SetSelectedNotebook> {
        return [inst.path];
    }
}

export class ViewAbout extends UIAction {
    constructor(readonly section: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: ViewAbout): ConstructorParameters<typeof ViewAbout> {
        return [inst.section];
    }
}

export class ShowValueInspector extends UIAction {
    constructor(readonly result: ResultValue, readonly tab?: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: ShowValueInspector): ConstructorParameters<typeof ShowValueInspector> {
        return [inst.result, inst.tab];
    }
}

export class HideValueInspector extends UIAction {
    constructor() {
        super();
        Object.freeze(this);
    }

    static unapply(inst: HideValueInspector): ConstructorParameters<typeof HideValueInspector> {
        return [];
    }
}

export class RequestDataBatch extends UIAction {
    constructor(readonly handleType: number, readonly handleId: number, readonly size: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestDataBatch): ConstructorParameters<typeof RequestDataBatch> {
        return [inst.handleType, inst.handleId, inst.size]
    }
}

export class ModifyDataStream extends UIAction {
    constructor(readonly handleId: number, readonly mods: TableOp[]) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: ModifyDataStream): ConstructorParameters<typeof ModifyDataStream> {
        return [inst.handleId, inst.mods]
    }
}

export class StopDataStream extends UIAction {
    constructor(readonly handleType: number, readonly handleId: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: StopDataStream): ConstructorParameters<typeof StopDataStream> {
        return [inst.handleType, inst.handleId]
    }
}
