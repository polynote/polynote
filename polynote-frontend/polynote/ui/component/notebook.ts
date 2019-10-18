import {CellsLoaded, ReprDataRequest, SelectCell, UIMessage, UIMessageTarget} from "../util/ui_event";
import {NotebookCellsUI} from "./nb_cells";
import {KernelUI} from "./kernel_ui";
import {EditBuffer} from "../../data/edit_buffer";
import * as messages from "../../data/messages";
import {Cell, CodeCell, TextCell} from "./cell";
import match from "../../util/match";
import { ClearResults, ClientResult, Output, Result, ResultValue } from "../../data/result";
import {DataRepr, DataStream, StreamingDataRepr} from "../../data/value_repr";
import {clientInterpreters} from "../../interpreter/client_interpreter";
import {CellMetadata, NotebookCell, NotebookConfig} from "../../data/data";
import {SocketSession} from "../../comms";
import {MainUI} from "./ui";
import {CompletionCandidate, Signatures, TaskInfo, TaskStatus} from "../../data/messages";
import {languages, Range} from "monaco-editor";
import {ContentEdit} from "../../data/content_edit";
import {Either, Left, Right} from "../../data/types";
import * as Tinycon from "tinycon";
import CompletionList = languages.CompletionList;
import SignatureHelp = languages.SignatureHelp;

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

    // TODO: remove mainUI reference
    constructor(eventParent: UIMessageTarget, readonly path: string, readonly mainUI: MainUI) {
        super(eventParent);
        let cellUI = new NotebookCellsUI(this, path);
        let kernelUI = new KernelUI(this, path);
        this.cellUI = cellUI;
        this.kernelUI = kernelUI;

        this.cellResults = {};

        this.globalVersion = 0;
        this.localVersion = 0;

        this.editBuffer = new EditBuffer();

        // TODO: remove listeners on children.

        this.subscribe(SelectCell, cell => {
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
            this.kernelUI.symbols.presentFor(id, ids);
        });

        this.subscribe(ReprDataRequest, (reqHandleType, reqHandleId, reqCount, reqOnComplete, reqOnFail) => {
            SocketSession.get.listenOnceFor(messages.HandleData, (path, handleType, handleId, count, data: Left<messages.Error> | Right<ArrayBuffer[]>) => {
                if (path === this.path && handleType === reqHandleType && handleId === reqHandleId) {
                    Either.fold(data, err => reqOnFail(err), bufs => reqOnComplete(bufs));
                    return false;
                } else return true;
            });
            SocketSession.get.send(new messages.HandleData(path, reqHandleType, reqHandleId, reqCount, Either.right([])));
        });

        SocketSession.get.addMessageListener(messages.NotebookCells, this.onCellsLoaded.bind(this));

        SocketSession.get.addMessageListener(messages.KernelStatus, (path, update) => {
            if (path === this.path) {
                switch(update.constructor) {
                    case messages.UpdatedTasks:
                        update.tasks.forEach((task: TaskInfo) => {
                            this.handleTaskUpdate(task);
                        });
                        break;
                    case messages.KernelBusyState:
                        const state = (update.busy && 'busy') || (!update.alive && 'dead') || 'idle';
                        this.kernelUI.setKernelState(state);
                        break;
                    case messages.KernelInfo:
                        this.kernelUI.updateInfo(update.content);
                        break;
                    case messages.ExecutionStatus:
                        this.cellUI.setExecutionHighlight(update.cellId, update.pos || null);
                        break;
                }
            }
        });

        SocketSession.get.addMessageListener(messages.NotebookUpdate, (update: messages.NotebookUpdate) => {
            if (update.path === this.path) {
                if (update.globalVersion >= this.globalVersion) {
                    this.globalVersion = update.globalVersion;

                    if (update.localVersion < this.localVersion) {
                        const prevUpdates = this.editBuffer.range(update.localVersion, this.localVersion);
                        update = messages.NotebookUpdate.rebase(update, prevUpdates);
                    }


                    this.localVersion++;

                    match(update)
                        .when(messages.UpdateCell, (p: string, g: number, l: number, id: number, edits: ContentEdit[], metadata?: CellMetadata) => {
                            const cell = this.cellUI.getCell(id);
                            if (cell) {
                                cell.applyEdits(edits);
                                this.editBuffer.push(this.localVersion, update);
                                if (metadata) {
                                    cell.setMetadata(metadata);
                                }
                            }
                        })
                        .when(messages.InsertCell, (p: string, g: number, l: number, cell: NotebookCell, after: number) => {
                            const prev = this.cellUI.getCell(after);
                            const newCell = (prev && prev.language && prev.language !== "text")
                                ? new CodeCell(cell.id, cell.content, cell.language, this.path)
                                : new TextCell(cell.id, cell.content, this.path);

                            this.cellUI.insertCellBelow(prev && prev.container, () => newCell)
                        })
                        .when(messages.DeleteCell, (p: string, g: number, l: number, id: number) => this.cellUI.deleteCell(id))
                        .when(messages.UpdateConfig, (p: string, g: number, l: number, config: NotebookConfig) => this.cellUI.configUI.setConfig(config))
                        .when(messages.SetCellLanguage, (p: string, g: number, l: number, id, language: string) => {
                            const cell = this.cellUI.getCell(id);
                            if (cell) {
                                this.cellUI.setCellLanguage(cell, language)
                            } else {
                                throw new Error(`Cell ${id} does not exist in the current notebook`)
                            }
                        })
                        .when(messages.SetCellOutput, (p: string, g: number, l: number, id, output?: Output) => {
                            const cell = this.cellUI.getCell(id);
                            if (cell instanceof CodeCell) {
                                cell.clearResult();
                                if (output) {
                                    cell.addOutput(output.contentType, output.content);
                                }
                            }
                        });


                    // discard edits before the local version from server – it will handle rebasing at least until that point
                    this.editBuffer.discard(update.localVersion);

                }
            }
        });


        // TODO: this doesn't seem like the best place for this reconnection logic.
        // when the socket is disconnected, we're going to try reconnecting when the window gets focus.
        const reconnectOnWindowFocus = () => {
            if (SocketSession.get.isClosed) {
                SocketSession.get.reconnect(true);
            }
            // TODO: replace with `socket.request`
            SocketSession.get.listenOnceFor(messages.NotebookVersion, (path, serverGlobalVersion) => {
                if (this.globalVersion !== serverGlobalVersion) {
                    // looks like there's been a change while we were disconnected, so reload.
                    document.location.reload();
                }
            });
            SocketSession.get.send(new messages.NotebookVersion(path, this.globalVersion))
        };

        SocketSession.get.addEventListener('close', evt => {
            this.cellUI.setDisabled(true);
            window.addEventListener('focus', reconnectOnWindowFocus);
        });

        SocketSession.get.addEventListener('open', evt => {
            window.removeEventListener('focus', reconnectOnWindowFocus);
            SocketSession.get.send(new messages.KernelStatus(path, new messages.KernelBusyState(false, false)));
            this.cellUI.setDisabled(false);
        });

        SocketSession.get.addMessageListener(messages.CellResult, (path, id, result) => {
            if (path === this.path) {
                const cell = this.cellUI.getCell(id);

                // Cell ids less than 0 refer to the Predef and other server-side things we can safely ignore.
                // However, Cell Ids >=0 are expected to exist in the current notebook, so we throw if we can't find 'em
                if (id >=0 && !cell) throw new Error(`Cell Id ${id} does not exist in the current notebook`);

                this.updateCellResults(result, id);
                if (cell instanceof CodeCell) cell.addResult(result)
            }
        });

        // update list of currently running cells
        this.cellStatusListeners.push((cellId: number, status: number) => {
            if (status === TaskStatus.Running) {
                this.runningCell = cellId;
                this.queuedCells = this.queuedCells.filter(item => item !== cellId)
            } else if (status === TaskStatus.Complete || status === TaskStatus.Error) {
                this.runningCell = undefined;
                this.queuedCells = this.queuedCells.filter(item => item !== cellId) // just in case
            } else if (status === TaskStatus.Queued) {
                if (!this.queuedCells.includes(cellId)) {
                    this.queuedCells.push(cellId)
                }
            }

            const numRunningOrQueued = this.queuedCells.length + (this.runningCell ? 1 : 0);
            if (numRunningOrQueued <= 0) {
                Tinycon.setBubble(0);
                Tinycon.reset();
            } else {
                Tinycon.setBubble(numRunningOrQueued)
            }
        })
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
            this.kernelUI.symbols.addSymbol(result);

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
                        bestValue = new DataStream(this.path, streamingRepr as StreamingDataRepr);
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
                                result.toOutput().then(output => SocketSession.get.send(new messages.SetCellOutput(this.path, this.globalVersion, this.localVersion++, id, output)));
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
        SocketSession.get.send(new messages.RunCell(this.path, serverRunCells));
    }

    onCellLanguageSelected(setLanguage: string, id?: number) {
        id = id !== undefined ? id : (this.currentCell ? this.currentCell.id : undefined);
        const cell = id && this.cellUI.getCell(id);
        if (id && cell) {
            if (cell.language !== setLanguage) {
                this.cellUI.setCellLanguage(cell, setLanguage);
                SocketSession.get.send(new messages.SetCellLanguage(this.path, this.globalVersion, this.localVersion++, id, setLanguage));
            }
        }

    }

    onCellsLoaded(path: string, cells: NotebookCell[], config?: NotebookConfig) {
        console.log(`Loaded ${path}`);
        if (path === this.path) {
            if (config) {
                this.cellUI.configUI.setConfig(config);
            } else {
                this.cellUI.configUI.setConfig(NotebookConfig.default);
            }
            // TODO: move all of this logic out.
            SocketSession.get.removeMessageListener([messages.NotebookCells, this.onCellsLoaded]);
            for (const cellInfo of cells) {
                let cell: Cell;
                switch (cellInfo.language) {
                    case 'text':
                    case 'markdown':
                        cell = new TextCell(cellInfo.id, cellInfo.content, path, cellInfo.metadata);
                        break;
                    default:
                        cell = new CodeCell(cellInfo.id, cellInfo.content, cellInfo.language, path, cellInfo.metadata);
                }

                // inserts cells at the end
                this.cellUI.insertCellBelow(undefined, () => cell);
                cellInfo.results.forEach(result => {
                    if (cell instanceof CodeCell) {
                        cell.addResult(result);
                    }
                    this.updateCellResults(result, cellInfo.id);
                })
            }
        }
        this.mainUI.publish(new CellsLoaded());
    }

    get currentCell() {
        return Cell.currentFocus // TODO: better way to keep track of this.
    }

    insertCell(direction: "above" | "below", anchor?: number, mkCell?: (nextCellId: number) => Cell, results?: Output[], postInsertCb?: (cell: Cell) => void): void {

        const anchorCell = anchor !== undefined ? this.cellUI.getCell(anchor) : this.currentCell || undefined; // sigh
        const anchorEl = anchorCell && anchorCell.container;

        let insertedCell: Cell;
        if (direction === "above") {
            insertedCell = this.cellUI.insertCellAbove(anchorEl, mkCell)
        } else {
            insertedCell = this.cellUI.insertCellBelow(anchorEl, mkCell)
        }

        const notebookCell = new NotebookCell(insertedCell.id, insertedCell.language, insertedCell.content, results || [], insertedCell.metadata);

        const prevCell = this.cellUI.getCellBefore(insertedCell);
        const update = new messages.InsertCell(this.path, this.globalVersion, ++this.localVersion, notebookCell, prevCell ? prevCell.id : -1);
        SocketSession.get.send(update);
        this.editBuffer.push(this.localVersion, update);

        if (postInsertCb) {
            postInsertCb(insertedCell);
        }

        insertedCell.focus();
    }

    deleteCell(cellId?: number): void {
        const deleteCellId = cellId !== undefined ? cellId : (this.currentCell ? this.currentCell.id : undefined);
        if (deleteCellId !== undefined) {
            this.cellUI.deleteCell(deleteCellId, () => {
                const update = new messages.DeleteCell(this.path, this.globalVersion, ++this.localVersion, deleteCellId);
                SocketSession.get.send(update);
                this.editBuffer.push(this.localVersion, update);

            });
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
        const update = new messages.UpdateCell(this.path, this.globalVersion, ++this.localVersion, cellId, edits, metadata);
        SocketSession.get.send(update);
        this.editBuffer.push(this.localVersion, update);
    }

    completionRequest(id: number, pos: number, resolve: (completions: CompletionList) => void, reject: () => void) {
        const receiveCompletions = (notebook: string, cell: number, receivedPos: number, completions: CompletionCandidate[]) => {
            if (notebook === this.path && cell === id && pos === receivedPos) {
                SocketSession.get.removeMessageListener([messages.CompletionsAt, receiveCompletions]);
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
                        insertTextRules: 4,
                        sortText: ("" + index).padStart(indexStrLen, '0'),
                        detail: candidate.type,
                        range: range
                    };
                });
                resolve({suggestions: completionResults});
            }
        };

        SocketSession.get.addMessageListener(messages.CompletionsAt, receiveCompletions);
        SocketSession.get.send(new messages.CompletionsAt(this.path, id, pos, []));
    }

    paramHintRequest(id: number, pos: number, resolve: (completions?: SignatureHelp) => void, reject: () => void) {

        const receiveHints = (notebook: string, cell: number, receivedPos: number, signatures?: Signatures) => {
            if (notebook === this.path && cell === id && pos === receivedPos) {
                SocketSession.get.removeMessageListener([messages.ParametersAt, receiveHints]);
                if (signatures) {
                    resolve({
                        activeParameter: signatures.activeParameter,
                        activeSignature: signatures.activeSignature,
                        signatures: signatures.hints.map(sig => {
                            const params = sig.parameters.map(param => {
                                return {
                                    label: param.typeName ? `${param.name}: ${param.typeName}` : param.name,
                                    documentation: param.docString || undefined
                                };
                            });

                            return {
                                documentation: sig.docString || undefined,
                                label: sig.name,
                                parameters: params
                            }
                        })
                    });
                } else resolve({activeSignature: 0, activeParameter: 0, signatures: []});
            }
        };

        SocketSession.get.addMessageListener(messages.ParametersAt, receiveHints);
        SocketSession.get.send(new messages.ParametersAt(this.path, id, pos))
    }

    updateConfig(conf: NotebookConfig) {
        const update = new messages.UpdateConfig(this.path, this.globalVersion, ++this.localVersion, conf);
        this.editBuffer.push(this.localVersion, update);
        this.kernelUI.tasks.clear(); // old tasks no longer relevant with new config.
        SocketSession.get.send(update);
    }
}