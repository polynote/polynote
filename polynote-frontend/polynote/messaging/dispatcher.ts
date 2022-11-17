import * as messages from "../data/messages";
import {
    HandleData,
    ModifyStream,
    NotebookUpdate,
    NotebookVersion,
    ReleaseHandle,
    NotebookSaved,
    TableOp
} from "../data/messages";
import {
    ClientResult,
    Output,
    ServerErrorWithCause
} from "../data/result";
import {
    Disposable, setProperty,
    setValue,
    StateHandler,
    StateView, UpdateResult
} from "../state";
import {About} from "../ui/component/about";
import {collect, partition} from "../util/helpers";
import {Either} from "../data/codec_types";
import {DialogModal} from "../ui/layout/modal";
import {ClientInterpreter, ClientInterpreters} from "../interpreter/client_interpreter";
import {ConnectionStatus, SocketStateHandler} from "../state/socket_state";
import {CellState, NotebookState, NotebookStateHandler} from "../state/notebook_state";
import {ErrorStateHandler} from "../state/error_state";
import {ClientBackup} from "../state/client_backup";
import {ServerState, ServerStateHandler} from "../state/server_state";

/**
 * The Dispatcher is used to handle actions initiated by the UI.
 *
 * It connects a `socket` instance with the UI `state`. Only the Dispatcher should be sending messages on a `socket`.
 */
export abstract class MessageDispatcher<S, H extends StateHandler<S> = StateHandler<S>> extends Disposable{
    protected readonly socket: SocketStateHandler;
    protected readonly handler: H;
    protected constructor(socket: SocketStateHandler, handler: H) {
        super()
        this.socket = socket.fork(this);
        this.handler = handler.fork(this) as H;

        handler.onDispose.then(() => {
            this.socket.close()
        })
        this.socket.onDispose.then(() => {
            if (!this.isDisposed) this.dispose()
        })
    }

    get state() {
        return this.handler.state;
    }
}

export class NotebookMessageDispatcher extends MessageDispatcher<NotebookState, NotebookStateHandler> {
    constructor(socketState: SocketStateHandler, notebookState: NotebookStateHandler) {
        super(socketState, notebookState);

        // when the socket is opened, send a KernelStatus message to request the current status from the server.
        this.socket.observeKey("status", next => {
            if (next === "connected") {
                this.socket.send(new messages.KernelStatus(new messages.KernelBusyState(false, false)))
            }
        });

        this.socket.observeKey("error", err => {
            if (err) {
                ErrorStateHandler.addKernelError(this.handler.state.path, err.error)
            }
        })

        this.handler.observeKey("activeSignature", sig => {
            if (sig) {
                this.socket.send(new messages.ParametersAt(sig.cellId, sig.offset))
            }
        })

        this.handler.observeKey("activeCompletion", sig => {
            if (sig) {
                this.socket.send(new messages.CompletionsAt(sig.cellId, sig.offset, []))
            }
        })

        let nextReqId = 0;

        this.handler.observeKey("requestedDefinition", req => {
            if (req) {
                this.socket.send(new messages.GoToDefinitionRequest(req.cellOrFile, req.offset, nextReqId++));
            }
        })

        this.handler.updateHandler.addObserver((update, rep) => {
            if (rep) {
                // notify when a response message arrives
                const listener = this.socket.addInstanceListener(update.constructor as any, inst => {
                    if (inst.isResponse(update)) {
                        listener.dispose();
                        rep.resolve(inst as NotebookUpdate);
                    }
                })
            }
            this.sendUpdate(update)
        }).disposeWith(this);

        const cells: Record<number, StateView<CellState>> = {};
        const cellsState = this.handler.view("cells");
        this.handler.observeKey("cellOrder", (newOrder, updateResult) => {
            Object.values(updateResult.addedValues ?? {}).forEach(id => {
                if (id !== undefined && !cells[id]) {
                    const handler = cellsState.view(id)
                    cells[id] = handler
                    this.watchCell(handler)
                }
            })
            Object.values(updateResult.removedValues ?? {}).forEach(id => {
                if (id !== undefined && cells[id]) {
                    cells[id].tryDispose();
                    delete cells[id];
                }
            })
        })

        // periodically inform server about known global version, so it can clean up the version buffer
        let lastVersion = this.handler.updateHandler.globalVersion
        const interval = window.setInterval(
            () => {
                const currentVersion = this.handler.updateHandler.globalVersion
                if (currentVersion !== lastVersion) {
                    this.socket.send(new NotebookVersion(this.handler.state.path, this.handler.updateHandler.globalVersion))
                    lastVersion = currentVersion
                }
            },
            30000   // every 30 seconds
        );
        this.onDispose.then(() => window.clearInterval(interval))
    }

    private watchCell(cellView: StateView<CellState>) {
        const id = cellView.state.id;

        cellView.observeKey("currentSelection", range => {
            if (range) {
                this.socket.send(new messages.CurrentSelection(id, range))
            }
        })

    }

    private sendUpdate(upd: NotebookUpdate) {
        this.socket.send(upd)
        ClientBackup.updateNb(this.handler.state.path, upd)
            .catch(err => console.error("Error backing up update", err))
    }

    /*******************************
     ** Task management methods **
     *******************************/

    cancelTasks() {
        this.socket.send(new messages.CancelTasks(this.state.path))
    }

    cancelTask(id: string) {
        this.socket.send(new messages.CancelTasks(this.state.path, id));
    }

    /*******************************
     ** Notebook management methods **
     *******************************/

    clearOutput() {
        this.socket.send(new messages.ClearOutput())
    }

    downloadNotebook() {
        const path = window.location.pathname + "?download=true"
        const link = document.createElement('a');
        link.setAttribute("href", path);
        link.setAttribute("download", this.state.path);
        link.click()
    }

    /*******************************
     ** Kernel management methods **
     *******************************/

    reconnect(onlyIfClosed: boolean): void {
        console.log("Attempting to reconnect to notebook")
        this.socket.reconnect(onlyIfClosed)
        const errorView = this.socket.lens("error").disposeWith(this)
        errorView.addObserver(err => {
            // if there was an error on reconnect, push it to the notebook state so it can be displayed
            if (err) {
                console.error("error on reconnecting notebook", err)
                ErrorStateHandler.addKernelError(this.handler.state.path, err.error)
            }
        })

        this.socket.observeKey("status", status => {
            if (status === "connected") {
                this.socket.update(() => ({
                        error: setValue(undefined) // any errors from before are no longer relevant, right?
                }))
                errorView.tryDispose()
            }
        }).disposeWith(errorView)
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

    /*******************************
     ** Cell management methods **
     *******************************/

    runCells(cellIds: number[]) {
        // empty cellIds means run all of them!
        if (cellIds.length === 0) {
            cellIds = this.state.cellOrder
        }

        cellIds = collect(cellIds, id => this.state.cells[id]?.language !== "text" ? id : undefined);

        const [clientCells, serverCells] = partition(cellIds, id => {
            const cell = this.state.cells[id]
            if (cell) {
                return Object.keys(ClientInterpreters).includes(cell.language)
            } else {
                console.warn("Run requested for cell with ID", id, "but a cell with that ID was not found in", this.state.cells)
                return true // should this fail?
            }
        })
        clientCells.forEach(id => {
            const idx = cellIds.indexOf(id)
            const prevId = cellIds[idx - 1]
            const clientInterpreter = ClientInterpreter.forPath(this.state.path);
            if (clientInterpreter) {
                clientInterpreter.runCell(id, this, prevId)
            } else {
                const cell = this.state.cells[id];
                const message = `Missing Client Interpreter for cell ${cell.id} of type ${cell.language}`
                console.error(message)
                ErrorStateHandler.addKernelError(this.handler.state.path, new ServerErrorWithCause("Missing Client Interpreter", message, []))
            }
        })
        this.socket.send(new messages.RunCell(serverCells));
    }

    // TODO: move this out of dispatcher. Maybe think about a better way to deal with this whole thing.
    setCellOutput(cellId: number, output: Output | ClientResult) {
        if (output instanceof Output) {
            this.handler.cellsHandler.updateField(cellId, cellState => ({output: setValue([output])}));
        } else {
            this.handler.cellsHandler.updateField(cellId, cellState => ({results: setValue([output])}));
        }
    }

    runActiveCell() {
        const id = this.handler.state.activeCellId;
        if (id !== undefined) {
            this.runCells([id]);
        }
    }

    runToActiveCell() {
        const state = this.handler.state;
        const id = state.activeCellId;
        if (id !== undefined) {
            const activeIdx = state.cellOrder.indexOf(id)
            const cellsToRun = state.cellOrder.slice(0, activeIdx + 1);
            if (cellsToRun.length > 0) {
                this.runCells(cellsToRun)
            }
        }
    }

    runFromActiveCell() {
        const state = this.handler.state;
        const id = state.activeCellId;
        if (id !== undefined) {
            const activeIdx = state.cellOrder.indexOf(id)
            const cellsToRun = state.cellOrder.slice(activeIdx);
            if (cellsToRun.length > 0) {
                this.runCells(cellsToRun)
            }
        }
    }

    /*******************************
     ** Data streaming methods **
     *******************************/

    requestDataBatch(handleType: number, handleId: number, batchSize: number) {
        this.socket.send(new HandleData(handleType, handleId, batchSize, Either.right([])))
    }

    modifyDataStream(handleId: number, mods: TableOp[]) {
        this.socket.send(new ModifyStream(handleId, mods))
    }

    stopDataStream(handleType: number, handleId: number) {
        this.socket.send(new ReleaseHandle(handleType, handleId))
    }
}

// TODO: should this be a singleton too?
export class ServerMessageDispatcher extends MessageDispatcher<ServerState>{
    constructor(socket: SocketStateHandler) {
        super(socket, ServerStateHandler.get);

        socket.observeKey("error", err => {
            if (err) {
                ErrorStateHandler.addServerError(err.error)
            }
        }).disposeWith(this)
    }

    /*******************************
     ** Server management methods **
     *******************************/

    reconnect(onlyIfClosed: boolean) {
        console.warn("Attempting to reconnect to server") // TODO: once we have a proper place for server errors, we can display this log there.
        this.socket.reconnect(onlyIfClosed)
        const observeError = this.socket.observeKey("error", err => {
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
        }).disposeWith(this)

        // TODO: depending on how complicated reconnecting is, maybe we should just reload the page every time?
        this.socket.view("status").addObserver(status => {
            if (status === "connected") {
                console.warn("Reconnected successfully, now reconnecting to notebook sockets")
                this.socket.update(() => ({
                    error: undefined // any errors from before are no longer relevant, right?
                }))
                ServerStateHandler.reconnectNotebooks(onlyIfClosed)
                observeError.tryDispose()
            }
        }).disposeWith(this)
    }

    requestNotebookList() {
        this.socket.send(new messages.ListNotebooks([]))
    }

    requestRunningKernels() {
        this.socket.send(new messages.RunningKernels([]))
    }

    createNotebook(path?: string, content?: string) {
        const waitForNotebook = (nbPath: string) => {
            const disposable = this.handler.observeKey("notebooks", (current, updateResult) => {
                UpdateResult.addedOrChangedKeys(updateResult).forEach(newNb => {
                    if (newNb.includes(nbPath)) {
                        disposable.dispose()
                        ServerStateHandler.loadNotebook(newNb, true).then(nbInfo => {
                            nbInfo.handler.updateField("config", () => setProperty("open", true))
                            ServerStateHandler.selectFile(newNb)
                        })
                    }
                })
            })
        }
        if (path && content) {
            this.socket.send(new messages.CreateNotebook(path, content))
            waitForNotebook(path)
        } else {
            new DialogModal('Create Notebook', (path ?? 'path/to/notebook') + "/", 'Create').show().then(newNbInfo => {
                this.socket.send(new messages.CreateNotebook(newNbInfo.path, content, newNbInfo.template))
                waitForNotebook(newNbInfo.path)
            })
        }
    }

    renameNotebook(oldPath: string, newPath?: string) {
        if (newPath) {
            this.socket.send(new messages.RenameNotebook(oldPath, newPath))
        } else {
            new DialogModal('Rename Notebook', oldPath, 'Rename').show().then(newNbInfo => {
                this.socket.send(new messages.RenameNotebook(oldPath, newNbInfo.path))
            })
        }
    }

    copyNotebook(oldPath: string, newPath?: string) {
        if (newPath) {
            this.socket.send(new messages.CopyNotebook(oldPath, newPath))
        } else {
            new DialogModal('Copy Notebook', oldPath, 'Copy').show().then(newNbInfo => {
                this.socket.send(new messages.CopyNotebook(oldPath, newNbInfo.path))
            })
        }
    }

    deleteNotebook(path: string) {
        if (confirm(`Permanently delete ${path}?`)) {
            this.socket.send(new messages.DeleteNotebook(path))
        }
    }

    searchNotebooks(query: string) {
        this.socket.send(new messages.SearchNotebooks(query, []));
    }

    /*******************************
     ** UI methods (which don't   **
     ** really belong here)       **
     *******************************/

    viewAbout(section: string) {
        About.show(this, section)
    }
}
