/**
 * The MessageReceiver is used to translate external events into state changes.
 * So far, the only external events are socket messages.
 */
import {
    append,
    clearArray,
    destroy,
    Disposable,
    editString,
    insert, moveArrayValue,
    NoUpdate,
    removeIndex,
    removeKey,
    setValue,
    StateHandler,
    UpdateOf,
    valueToUpdate
} from "../state";
import * as messages from "../data/messages";
import {GoToDefinitionResponse, Identity, Message, TaskInfo, TaskStatus} from "../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../data/data";
import match, {purematch} from "../util/match";
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
import {SocketStateHandler} from "../state/socket_state";
import {arrDelete, deepCopy, arrInsert, collect, collectFields, deepEquals, mapValues, removeKeys, unzip} from "../util/helpers";
import {ClientInterpreters} from "../interpreter/client_interpreter";
import {
    CellState,
    KernelState,
    KernelSymbols,
    NotebookState,
    NotebookStateHandler,
    PresenceState
} from "../state/notebook_state";
import {ClientBackup} from "../state/client_backup";
import {ErrorStateHandler} from "../state/error_state";
import {ServerState, ServerStateHandler} from "../state/server_state";
import {posToRange} from "../util/helpers";
import {IRange, languages, Uri} from "monaco-editor";
import Definition = languages.Definition;

export class MessageReceiver<S> extends Disposable {
    protected readonly socket: SocketStateHandler;
    protected readonly state: StateHandler<S>
    constructor(socket: SocketStateHandler, state: StateHandler<S>) {
        super();
        this.socket = socket.fork(this);
        this.state = state.fork(this);

        this.state.onDispose.then(() => {
            this.socket.close()
        })
        this.socket.onDispose.then(() => {
            if (!this.isDisposed) this.dispose()
        })
    }

    protected receive<M extends messages.Message, C extends (new (...args: any[]) => M) & typeof messages.Message>(msgType: C, fn: (state: Readonly<S>,...args: ConstructorParameters<typeof msgType>) => UpdateOf<S>) {
        this.socket.addMessageListener(msgType, (...args: ConstructorParameters<typeof msgType>) => {
            this.state.update(state => fn(state, ...args), this)
        }).disposeWith(this)
    }

    // Handle a message as if it were received on the wire. Useful for short-circuiting or simulating server messages.
    inject(msg: Message) {
        this.socket.handleMessage(msg)
    }
}

// A predicate for filtering out updates originating from the server
export function notReceiver(source: any): boolean {
    return !(source instanceof MessageReceiver)
}

export class NotebookMessageReceiver extends MessageReceiver<NotebookState> {
    constructor(socketState: SocketStateHandler, notebookState: NotebookStateHandler) {
        super(socketState, notebookState);
        const updateHandler = notebookState.updateHandler;

        this.receive(messages.Error, (s, code, err) => {
            ErrorStateHandler.addKernelError(s.path, err);
            return NoUpdate
        });

        this.socket.view("status").addObserver(status => {
            if (status === "disconnected") {
                this.state.update(state => ({
                    kernel: {
                        status: status
                    },
                    cells: collectFields(state.cells, (id, cell) => ({
                        running: false,
                        queued: false,
                        currentHighlight: undefined
                    }))
                }))
            } else if (this.state.state.kernel.status === 'disconnected') {
                this.state.update(() => ({
                    kernel: {
                        status: setValue('dead')
                    }
                }))
            }
        })

        this.receive(messages.CompletionsAt, (s, cell, offset, completions) => {
            if (s.activeCompletion) {
                s.activeCompletion.resolve({cell, offset, completions});
                return {
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
                    activeSignature: undefined
                }
            } else {
                console.warn("Got signature response but there was no activeSignature, this is a bit odd.", {cell, offset, signatures})
                return NoUpdate
            }
        });
        this.receive(messages.GoToDefinitionResponse, (s, reqId, location) => {
            if (s.requestedDefinition) {
                const definition: Definition = location.map(location => {
                    const loc: languages.Location = {uri: Uri.parse(location.uri), range: posToRange(location)};
                    return loc
                })
                s.requestedDefinition.resolve(new GoToDefinitionResponse(reqId, location));
                return {
                    requestedDefinition: destroy()
                }
            } else {
                return NoUpdate;
            }
        });
        this.receive(messages.NotebookVersion, (s, path, serverGlobalVersion) => {
            if (updateHandler.globalVersion === 0) {
                // first version, just set it
                updateHandler.globalVersion = serverGlobalVersion
            } else if (updateHandler.globalVersion !== serverGlobalVersion){
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
            const cells = {} as Record<number, UpdateOf<CellState>>;
            cellStates.forEach(state => cells[state.id] = setValue(state));
            const cellOrder = notebookCells.map(cell => cell.id)

            // add this notebook to the backups
            ClientBackup.addNb(path, notebookCells, config)
                // .then(backups => console.log("Added new backup. All backups for this notebook:", backups))
                .catch(err => console.error("Error adding backup", err));

            return {
                path: setValue(path),
                cells,
                cellOrder: setValue(cellOrder),
                config: { config: config ?? NotebookConfig.default },
                kernel: {
                    symbols
                }
            }
        });
        this.receive(messages.KernelStatus, (s, update) => {
            return purematch<messages.KernelStatusUpdate, UpdateOf<NotebookState> | typeof NoUpdate>(update)
                .when(messages.UpdatedTasks, tasks => {
                    const taskMap = tasks.reduce<Record<string, TaskInfo>>((acc, next) => {
                        acc[next.id] = next
                        return acc
                    }, {})
                    return {
                        kernel: {
                            tasks: taskMap
                        }
                    }
                })
                .whenInstance(messages.KernelBusyState, kernelState => {
                    const status = kernelState.asStatus;
                    return {
                        kernel: {
                            status,
                            symbols: status === 'dead' ? setValue({}) : NoUpdate
                        }
                    }
                })
                .when(messages.KernelInfo, info => {
                    return {
                        kernel: {
                            info: setValue(info)
                        }
                    }
                })
                .when(messages.ExecutionStatus, (id, pos) => {
                    return {
                        cells:  {
                            [id]: {
                                currentHighlight: pos ? setValue({ range: pos, className: "currently-executing" }) : undefined
                            }
                        }
                    }
                })
                .when(messages.PresenceUpdate, (added, removed) => {
                    const activePresence = s.activePresence;
                    const activePresenceUpdate: UpdateOf<Record<number, PresenceState>> = {};
                    const cellsUpdate: UpdateOf<Record<number, CellState>> = {};

                    added.forEach(p => {
                        if (activePresence[p.id] === undefined) {
                            const color = Object.keys(activePresence).length % 8;
                            activePresenceUpdate[p.id] = setValue({id: p.id, name: p.name, color: `presence${color}`, avatar: p.avatar})
                        }
                    });

                    removed.forEach(removedId => {
                        activePresenceUpdate[removedId] = destroy()
                        for (const prop of Object.keys(s.cells)) {
                            if (s.cells.hasOwnProperty(prop)) {
                                const cellId = prop as unknown as number;  // Record<number, ?> is a lie! This is a string!
                                const cell = s.cells[cellId];
                                if (removedId in cell.presence) {
                                    const cellUpdate = cellsUpdate[cellId] as any || {};  // Again, can't reconcile the types because arrays
                                    if (!cellUpdate.presence) {
                                        cellUpdate.presence = {}
                                    }
                                    cellUpdate.presence[removedId] = destroy();
                                    cellsUpdate[cellId] = cellUpdate;
                                }

                            }
                        }
                    });



                    return {
                        activePresence: activePresenceUpdate,
                        cells: cellsUpdate
                    }
                })
                .when(messages.PresenceSelection, (id, cellId, range) => {
                    const maybePresence = s.activePresence[id]
                    if (maybePresence) {
                        return {
                            activePresence: {
                                [id]: {
                                    selection: {cellId, range}
                                }
                            },
                            cells: {
                                [cellId]: {
                                    presence: {
                                        [id]: setValue({...maybePresence, range})
                                    }
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

                    let kernel = NoUpdate as UpdateOf<KernelState>;
                    if (status === TaskStatus.Queued) {
                        const taskId = `Cell ${cellId}`;
                        kernel = {
                            tasks: {
                                [taskId]: new TaskInfo(taskId, taskId, '', TaskStatus.Queued, 0)
                            }
                        }
                    }

                    return {
                        cells: {
                            [cellId]: {
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
            if (update.globalVersion >= updateHandler.globalVersion) {
                update = updateHandler.rebaseUpdate(update)

                const res = purematch<messages.NotebookUpdate, UpdateOf<NotebookState>>(update)
                    .when(messages.UpdateCell, (g, l, id: number, edits: ContentEdit[], metadata?: CellMetadata) => {
                        return {
                            cells: {
                                [id]: {
                                    content: editString(edits),
                                    metadata: metadata ? setValue(metadata) : NoUpdate
                                }
                            }
                        }
                    })
                    .when(messages.InsertCell, (g, l, cell: NotebookCell, after: number) => {
                        const newCell = this.cellToState(cell);
                        const insertIdx = s.cellOrder.findIndex(id => id === after) + 1;
                        return {
                            cells: {
                                [cell.id]: setValue(newCell)
                            },
                            cellOrder: insert(newCell.id, insertIdx)
                        }
                    })
                    .when(messages.DeleteCell, (g, l, id: number) => {
                        const idx = s.cellOrder.indexOf(id)
                        if (idx > -1) {
                            return {
                                cells: removeKey(id),
                                cellOrder: removeIndex(s.cellOrder, idx),
                                activeCellId: s.activeCellId === id ? undefined : s.activeCellId // clear activeCellId if it was deleted.
                            }
                        } else return s
                    })
                    .when(messages.UpdateConfig, (g, l, config: NotebookConfig) => {
                        return {
                            config: {config}
                        }
                    })
                    .when(messages.SetCellLanguage, (g, l, id: number, language: string) => {
                        return {
                            cells: {
                                [id]: {
                                    language
                                }
                            }
                        }
                    })
                    .when(messages.SetCellOutput, (g, l, id: number, output?: Output) => {
                        // is `output` ever undefined??
                        if (output) {
                            return {
                                cells: {
                                    [id]: {
                                        output: setValue([output])
                                    }
                                }
                            }
                        } else return s
                    })
                    .when(messages.CreateComment, (g, l, id: number, comment: CellComment) => {
                        return {
                            cells: {
                                [id]: {
                                    comments: {
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
                            cells: {
                                [id]: {
                                    comments: {
                                        [commentId]: {
                                            range,
                                            content
                                        }
                                    }
                                }
                            }
                        }
                    })
                    .when(messages.DeleteComment, (g, l, id: number, commentId: string) => {
                        return {
                            cells: {
                                [id]: {
                                    comments: removeKey(commentId)
                                }
                            }
                        }
                    })
                    .when(messages.MoveCell, (g, l, id, after) => {
                        const currentIndex = s.cellOrder.indexOf(id);
                        const afterIndex = s.cellOrder.indexOf(after);
                        const toIndex = currentIndex <= afterIndex ? afterIndex : afterIndex + 1;
                        return {
                            cellOrder: moveArrayValue(currentIndex, toIndex)
                        }
                    }).otherwiseThrow ?? s;

                // make sure to update backups.
                ClientBackup.updateNb(s.path, update)
                    .catch(err => console.error("Error updating backup", err));

                return res
            } else {
                console.warn(
                    "Ignoring NotebookUpdate with globalVersion", update.globalVersion,
                    "that is less than our globalVersion", updateHandler.globalVersion,
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
                const symbols = match(result).typed<UpdateOf<KernelSymbols>>()
                    .whenInstance(ResultValue, r => ['busy', 'idle'].includes(s.kernel.status) ? {[cellId]: {[r.name]: setValue(r)}} : NoUpdate)
                    .whenInstance(ClearResults, _ => ({[cellId]: setValue({})}))
                    .otherwise(NoUpdate)

                const cells = cellId < 0 ? NoUpdate : { [cellId]: this.parseResult(s.cells[cellId], result) }
                return {
                    cells,
                    kernel: { symbols }
                }
            }
        });

        //************* Streaming Messages ****************
        this.receive(messages.HandleData, (s, handlerType, handleId, count, data) => {
            const msg = new messages.HandleData(handlerType, handleId, count, data)
            return {
                activeStreams: {
                    [handleId]:
                        s.activeStreams[handleId] ?
                            append(msg) :
                            setValue([msg])
                }
            }
        })

        this.receive(messages.ModifyStream, (s, fromHandle, ops, newRepr) => {
            const msg = new messages.ModifyStream(fromHandle, ops, newRepr);
            return {
                activeStreams: {
                    [fromHandle]: s.activeStreams[fromHandle] ?
                        append(msg) :
                        setValue([msg])
                }
            }
        })
    }

    private cellToState(cell: NotebookCell): CellState {
        const state: CellState = {
            id: cell.id,
            language: cell.language,
            content: cell.content,
            metadata: cell.metadata,
            comments: cell.comments,
            output: [],
            results: [],
            compileErrors: [],
            runtimeError: undefined,
            presence: {},
            editing: false,
            error: false,
            running: false,
            queued: false,
            currentSelection: undefined,
            currentHighlight: undefined,
        }
        cell.results.forEach(result => valueToUpdate(this.parseResult(state, result)).applyMutate(state));
        return state;
    }

    private parseResult(cell: CellState, result: Result): UpdateOf<CellState> {
        return purematch<Result, UpdateOf<CellState>>(result)
            .when(ClearResults, () => {
                return {output: clearArray(), results: clearArray(), compileErrors: clearArray(), runtimeError: undefined, error: false}
            })
            .whenInstance(ResultValue, result => {
                return { results: append(result)}
            })
            .whenInstance(CompileErrors, result => {
                return { compileErrors: append(result), error: true}
            })
            .whenInstance(RuntimeError, result => {
                return { runtimeError: setValue(result) }
            })
            .whenInstance(Output, result => {
                return {output: append(result)}
            })
            .whenInstance(ExecutionInfo, result => {
                return {
                    metadata: cell.metadata.copy({executionInfo: result})
                }
            })
            .whenInstance(ClientResult, result => {
                return {results: append(result)}
            }).otherwiseThrow || cell
    }
}

export class ServerMessageReceiver extends MessageReceiver<ServerState> {
    public notebooks: Record<string, NotebookMessageReceiver> = {};

    constructor() {
        super(SocketStateHandler.global, ServerStateHandler.get);

        this.socket.view("status").addObserver(status => {
            this.state.updateField("connectionStatus", () => status);
        }).disposeWith(this.state);

        this.receive(messages.Error, (s, code, err) => {
            ErrorStateHandler.addServerError(err)
            return NoUpdate
        });

        this.receive(messages.KernelStatus, (state, update) => {
            if (update instanceof messages.KernelInfo) {
                // Getting KernelInfo means we successfully launched a new kernel, so we can clear any old errors lying around.
                // This seems a bit hacky, maybe there's a better way to clear these errors?
                ErrorStateHandler.clear()
            }
            return NoUpdate;
        })

        this.receive(messages.CreateNotebook, (s, path) => {
            return {
                notebooks: {
                    [path]: ServerStateHandler.getOrCreateNotebook(path).loaded
                },
                notebookTimestamps: {
                    [path]: Date.now()
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
        this.receive(messages.ListNotebooks, (s, nbs) => {
            const notebooks = {...s.notebooks}
            const notebookTimestamps = {...s.notebookTimestamps};
            nbs.forEach(nb => {
                notebooks[nb.path] = ServerStateHandler.getOrCreateNotebook(nb.path).loaded
                notebookTimestamps[nb.path] = Number(nb.lastSaved); // cast BigInt (uint64) to number
            })
            return {
                notebooks: setValue(notebooks),
                notebookTimestamps: setValue(notebookTimestamps)
            }
        });
        this.receive(messages.ServerHandshake, (s, interpreters, serverVersion, serverCommit, identity, sparkTemplates, notebookTemplates, notifications) => {
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
                interpreters: setValue(interpreters),
                serverVersion: setValue(serverVersion),
                serverCommit: setValue(serverCommit),
                identity: setValue(identity ?? new Identity("Unknown User", null)),
                sparkTemplates: setValue(sparkTemplates),
                notebookTemplates: setValue(notebookTemplates),
                notifications: setValue(notifications)
            }
        });
        this.receive(messages.RunningKernels, (s, kernelStatuses) => {
            const notebooks: string[] = []
            kernelStatuses.forEach(kv => {
                const path = kv.first;
                const status = kv.second;
                const nbInfo = ServerStateHandler.getOrCreateNotebook(path)
                nbInfo.handler.updateField("kernel", () => ({
                    status: status.asStatus
                }))
                notebooks.push(path)
            })
            return { serverOpenNotebooks: notebooks }
        })
        this.receive(messages.SearchNotebooks, (s, query, notebookSearchResults) => {
            return { searchResults: notebookSearchResults }
        })
        this.receive(messages.NotebookSaved, (s, path, timestamp) => {
            return {
                notebookTimestamps: {
                    [path]: Number(timestamp) // cast BigInt (uint64) to number
                }
            }
        })
    }
}
