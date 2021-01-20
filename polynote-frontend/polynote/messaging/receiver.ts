/**
 * The MessageReceiver is used to translate external events into state changes.
 * So far, the only external events are socket messages.
 */
import {CellState, NotebookState, NotebookStateHandler, outputs} from "../state/notebook_state";
import {ServerState, ServerStateHandler} from "../state/server_state";
import * as messages from "../data/messages";
import {Identity, Message, TaskInfo, TaskStatus} from "../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../data/data";
import {purematch} from "../util/match";
import {ContentEdit} from "../data/content_edit";
import {
    ClearResults,
    ClientResult,
    CompileErrors,
    ExecutionInfo,
    Output,
    PosRange,
    Result,
    ResultValue,
    RuntimeError
} from "../data/result";
import {Disposable, NoUpdate, StateHandler, StateView, StateWrapper} from "../state/state_handler";
import {SocketStateHandler} from "../state/socket_state";
import {arrDelete, deepCopy, arrInsert, collect, deepEquals, mapValues, removeKeys, unzip} from "../util/helpers";
import {ClientInterpreters} from "../interpreter/client_interpreter";
import {ClientBackup} from "../state/client_backup";
import {ErrorStateHandler} from "../state/error_state";

class MessageReceiver<S> extends Disposable {
    constructor(protected socket: SocketStateHandler, protected state: StateHandler<S>) {
        super()
    }

    receive<M extends messages.Message, C extends (new (...args: any[]) => M) & typeof messages.Message>(msgType: C, fn: (state: S, ...args: ConstructorParameters<typeof msgType>) => S | typeof NoUpdate) {
        this.socket.addMessageListener(msgType, (...args: ConstructorParameters<typeof msgType>) => {
            this.state.update(s => {
                return fn(s, ...args) ?? NoUpdate
            }, this)
        })
    }

    // Handle a message as if it were received on the wire. Useful for short-circuiting or simulating server messages.
    inject(msg: Message) {
        this.socket.handleMessage(msg)
    }
}

/**
 *  Filter out state updates that originate from the server. Useful if you need to do something IFF the update was
 *  triggered by a user action in this UI.
 */
export class IgnoreServerUpdatesWrapper<S> extends StateWrapper<S> {
    protected matchSource(updateSource: any, x: any): boolean {
        return ! (updateSource instanceof MessageReceiver)
    }

    protected compare(s1: any, s2: any): boolean {
        return deepEquals(s1, s2)
    }

    view<K extends keyof S>(key: K): StateView<S[K]> {
        return new IgnoreServerUpdatesWrapper(super.view(key));
    }
}

export class NotebookMessageReceiver extends MessageReceiver<NotebookState> {
    constructor(socket: SocketStateHandler, state: NotebookStateHandler) {
        super(socket, state);

        socket.view("status").addObserver(status => {
            if (status === "disconnected") {
                state.update(s => {
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            status: status
                        },
                        cells: mapValues(s.cells, cell => ({
                            ...cell,
                            running: false,
                            queued: false,
                            currentHighlight: undefined
                        }))
                    }
                })
            } else {
                state.update(s => {
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            status: s.kernel.status === 'disconnected' ? 'dead' : s.kernel.status // we know it can't be disconnected
                        }
                    }
                })
            }
        }, state)

        this.receive(messages.CompletionsAt, (s, cell, offset, completions) => {
            if (s.activeCompletion) {
                s.activeCompletion.resolve({cell, offset, completions});
                return {
                    ...s,
                    activeCompletion: undefined
                }
            } else {
                console.warn("Got completion response but there was no activeCompletion, this is a bit odd.", {cell, offset, completions})
                return NoUpdate
            }
        });
        this.receive(messages.ParametersAt, (s, cell, offset, signatures) => {
            if (s.activeSignature) {
                s.activeSignature.resolve({cell, offset, signatures})
                return {
                    ...s,
                    activeSignature: undefined
                }
            } else {
                console.warn("Got signature response but there was no activeSignature, this is a bit odd.", {cell, offset, signatures})
                return NoUpdate
            }
        });
        this.receive(messages.NotebookVersion, (s, path, serverGlobalVersion) => {
            if (state.updateHandler.globalVersion === -1) {
                // first version, just set it
                state.updateHandler.globalVersion = serverGlobalVersion
            } else if (state.updateHandler.globalVersion !== serverGlobalVersion){
                // this means we must have been disconnected for a bit and the server state has changed.
                document.location.reload() // is it ok to trigger the reload here?
            }
            return NoUpdate
        });
        this.receive(messages.NotebookCells, (s: NotebookState, path: string, notebookCells: NotebookCell[], config?: NotebookConfig) => {
            const symbols = {...s.kernel.symbols};
            const cellStates = notebookCells.map(cell => {
                const cellState = this.cellToState(cell);
                if (!symbols[cell.id]) {
                    symbols[cell.id] = {};
                }
                // unfortunately, this can't be an anonymous function if we want TS to correctly narrow the type.
                function isRV(maybe: ResultValue | ClientResult): maybe is ResultValue {
                    return maybe instanceof ResultValue
                }

                const newResults = cellState.results.filter(isRV);
                if (newResults.length > 0) {
                    const cellResults = {...symbols[cell.id]};
                    newResults.forEach(rv => {
                        cellResults[rv.name] = rv;
                    });
                    symbols[cell.id] = cellResults;
                }
                return cellState;
            });
            const cells = cellStates.reduce((acc, next) => ({...acc, [next.id]: next}), {});
            const cellOrder = notebookCells.map(cell => cell.id)

            // add this notebook to the backups
            ClientBackup.addNb(path, notebookCells, config)
                // .then(backups => console.log("Added new backup. All backups for this notebook:", backups))
                .catch(err => console.error("Error adding backup", err));

            return {
                ...s,
                path,
                cells,
                cellOrder,
                config: {...s.config, config: config ?? NotebookConfig.default},
                kernel: {
                    ...s.kernel,
                    symbols
                }
            }
        });
        this.receive(messages.KernelStatus, (s, update) => {
            return purematch<messages.KernelStatusUpdate, NotebookState | typeof NoUpdate>(update)
                .when(messages.UpdatedTasks, tasks => {
                    const taskMap = tasks.reduce<Record<string, TaskInfo>>((acc, next) => {
                        acc[next.id] = next
                        return acc
                    }, {})
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            tasks: {...s.kernel.tasks, ...taskMap}
                        }
                    }
                })
                .whenInstance(messages.KernelBusyState, kernelState => {
                    const status = kernelState.asStatus;
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            status,
                            symbols: status === 'dead' ? {} : s.kernel.symbols
                        }
                    }
                })
                .when(messages.KernelInfo, info => {
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            info: info
                        },
                        // Getting KernelInfo means we successfully launched a new kernel, so we can clear any old errors lying around.
                        // This seems a bit hacky, maybe there's a better way to clear these errors?
                        errors: []
                    }
                })
                .when(messages.ExecutionStatus, (id, pos) => {
                    return {
                        ...s,
                        cells:  {
                            ...s.cells,
                            [id]: {
                                ...s.cells[id],
                                currentHighlight: pos ? { range: pos, className: "currently-executing" } : undefined
                            }
                        }
                    }
                })
                .when(messages.PresenceUpdate, (added, removed) => {
                    const activePresence = {...s.activePresence}
                    added.forEach(p => {
                        if (activePresence[p.id] === undefined) {
                            const color = Object.keys(activePresence).length % 8;
                            activePresence[p.id] = {id: p.id, name: p.name, color: `presence${color}`, avatar: p.avatar}
                        }
                    });
                    removed.forEach(id => delete activePresence[id]);

                    return {
                        ...s,
                        activePresence: activePresence,
                        cells: mapValues(s.cells, cell => ({ ...cell, presence: cell.presence.filter(p => ! removed.includes(p.id))}))
                    }
                })
                .when(messages.PresenceSelection, (id, cellId, range) => {
                    const maybePresence = s.activePresence[id]
                    if (maybePresence) {
                        return {
                            ...s,
                            activePresence: {
                                ...s.activePresence,
                                [id]: {
                                    ...maybePresence,
                                    selection: {cellId, range}
                                }
                            },
                            cells: {
                                ...s.cells,
                                [cellId]: {
                                    ...s.cells[cellId],
                                    presence: [...s.cells[cellId].presence, {
                                        id: maybePresence.id,
                                        name: maybePresence.name,
                                        color: maybePresence.color,
                                        range: range
                                    }]
                                }
                            }
                        }
                    } else return NoUpdate
                })
                .when(messages.KernelError, (err) => {
                    ErrorStateHandler.addKernelError(s.path, err)
                    return NoUpdate
                })
                .when(messages.CellStatusUpdate, (cellId, status) => {
                    // Special handling for queuing cells: to ensure the correct order in the list, we'll handle creating
                    // the queued task right now.
                    // This is needed because TaskManager.queue both enqueues the cell AND waits until the queue is empty
                    // and the cell is ready to be run. Unfortunately, this means that the backend sends the  Queue Task
                    // AFTER the Queue CellStatusUpdate, and this race condition can mess up the order of tasks on the sidebar.
                    // TODO: rethink how TaskManager.queue works, or figure out some other way to order this deterministically.
                    let kernel = s.kernel;
                    if (status === TaskStatus.Queued) {
                        const taskId = `Cell ${cellId}`;
                        kernel = {
                            ...s.kernel,
                            tasks: {
                                ...s.kernel.tasks,
                                [taskId]: new TaskInfo(taskId, taskId, '', TaskStatus.Queued, 0)
                            }
                        }
                    }

                    return {
                        ...s,
                        cells: {
                            ...s.cells,
                            [cellId]: {
                                ...s.cells[cellId],
                                queued: status === TaskStatus.Queued,
                                running: status === TaskStatus.Running,
                                error: status === TaskStatus.Error,
                            }
                        },
                        kernel
                    }
                })
                .otherwiseThrow || NoUpdate
        });
        this.receive(messages.NotebookUpdate, (s: NotebookState, update: messages.NotebookUpdate) => {
            if (update.globalVersion >= state.updateHandler.globalVersion) {

                update = state.updateHandler.rebaseUpdate(update)

                const res = purematch<messages.NotebookUpdate, NotebookState>(update)
                    .when(messages.UpdateCell, (g, l, id: number, edits: ContentEdit[], metadata?: CellMetadata) => {
                        return {
                            ...s,
                            cells: {
                                ...s.cells,
                                [id]: {
                                    ...s.cells[id],
                                    incomingEdits: edits,
                                    content: edits.reduce<string>((content, edit) => edit.apply(content), s.cells[id].content),
                                    metadata: metadata || s.cells[id].metadata,
                                }
                            }
                        }
                    })
                    .when(messages.InsertCell, (g, l, cell: NotebookCell, after: number) => {
                        const newCell = this.cellToState(cell);
                        const insertIdx = s.cellOrder.findIndex(id => id === after) + 1;
                        return {
                            ...s,
                            cells: {
                                ...s.cells,
                                [cell.id]: newCell
                            },
                            cellOrder: arrInsert(s.cellOrder, insertIdx, newCell.id)
                        }
                    })
                    .when(messages.DeleteCell, (g, l, id: number) => {
                        const idx = s.cellOrder.indexOf(id)
                        if (idx > -1) {
                            return {
                                ...s,
                                cells: {
                                    ...removeKeys(s.cells, id),
                                },
                                cellOrder: arrDelete(s.cellOrder, idx),
                                activeCellId: s.activeCellId === id ? undefined : s.activeCellId // clear activeCellId if it was deleted.
                            }
                        } else return s
                    })
                    .when(messages.UpdateConfig, (g, l, config: NotebookConfig) => {
                        return {
                            ...s,
                            config: {...s.config, config}
                        }
                    })
                    .when(messages.SetCellLanguage, (g, l, id: number, language: string) => {
                        return {
                            ...s,
                            cells: {
                                ...s.cells,
                                [id]: {
                                    ...s.cells[id],
                                    language
                                }
                            }
                        }
                    })
                    .when(messages.SetCellOutput, (g, l, id: number, output?: Output) => {
                        // is `output` ever undefined??
                        if (output) {
                            return {
                                ...s,
                                cells: {
                                    ...s.cells,
                                    [id]: {
                                        ...s.cells[id],
                                        output: outputs([output], true)
                                    }
                                }
                            }
                        } else return s
                    })
                    .when(messages.CreateComment, (g, l, id: number, comment: CellComment) => {
                        return {
                            ...s,
                            cells: {
                                ...s.cells,
                                [id]: {
                                    ...s.cells[id],
                                    comments: {
                                        ...s.cells[id].comments,
                                        [comment.uuid]: comment // we're trusting the server to be correct here.
                                    }
                                }
                            }
                        }
                    })
                    .when(messages.UpdateComment, (g, l, id: number, commentId: string, range: PosRange, content: string) => {
                        const prevComment = s.cells[id].comments[commentId];
                        const updatedComment = new CellComment(commentId, range, prevComment.author, prevComment.authorAvatarUrl, prevComment.createdAt, content)
                        return {
                            ...s,
                            cells: {
                                ...s.cells,
                                [id]: {
                                    ...s.cells[id],
                                    comments: {
                                        ...s.cells[id].comments,
                                        [commentId]: updatedComment
                                    }
                                }
                            }
                        }
                    })
                    .when(messages.DeleteComment, (g, l, id: number, commentId: string) => {
                        const comments = {...s.cells[id].comments}
                        delete comments[commentId]
                        return {
                            ...s,
                            cells: {
                                ...s.cells,
                                [id]: {
                                    ...s.cells[id],
                                    comments: {...comments}
                                }
                            }
                        }
                    }).otherwiseThrow ?? s;

                // make sure to update backups.
                ClientBackup.updateNb(s.path, update)
                    .catch(err => console.error("Error updating backup", err));

                return res
            } else {
                console.warn(
                    "Ignoring NotebookUpdate with globalVersion", update.globalVersion,
                    "that is less than our globalVersion", state.updateHandler.globalVersion,
                    ". This might mean something is wrong.", update)
                return NoUpdate
            }
        });
        this.receive(messages.CellResult, (s, cellId, result) => {
            if (cellId < 0 && ! (result instanceof ResultValue)) { // from a predef cell
                return purematch<Result, NotebookState | typeof NoUpdate>(result)
                    .whenInstance(CompileErrors, result => {
                        // TODO: should somehow save this state somewhere!! Maybe this should also go into s.errors?
                        console.warn("Something went wrong compiling a predef cell", result)
                        return NoUpdate
                    })
                    .whenInstance(RuntimeError, result => {
                        ErrorStateHandler.addKernelError(s.path, result.error)
                        return NoUpdate
                    })
                    .otherwise(NoUpdate)
            } else {
                const symbols = {...s.kernel.symbols};
                if (['busy', 'idle'].includes(s.kernel.status) && result instanceof ResultValue) {
                    symbols[cellId] = {...(symbols[cellId] || {})};
                    symbols[cellId][result.name] = result;
                }

                const cells = cellId < 0 ? s.cells : {...s.cells, [cellId]: this.parseResults(s.cells[cellId], [result])}
                return {
                    ...s,
                    cells,
                    kernel: {...s.kernel, symbols }
                }
            }
        });

        //************* Streaming Messages ****************
        this.receive(messages.HandleData, (s, handlerType, handleId, count, data) => {
            return {
                ...s,
                activeStreams: {
                    ...s.activeStreams,
                    [handleId]: [...(s.activeStreams[handleId] || []), new messages.HandleData(handlerType, handleId, count, data)]
                }
            }
        })

        this.receive(messages.ModifyStream, (s, fromHandle, ops, newRepr) => {
            return {
                ...s,
                activeStreams: {
                    ...s.activeStreams,
                    [fromHandle]: [...(s.activeStreams[fromHandle] || []), new messages.ModifyStream(fromHandle, ops, newRepr)]
                }
            }
        })
    }

    private cellToState(cell: NotebookCell): CellState {
        return this.parseResults({
            id: cell.id,
            language: cell.language,
            content: cell.content,
            metadata: cell.metadata,
            comments: cell.comments,
            output: [],
            results: [],
            compileErrors: [],
            runtimeError: undefined,
            incomingEdits: [],
            outgoingEdits: [],
            presence: [],
            editing: false,
            selected: false,
            error: false,
            running: false,
            queued: false,
            currentSelection: undefined,
            currentHighlight: undefined,
        }, cell.results);
    }

    private parseResults(cell: CellState, results: Result[]): CellState {
        return results.reduce<CellState>((cell, result) => {
            return purematch<Result, CellState>(result)
                .when(ClearResults, () => {
                    return {...cell, output: [], results: [], compileErrors: [], runtimeError: undefined, error: false}
                })
                .whenInstance(ResultValue, result => {
                    return {...cell, results: [...cell.results, result]}

                })
                .whenInstance(CompileErrors, result => {
                    return {...cell, compileErrors: [...cell.compileErrors, result], error: true}
                })
                .whenInstance(RuntimeError, result => {
                    return {...cell, runtimeError: result, error: true}
                })
                .whenInstance(Output, result => {
                    return {...cell, output: [result]}
                })
                .whenInstance(ExecutionInfo, result => {
                    return {
                        ...cell,
                        metadata: cell.metadata.copy({executionInfo: result})
                    }
                })
                .whenInstance(ClientResult, result => {
                    return {...cell, results: [...cell.results, result]}
                }).otherwiseThrow || cell
        }, cell)
    }
}

export class ServerMessageReceiver extends MessageReceiver<ServerState> {
    public notebooks: Record<string, NotebookMessageReceiver> = {};

    constructor() {
        super(SocketStateHandler.global, ServerStateHandler.get);

        this.socket.view("status").addObserver(status => {
            this.state.update(s => {
                return {
                    ...s, connectionStatus: status
                }
            })
        }, this.state);

        this.receive(messages.Error, (s, code, err) => {
            ErrorStateHandler.addServerError(err)
            return NoUpdate
        });

        this.receive(messages.CreateNotebook, (s, path) => {
            return {
                ...s,
                notebooks: {
                    ...s.notebooks,
                    [path]: ServerStateHandler.getOrCreateNotebook(path).loaded
                }
            }
        });
        this.receive(messages.RenameNotebook, (s, oldPath, newPath) => {
            ServerStateHandler.renameNotebook(oldPath, newPath)
            ErrorStateHandler.notebookRenamed(oldPath, newPath) // TODO: there's probably a better way to do this, some sort of Ref value
            return NoUpdate // `renameNotebook` already takes care of updating the state.
        });
        this.receive(messages.DeleteNotebook, (s, path) => {
            ServerStateHandler.deleteNotebook(path)
            return NoUpdate // `deleteNotebook` already takes care of updating the state.
        });
        this.receive(messages.ListNotebooks, (s, paths) => {
            const notebooks = {...s.notebooks}
            paths.forEach(path => {
                notebooks[path] = ServerStateHandler.getOrCreateNotebook(path).loaded
            })
            return { ...s, notebooks }
        });
        this.receive(messages.ServerHandshake, (s, interpreters, serverVersion, serverCommit, identity, sparkTemplates) => {
            // First, we need to check to see if versions match. If they don't, we need to reload to clear out any
            // messed up state!
            if (s.serverVersion !== "unknown" && serverVersion !== s.serverVersion) {
                window.location.reload()
            }

            // inject the client interpreters here as well.
            Object.keys(ClientInterpreters).forEach(key => {
                if (!ClientInterpreters[key].hidden)
                    interpreters[key] = ClientInterpreters[key].languageTitle;
            });

            return {
                ...s,
                interpreters: interpreters,
                serverVersion: serverVersion,
                serverCommit: serverCommit,
                identity: identity ?? new Identity("Unknown User", null),
                sparkTemplates: sparkTemplates,
            }
        });
        this.receive(messages.RunningKernels, (s, kernelStatuses) => {
            const notebooks = {...s.notebooks}
            kernelStatuses.forEach(kv => {
                const path = kv.first;
                const status = kv.second;
                const nbInfo = ServerStateHandler.getOrCreateNotebook(path)
                nbInfo.handler.update(nbState => {
                    return {
                        ...nbState,
                        kernel: {
                            ...nbState.kernel,
                            status: status.asStatus
                        }
                    }
                })
                notebooks[path] = nbInfo.loaded
            })
            return { ...s, notebooks}
        })
    }
}
