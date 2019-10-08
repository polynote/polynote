import {UIEvent, UIEventTarget} from "../util/ui_event";
import {NotebookCellsUI} from "./nb_cells";
import {KernelUI} from "./kernel_ui";
import {EditBuffer} from "../../data/edit_buffer";
import * as messages from "../../data/messages";
import {BeforeCellRunEvent, Cell, CellExecutionFinished, CodeCell, TextCell} from "./cell";
import match from "../../util/match";
import {
    ClearResults,
    ClientResult,
    CompileErrors,
    ExecutionInfo,
    Output, Result,
    ResultValue,
    RuntimeError
} from "../../data/result";
import {DataRepr, DataStream, StreamingDataRepr} from "../../data/value_repr";
import {clientInterpreters} from "../../interpreter/client_interpreter";
import {CellMetadata, NotebookCell, NotebookConfig} from "../../data/data";
import {SocketSession} from "../../comms";
import {MainUI} from "./ui";
import {CompletionCandidate, Signatures} from "../../data/messages";
import {languages, Range} from "monaco-editor";
import CompletionItem = languages.CompletionItem;
import {ContentEdit} from "../../data/content_edit";
import {StructType} from "../../data/data_type";
import {Either, Left, Right} from "../../data/types";

export class NotebookUI extends UIEventTarget {
    readonly cellUI: NotebookCellsUI;
    readonly kernelUI: KernelUI;
    private cellResults: Record<number, Record<string, ResultValue>>;
    private globalVersion: number;
    private localVersion: number;
    private editBuffer: EditBuffer;

    // TODO: remove mainUI reference
    constructor(eventParent: UIEventTarget, readonly path: string, readonly mainUI: MainUI) {
        super(eventParent);
        let cellUI = new NotebookCellsUI(this, path);
        let kernelUI = new KernelUI(this, path);
        this.cellUI = cellUI;
        this.kernelUI = kernelUI;

        this.cellResults = {};

        this.globalVersion = 0;
        this.localVersion = 0;

        this.editBuffer = new EditBuffer();

        this.addEventListener('SetCellLanguage', evt => this.onCellLanguageSelected(evt.detail.language, evt.detail.cellId));

        // TODO: remove listeners on children.
        this.cellUI.addEventListener('UpdatedConfig', evt => {
            const update = new messages.UpdateConfig(path, this.globalVersion, ++this.localVersion, evt.detail.config);
            this.editBuffer.push(this.localVersion, update);
            this.kernelUI.tasks.clear(); // old tasks no longer relevant with new config.
            SocketSession.get.send(update);
        });

        this.cellUI.addEventListener('SelectCell', evt => {
            const cellTypeSelector = mainUI.toolbarUI.cellToolbar.cellTypeSelector;
            const id = evt.detail.cellId;
            let i = 0;

            // update cell type selector
            for (const opt of cellTypeSelector.options) {
                if (opt.value === evt.detail.cell.language) {
                    cellTypeSelector.selectedIndex = i;
                    break;
                }
                i++;
            }

            // notify toolbar of context change
            mainUI.toolbarUI.onContextChanged();

            // check if element is in viewport
            const viewport = mainUI.notebookContent;
            const viewportScrollTop = viewport.scrollTop;
            const viewportScrollBottom = viewportScrollTop + viewport.clientHeight;

            const container = evt.detail.cell.container;
            const elTop = container.offsetTop;
            const elBottom = elTop + container.offsetHeight;

            if (elBottom < viewportScrollTop) { // need to scroll up
                evt.detail.cell.container.scrollIntoView({behavior: "auto", block: "start", inline: "nearest"})
            } else if (elTop > viewportScrollBottom) { // need to scroll down
                evt.detail.cell.container.scrollIntoView({behavior: "auto", block: "end", inline: "nearest"})
            }

            //update notebook scroll store with new position:
            mainUI.tabUI.setCurrentScrollLocation(viewport.scrollTop + viewport.clientHeight / 2);

            // update the symbol table to reflect what's visible from this cell
            const ids = this.cellUI.getCodeCellIdsBefore(id);
            this.kernelUI.symbols.presentFor(id, ids);
        });

        this.cellUI.addEventListener('AdvanceCell', evt => {
            if (this.currentCell) {
                if (evt.detail.backward) {
                    const prev = this.cellUI.getCellBefore(this.currentCell);
                    if (prev) {
                        prev.focus();
                    }
                } else {
                    const next = this.cellUI.getCellAfter(this.currentCell);
                    if (next) {
                        next.focus();
                    } else {
                        this.insertCell("below", this.currentCell.id);
                    }
                }
            }
        });

        this.cellUI.addEventListener('ContentChange', (evt) => {
            const update = new messages.UpdateCell(path, this.globalVersion, ++this.localVersion, evt.detail.cellId, evt.detail.edits, evt.detail.metadata);
            SocketSession.get.send(update);
            this.editBuffer.push(this.localVersion, update);
        });

        this.cellUI.addEventListener('CompletionRequest', (evt) => {
            const id = evt.detail.cellId;
            const pos = evt.detail.pos;
            const resolve = evt.detail.resolve;
            const reject = evt.detail.reject;

            const receiveCompletions = (notebook: string, cell: number, receivedPos: number, completions: CompletionCandidate[]) => {
                if (notebook === path && cell === id && pos === receivedPos) {
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
                            candidate.name; //+ (params.length ? '($2)' : '');

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
                    //console.log(completionResults);
                    resolve({suggestions: completionResults});
                }
            };

            SocketSession.get.addMessageListener(messages.CompletionsAt, receiveCompletions);
            SocketSession.get.send(new messages.CompletionsAt(path, id, pos, []));
        });

        this.cellUI.addEventListener('ParamHintRequest', (evt) => {
            const id = evt.detail.cellId;
            const pos = evt.detail.pos;
            const resolve = evt.detail.resolve;
            const reject = evt.detail.reject;

            const receiveHints = (notebook: string, cell: number, receivedPos: number, signatures?: Signatures) => {
                if (notebook === path && cell === id && pos === receivedPos) {
                    SocketSession.get.removeMessageListener([messages.ParametersAt, receiveHints]);
                    if (signatures) {
                        resolve({
                            activeParameter: signatures.activeParameter,
                            activeSignature: signatures.activeSignature,
                            signatures: signatures.hints.map(sig => {
                                const params = sig.parameters.map(param => {
                                    return {
                                        label: param.typeName ? `${param.name}: ${param.typeName}` : param.name,
                                        documentation: param.docString
                                    };
                                });

                                return {
                                    documentation: sig.docString,
                                    label: sig.name,
                                    parameters: params
                                }
                            })
                        });
                    } else resolve(undefined);
                }
            };

            SocketSession.get.addMessageListener(messages.ParametersAt, receiveHints);
            SocketSession.get.send(new messages.ParametersAt(path, id, pos))
        });

        this.cellUI.addEventListener("ReprDataRequest", evt => {
            const req = evt.detail;
            SocketSession.get.listenOnceFor(messages.HandleData, (path, handleType, handleId, count, data: Left<messages.Error> | Right<ArrayBuffer[]>) => {
                if (path === this.path && handleType === req.handleType && handleId === req.handleId) {
                    Either.fold(data, err => req.onFail(err), bufs => req.onComplete(bufs));
                    return false;
                } else return true;
            });
            SocketSession.get.send(new messages.HandleData(path, req.handleType, req.handleId, req.count, Either.right([])));
        });

        SocketSession.get.addMessageListener(messages.NotebookCells, this.onCellsLoaded.bind(this));

        this.addEventListener('UpdatedTask', evt => {
            // TODO: this is a quick-and-dirty running cell indicator. Should do this in a way that doesn't use the task updates
            //       and instead have an EOF message to tell us when a cell is done
            const taskInfo = evt.detail.taskInfo;
            const cellMatch = taskInfo.id.match(/^Cell (\d+)$/);
            if (cellMatch && cellMatch[1]) {
                this.cellUI.setStatus(+(cellMatch[1]), taskInfo);
            }
        });

        this.addEventListener('UpdatedExecutionStatus', evt => {
            const update = evt.detail.update;
            this.cellUI.setExecutionHighlight(update.cellId, update.pos || null);
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

                this.handleResult(result, id, cell);
            }
        });
    }

    // Some results (like Predef ResultValues or ClearResults) have an ID but no cell associated with them.
    handleResult(result: Result, id: number, cell?: Cell) {
        const ifCell = (f: (_: CodeCell) => void) => {
            if (cell instanceof CodeCell) f(cell)
        };

        if (result instanceof CompileErrors) {
            ifCell(cell => cell.setErrors(result.reports));
        } else if (result instanceof RuntimeError) {
            console.log(result.error);
            ifCell(cell => cell.setRuntimeError(result.error));
        } else if (result instanceof Output) {
            ifCell(cell => cell.addOutput(result.contentType, result.content));
        } else if (result instanceof ClearResults) {
            this.cellResults[id] = {};
            ifCell(cell => cell.clearResult());
        } else if (result instanceof ExecutionInfo) {
            ifCell(cell => cell.setExecutionInfo(result));
        } else if (result instanceof ResultValue) {
            this.kernelUI.symbols.addSymbol(result);

            if (!this.cellResults[id]) {
                this.cellResults[id] = {};
            }
            this.cellResults[id][result.name] = result;

            ifCell(cell => cell.addResult(result));
        } else if (result instanceof ClientResult) {
            ifCell(cell => cell.addResult(result));
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
        let prevCell: number;
        cellIds.forEach(id => {
            const cell = this.cellUI.getCell(id);
            if (cell) {
                cell.dispatchEvent(new BeforeCellRunEvent(id));
                if (!clientInterpreters[cell.language]) {
                    serverRunCells.push(id);
                } else if (cell.language !== 'text' && cell instanceof CodeCell) {
                    const code = cell.content;
                    const cellsBefore = this.cellUI.getCodeCellIdsBefore(id);
                    const runCell = () => {
                        cell.clearResult();
                        let results = clientInterpreters[cell.language].interpret(
                            code,
                            {id, availableValues: this.getCellContext(cellsBefore)}
                        );
                        results.forEach(result => {
                            this.handleResult(result, id, cell);
                            if (result instanceof ClientResult) {
                                // notify the server of the MIME representation
                                result.toOutput().then(output => SocketSession.get.send(new messages.SetCellOutput(this.path, this.globalVersion, this.localVersion++, id, output)));
                            }
                        });
                    };
                    if (prevCell) {
                        const predecessor = prevCell;
                        // when the preceeding cell finishes executing, execute this one
                        const listener = (evt: CellExecutionFinished) => {
                            if (evt.cellId === predecessor) {
                                removeEventListener('CellExecutionFinished', listener);
                                runCell();
                            }
                        };
                        addEventListener('CellExecutionFinished', listener)
                    } else {
                        runCell();
                    }
                }
                if (cell.language !== 'text') {
                    prevCell = id;
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
                cellInfo.results.forEach(
                    result => {
                        if (result instanceof CompileErrors) {
                            (cell as CodeCell).setErrors(result.reports)
                        } else if (result instanceof RuntimeError) {
                            (cell as CodeCell).setRuntimeError(result.error)
                        } else if (result instanceof Output) {
                            (cell as CodeCell).addOutput(result.contentType, result.content)
                        } else if (result instanceof ResultValue) {
                            (cell as CodeCell).addResult(result);
                        }
                    }
                )
            }
        }
        this.mainUI.dispatchEvent(new UIEvent('CellsLoaded'))
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
}