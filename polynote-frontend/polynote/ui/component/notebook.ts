import {
    CellsLoaded, ReprDataRequest, CellSelected, UIMessageTarget, FocusCell, CreateComment, UpdateComment, DeleteComment
} from "../util/ui_event";
import {NotebookCellsUI} from "./nb_cells";
import {KernelUI} from "./kernel_ui";
import {EditBuffer} from "../../data/edit_buffer";
import * as messages from "../../data/messages";
import {Cell, CodeCell, TextCell} from "./cell";
import match from "../../util/match";
import {ClearResults, ClientResult, ServerError, Output, PosRange, Result, ResultValue} from "../../data/result";
import {DataRepr, DataStream, StreamingDataRepr} from "../../data/value_repr";
import {clientInterpreters} from "../../interpreter/client_interpreter";
import {CellMetadata, NotebookCell, NotebookConfig} from "../../data/data";
import {SocketSession} from "../../comms";
import {MainUI} from "./ui";
import {
    CompletionCandidate,
    Message,
    NotebookUpdate,
    Presence,
    Signatures,
    TaskInfo,
    TaskStatus
} from "../../data/messages";
import {languages, Range} from "monaco-editor";
import {ContentEdit} from "../../data/content_edit";
import {Either, Left, Right} from "../../data/types";
import * as Tinycon from "tinycon";
import CompletionList = languages.CompletionList;
import SignatureHelp = languages.SignatureHelp;
import {CurrentSelection} from "../../data/messages";
import {CurrentNotebook} from "./current_notebook";
import {notificationsEnabled} from "../util/notifications";
import container from "vega-embed/build/src/container";
import {ClientBackup} from "./client_backup";

const notebooks: Record<string, NotebookUI> = {};

class PresenceColor {
    constructor(readonly presence: Presence, readonly color: string) {}
}

export class NotebookUI extends UIMessageTarget {
    readonly cellUI: NotebookCellsUI;
    readonly kernelUI: KernelUI;
    private cellResults: Record<number, Record<string, ResultValue>>;
    private globalVersion: number;
    private localVersion: number;
    private editBuffer: EditBuffer;
    private cellStatusListeners: ((cellId: number, status: number) => void)[] = [];
    private queuedCells: number[] = [];
    private runningCell?: number;
    private closed: boolean = false;
    readonly socket: SocketSession;

    private otherUsers: Record<number, PresenceColor> = {};
    private otherUserSelections: Record<number, [number, PosRange]> = {};

    private currentSelectionTimeout?: number = undefined;

    private nextColor = 0;

    private getNextColor() {
        this.nextColor++;
        this.nextColor = this.nextColor % 8;
        return `presence${this.nextColor}`;
    }

    static getOrCreate(eventParent: UIMessageTarget, path: string, mainUI: MainUI) {
        if (notebooks[path])
            return notebooks[path];

        const nb = new NotebookUI(eventParent, path, mainUI);
        notebooks[path] = nb;
        return nb;
    }

    static getInstance(path: string): NotebookUI | undefined {
        return notebooks[path];
    }

    static renameInstance(oldPath: string, newPath: string): void {
        if (notebooks[oldPath]) {
            notebooks[newPath] = notebooks[oldPath];
            notebooks[newPath].path = newPath;
            delete notebooks[oldPath];
        }
    }

    static disableAll() {
        for (const path in Object.keys(notebooks)) {
            notebooks[path].cellUI.setDisabled(true);
        }
    }

    static enableAll() {
        for (const path in Object.keys(notebooks)) {
            notebooks[path].cellUI.setDisabled(false);
        }
    }

    // TODO: remove mainUI reference
    private constructor(eventParent: UIMessageTarget, public path: string, readonly mainUI: MainUI) {
        super(eventParent);
        this.socket = SocketSession.fromRelativeURL(`ws/${encodeURIComponent(path)}`);
        this.socket.addEventListener("error", err => {
           const url = new URL(this.socket.url.toString());
           url.protocol = document.location.protocol;
           const req = new XMLHttpRequest();
           req.responseType = "arraybuffer";
           req.addEventListener("readystatechange", evt => {
               if (req.readyState == 4) {
                   if (req.response instanceof ArrayBuffer && req.response.byteLength > 0) {
                       const msg = Message.decode(req.response);
                       if (msg instanceof messages.Error) {
                           this.socket.close();
                           this.closed = true;
                           this.cellUI.setLoadingFailure(msg.error);
                       }
                   }
               }
           });
           req.open("GET", url.toString());
           req.send(null);
        });
        let cellUI = new NotebookCellsUI(this, path);
        let kernelUI = new KernelUI(this);
        this.cellUI = cellUI;
        this.kernelUI = kernelUI;

        this.cellResults = {};

        this.globalVersion = 0;
        this.localVersion = 0;

        this.editBuffer = new EditBuffer();

        this.subscribe(CellSelected, cell => {
            const cellTypeSelector = mainUI.toolbarUI.cellToolbar.cellTypeSelector;
            const id = cell.id;
            let i = 0;

            // update cell type selector
            for (const opt of cellTypeSelector.options) {
                if (opt.value === cell.language) {
                    cellTypeSelector.selectedIndex = i;
                    break;
                }
                i++;
            }

            // notify toolbar of context change
            mainUI.toolbarUI.onContextChanged();

            // TODO: should probably generate some scroll-to-element event and handle this up in mainUI itself
            // check if element is in viewport
            const viewport = mainUI.notebookContent;
            const viewportScrollTop = viewport.scrollTop;
            const viewportScrollBottom = viewportScrollTop + viewport.clientHeight;

            const container = cell.container;
            const elTop = container.offsetTop;
            const elBottom = elTop + container.offsetHeight;

            if (elBottom < viewportScrollTop) { // need to scroll up
                cell.container.scrollIntoView({behavior: "auto", block: "start", inline: "nearest"})
            } else if (elTop > viewportScrollBottom) { // need to scroll down
                cell.container.scrollIntoView({behavior: "auto", block: "end", inline: "nearest"})
            }

            //update notebook scroll store with new position:
            mainUI.tabUI.setCurrentScrollLocation(viewport.scrollTop + viewport.clientHeight / 2);

            // update the symbol table to reflect what's visible from this cell
            const ids = this.cellUI.getCodeCellIdsBefore(id);
            this.kernelUI.presentSymbols(id, ids);
        });

        this.subscribe(ReprDataRequest, (reqHandleType, reqHandleId, reqCount, reqOnComplete, reqOnFail) => {
            this.socket.listenOnceFor(messages.HandleData, (handleType, handleId, count, data: Left<messages.Error> | Right<ArrayBuffer[]>) => {
                Either.fold(data, err => reqOnFail(err), bufs => reqOnComplete(bufs));
                return false;
            });
            this.socket.send(new messages.HandleData(reqHandleType, reqHandleId, reqCount, Either.right([])));
        });

        this.socket.addMessageListener(messages.NotebookCells, this.onCellsLoaded.bind(this));

        this.socket.addMessageListener(messages.KernelStatus, (update) => match(update)
            .when(messages.UpdatedTasks, (tasks) => tasks.forEach((task: TaskInfo) => {
                this.handleTaskUpdate(task);
            }))
            .when(messages.KernelBusyState, (busy, alive) => {
                const state = (busy && 'busy') || (!alive && 'dead') || 'idle';
                this.kernelUI.setKernelState(state);
                this.cellUI.configUI.setKernelState(state);
            })
            .when(messages.KernelInfo, info => this.kernelUI.updateInfo(info))
            .when(messages.ExecutionStatus, (id, pos) => this.cellUI.setExecutionHighlight(id, pos))
            .when(messages.PresenceUpdate, (added, removed) => {
                added.forEach(p => this.otherUsers[p.id] = new PresenceColor(p, this.getNextColor()));
                removed.forEach(id => { this.removePresenceSelection(id); delete this.otherUsers[id] });
            })
            .when(messages.PresenceSelection, (id, cellId, range) => this.setPresenceSelection(id, cellId, range))
            .when(messages.KernelError, (err) => {
                this.kernelUI.errorDisplay(0, err)
                console.log(err)
            })
        );
        this.socket.addEventListener('close', () => this.cellUI.configUI.setKernelState('disconnected'));

        this.socket.addMessageListener(messages.NotebookUpdate, (update: messages.NotebookUpdate) => {
            if (update.globalVersion >= this.globalVersion) {
                this.globalVersion = update.globalVersion;

                if (update.localVersion < this.localVersion) {
                    const prevUpdates = this.editBuffer.range(update.localVersion, this.localVersion);
                    update = messages.NotebookUpdate.rebase(update, prevUpdates);
                }


                this.localVersion++;

                match(update)
                    .when(messages.UpdateCell, (g: number, l: number, id: number, edits: ContentEdit[], metadata?: CellMetadata) => {
                        const cell = this.cellUI.getCell(id);
                        if (cell) {
                            cell.applyEdits(edits);
                            this.editBuffer.push(this.localVersion, update);
                            if (metadata) {
                                cell.setMetadata(metadata);
                            }
                        }
                    })
                    .when(messages.InsertCell, (g: number, l: number, cell: NotebookCell, after: number) => {
                        const prev = this.cellUI.getCell(after);
                        const newCell = (prev && prev.language && prev.language !== "text")
                            ? new CodeCell(cell.id, cell.content, cell.language, this)
                            : new TextCell(cell.id, cell.content, this);

                        this.cellUI.insertCellBelow(prev && prev.container, () => newCell)
                    })
                    .when(messages.DeleteCell, (g: number, l: number, id: number) => this.cellUI.deleteCell(id))
                    .when(messages.UpdateConfig, (g: number, l: number, config: NotebookConfig) => this.cellUI.configUI.setConfig(config))
                    .when(messages.SetCellLanguage, (g: number, l: number, id, language: string) => {
                        const cell = this.cellUI.getCell(id);
                        if (cell) {
                            this.cellUI.setCellLanguage(cell, language)
                        } else {
                            throw new Error(`Cell ${id} does not exist in the current notebook`)
                        }
                    })
                    .when(messages.SetCellOutput, (g: number, l: number, id, output?: Output) => {
                        const cell = this.cellUI.getCell(id);
                        if (cell instanceof CodeCell) {
                            cell.clearResult();
                            if (output) {
                                cell.addOutput(output.contentType, output.content.join(''));
                            }
                        }
                    })
                    .when(messages.CreateComment, (g: number, l: number, id, comment) => {
                        const cell = this.cellUI.getCell(id);
                        if (cell instanceof CodeCell) {
                            cell.commentHandler.add(comment)
                        }
                    })
                    .when(messages.UpdateComment, (g: number, l: number, id, commentId, range, content) => {
                        const cell = this.cellUI.getCell(id);
                        if (cell instanceof CodeCell) {
                            cell.commentHandler.update(commentId, range, content)
                        }
                    })
                    .when(messages.DeleteComment, (g: number, l: number, id, commentId) => {
                        const cell = this.cellUI.getCell(id);
                        if (cell instanceof CodeCell) {
                            cell.commentHandler.delete(commentId)
                        }
                    });


                // discard edits before the local version from server – it will handle rebasing at least until that point
                this.editBuffer.discard(update.localVersion);

                // make sure to update backups.
                ClientBackup.updateNb(this.path, update)
                    .catch(err => console.error("Error updating backup", err));
            }
        });


        // when the socket is disconnected, we're going to try reconnecting when the window gets focus.
        const reconnectOnWindowFocus = () => {
            if (this.closed) {
                window.removeEventListener("focus", reconnectOnWindowFocus);
                return;
            }

            if (this.socket.isClosed) {
                this.socket.reconnect(true);
            }
            // TODO: replace with `socket.request`
            this.socket.listenOnceFor(messages.NotebookVersion, (path, serverGlobalVersion) => {
                if (this.globalVersion !== serverGlobalVersion) {
                    // looks like there's been a change while we were disconnected, so reload.
                    document.location.reload();
                }
            });
            this.socket.send(new messages.NotebookVersion(path, this.globalVersion))
        };

        this.socket.addEventListener('close', evt => {
            this.cellUI.setDisabled(true);
            window.addEventListener('focus', reconnectOnWindowFocus);
        });

        this.socket.addEventListener('open', evt => {
            window.removeEventListener('focus', reconnectOnWindowFocus);
            this.socket.send(new messages.KernelStatus(new messages.KernelBusyState(false, false)));
            this.cellUI.setDisabled(false);
        });

        this.socket.addMessageListener(messages.CellResult, (id, result) => {
            const cell = this.cellUI.getCell(id);

            // Cell ids less than 0 refer to the Predef and other server-side things we can safely ignore.
            // However, Cell Ids >=0 are expected to exist in the current notebook, so we throw if we can't find 'em
            if (id >=0 && !cell) throw new Error(`Cell Id ${id} does not exist in the current notebook`);

            this.updateCellResults(result, id);
            if (cell instanceof CodeCell) cell.addResult(result)
        });

        // update list of currently running cells
        this.cellStatusListeners.push((cellId: number, status: number) => {
            if (status === TaskStatus.Running) {
                this.runningCell = cellId;
                this.queuedCells = this.queuedCells.filter(item => item !== cellId)
            } else if (status === TaskStatus.Complete || status === TaskStatus.Error) {
                this.runningCell = undefined;
                this.queuedCells = this.queuedCells.filter(item => item !== cellId); // just in case

                // only notify if this notebook doesn't have focus
                if (!this.isFocused()) {
                    if (notificationsEnabled()) {
                        // Is there really no better way to fetch the favicon??
                        const favicon = (document.getElementsByTagName('head')[0].querySelector("link[rel*='icon") as HTMLLinkElement).href;
                        const cell = this.cellUI.getCell(cellId);
                        const statusString = (cell instanceof CodeCell && cell.isError()) ? "Error" : "Complete";
                        const n = new Notification(path, {body: `Cell ${cellId} ${statusString}`, icon: favicon});
                        n.addEventListener("click", (ev) => {
                            this.publish(new FocusCell(path, cellId));
                            n.close();
                        });
                    }
                }
            } else if (status === TaskStatus.Queued) {
                if (!this.queuedCells.includes(cellId)) {
                    this.queuedCells.push(cellId)
                }
            }

            this.setIconBubble()
        });

        this.subscribe(CreateComment, (cellId, comment) => {
            this.socket.send(new messages.CreateComment(this.globalVersion, this.localVersion, cellId, comment))
        });
        this.subscribe(UpdateComment, (cellId, commentId, range, content) => {
            this.socket.send(new messages.UpdateComment(this.globalVersion, this.localVersion, cellId, commentId, range, content))
        });
        this.subscribe(DeleteComment, (cellId, commentId) => {
            this.socket.send(new messages.DeleteComment(this.globalVersion, this.localVersion, cellId, commentId))
        });
    }

    isFocused() {
        return CurrentNotebook.equals(this) && document.hasFocus()
    }

    setIconBubble() {
        if (CurrentNotebook.equals(this)) {
            const numRunningOrQueued = this.queuedCells.length + (this.runningCell ? 1 : 0);
            if (numRunningOrQueued <= 0) {
                Tinycon.setBubble(0);
                Tinycon.reset();
            } else {
                Tinycon.setBubble(numRunningOrQueued)
            }
        }
    }

    setCurrentSelection(cellId: number, range: PosRange) {
        if (this.currentSelectionTimeout) {
            window.clearTimeout(this.currentSelectionTimeout);
        }

        this.currentSelectionTimeout = window.setTimeout(
            () => {
                this.socket.send(new CurrentSelection(cellId, range));
                this.currentSelectionTimeout = undefined;
            },
            50);
    }

    setPresenceSelection(id: number, cellId: number, pos?: PosRange) {
        this.removePresenceSelection(id);
        const presence = this.otherUsers[id];
        if (presence && pos) {
            this.otherUserSelections[id] = [cellId, pos];
            const cell = this.cellUI.getCell(cellId);
            if (cell instanceof CodeCell) {
                cell.setPresence(id, presence.presence.name, presence.color, pos);
            }
        }
    }

    removePresenceSelection(id: number) {
        const prevSelection = this.otherUserSelections[id];
        if (prevSelection) {
            const [cellId, range] = prevSelection;
            const cell = this.cellUI.getCell(cellId);
            if (cell instanceof CodeCell) {
                cell.removePresence(id);
            }
        }
    }

    close() {
        this.socket.close();
        this.closed = true;
        delete notebooks[this.path];
    }

    handleTaskUpdate(task: TaskInfo) {
        this.kernelUI.updateTask(task);
        // TODO: this is a quick-and-dirty running cell indicator. Should do this in a way that doesn't use the task updates
        //       and instead have an EOF message to tell us when a cell is done
        const cellMatch = task.id.match(/^Cell (\d+)$/);
        if (cellMatch && cellMatch[1]) {
            const cellId = +(cellMatch[1]);
            this.cellUI.setStatus(cellId, task);
            this.cellStatusListeners.forEach(listener => listener(cellId, task.status))
        }
    }

    updateCellResults(result: Result, id: number) {
        if (result instanceof ClearResults) {
            this.cellResults[id] = {};
        } else if (result instanceof ResultValue) {
            this.kernelUI.addSymbol(result);

            if (!this.cellResults[id]) {
                this.cellResults[id] = {};
            }
            this.cellResults[id][result.name] = result;
        }
    }

    getCellContext(ids: number[]): Record<string, any> {
        const cellContext: Record<string, any> = {};
        for (let id of ids) {
            const results = this.cellResults[id];
            if (!results) {
                continue;
            }

            for (let key of Object.keys(results)) {
                const result = results[key];
                let bestValue: any = result.valueText;
                const dataRepr = result.reprs.find(repr => repr instanceof DataRepr);
                if (dataRepr) {
                    bestValue = (dataRepr as DataRepr).decode();
                } else {
                    const streamingRepr = result.reprs.find(repr => repr instanceof StreamingDataRepr);
                    if (streamingRepr) {
                        bestValue = new DataStream(this.socket, streamingRepr as StreamingDataRepr);
                    }
                }
                cellContext[key] = bestValue;
            }
        }
        return cellContext;
    }

    runToCursor() {
        if (this.currentCell) {
            const allCellIds = this.cellUI.getCodeCellIds();
            const activeCellIdx = allCellIds.indexOf(this.currentCell.id);
            if (activeCellIdx < 0) {
                console.log("Active cell is not part of current notebook?")
            } else {
                const cellIds = this.cellUI.getCodeCellIds();
                this.runCells(cellIds.slice(0, activeCellIdx + 1));
            }
        }
    }

    runCurrentCell() {
        if (this.currentCell) {
            this.runCells(this.currentCell.id)
        }
    }

    runAllCells() {
        const cellIds = this.cellUI.getCodeCellIds();
        this.runCells(cellIds);
    }

    runCells(cellIds: number[] | number) {
        if (!(cellIds instanceof Array)) {
            cellIds = [cellIds];
        }
        const serverRunCells: number[] = [];
        cellIds.forEach(id => {
            const cell = this.cellUI.getCell(id);
            if (cell && !this.queuedCells.includes(id) && this.runningCell !== id) {
                if (!clientInterpreters[cell.language]) {
                    serverRunCells.push(id);
                } else if (cell.language !== 'text' && cell instanceof CodeCell) {
                    const code = cell.content;
                    const cellsBefore = this.cellUI.getCodeCellIdsBefore(id);
                    const taskId = `Cell ${id}`;
                    const runCell = () => {
                        cell.clearResult();
                        this.handleTaskUpdate(new TaskInfo(taskId, taskId, '', TaskStatus.Running, 1));
                        let results = clientInterpreters[cell.language].interpret(
                            code,
                            {id, availableValues: this.getCellContext(cellsBefore)}
                        );
                        this.handleTaskUpdate(new TaskInfo(taskId, taskId, '', TaskStatus.Complete, 256));
                        results.forEach(result => {
                            this.updateCellResults(result, id);
                            cell.addResult(result);
                            if (result instanceof ClientResult) {
                                // notify the server of the MIME representation
                                result.toOutput().then(output => this.socket.send(new messages.SetCellOutput(this.globalVersion, this.localVersion++, id, output)));
                            }
                        });
                    };

                    const prevCellId = serverRunCells[serverRunCells.length -1] // check whether we need to wait for a soon-to-be queued up cell
                        || this.queuedCells[this.queuedCells.length - 1] // otherwise, make sure we wait for cells already queued up
                        || this.runningCell; // ok, what if there's a currently running cell then...
                    if (prevCellId) {
                        const runListener = (cellId: number, status: number) => {
                            if (cellId === prevCellId && status === TaskStatus.Complete) {
                                this.cellStatusListeners = this.cellStatusListeners.filter(item => item !== runListener);
                                runCell();
                            }
                        };
                        this.cellStatusListeners.push(runListener);

                        // this might be overkill, but it ensures that the cell queue task shows up in order...
                        const queueTask = () => this.handleTaskUpdate(new TaskInfo(taskId, taskId, '', TaskStatus.Queued, 0));
                        if (this.queuedCells.includes(prevCellId) || this.runningCell === prevCellId) {
                            queueTask()
                        } else {
                            const queueListener = (cellId: number, status: number) => {
                                if (cellId === prevCellId && (status === TaskStatus.Queued || status === TaskStatus.Running)) {
                                    this.cellStatusListeners = this.cellStatusListeners.filter(item => item !== queueListener);
                                    queueTask()
                                }
                            };
                            this.cellStatusListeners.push(queueListener)
                        }
                    } else {
                        runCell();
                    }
                }
            }
        });
        this.socket.send(new messages.RunCell(serverRunCells));
    }

    onCellLanguageSelected(setLanguage: string, id?: number) {
        id = id ?? this.currentCell?.id;
        const cell = id && this.cellUI.getCell(id);
        if (id && cell) {
            if (cell.language !== setLanguage) {
                this.cellUI.setCellLanguage(cell, setLanguage);
                this.socket.send(new messages.SetCellLanguage(this.globalVersion, this.localVersion++, id, setLanguage));
            }
        }

    }

    onCellsLoaded(path: string, cells: NotebookCell[], config?: NotebookConfig) {
        console.log(`Loaded ${path}`);

        ClientBackup.addNb(path, cells, config)
            // .then(backups => console.log("Added new backup. All backups for this notebook:", backups))
            .catch(err => console.error("Error adding backup", err));

        if (path === this.path) {
            if (config) {
                this.cellUI.configUI.setConfig(config);
            } else {
                this.cellUI.configUI.setConfig(NotebookConfig.default);
            }
            // TODO: move all of this logic out.
            this.socket.removeMessageListener([messages.NotebookCells, this.onCellsLoaded]);
            for (const cellInfo of cells) {
                let cell: Cell;
                switch (cellInfo.language) {
                    case 'text':
                    case 'markdown':
                        cell = new TextCell(cellInfo.id, cellInfo.content, this, cellInfo.metadata);
                        break;
                    default:
                        cell = new CodeCell(cellInfo.id, cellInfo.content, cellInfo.language, this, cellInfo.metadata);
                }

                // inserts cells at the end
                this.cellUI.insertCellBelow(undefined, () => cell);
                cellInfo.results.forEach(result => {
                    if (cell instanceof CodeCell) {
                        cell.addResult(result);
                    }
                    this.updateCellResults(result, cellInfo.id);
                });

                // handle comments
                if (cell instanceof CodeCell) {
                    Object.values(cellInfo.comments).forEach(function(comment) {
                        // not sure why I need this cast...
                        (cell as CodeCell).commentHandler.add(comment)
                    })
                }
            }
        }
        this.mainUI.publish(new CellsLoaded());
    }

    get currentCell() {
        return Cell.currentFocus // TODO: better way to keep track of this.
    }

    insertCell(direction: "above" | "below", anchor?: number, mkCell?: (nextCellId: number) => Cell, results?: Output[], postInsertCb?: (cell: Cell) => void): void {

        const anchorCell = anchor !== undefined ? this.cellUI.getCell(anchor) : this.currentCell || undefined; // sigh
        const anchorEl = anchorCell?.container;

        let insertedCell: Cell;
        if (direction === "above") {
            insertedCell = this.cellUI.insertCellAbove(anchorEl, mkCell)
        } else {
            insertedCell = this.cellUI.insertCellBelow(anchorEl, mkCell)
        }

        const notebookCell = new NotebookCell(insertedCell.id, insertedCell.language, insertedCell.content, results || [], insertedCell.metadata);

        const prevCell = this.cellUI.getCellBefore(insertedCell);
        const update = new messages.InsertCell(this.globalVersion, ++this.localVersion, notebookCell, prevCell?.id ?? -1);
        this.sendUpdate(update);
        this.editBuffer.push(this.localVersion, update);

        if (postInsertCb) {
            postInsertCb(insertedCell);
        }

        insertedCell.focus();
    }

    deleteCell(cellId?: number): void {
        const deleteCellId = cellId ?? this.currentCell?.id ;
        if (deleteCellId !== undefined) {
            this.cellUI.deleteCell(deleteCellId, () => {
                const update = new messages.DeleteCell(this.globalVersion, ++this.localVersion, deleteCellId);
                this.sendUpdate(update);
                this.editBuffer.push(this.localVersion, update);

            });
        }
    }

    selectCell(cellId: number) {
        const cell = this.cellUI.getCell(cellId);
        if (cell) {
            cell.focus()
        }
    }

    selectNextCell(cellId: number) {
        const current = this.cellUI.getCell(cellId);
        if (current) {
            const next = this.cellUI.getCellAfter(current);
            if (next) {
                next.focus();
            } else {
                this.insertCell("below", cellId);
            }
        }
    }

    selectPrevCell(cellId: number) {
        const current = this.cellUI.getCell(cellId);
        if (current) {
            const prev = this.cellUI.getCellBefore(current);
            if (prev) {
                prev.focus();
            }
        }
    }

    handleContentChange(cellId: number, edits: ContentEdit[], metadata?: CellMetadata) {
        const update = new messages.UpdateCell(this.globalVersion, ++this.localVersion, cellId, edits, metadata);
        this.sendUpdate(update);
        this.editBuffer.push(this.localVersion, update);
    }

    completionRequest(id: number, pos: number, resolve: (completions: CompletionList) => void, reject: () => void) {
        const receiveCompletions = (cell: number, receivedPos: number, completions: CompletionCandidate[]) => {
            this.socket.removeMessageListener([messages.CompletionsAt, receiveCompletions]);
            const len = completions.length;
            const indexStrLen = ("" + len).length;
            const completionResults = completions.map((candidate, index) => {
                const isMethod = candidate.params.length > 0 || candidate.typeParams.length > 0;

                const typeParams = candidate.typeParams.length ? `[${candidate.typeParams.join(', ')}]`
                    : '';

                const params = isMethod ? candidate.params.map(pl => `(${pl.map(param => `${param.name}: ${param.type}`).join(', ')})`).join('')
                    : '';

                const label = `${candidate.name}${typeParams}${params}`;

                const insertText =
                    candidate.insertText || candidate.name; //+ (params.length ? '($2)' : '');

                // Calculating Range (TODO: Maybe we should try to standardize our range / position / offset usage across the codebase, it's a pain to keep converting back and forth).
                const model = (this.cellUI.getCell(cell) as CodeCell).editor.getModel()!;
                const p = model.getPositionAt(pos);
                const word = model.getWordUntilPosition(p);
                const range = new Range(p.lineNumber, word.startColumn, p.lineNumber, word.endColumn);
                return {
                    kind: isMethod ? 1 : 9,
                    label: label,
                    insertText: insertText,
                    filterText: insertText,
                    insertTextRules: 4,
                    sortText: ("" + index).padStart(indexStrLen, '0'),
                    detail: candidate.type,
                    range: range
                };
            });
            resolve({suggestions: completionResults});
        };

        this.socket.addMessageListener(messages.CompletionsAt, receiveCompletions);
        this.socket.send(new messages.CompletionsAt(id, pos, []));
    }

    paramHintRequest(id: number, pos: number, resolve: (completions?: SignatureHelp) => void, reject: () => void) {

        const receiveHints = (cell: number, receivedPos: number, signatures?: Signatures) => {
            this.socket.removeMessageListener([messages.ParametersAt, receiveHints]);
            if (signatures) {
                resolve({
                    activeParameter: signatures.activeParameter,
                    activeSignature: signatures.activeSignature,
                    signatures: signatures.hints.map(sig => {
                        const params = sig.parameters.map(param => {
                            return {
                                label: param.typeName ? `${param.name}: ${param.typeName}` : param.name,
                                documentation: param.docString || undefined
                            }
                        });

                        return {
                            documentation: sig.docString || undefined,
                            label: sig.name,
                            parameters: params
                        }
                    })
                });
            } else resolve({activeSignature: 0, activeParameter: 0, signatures: []});
        };

        this.socket.addMessageListener(messages.ParametersAt, receiveHints);
        this.socket.send(new messages.ParametersAt(id, pos))
    }

    updateConfig(conf: NotebookConfig) {
        const update = new messages.UpdateConfig(this.globalVersion, ++this.localVersion, conf);
        this.editBuffer.push(this.localVersion, update);
        this.kernelUI.tasks.clear(); // old tasks no longer relevant with new config.
        this.sendUpdate(update);
    }

    private sendUpdate(upd: NotebookUpdate) {
        this.socket.send(upd);
        ClientBackup.updateNb(this.path, upd)
            .catch(err => console.error("Error backing up update", err))
    }
}