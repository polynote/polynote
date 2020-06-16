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
import {NoUpdate, StateHandler} from "../state/state_handler";
import {clientInterpreters} from "../../../interpreter/client_interpreter";
import {SocketStateHandler} from "../state/socket_state";
import {arrDelete, arrInsert, unzip} from "../../../util/functions";

class MessageReceiver<S> {
    constructor(protected socket: SocketStateHandler, protected state: StateHandler<S>) {}

    receive<M extends messages.Message, C extends (new (...args: any[]) => M) & typeof messages.Message>(msgType: C, fn: (state: S, ...args: ConstructorParameters<typeof msgType>) => S | typeof NoUpdate) {
        this.socket.addMessageListener(msgType, (...args: ConstructorParameters<typeof msgType>) => {
            this.state.updateState(s => {
                return fn(s, ...args) ?? NoUpdate
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
            if (s.globalVersion !== serverGlobalVersion){
                // this means we must have been disconnected for a bit and the server state has changed.
                document.location.reload() // is it ok to trigger the reload here?
            }
            return NoUpdate
        });
        this.receive(messages.NotebookCells, (s: NotebookState, path: string, cells: NotebookCell[], config?: NotebookConfig) => {
            const [cellStates, results] = unzip(cells.map(cell => {
                const cellState = this.cellToState(cell)
                // unfortunately, this can't be an anonymous function if we want TS to correctly narrow the type.
                function isRV(maybe: ResultValue | ClientResult): maybe is ResultValue {
                    return maybe instanceof ResultValue
                }
                const resultsValues = cellState.results.filter(isRV)
                return [cellState, resultsValues]
            }));
            return {
                ...s,
                path: path,
                cells: cellStates,
                config: config ?? NotebookConfig.default,
                kernel: {
                    ...s.kernel,
                    symbols: [...s.kernel.symbols, ...results.flat()]
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
                    return {
                        ...s,
                        cells: s.cells.map(c => {
                            if (c.id === id) {
                                return <CellState>{
                                    ...c,
                                    currentHighlight: pos
                                }
                            } else return c
                        })
                    }
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
                    } else return NoUpdate
                })
                .when(messages.KernelError, (err) => {
                    return {
                        ...s,
                        errors: [...s.errors, err]
                    }
                })
                .otherwiseThrow || NoUpdate
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
                        const newCell = this.cellToState(cell);
                        const insertIdx = s.cells.findIndex(c => c.id === after) + 1;
                        return {
                            ...s,
                            cells: arrInsert(s.cells, insertIdx, newCell)
                        }
                    })
                    .when(messages.DeleteCell, (g, l, id: number) => {
                        const idx = s.cells.findIndex(c => c.id === id);
                        if (idx > -1) {
                            const cells = s.cells.reduce<[CellState[], CellState | null]>(([acc, prev], next, idx) => {
                                // Upon deletion, we want to set the selected cell to be the cell below the deleted one, if present. Otherwise, we want to select the cell above.
                                if (next.id === id) {
                                    if (idx === s.cells.length - 1) {
                                        // deleting the last cell, so try to select the previous cell
                                        const maybePrevious = acc[acc.length - 1];
                                        if (maybePrevious) {
                                            // if the previous cell exists, select it
                                            const selectPrevious: CellState = {...maybePrevious, selected: true}
                                            return [[...acc.slice(0, acc.length - 1), selectPrevious], next]
                                        } else {
                                            // there's no previous cell, which means this is the last cell... nothing to select.
                                            return [acc, next]
                                        }
                                    } else {
                                        // not deleting the last cell. we will select the next cell in the next iteration.
                                        return [acc, next]
                                    }
                                } else if (prev && prev.id === id) {
                                    // the previous cell was deleted, so select this one.
                                    const selectedNext = {...next, selected: true}
                                    return [[...acc, selectedNext], selectedNext]
                                } else {
                                    return [[...acc, next], next]
                                }
                            }, [[], null])[0]


                            return {
                                ...s,
                                cells: cells,
                                activeCell: cells.find(cell => cell.selected === true)
                            }
                        } else return s
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
                                        return {...c, output: [output]}
                                    } else return c
                                })
                            }
                        } else return s
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
                return NoUpdate
            }
        });
        this.receive(messages.CellResult, (s, id, result) => {
            if (id === -1) { // from a predef cell
                return purematch<Result, NotebookState | typeof NoUpdate>(result)
                    .whenInstance(CompileErrors, result => {
                        // TODO: should somehow save this state somewhere!! Maybe this should also go into s.errors?
                        console.warn("Something went wrong compiling a predef cell", result)
                        return NoUpdate
                    })
                    .whenInstance(RuntimeError, result => {
                        return {...s, errors: [...s.errors, result.error]}
                    })
                    .otherwiseThrow ?? NoUpdate
            } else {
                return {
                    ...s,
                    cells: s.cells.map(c => {
                        if (c.id === id) {
                            return this.parseResults(c, [result])
                        } else return c
                    }),
                    kernel: result instanceof ResultValue ? {...s.kernel, symbols: [...s.kernel.symbols, result]} : s.kernel
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
            pendingEdits: [],
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
                    const isRunning = cell.running
                        ? result.endTs === undefined  // if the cell is already running, it stays running as long as we didn't get an endTs.
                        : cell.queued === true        // if the cell is not yet running but it is currently queued, we know it started to run once we get the initial execution info.
                    return {
                        ...cell,
                        metadata: cell.metadata.copy({executionInfo: result}),
                        output: result.endTs === undefined ? [] : cell.output, // clear output when the cell starts running
                        queued: false, // we are certain the cell is no longer queued when we get Exec Info.
                        running: isRunning,
                        error: result.endTs === undefined ? false : cell.error, // if the cell just started running we clear any error marker
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
