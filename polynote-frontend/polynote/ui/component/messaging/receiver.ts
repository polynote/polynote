/**
 * The MessageReceiver is used to translate external events into state changes.
 * So far, the only external events are socket messages.
 */
import {SocketSession} from "../../../comms";
import {CellState, NotebookState, NotebookStateHandler} from "../state/notebook_state";
import {ServerState, ServerStateHandler} from "../state/server_state";
import * as messages from "../../../data/messages";
import {TaskInfo} from "../../../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../../../data/data";
import match from "../../../util/match";
import {Message} from "../../../data/messages";
import {ContentEdit} from "../../../data/content_edit";
import {
    ClearResults, ClientResult,
    CompileErrors,
    ExecutionInfo,
    Output,
    PosRange,
    ResultValue,
    RuntimeError
} from "../../../data/result";
import {StateHandler} from "../state/state_handler";
import {clientInterpreters} from "../../../interpreter/client_interpreter";
import {EditBuffer} from "../../../data/edit_buffer";

class MessageReceiver<S> {
    constructor(protected socket: SocketSession, protected state: StateHandler<S>) {}

    receive<M extends Message, C extends (new (...args: any[]) => M) & typeof Message>(msgType: C, fn: (state: S, ...args: ConstructorParameters<typeof msgType>) => void) {
        this.socket.addMessageListener(msgType, (...args) => {
            this.state.updateState(s => {
                fn(s, ...args);
                return s
            })
        })
    }
}

export class NotebookMessageReceiver extends MessageReceiver<NotebookState> {
    constructor(socket: SocketSession, state: NotebookStateHandler) {
        super(socket, state);

        socket.addEventListener('close', evt => {
            state.updateState(s => {
                s.kernel.status = "disconnected";
                return s
            })
        });
        socket.addEventListener('error', evt => {
            const url = new URL(socket.url.toString());
            url.protocol = document.location.protocol;
            const req = new XMLHttpRequest();
            req.responseType = "arraybuffer";
            req.addEventListener("readystatechange", evt => {
                if (req.readyState == 4) {
                    if (req.response instanceof ArrayBuffer && req.response.byteLength > 0) {
                        const msg = Message.decode(req.response);
                        if (msg instanceof messages.Error) {
                            this.socket.close();
                            state.updateState(s => {
                                s.errors.push(msg.error);
                                s.kernel.status = "disconnected";
                                return s
                            })
                        }
                    }
                }
            });
            req.open("GET", url.toString());
            req.send(null);
        });

        this.receive(messages.CompletionsAt, (s, cell, offset, completions) => {
            if (s.activeCompletion) {
                s.activeCompletion.resolve({cell, offset, completions});
                s.activeCompletion = undefined;
            } else {
                console.warn("Got completion response but there was no activeCompletion, this is a bit odd.", {cell, offset, completions})
            }
        });
        this.receive(messages.ParametersAt, (s, cell, offset, signatures) => {
            if (s.activeSignature) {
                s.activeSignature.resolve({cell, offset, signatures})
            } else {
                console.warn("Got signature response but there was no activeSignature, this is a bit odd.", {cell, offset, signatures})
            }
        });
        this.receive(messages.NotebookVersion, (s, path, serverGlobalVersion) => {
            if (s.globalVersion !== serverGlobalVersion){
                // this means we must have been disconnected for a bit and the server state has changed.
                document.location.reload() // is it ok to trigger the reload here?
            }
        });
        this.receive(messages.NotebookCells, (s: NotebookState, path: string, cells: NotebookCell[], config?: NotebookConfig) => {
            const cellStates = cells.map(cell => {
                return {
                    ...cell,
                    pendingEdits: [],
                };
            });
            s.path = path;
            s.cells = cellStates;
            s.config = config ?? NotebookConfig.default;
        });
        this.receive(messages.KernelStatus, (s, update) => {
            match(update)
                .when(messages.UpdatedTasks, tasks => {
                    tasks.forEach((task: TaskInfo) => {
                        s.kernel.tasks[task.id] = task
                    });
                })
                .when(messages.KernelBusyState, (busy, alive) => {
                    s.kernel.status = (busy && 'busy') || (!alive && 'dead') || 'idle';
                })
                .when(messages.KernelInfo, info => {
                    s.kernel.info = info;
                })
                .when(messages.ExecutionStatus, (id, pos) => {
                    const cellIdx = s.cells.findIndex(c => c.id === id);
                    if (cellIdx > -1) {
                        s.cells[cellIdx].currentHighlight = pos
                    }
                })
                .when(messages.PresenceUpdate, (added, removed) => {
                    added.forEach(p => s.activePresence[p.id] = {presence: p});
                    removed.forEach(id => delete s.activePresence[id]);
                })
                .when(messages.PresenceSelection, (id, cell, range) => {
                    if (s.activePresence[id]) {
                        s.activePresence[id].selection = {cell, range};
                    }
                })
                .when(messages.KernelError, (err) => {
                    s.errors.push(err);
                });
        });
        this.receive(messages.NotebookUpdate, (s: NotebookState, update: messages.NotebookUpdate) => {
            if (update.globalVersion >= s.globalVersion) {
                s.globalVersion = update.globalVersion;
                if (update.localVersion < s.localVersion) {
                    const prevUpdates = s.editBuffer.range(update.localVersion, s.localVersion);
                    update = messages.NotebookUpdate.rebase(update, prevUpdates)
                }

                s.localVersion++;

                match(update)
                    .when(messages.UpdateCell, (g, l, id: number, edits: ContentEdit[], metadata?: CellMetadata) => {
                        const cellIdx = s.cells.findIndex(c => c.id === id);
                        if (cellIdx > -1) {
                            const cell = s.cells[cellIdx];
                            cell.pendingEdits = edits;
                            if (metadata) cell.metadata = metadata;
                            s.cells[cellIdx] = cell;
                        }
                    })
                    .when(messages.InsertCell, (g, l, cell: NotebookCell, after: number) => {
                        const newCell = {...cell, pendingEdits: []};
                        const insertIdx = s.cells.findIndex(c => c.id === after);
                        if (insertIdx > -1) {
                            s.cells.splice(insertIdx, 0, newCell)
                        } else {
                            s.cells.push(newCell)
                        }
                    })
                    .when(messages.DeleteCell, (g, l, id: number) => {
                        const idx = s.cells.findIndex(c => c.id === id);
                        if (idx > -1) {
                            s.cells.splice(idx, 1);
                        }
                    })
                    .when(messages.UpdateConfig, (g, l, config: NotebookConfig) => {
                        s.config = config;
                    })
                    .when(messages.SetCellLanguage, (g, l, id: number, language: string) => {
                        const idx = s.cells.findIndex(c => c.id === id);
                        if (idx > -1) {
                            s.cells[idx].language = language;
                        }
                    })
                    .when(messages.SetCellOutput, (g, l, id: number, output?: Output) => {
                        // is `output` ever undefined??
                        if (output) {
                            const idx = s.cells.findIndex(c => c.id === id);
                            if (idx > -1) {
                                s.cells[idx].results = [output]
                            }
                        }
                    })
                    .when(messages.CreateComment, (g, l, id: number, comment: CellComment) => {
                        const idx = s.cells.findIndex(c => c.id === id);
                        if (idx > -1) {
                            s.cells[idx].comments[comment.uuid] = comment
                        }
                    })
                    .when(messages.UpdateComment, (g, l, id: number, commentId: string, range: PosRange, content: string) => {
                        const idx = s.cells.findIndex(c => c.id === id);
                        if (idx > -1) {
                            const comment = s.cells[idx].comments[commentId];
                            s.cells[idx].comments[commentId] = new CellComment(commentId, range, comment.author, comment.authorAvatarUrl, comment.createdAt, content)
                        }
                    })
                    // TODO: Make sure the server handles the case where a root comment is deleted, since the client
                    //       will need to be told to delete all child comments.
                    .when(messages.DeleteComment, (g, l, id: number, commentId: string) => {
                        const idx = s.cells.findIndex(c => c.id === id);
                        if (idx > -1) {
                            delete s.cells[idx].comments[commentId];
                        }
                    });

                // discard edits before the local version from server – it will handle rebasing at least until that point
                s.editBuffer.discard(update.localVersion);
            } else {
                console.warn(
                    "Ignoring NotebookUpdate with globalVersion", update.globalVersion,
                    "that is less than our globalVersion", s.globalVersion,
                    ". This might mean something is wrong.", update)
            }
        });
        this.receive(messages.CellResult, (s, id, result) => {
            const idx = s.cells.findIndex(c => c.id === id);
            match(result)
                .when(ClearResults, () => {
                    if (idx > -1) {
                        s.cells[idx].results = []
                    }
                })
                .whenInstance(ResultValue, result => {
                    s.kernel.symbols.push(result)
                    if (idx > -1) {
                        s.cells[idx].results.push(result)
                    }
                })
                .whenInstance(CompileErrors, result => {
                    if (idx > -1) {
                        s.cells[idx].results.push(result)
                    } else {
                        // TODO: should somehow save this state somewhere!!
                        console.warn("Something went wrong compiling a predef cell", result)
                    }
                })
                .whenInstance(RuntimeError, result => {
                    if (idx > -1) {
                        s.cells[idx].results.push(result)
                    } else {
                        // this means there was an error executing a predef cell
                        s.errors.push(result.error)
                    }
                })
                .whenInstance(Output, result => {
                    if (idx > -1) {
                        s.cells[idx].results.push(result)
                    }
                })
                .whenInstance(ExecutionInfo, result => {
                    if (idx > -1) {
                        s.cells[idx].metadata = s.cells[idx].metadata.copy({executionInfo: result});
                    }
                })
                .whenInstance(ClientResult, result => {
                    if (idx > -1) {
                        s.cells[idx].results.push(result)
                    }
                })
        });
    }
}

export class ServerMessageReceiver extends MessageReceiver<ServerState> {
    public notebooks: Record<string, NotebookMessageReceiver> = {};

    constructor() {
        super(SocketSession.global, ServerStateHandler.get);

        this.socket.addEventListener('open', evt => {
            this.state.updateState(s => {
                s.connectionStatus = "connected";
                return s;
            })
        });
        this.socket.addEventListener('close', evt => {
            this.state.updateState(s => {
                s.connectionStatus = "disconnected";
                return s;
            })
        });

        this.receive(messages.Error, (s, code, err) => {
            s.errors.push({code, err});
        });
        this.receive(messages.CreateNotebook, (s, path) => {
            s.notebooks[path] = ServerStateHandler.newNotebookState(path)
        });
        this.receive(messages.RenameNotebook, (s, oldPath, newPath) => {
            const nbState = s.notebooks[oldPath];
            if (nbState) {
                nbState.state.path = newPath;
                s.notebooks[newPath] = nbState;
                delete s.notebooks[oldPath];
            }
        });
        this.receive(messages.DeleteNotebook, (s, path) => {
            delete s.notebooks[path];
        });
        this.receive(messages.ListNotebooks, (s, notebooks) => {
            notebooks.forEach(path => {
                s.notebooks[path] = ServerStateHandler.newNotebookState(path)
            })
        });
        this.receive(messages.ServerHandshake, (s, interpreters, serverVersion, serverCommit, identity, sparkTemplates) => {
            // make sure to add the client interpreters as well.
            Object.keys(clientInterpreters).forEach(key => {
                interpreters[key] = clientInterpreters[key].languageTitle
            });
            s.interpreters = interpreters;
            s.serverVersion = serverVersion;
            s.serverCommit = serverCommit;
            s.identity = identity ?? undefined;
            s.sparkTemplates = sparkTemplates;
        });
        this.receive(messages.RunningKernels, (s, kernelStatuses) => {
            kernelStatuses.forEach(kv => {
                const nbState = s.notebooks[kv.first]?.state ?? ServerStateHandler.newNotebookState(kv.first).state;
                nbState.kernel.status = (kv.second.busy && 'busy') || (!kv.second.alive && 'dead') || 'idle';
                s.notebooks[kv.first].state = nbState;
            })
        })
    }
}
