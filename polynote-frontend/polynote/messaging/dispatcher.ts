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
import {NoUpdate, StateHandler} from "../state/state_handler";
import {CompletionHint, NotebookState, NotebookStateHandler, SignatureHint} from "../state/notebook_state";
import {ContentEdit} from "../data/content_edit";
import {NotebookInfo, ServerState, ServerStateHandler} from "../state/server_state";
import {ConnectionStatus, SocketStateHandler} from "../state/socket_state";
import {About} from "../ui/component/about";
import {ValueInspector} from "../ui/component/value_inspector";
import {arrDeleteFirstItem, collect, deepEquals, diffArray, equalsByKey, partition, removeKey} from "../util/helpers";
import {Either} from "../data/codec_types";
import {DialogModal} from "../ui/layout/modal";
import {ClientInterpreter, ClientInterpreters} from "../interpreter/client_interpreter";
import {OpenNotebooksHandler} from "../state/preferences";
import {ClientBackup} from "../state/client_backup";

/**
 * The Dispatcher is used to handle actions initiated by the UI.
 * It knows whether an Action should be translated to a message and then sent on the socket, or if it should be
 * handled by something else.
 */

export class NotebookMessageDispatcher {
    constructor(private socket: SocketStateHandler, private handler: NotebookStateHandler) {
        // when the socket is opened, send a KernelStatus message to request the current status from the server.
        socket.view("status").addObserver(next => {
            if (next === "connected") {
                socket.send(new messages.KernelStatus(new messages.KernelBusyState(false, false)))
            }
        });
        const errorView = socket.view("error")
        errorView.addObserver(err => {
            if (err) {
                this.handler.updateState(s => {
                    if (s.errors.find(e => deepEquals(e, err.error))) {
                        return NoUpdate
                    } else return {
                        ...s,
                        errors: [...s.errors, err.error]
                    }
                })
            }
        })
    }

    setCellOutput(id: number, result: ClientResult): void {
        result.toOutput().then(o => {
            this.handler.updateState(state => {
                this.sendUpdate(new messages.SetCellOutput(state.globalVersion, state.localVersion, id, o))
                return {
                    ...state,
                    cells: state.cells.map(cell => {
                        if (cell.id === id) {
                            return {...cell, results: [...cell.results, result]}
                        } else return cell
                    })
                }
            })
        })
    }

    /****************************
     ** Data streaming methods **
     ****************************/

    clearDataStream(handleId: number): void {
        this.handler.updateState(state => {
            return {
                ...state,
                activeStreams: {
                    ...state.activeStreams,
                    [handleId]: []
                }
            }
        })
    }

    modifyDataStream(handleId: number, mods: TableOp[]): void {
        this.socket.send(new ModifyStream(handleId, mods))
    }

    requestDataBatch(handleTypeId: number, handleId: number, batchSize: number): void {
        this.socket.send(new HandleData(handleTypeId, handleId, batchSize, Either.right([])))
    }

    stopDataStream(handleTypeId: number, handleId: number): void {
        this.socket.send(new ReleaseHandle(handleTypeId, handleId))
    }

    /*****************************
     ** Task management methods **
     *****************************/

    requestCancelTasks(): void {
        const state = this.handler.state
        this.socket.send(new messages.CancelTasks(state.path))
    }

    removeTask(taskId: string): void {
        this.handler.updateState(s => {
            return {
                ...s,
                kernel: {
                    ...s.kernel,
                    tasks: removeKey(s.kernel.tasks, taskId)
                }
            }
        })
    }

    /*****************************
     ** Cell management methods **
     *****************************/

    createCell(language: string, content: string, metadata: CellMetadata, prev: number): void {
        this.handler.updateState(state => {
            let localVersion = state.localVersion + 1;
            // generate the max ID here. Note that there is a possible race condition if another client is inserting a cell at the same time.
            const maxId = state.cells.reduce((acc, cell) => acc > cell.id ? acc : cell.id, -1)
            const cell = new NotebookCell(maxId + 1, language, content, [], metadata);
            const update = new messages.InsertCell(state.globalVersion, localVersion, cell, prev);
            this.sendUpdate(update);
            return {
                ...state,
                editBuffer: state.editBuffer.push(state.localVersion, update)
            }
        })
    }

    updateCell(cellId: number, edits: ContentEdit[], newContent?: string, metadata?: CellMetadata): void {
        this.handler.updateState(state => {
            let localVersion = state.localVersion + 1;
            const update = new messages.UpdateCell(state.globalVersion, localVersion, cellId, edits, metadata);
            this.sendUpdate(update);
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
    }

    deleteCell(id?: number){
        if (id === undefined) {
            id = this.handler.state.activeCell?.id;
        }
        if (id !== undefined) {
            this.handler.updateState(state => {
                state = {...state}
                const update = new messages.DeleteCell(state.globalVersion, ++state.localVersion, id as number);
                this.sendUpdate(update);
                state.editBuffer = state.editBuffer.push(state.localVersion, update)
                return state
            })
        }
    }

    setCellLanguage(cellId: number, language: string): void {
        const state = this.handler.state;
        this.sendUpdate(new messages.SetCellLanguage(state.globalVersion, state.localVersion, cellId, language));
    }

    removeCellError(cellId: number, error: RuntimeError | CompileErrors): void {
        this.handler.updateState(state => {
            return {
                ...state,
                cells: state.cells.map(cell => {
                    if (cell.id === cellId) {
                        if (error instanceof RuntimeError) {
                            return {
                                ...cell,
                                runtimeError: undefined
                            }
                        } else {
                            return {
                                ...cell,
                                compileErrors: cell.compileErrors.filter(e => ! deepEquals(e, error))
                            }
                        }
                    } else return cell
                })
            }
        })
    }

    requestCellRun(cellIds: number[]): void {
        this.handler.updateState(state => {
            // empty cellIds means run all of them!
            if (cellIds.length === 0) {
                cellIds = state.cells.map(c => c.id)
            }

            cellIds = collect(cellIds, id => state.cells.find(cell => cell.id === id)?.language !== "text" ? id : undefined);

            const [clientCells, serverCells] = partition(cellIds, id => {
                const cell = state.cells.find(c => c.id === id)
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
                    this.handler.updateState(s => {
                        return {
                            ...s,
                            errors: [...s.errors, new ServerErrorWithCause("Missing Client Interpreter", message, [])]
                        }
                    })
                }
            })
            this.socket.send(new messages.RunCell(serverCells));
            return {
                ...state,
                cells: state.cells.map(cell => {
                    if (cellIds.includes(cell.id)) {
                        return { ...cell, results: [] }
                    } else return cell
                })
            }
        })
    }

    setSelectedCell(selected: number | undefined, options?: { relative?: "above" | "below", skipHiddenCode?: boolean, editing?: boolean}): void {
        this.handler.updateState(state => {
            let id = selected;
            if (options?.relative === "above")  {
                const anchorIdx = state.cells.findIndex(cell => cell.id === id);
                let prevIdx = anchorIdx - 1;
                id = state.cells[prevIdx]?.id;
                if (options?.skipHiddenCode) {
                    while (state.cells[prevIdx]?.metadata.hideSource) {
                        --prevIdx;
                    }
                    id = state.cells[prevIdx]?.id;
                }
            } else if (options?.relative === "below") {
                const anchorIdx = state.cells.findIndex(cell => cell.id === id);
                let nextIdx = anchorIdx + 1
                id = state.cells[nextIdx]?.id;
                if (options?.skipHiddenCode) {
                    while (state.cells[nextIdx]?.metadata.hideSource) {
                        ++nextIdx;
                    }
                    id = state.cells[nextIdx]?.id;
                }
            }
            if (id === undefined && (options?.relative !== undefined)) { // if ID is undefined, create cell above/below as needed
                this.insertCell(options.relative)
                    .then(newId => this.setSelectedCell(newId))
                return NoUpdate
            } else {
                id = id ?? (selected === -1 ? 0 : selected); // if "above" or "below" don't exist, just select `selected`.
                const activeCell = state.cells.find(cell => cell.id === id)
                return {
                    ...state,
                    activeCell: activeCell,
                    cells: state.cells.map(cell => {
                        return {
                            ...cell,
                            selected: cell.id === id,
                            editing: cell.id === id && (options?.editing ?? false)
                        }
                    })
                }
            }
        })
    }

    deselectCell(cellId: number): void {
        this.handler.updateState(state => {
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
    }

    setCellHighlight(cellId: number, range: PosRange, className: string): void {
        this.handler.updateState(state => {
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
    }

    currentSelection(cellId: number, range: PosRange): void {
        this.socket.send(new messages.CurrentSelection(cellId, range));
        this.handler.updateState(state => {
            return {
                ...state,
                cells: state.cells.map(cell => {
                    return {
                        ...cell,
                        currentSelection: cell.id === cellId ? range : undefined
                    }
                })
            }
        })
    }

    clearCellEdits(cellId: number): void {
        this.handler.updateState(state => {
            return {
                ...state,
                cells: state.cells.map(cell => {
                    if (cell.id === cellId) {
                        return {
                            ...cell,
                            pendingEdits: []
                        }
                    } else return cell
                })
            }
        })
    }

    /*********************************
     ** Notebook management methods **
     *********************************/

    toggleNotebookConfig(open?: boolean): void {
        this.handler.updateState(s => {
            return {
                ...s,
                config: {...s.config, open: (open ?? !s.config.open)}
            }
        })
    }

    closeNotebook(): void {
        this.socket.close()
    }

    /*******************************
     ** Kernel management methods **
     *******************************/

    reconnect(onlyIfClosed: boolean): void {
        console.log("Attempting to reconnect to server")
        this.socket.reconnect(onlyIfClosed)
        const errorView = this.socket.view("error")
        errorView.addObserver(err => {
            // if there was an error on reconnect, push it to the notebook state so it can be displayed
            if (err) {
                console.error("error on reconnecting notebook", err)
                this.handler.updateState(s => {
                    return {
                        ...s,
                        errors: [...s.errors, err.error]
                    }
                })
            }
        });
        this.socket.view("status", undefined, errorView).addObserver(status => {
            if (status === "connected") {
                this.handler.updateState(s => {
                    return {
                        ...s,
                        errors: [] // any errors from before are no longer relevant, right?
                    }
                })
                errorView.dispose()
            }
        });
    }

    startKernel(): void {
        this.socket.send(new messages.StartKernel(messages.StartKernel.NoRestart));
    }

    killKernel(): void {
        if (confirm("Kill running kernel? State will be lost.")) {
            this.socket.send(new messages.StartKernel(messages.StartKernel.Kill));
        }
    }

    /*******************************
     ** Notebook-level UI methods **
     *******************************/

    hideValueInspector(): void {
        ValueInspector.get.hide();
    }

    showValueInspector(result: ResultValue, tab?: string): void {
        ValueInspector.get.inspect(this, this.handler, result, tab)
    }

    downloadNotebook(): void {
        const path = window.location.pathname + "?download=true"
        const link = document.createElement('a');
        link.setAttribute("href", path);
        link.setAttribute("download", this.handler.state.path);
        link.click()
    }

    updateConfig(config: NotebookConfig): void {
        this.handler.updateState(state => {
            state = {...state};
            const update = new messages.UpdateConfig(state.globalVersion, ++state.localVersion, config);
            this.sendUpdate(update);
            state.editBuffer = state.editBuffer.push(state.localVersion, update);
            return state;
        })
    }

    removeError(err: ServerErrorWithCause): void {
        this.handler.updateState(s => {
            return {
                ...s,
                errors: s.errors.filter(e => ! deepEquals(e, err))
            }
        })
    }

    requestNotebookVersion(version: number): void {
        const state = this.handler.state
        this.socket.send(new messages.NotebookVersion(state.path, version))
    }

    requestClearOutput(): void {
        this.socket.send(new messages.ClearOutput());
    }

    /**************************
     ** IntelliSense-related **
     **************************/

    requestCompletions(cellId: number, offset: number, resolve: (completion: CompletionHint) => void, reject: () => void): void {
        this.socket.send(new messages.CompletionsAt(cellId, offset, []));
        this.handler.updateState(state => {
            if (state.activeCompletion) {
                state.activeCompletion.reject();
            }
            return {
                ...state,
                activeCompletion: {resolve, reject}
            }
        })
    }

    requestSignature(cellId: number, offset: number, resolve: (value: SignatureHint) => void, reject: () => void): void {
        this.socket.send(new messages.ParametersAt(cellId, offset));
        this.handler.updateState(state => {
            if (state.activeSignature) {
                state.activeSignature.reject();
            }
            return {
                ...state,
                activeSignature: {resolve, reject}
            }
        })
    }

    /****************
     ** Commenting **
     ****************/

    createComment(cellId: number, comment: CellComment): void {
        const state = this.handler.state;
        this.sendUpdate(new messages.CreateComment(state.globalVersion, state.localVersion, cellId, comment));
    }

    deleteComment(cellId: number, commentId: string): void {
        const state = this.handler.state;
        this.sendUpdate(new messages.DeleteComment(state.globalVersion, state.localVersion, cellId, commentId));
    }

    updateComment(cellId: number, commentId: string, range: PosRange, content: string): void {
        const state = this.handler.state;
        this.sendUpdate(new messages.UpdateComment(state.globalVersion, state.localVersion, cellId, commentId, range, content));
    }

    private sendUpdate(upd: NotebookUpdate) {
        this.socket.send(upd)
        ClientBackup.updateNb(this.handler.state.path, upd)
            .catch(err => console.error("Error backing up update", err))
    }

    // Helper methods
    /**
     * Helper for inserting a cell.
     *
     * @param direction   Whether to insert below of above the anchor
     * @param maybeAnchor The anchor. If it is undefined, the anchor is based on the currently selected cell. If none is
     *                    selected, the anchor is either the first or last cell (depending on the direction supplied).
     *                    The anchor is used to determine the location, language, and metadata to supply to the new cell.
     * @return            A Promise that resolves with the inserted cell's id.
     */
    insertCell(direction: 'above' | 'below', maybeAnchor?: {id: number, language: string, metadata: CellMetadata, content?: string}): Promise<number> {
        const currentState = this.handler.state;
        const currentCell = currentState.activeCell ?? (direction == 'above' ? currentState.cells[0] : currentState.cells[currentState.cells.length - 1]);
        const anchor = maybeAnchor ?? {id: currentCell.id, language: currentCell.language, metadata: currentCell.metadata};

        const anchorIdx = currentState.cells.findIndex(cell => cell.id === anchor!.id);
        const prevIdx = direction === 'above' ? anchorIdx - 1 : anchorIdx;
        const maybePrev = currentState.cells[prevIdx];
        this.createCell(anchor.language, anchor.content ?? '', anchor.metadata, maybePrev.id);
        return new Promise((resolve, reject) => {
            const obs = this.handler.addObserver(state => {
                const anchorCellIdx = state.cells.findIndex(cell => cell.id === maybePrev.id)
                const didInsert = state.cells.find((cell, idx) => {
                    const below = idx > anchorCellIdx;
                    const newer = cell.id > maybePrev.id;
                    const matches =
                        cell.language === anchor.language &&
                        cell.content === anchor.content &&
                        deepEquals(cell.metadata, anchor.metadata);

                    return below && newer && matches
                });
                if (didInsert !== undefined) {
                    this.handler.removeObserver(obs);
                    resolve(didInsert.id)
                }
            })
        })
    }

    runActiveCell() {
        const id = this.handler.state.activeCell?.id;
        if (id !== undefined) {
            this.requestCellRun([id]);
        }
    }

    runToActiveCell() {
        const state = this.handler.state;
        const id = state.activeCell?.id;
        const activeIdx = state.cells.findIndex(cell => cell.id === id);
        const cellsToRun = state.cells.slice(0, activeIdx + 1).map(c => c.id);
        if (cellsToRun.length > 0) {
            this.requestCellRun(cellsToRun)
        }
    }

}

// TODO: should this be a singleton too?
export class ServerMessageDispatcher {
    private handler: ServerStateHandler = ServerStateHandler.get;
    constructor(private socket: SocketStateHandler) {
        const errorView = socket.view("error")
        errorView.addObserver(err => {
            if (err) {
                this.handler.updateState(s => {
                    if (s.errors.find(e => deepEquals(e, {err: err.error}))) {
                        return NoUpdate
                    } else return {
                        ...s,
                        errors: [...s.errors, {err: err.error}]
                    }
                })
            }
        })

        this.handler.view("openNotebooks").addObserver(nbs => {
            OpenNotebooksHandler.updateState(() => nbs)
        })
    }

    requestNotebooksList(): void {
        this.socket.send(new messages.ListNotebooks([]));
    }

    loadNotebook(path: string, open?: boolean): Promise<NotebookInfo> {
        return new Promise(resolve => {
            this.handler.updateState(s => {
                let notebooks = s.notebooks
                if (! s.notebooks[path])  {
                    notebooks = {...notebooks, [path]: ServerStateHandler.loadNotebook(path).loaded}
                }
                return {
                    ...s,
                    notebooks: notebooks,
                    openNotebooks: open && !s.openNotebooks.includes(path) ? [...s.openNotebooks, path] : s.openNotebooks
                };
            });

            const info = ServerStateHandler.getOrCreateNotebook(path)
            const checkIfLoaded = () => {
                const maybeLoaded = ServerStateHandler.getOrCreateNotebook(path)
                if (maybeLoaded.loaded && maybeLoaded.info) {
                    info.handler.removeObserver(loading);
                    resolve(maybeLoaded)
                }
            }
            const loading = info.handler.addObserver(checkIfLoaded)
            checkIfLoaded()
        })
    }

    createNotebook(path?: string, content?: string): void {
        const waitForNotebook = (nbPath: string) => {
            const nbs = this.handler.view("notebooks")
            nbs.addObserver((current, prev) => {
                const [added, _] = diffArray(Object.keys(current), Object.keys(prev))
                added.forEach(newNb => {
                    if (newNb.includes(nbPath)) {
                        nbs.dispose()
                        this.loadNotebook(newNb, true).then(nbInfo => {
                            nbInfo.info?.dispatcher.toggleNotebookConfig(true)  // open config automatically for newly created notebooks.
                            this.setSelectedNotebook(newNb);
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
    }

    renameNotebook(oldPath: string, newPath?: string): void {
        if (newPath) {
            this.socket.send(new messages.RenameNotebook(oldPath, newPath))
        } else {
            new DialogModal('Rename Notebook', oldPath, 'Rename').show().then(newPath => {
                this.socket.send(new messages.RenameNotebook(oldPath, newPath))
            })
        }
    }

    copyNotebook(oldPath: string, newPath?: string): void {
        if (newPath) {
            this.socket.send(new messages.CopyNotebook(oldPath, newPath))
        } else {
            new DialogModal('Copy Notebook', oldPath, 'Copy').show().then(newPath => {
                this.socket.send(new messages.CopyNotebook(oldPath, newPath))
            })
        }
    }

    deleteNotebook(path: string): void {
        this.socket.send(new messages.DeleteNotebook(path));
    }

    closeNotebook(path: string): void {
        ServerStateHandler.closeNotebook(path);
        this.handler.updateState(s => {
            return {
                ...s,
                notebooks: {
                    ...s.notebooks,
                    [path]: false
                },
                openNotebooks: arrDeleteFirstItem(s.openNotebooks, path)
            }
        })
    }

    viewAbout(section: string): void {
        About.show(this, section);
    }

    setSelectedNotebook(path: string): void {
        this.handler.updateState(s => {
            return {
                ...s,
                currentNotebook: path
            }
        })
    }

    requestRunningKernels(): void {
        this.socket.send(new messages.RunningKernels([]));
    }

    removeError(err: ServerErrorWithCause): void {
        this.handler.updateState(s => {
            return {
                ...s,
                errors: s.errors.filter(e => ! deepEquals(e.err, err))
            }
        })
    }

    reconnect(onlyIfClosed: boolean): void {
        console.warn("Attempting to reconnect to notebook") // TODO: once we have a proper place for server errors, we can display this log there.
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
                    this.handler.updateState(s => {
                        return {
                            ...s,
                            errors: [...s.errors, {err: err.error}]
                        }
                    })
                }
            }
        })
        // TODO: depending on how complicated reconnecting is, maybe we should just reload the page every time?
        this.socket.view("status", undefined, errorView).addObserver(status => {
            if (status === "connected") {
                console.warn("Reconnected successfully, now reconnecting to notebook sockets")
                this.handler.updateState(s => {
                    return {
                        ...s,
                        errors: [] // any errors from before are no longer relevant, right?
                    }
                })
                ServerStateHandler.reconnectNotebooks(onlyIfClosed)
                errorView.dispose()
            }
        })
    }

}
