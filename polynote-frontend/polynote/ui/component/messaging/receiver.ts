/**
 * The MessageReceiver is used to translate external events into state changes.
 * So far, the only external events are socket messages.
 */
import {CellState, NotebookState, NotebookStateHandler} from "../state/notebook_state";
import {ServerState, ServerStateHandler} from "../state/server_state";
import * as messages from "../../../data/messages";
import {TaskInfo} from "../../../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../../../data/data";
import {purematch} from "../../../util/match";
import {ContentEdit} from "../../../data/content_edit";
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
} from "../../../data/result";
import {StateHandler} from "../state/state_handler";
import {clientInterpreters} from "../../../interpreter/client_interpreter";
import {SocketStateHandler} from "../state/socket_state";
import {EditBuffer} from "../state/edit_buffer";

class MessageReceiver<S> {
    constructor(protected socket: SocketStateHandler, protected state: StateHandler<S>) {}

    receive<M extends messages.Message, C extends (new (...args: any[]) => M) & typeof messages.Message>(msgType: C, fn: (state: S, ...args: ConstructorParameters<typeof msgType>) => S | undefined) {
        this.socket.addMessageListener(msgType, (...args: ConstructorParameters<typeof msgType>) => {
            this.state.updateState(s => {
                return fn(s, ...args) ?? undefined
            })
        })
    }
}

export class NotebookMessageReceiver extends MessageReceiver<NotebookState> {
    constructor(socket: SocketStateHandler, state: NotebookStateHandler) {
        super(socket, state);

        socket.view("status").addObserver(status => {
            if (status === "disconnected") {
                state.updateState(s => {
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            status: status
                        }
                    }
                })
            }
        })

        this.receive(messages.CompletionsAt, (s, cell, offset, completions) => {
            if (s.activeCompletion) {
                s.activeCompletion.resolve({cell, offset, completions});
                return {
                    ...s,
                    activeCompletion: undefined
                }
            } else {
                console.warn("Got completion response but there was no activeCompletion, this is a bit odd.", {cell, offset, completions})
                return undefined
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
                return undefined
            }
        });
        this.receive(messages.NotebookVersion, (s, path, serverGlobalVersion) => {
            if (s.globalVersion !== serverGlobalVersion){
                // this means we must have been disconnected for a bit and the server state has changed.
                document.location.reload() // is it ok to trigger the reload here?
            }
            return undefined;
        });
        this.receive(messages.NotebookCells, (s: NotebookState, path: string, cells: NotebookCell[], config?: NotebookConfig) => {
            const cellStates = cells.map(cell => {
                return {
                    ...cell,
                    pendingEdits: [],
                };
            });
            return {
                ...s,
                path: path,
                cells: cellStates,
                config: config ?? NotebookConfig.default
            }
        });
        this.receive(messages.KernelStatus, (s, update) => {
            return purematch<messages.KernelStatusUpdate, NotebookState>(update)
                .when(messages.UpdatedTasks, tasks => {
                    const taskMap = tasks.reduce<Record<string, TaskInfo>>((acc, next) => {
                        acc[next.id] = next
                        return acc
                    }, {})
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            tasks: {
                                ...s.kernel.tasks,
                                ...taskMap,
                            }
                        }
                    }
                })
                .when(messages.KernelBusyState, (busy, alive) => {
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            status: (busy && 'busy') || (!alive && 'dead') || 'idle'
                        }
                    }
                })
                .when(messages.KernelInfo, info => {
                    return {
                        ...s,
                        kernel: {
                            ...s.kernel,
                            info: info
                        }
                    }
                })
                .when(messages.ExecutionStatus, (id, pos) => {
                    const cellIdx = s.cells.findIndex(c => c.id === id);
                    if (cellIdx > -1) {
                        return {
                            ...s,
                            cells: {
                                ...s.cells,
                                [cellIdx]: {
                                    ...s.cells[cellIdx],
                                    currentHighlight: pos
                                }
                            }
                        }
                    } else return null
                })
                .when(messages.PresenceUpdate, (added, removed) => {
                    const activePresence = {...s.activePresence}
                    added.forEach(p => activePresence[p.id] = {presence: p});
                    removed.forEach(id => delete activePresence[id]);

                    return {
                        ...s,
                        activePresence: activePresence
                    }
                })
                .when(messages.PresenceSelection, (id, cell, range) => {
                    if (s.activePresence[id]) {
                        return {
                            ...s,
                            activePresence: {
                                ...s.activePresence,
                                [id]: {
                                    ...s.activePresence[id],
                                    selection: {cell, range}
                                }
                            }
                        }
                    } else return null
                })
                .when(messages.KernelError, (err) => {
                    return {
                        ...s,
                        errors: [...s.errors, err]
                    }
                })
                .otherwiseThrow || undefined
        });
        this.receive(messages.NotebookUpdate, (s: NotebookState, update: messages.NotebookUpdate) => {
            if (update.globalVersion >= s.globalVersion) {
                const globalVersion = update.globalVersion
                const localVersion = s.localVersion + 1

                if (update.localVersion < s.localVersion) {
                    const prevUpdates = s.editBuffer.range(update.localVersion, s.localVersion);
                    update = messages.NotebookUpdate.rebase(update, prevUpdates)
                }

                const res = purematch<messages.NotebookUpdate, NotebookState>(update)
                    .when(messages.UpdateCell, (g, l, id: number, edits: ContentEdit[], metadata?: CellMetadata) => {
                        return {
                            ...s,
                            cells: s.cells.map(c => {
                                if (c.id === id){
                                    return <CellState>{
                                        ...c,
                                        pendingEdits: edits,
                                        metadata: metadata || c.metadata,
                                    }
                                } else return c
                            })
                        }
                    })
                    .when(messages.InsertCell, (g, l, cell: NotebookCell, after: number) => {
                        const newCell = {...cell, pendingEdits: []};
                        const insertIdx = s.cells.findIndex(c => c.id === after);
                        if (insertIdx > -1) {
                            return {
                                ...s,
                                cells: [...s.cells.slice(0, insertIdx), newCell, ...s.cells.slice(insertIdx + 1)]
                            }
                        } else {
                            return {
                                ...s,
                                cells: [...s.cells, newCell]
                            }
                        }
                    })
                    .when(messages.DeleteCell, (g, l, id: number) => {
                        const idx = s.cells.findIndex(c => c.id === id);
                        if (idx > -1) {
                            return {
                                ...s,
                                cells: [...s.cells.slice(0, idx), ...s.cells.slice(idx + 1)]
                            }
                        } else return null
                    })
                    .when(messages.UpdateConfig, (g, l, config: NotebookConfig) => {
                        return {
                            ...s,
                            config
                        }
                    })
                    .when(messages.SetCellLanguage, (g, l, id: number, language: string) => {
                        return {
                            ...s,
                            cells: s.cells.map(c => {
                                if (c.id === id) {
                                    return {...c, language}
                                } else return c
                            })
                        }
                    })
                    .when(messages.SetCellOutput, (g, l, id: number, output?: Output) => {
                        // is `output` ever undefined??
                        if (output) {
                            return {
                                ...s,
                                cells: s.cells.map(c => {
                                    if (c.id === id) {
                                        return {...c, results: [output]}
                                    } else return c
                                })
                            }
                        } else return null
                    })
                    .when(messages.CreateComment, (g, l, id: number, comment: CellComment) => {
                        return {
                            ...s,
                            cells: s.cells.map(c => {
                                if (c.id === id){
                                    return {
                                        ...c,
                                        comments: {
                                            ...c.comments,
                                            [comment.uuid]: comment // we're trusting the server to be correct here.
                                        }
                                    }
                                } else return c
                            })
                        }
                    })
                    .when(messages.UpdateComment, (g, l, id: number, commentId: string, range: PosRange, content: string) => {
                        return {
                            ...s,
                            cells: s.cells.map(c => {
                                if (c.id === id) {
                                    const comment = c.comments[commentId];
                                    return {
                                        ...c,
                                        comments: {
                                            ...c.comments,
                                            [commentId]: new CellComment(commentId, range, comment.author, comment.authorAvatarUrl, comment.createdAt, content)
                                        }
                                    }
                                } else return c
                            })
                        }
                    })
                    // TODO: Make sure the server handles the case where a root comment is deleted, since the client
                    //       will need to be told to delete all child comments.
                    .when(messages.DeleteComment, (g, l, id: number, commentId: string) => {
                        return {
                            ...s,
                            cells: s.cells.map(c => {
                                if (c.id === id) {
                                    const comments = {...c.comments}
                                    delete comments[commentId]
                                    return {
                                        ...c,
                                        comments,
                                    }
                                } else return c
                            })
                        }
                    }).otherwiseThrow ?? s;

                // discard edits before the local version from server – it will handle rebasing at least until that point
                const editBuffer = s.editBuffer.discard(update.localVersion);

                return {
                    ...res,
                    editBuffer,
                    localVersion,
                    globalVersion,
                }
            } else {
                console.warn(
                    "Ignoring NotebookUpdate with globalVersion", update.globalVersion,
                    "that is less than our globalVersion", s.globalVersion,
                    ". This might mean something is wrong.", update)
                return undefined
            }
        });
        this.receive(messages.CellResult, (s, id, result) => {
            return purematch<Result, NotebookState>(result)
                .when(ClearResults, () => {
                    return {
                        ...s,
                        cells: s.cells.map(c => {
                            if (c.id === id) {
                                return {...c, results: []}
                            } else return c
                        })
                    }
                })
                .whenInstance(ResultValue, result => {
                    return {
                        ...s,
                        cells: s.cells.map(c => {
                            if (c.id === id) {
                                return {
                                    ...c,
                                    results: [...c.results, result]
                                }
                            } else return c
                        }),
                        kernel: {
                            ...s.kernel,
                            symbols: [...s.kernel.symbols, result]
                        }
                    }

                })
                .whenInstance(CompileErrors, result => {
                    if (id === -1) { // from a predef cell
                        // TODO: should somehow save this state somewhere!! Maybe this should also go into s.errors?
                        console.warn("Something went wrong compiling a predef cell", result)
                        return null
                    }
                    return {
                        ...s,
                        cells: s.cells.map(c => {
                            if (c.id === id) {
                                return {
                                    ...c,
                                    results: [...c.results, result]
                                }
                            } else return c
                        })
                    }
                })
                .whenInstance(RuntimeError, result => {
                    if (id === -1) { // from a predef cell
                        return {
                            ...s,
                            errors: [...s.errors, result.error]
                        }
                    }
                    return {
                        ...s,
                        cells: s.cells.map(c => {
                            if (c.id === id) {
                                return {
                                    ...c,
                                    results: [...c.results, result]
                                }
                            } else return c
                        })
                    }
                })
                .whenInstance(Output, result => {
                    return {
                        ...s,
                        cells: s.cells.map(c => {
                            if (c.id === id) {
                                return {
                                    ...c,
                                    results: [...c.results, result]
                                }
                            } else return c
                        })
                    }
                })
                .whenInstance(ExecutionInfo, result => {
                    return {
                        ...s,
                        cells: s.cells.map(c => {
                            if (c.id === id) {
                                return {
                                    ...c,
                                    metadata: c.metadata.copy({executionInfo: result})
                                }
                            } else return c
                        })
                    }
                })
                .whenInstance(ClientResult, result => {
                    return {
                        ...s,
                        cells: s.cells.map(c => {
                            if (c.id === id) {
                                return {
                                    ...c,
                                    results: [...c.results, result]
                                }
                            } else return c
                        })
                    }
                }).otherwiseThrow || undefined
        });
    }
}

export class ServerMessageReceiver extends MessageReceiver<ServerState> {
    public notebooks: Record<string, NotebookMessageReceiver> = {};

    constructor() {
        super(SocketStateHandler.global, ServerStateHandler.get);

        this.socket.view("status").addObserver(status => {
            this.state.updateState(s => {
                return {
                    ...s, connectionStatus: status
                }
            })
        });

        this.receive(messages.Error, (s, code, err) => {
            return {
                ...s,
                errors: [...s.errors, {code, err}]
            }
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
            return undefined // `renameNotebook` already takes care of updating the state.
        });
        this.receive(messages.DeleteNotebook, (s, path) => {
            ServerStateHandler.deleteNotebook(path)
            return undefined // `deleteNotebook` already takes care of updating the state.
        });
        this.receive(messages.ListNotebooks, (s, paths) => {
            const notebooks = {...s.notebooks}
            paths.forEach(path => {
                notebooks[path] = ServerStateHandler.getOrCreateNotebook(path).loaded
            })
            return { ...s, notebooks }
        });
        this.receive(messages.ServerHandshake, (s, interpreters, serverVersion, serverCommit, identity, sparkTemplates) => {
            // inject the client interpreters here as well.
            Object.keys(clientInterpreters).forEach(key => {
                interpreters[key] = clientInterpreters[key].languageTitle
            });

            return {
                ...s,
                interpreters: interpreters,
                serverVersion: serverVersion,
                serverCommit: serverCommit,
                identity: identity ?? undefined,
                sparkTemplates: sparkTemplates,
            }
        });
        this.receive(messages.RunningKernels, (s, kernelStatuses) => {
            const notebooks = {...s.notebooks}
            kernelStatuses.forEach(kv => {
                const path = kv.first;
                const status = kv.second;
                const nbInfo = ServerStateHandler.getOrCreateNotebook(path)
                nbInfo.handler.updateState(nbState => {
                    return {
                        ...nbState,
                        kernel: {
                            ...nbState.kernel,
                            status: (status.busy && 'busy') || (!status.alive && 'dead') || 'idle'
                        }
                    }
                })
                notebooks[path] = nbInfo.loaded
            })
            return { ...s, notebooks}
        })
    }
}
