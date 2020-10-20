import match from "../util/match";
import * as messages from "../data/messages";
import {HandleData, ModifyStream, NotebookUpdate, ReleaseHandle, TableOp} from "../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../data/data";
import {
    ClientResult,
    CompileErrors,
    Output,
    PosRange,
    ResultValue,
    RuntimeError,
    ServerErrorWithCause
} from "../data/result";
import {NoUpdate, StateHandler, StateView} from "../state/state_handler";
import {CellState, CompletionHint, NotebookState, NotebookStateHandler, SignatureHint} from "../state/notebook_state";
import {ContentEdit} from "../data/content_edit";
import {NotebookInfo, ServerState, ServerStateHandler} from "../state/server_state";
import {ConnectionStatus, SocketStateHandler} from "../state/socket_state";
import {About} from "../ui/component/about";
import {ValueInspector} from "../ui/component/value_inspector";
import {
    arrDeleteFirstItem,
    collect,
    deepEquals,
    diffArray,
    equalsByKey,
    mapValues,
    partition,
    removeKey
} from "../util/helpers";
import {Either} from "../data/codec_types";
import {DialogModal} from "../ui/layout/modal";
import {ClientInterpreter, ClientInterpreters} from "../interpreter/client_interpreter";
import {OpenNotebooksHandler} from "../state/preferences";
import {ClientBackup} from "../state/client_backup";
import {ErrorStateHandler} from "../state/error_state";
import {IgnoreServerUpdatesView} from "./receiver";

/**
 * The Dispatcher is used to handle actions initiated by the UI.
 * It knows whether an Action should be translated to a message and then sent on the socket, or if it should be
 * handled by something else.
 */
export abstract class MessageDispatcher<S, H extends StateHandler<S> = StateHandler<S>> {
    protected constructor(protected socket: SocketStateHandler, protected handler: H) {
        handler.onDispose.then(() => {
            this.socket.close()
        })
    }

    abstract dispatch(action: UIAction): void
}

export class NotebookMessageDispatcher extends MessageDispatcher<NotebookState, NotebookStateHandler> {
    constructor(socket: SocketStateHandler, state: NotebookStateHandler) {
        super(socket, state);
        // when the socket is opened, send a KernelStatus message to request the current status from the server.
        socket.view("status").addObserver(next => {
            if (next === "connected") {
                socket.send(new messages.KernelStatus(new messages.KernelBusyState(false, false)))
            }
        });
        const errorView = socket.view("error")
        errorView.addObserver(err => {
            if (err) {
                ErrorStateHandler.addKernelError(state.state.path, err.error)
            }
        })

        state.updateHandler.addObserver(updates => {
            if (updates.length > 0) {
                console.log("got updates to send", updates)
                updates.forEach(update => this.sendUpdate(update))
                state.updateHandler.update(() => [])
            }
        })

        const cells: Record<number, StateView<CellState>> = {};
        const cellsState = state.view("cells");
        state.view("cellOrder").addObserver((newOrder, prevOrder) => {
            const [_, added] = diffArray(prevOrder, newOrder);

            added.forEach(id => {
                const handler = cellsState.view(id)
                cells[id] = handler
                this.watchCell(handler)
            })
        })
    }

    // TODO: make sure this isn't being set every time
    private watchCell(cellView: StateView<CellState>) {
        const id = cellView.state.id;
        console.log("dispatcher: watching cell", id)

        cellView.view("currentSelection").addObserver(range => {
            if (range) {
                this.socket.send(new messages.CurrentSelection(id, range))
            }
        })
    }

    dispatch(action: UIAction) {
        match(action)
            .when(Reconnect, (onlyIfClosed: boolean) => {
                console.log("Attempting to reconnect to notebook")
                this.socket.reconnect(onlyIfClosed)
                const errorView = this.socket.view("error")
                errorView.addObserver(err => {
                    // if there was an error on reconnect, push it to the notebook state so it can be displayed
                    if (err) {
                        console.error("error on reconnecting notebook", err)
                        ErrorStateHandler.addKernelError(this.handler.state.path, err.error)
                    }
                })
                this.socket.view("status", undefined, errorView).addObserver(status => {
                    if (status === "connected") {
                        this.handler.update(s => {
                            return {
                                ...s,
                                errors: [] // any errors from before are no longer relevant, right?
                            }
                        })
                        errorView.dispose()
                    }
                })
            })
            .when(CreateComment, (cellId, comment) => {
                const state = this.handler.updateHandler
                this.sendUpdate(new messages.CreateComment(state.globalVersion, state.localVersion, cellId, comment))
            })
            .when(UpdateComment, (cellId, commentId, range, content) => {
                const state = this.handler.updateHandler
                this.sendUpdate(new messages.UpdateComment(state.globalVersion, state.localVersion, cellId, commentId, range, content))
            })
            .when(DeleteComment, (cellId, commentId) => {
                const state = this.handler.updateHandler
                this.sendUpdate(new messages.DeleteComment(state.globalVersion, state.localVersion, cellId, commentId))
            })
            .when(RequestCompletions, (cellId, offset, resolve, reject) => {
                this.socket.send(new messages.CompletionsAt(cellId, offset, []));
                this.handler.update(state => {
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
                this.handler.update(state => {
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
                const state = this.handler.state
                this.socket.send(new messages.NotebookVersion(state.path, version))
            })
            .when(RequestCellRun, cellIds => {
                this.handler.update(state => {
                    // empty cellIds means run all of them!
                    if (cellIds.length === 0) {
                        cellIds = state.cellOrder
                    }

                    cellIds = collect(cellIds, id => state.cells[id]?.language !== "text" ? id : undefined);

                    const [clientCells, serverCells] = partition(cellIds, id => {
                        const cell = state.cells[id]
                        if (cell) {
                            return Object.keys(ClientInterpreters).includes(cell.language)
                        } else {
                            console.warn("Run requested for cell with ID", id, "but a cell with that ID was not found in", state.cells)
                            return true // should this fail?
                        }
                    })
                    clientCells.forEach(id => {
                        const idx = cellIds.indexOf(id)
                        const prevId = cellIds[idx - 1]
                        const clientInterpreter = ClientInterpreter.forPath(state.path);
                        if (clientInterpreter) {
                            clientInterpreter.runCell(id, this, prevId)
                        } else {
                            const cell = state.cells[id];
                            const message = `Missing Client Interpreter for cell ${cell.id} of type ${cell.language}`
                            console.error(message)
                            ErrorStateHandler.addKernelError(this.handler.state.path, new ServerErrorWithCause("Missing Client Interpreter", message, []))
                        }
                    })
                    this.socket.send(new messages.RunCell(serverCells));
                    return {
                        ...state,
                        cells: mapValues(state.cells, cell => {
                            if (cellIds.includes(cell.id)) {
                                return { ...cell, results: [] }
                            } else return cell
                        })
                    }
                })
            })
            .when(RequestCancelTasks, () => {
                const state = this.handler.state
                this.socket.send(new messages.CancelTasks(state.path))
            })
            .when(RequestClearOutput, () => {
                this.socket.send(new messages.ClearOutput())
            })
            .when(DeselectCell, cellId => {
                this.handler.update(state => {
                    return {
                        ...state,
                        cells: {
                            ...state.cells,
                            [cellId]: {
                                ...state.cells[cellId],
                                selected: false
                            }
                        },
                        activeCellId: state.activeCellId === cellId ? undefined : state.activeCellId
                    }
                })
            })
            .when(RemoveCellError, (id, error) => {
                this.handler.update(state => {
                    let cell = {...state.cells[id]};
                    if (error instanceof RuntimeError) {
                        cell = {...cell, runtimeError: undefined};
                    } else {
                        cell = {...cell, compileErrors: cell.compileErrors.filter(e => ! deepEquals(e, error)) }
                    }
                    return {
                        ...state,
                        cells: {
                            ...state.cells,
                            [id]: cell
                        }
                    }
                })
            })
            .when(DownloadNotebook, () => {
                const path = window.location.pathname + "?download=true"
                const link = document.createElement('a');
                link.setAttribute("href", path);
                link.setAttribute("download", this.handler.state.path);
                link.click()
            })
            .when(ShowValueInspector, (result, tab) => {
                ValueInspector.get.inspect(this, this.handler, result, tab)
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
            .when(ClearDataStream, handleId => {
                this.handler.update(state => {
                    return {
                        ...state,
                        activeStreams: {
                            ...state.activeStreams,
                            [handleId]: []
                        }
                    }
                })
            })
            .when(ToggleNotebookConfig, open => {
                this.handler.update(s => {
                    return {
                        ...s,
                        config: {...s.config, open: (open ?? !s.config.open)}
                    }
                })
            })
    }

    private sendUpdate(upd: NotebookUpdate) {
        this.socket.send(upd)
        ClientBackup.updateNb(this.handler.state.path, upd)
            .catch(err => console.error("Error backing up update", err))
    }

    // Helper methods

    runActiveCell() {
        const id = this.handler.state.activeCellId;
        if (id !== undefined) {
            this.dispatch(new RequestCellRun([id]));
        }
    }

    runToActiveCell() {
        const state = this.handler.state;
        const id = state.activeCellId;
        if (id) {
            const activeIdx = state.cellOrder.indexOf(id)
            const cellsToRun = state.cellOrder.slice(0, activeIdx + 1);
            if (cellsToRun.length > 0) {
                this.dispatch(new RequestCellRun(cellsToRun))
            }
        }
    }

    kernelCommand(command: "start" | "kill") {
        if (command === "start") {
            this.socket.send(new messages.StartKernel(messages.StartKernel.NoRestart));
        } else if (command === "kill") {
            if (confirm("Kill running kernel? State will be lost.")) {
                this.socket.send(new messages.StartKernel(messages.StartKernel.Kill));
            }
        }
    }
}

// TODO: should this be a singleton too?
export class ServerMessageDispatcher extends MessageDispatcher<ServerState>{
    constructor(socket: SocketStateHandler) {
        super(socket, ServerStateHandler.get);

        const errorView = socket.view("error")
        errorView.addObserver(err => {
            if (err) {
                ErrorStateHandler.addServerError(err.error)
            }
        })

        this.handler.view("openNotebooks").addObserver(nbs => {
            OpenNotebooksHandler.update(() => nbs)
        })
    }

    dispatch(action: UIAction): void {
        match(action)
            .when(Reconnect, (onlyIfClosed: boolean) => {
                console.warn("Attempting to reconnect to server") // TODO: once we have a proper place for server errors, we can display this log there.
                this.socket.reconnect(onlyIfClosed)
                const errorView = this.socket.view("error")
                errorView.addObserver(err => {
                    if (err) {
                        // We don't want to reload if the connection is offline, instead we just want to display the
                        // error to the user
                        const reload = err.status === ConnectionStatus.ONLINE
                        if (reload) {
                            console.error("Error reconnecting, trying to reload the page")
                            document.location.reload();
                        } else {
                            ErrorStateHandler.addServerError(err.error)
                        }
                    }
                })
                // TODO: depending on how complicated reconnecting is, maybe we should just reload the page every time?
                this.socket.view("status", undefined, errorView).addObserver(status => {
                    if (status === "connected") {
                        console.warn("Reconnected successfully, now reconnecting to notebook sockets")
                        this.handler.update(s => {
                            return {
                                ...s,
                                errors: [] // any errors from before are no longer relevant, right?
                            }
                        })
                        ServerStateHandler.reconnectNotebooks(onlyIfClosed)
                        errorView.dispose()
                    }
                })
            })
            .when(RequestNotebooksList, () => {
                this.socket.send(new messages.ListNotebooks([]))
            })
            .when(CreateNotebook, (path, content) => {
                const waitForNotebook = (nbPath: string) => {
                    const nbs = this.handler.view("notebooks")
                    nbs.addObserver((current, prev) => {
                        const [added, _] = diffArray(Object.keys(current), Object.keys(prev))
                        added.forEach(newNb => {
                            if (newNb.includes(nbPath)) {
                                nbs.dispose()
                                ServerStateHandler.loadNotebook(newNb, true).then(nbInfo => {
                                    nbInfo.info?.dispatcher.dispatch(new ToggleNotebookConfig(true))  // open config automatically for newly created notebooks.
                                    ServerStateHandler.selectNotebook(newNb)
                                })
                            }
                        })
                    })
                }
                if (path) {
                    this.socket.send(new messages.CreateNotebook(path, content))
                    waitForNotebook(path)
                } else {
                    new DialogModal('Create Notebook', 'path/to/new notebook name', 'Create').show().then(newPath => {
                        this.socket.send(new messages.CreateNotebook(newPath, content))
                        waitForNotebook(newPath)
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
            .when(ViewAbout, section => {
                About.show(this, section)
            })
            .when(RequestRunningKernels, () => {
                this.socket.send(new messages.RunningKernels([]))
            })
    }
}

export class UIAction {
    // All empty classes are equivalent in typescript (because structural typing), so we need a dummy parameter here for typing.
    private __UIAction = undefined

    static unapply(inst: UIAction): any[] {return []}

    constructor(...args: any[]) {}
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

export class DeselectCell extends UIAction {
    constructor(readonly cellId: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeselectCell): ConstructorParameters<typeof DeselectCell> {
        return [inst.cellId]
    }
}

export class RemoveCellError extends UIAction {
    constructor(readonly cellId: number, readonly error: RuntimeError | CompileErrors) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RemoveCellError): ConstructorParameters<typeof RemoveCellError> {
        return [inst.cellId, inst.error];
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

export class ToggleNotebookConfig extends UIAction {
    constructor(readonly open?: boolean) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: ToggleNotebookConfig): ConstructorParameters<typeof ToggleNotebookConfig> {
        return [inst.open];
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

export class ClearDataStream extends UIAction {
    constructor(readonly handleId: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: ClearDataStream): ConstructorParameters<typeof ClearDataStream> {
        return [inst.handleId]
    }
}
