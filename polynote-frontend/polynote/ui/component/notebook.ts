// REMOVE SOCKET
import {UIEvent, UIEventTarget} from "../util/ui_event";
import {NotebookCellsUI} from "./nb_cells";
import {KernelUI} from "./kernel_ui";
import {EditBuffer} from "../../data/edit_buffer";
import * as messages from "../../data/messages";
import {BeforeCellRunEvent, Cell, CellExecutionFinished, CodeCell, TextCell} from "./cell";
import {div, span} from "../util/tags";
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

export class NotebookUI extends UIEventTarget {
    readonly cellUI: NotebookCellsUI;
    readonly kernelUI: KernelUI;
    private cellResults: Record<number, Record<string, ResultValue>>;
    private globalVersion: number;
    private localVersion: number;
    private editBuffer: EditBuffer;

    // TODO: remove socket, mainUI references
    constructor(eventParent: UIEventTarget, readonly path: string, readonly socket: SocketSession, readonly mainUI: MainUI) {
        super(eventParent);
        let cellUI = new NotebookCellsUI(this, path);
        let kernelUI = new KernelUI(this, path);
        //super(null, cellUI, kernelUI);
        //this.el.classList.add('notebook-ui');
        this.cellUI = cellUI;
        this.kernelUI = kernelUI;

        this.cellResults = {};

        this.globalVersion = 0;
        this.localVersion = 0;

        this.editBuffer = new EditBuffer();

        this.addEventListener('SetCellLanguage', evt => this.onCellLanguageSelected(evt.detail.language, this.path, evt.detail.cellId));

        // TODO: remove listeners on children.
        this.cellUI.addEventListener('UpdatedConfig', evt => {
            const update = new messages.UpdateConfig(path, this.globalVersion, ++this.localVersion, evt.detail.config);
            this.editBuffer.push(this.localVersion, update);
            this.kernelUI.tasks.clear(); // old tasks no longer relevant with new config.
            this.socket.send(update);
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
            if (Cell.currentFocus) {
                if (evt.detail.backward) {
                    const prev = Cell.currentFocus.prevCell!();
                    if (prev) {
                        prev.focus();
                    }
                } else {
                    const next = Cell.currentFocus.nextCell!();
                    if (next) {
                        next.focus();
                    } else {
                        this.cellUI.dispatchEvent(new UIEvent('InsertCellAfter', {cellId: Cell.currentFocus.id}));
                    }
                }
            }
        });

        this.addEventListener('InsertCellAfter', evt => {
            const current = this.cellUI.getCell(evt.detail.cellId) || this.cellUI.getCell(this.cellUI.firstCell().id);
            const nextId = this.cellUI.getMaxCellId() + 1;
            let newCell: Cell;
            if (evt.detail.mkCell) {
                newCell = evt.detail.mkCell(nextId);
            } else {
                newCell = current.language === 'text' ? new TextCell(nextId, '', this.path) : new CodeCell(nextId, '', current.language, this.path);
            }
            const notebookCell = new NotebookCell(newCell.id, newCell.language, newCell.content, evt.detail.results || [], newCell.metadata);
            const update = new messages.InsertCell(path, this.globalVersion, ++this.localVersion, notebookCell, current.id);
            this.socket.send(update);
            this.editBuffer.push(this.localVersion, update);
            this.cellUI.insertCell(newCell, current);
            if (evt.detail.afterInsert) {
                evt.detail.afterInsert(newCell);
            }
            newCell.focus();
        });

        // TODO: shares a lot of logic with InsertCellAfter
        // TODO: BUG! what if there are no cells!
        this.cellUI.addEventListener('InsertCellBefore', evt => {
            const current = this.cellUI.getCell(evt.detail.cellId) || this.cellUI.firstCell();
            const nextId = this.cellUI.getMaxCellId() + 1;
            const newCell = current.language === 'text' ? new TextCell(nextId, '', this.path) : new CodeCell(nextId, '', current.language, this.path);
            const notebookCell = new NotebookCell(newCell.id, newCell.language, newCell.content, evt.detail.results || [], newCell.metadata);
            if (current === this.cellUI.firstCell()) {
                const update = new messages.InsertCell(path, this.globalVersion, this.localVersion++, notebookCell, -1);
                this.socket.send(update);
                this.cellUI.insertCell(newCell, null);
            } else {
                const prev = current.prevCell!()!; // TODO: this could be undefined, fix later.
                const update = new messages.InsertCell(path, this.globalVersion, ++this.localVersion, notebookCell, prev.id);
                this.socket.send(update);
                this.cellUI.insertCell(newCell, prev);

            }
            newCell.focus();
        });

        this.cellUI.addEventListener('DeleteCell', evt => {
            const current = Cell.currentFocus;
            if (current) {
                const allCellIds = this.cellUI.getCells().map(cell => cell.id);
                const currentIndex = allCellIds.indexOf(current.id);
                if (currentIndex < 0) {
                    throw "Active cell is not part of current notebook?"
                }
                const prevCells = allCellIds.slice(0, currentIndex);

                const update = new messages.DeleteCell(path, this.globalVersion, ++this.localVersion, current.id);
                this.socket.send(update);
                this.editBuffer.push(this.localVersion, update);
                const nextCell = current.nextCell!();

                const cell = new NotebookCell(current.id, current.language, current.content);

                const undoEl = div(['undo-delete'], [
                    span(['close-button', 'fa'], ['']).click(evt => {
                        undoEl.parentNode!.removeChild(undoEl);
                    }),
                    span(['undo-message'], [
                        'Cell deleted. ',
                        span(['undo-link'], ['Undo']).click(evt => {
                            let prevCell = prevCells.pop()!;
                            while (prevCells.length && !this.cellUI.getCell(prevCell!)) {
                                prevCell = prevCells.pop()!;
                            }

                            // TODO: check if passing -1 actually works (Polykernel.updateNotebook might need to handle that case?) ! We might need to change InsertCell to take an optional `after` instead?
                            const update = new messages.InsertCell(path, this.globalVersion, ++this.localVersion, cell, this.cellUI.getCell(prevCell) ? prevCell : -1);
                            this.socket.send(update);
                            const newCell = cell.language === 'text' ? new TextCell(cell.id, cell.content, this.path) : new CodeCell(cell.id, cell.content, cell.language, this.path);
                            this.cellUI.insertCell(newCell, prevCell);
                            undoEl.parentNode!.removeChild(undoEl);
                        })
                    ])
                ]);

                if (nextCell) {
                    nextCell.focus();
                    nextCell.container.parentNode!.insertBefore(undoEl, nextCell.container);
                } else {
                    const prev = current.prevCell!();
                    if (prev) prev.focus();
                    current.container.parentNode!.insertBefore(undoEl, current.container);
                }
                this.cellUI.removeCell(current.id);
            }
        });

        this.cellUI.addEventListener('RunCell', (evt) => {
            this.runCells(evt.detail.cellId);
        });

        this.cellUI.addEventListener('RunCurrentCell', () => {
            this.runCells(Cell.currentFocus!.id);
        });

        this.cellUI.addEventListener('RunAll', () => {
            const cellIds = this.cellUI.getCodeCellIds();
            this.runCells(cellIds);
        });

        this.cellUI.addEventListener('RunToCursor', () => {
            const allCellIds = this.cellUI.getCodeCellIds();
            const activeCellIdx = allCellIds.indexOf(Cell.currentFocus!.id);
            if (activeCellIdx < 0) {
                console.log("Active cell is not part of current notebook?")
            } else {
                const cellIds = this.cellUI.getCodeCellIds();
                this.runCells(cellIds.slice(0, activeCellIdx + 1));
            }
        });

        this.cellUI.addEventListener('ContentChange', (evt) => {
            const update = new messages.UpdateCell(path, this.globalVersion, ++this.localVersion, evt.detail.cellId, evt.detail.edits, evt.detail.metadata);
            this.socket.send(update);
            this.editBuffer.push(this.localVersion, update);
        });

        this.cellUI.addEventListener('CompletionRequest', (evt) => {
            const id = evt.detail.cellId;
            const pos = evt.detail.pos;
            const resolve = evt.detail.resolve;
            const reject = evt.detail.reject;

            const receiveCompletions = (notebook: string, cell: number, receivedPos: number, completions: CompletionCandidate[]) => {
                if (notebook === path && cell === id && pos === receivedPos) {
                    this.socket.removeMessageListener([messages.CompletionsAt, receiveCompletions]);
                    const completionResults = completions.map(candidate => {
                        const isMethod = candidate.params.length > 0 || candidate.typeParams.length > 0;

                        const typeParams = candidate.typeParams.length ? `[${candidate.typeParams.join(', ')}]`
                            : '';

                        const params = isMethod ? candidate.params.map(pl => `(${pl.map(param => `${param.name}: ${param.type}`).join(', ')})`).join('')
                            : '';

                        const label = `${candidate.name}${typeParams}${params}`;

                        const insertText =
                            candidate.name + (typeParams.length ? '[$1]' : '') + (params.length ? '($2)' : '');

                        // Calculating Range (TODO: Maybe we should try to standardize our range / position / offset usage across the codebase, it's a pain to keep converting back and forth).
                        const model = (this.cellUI.getCell(cell) as CodeCell).editor.getModel()!;
                        const offsetAsPosition = model.getPositionAt(pos);
                        const range = Range.fromPositions(offsetAsPosition);

                        return {
                            kind: isMethod ? 1 : 9,
                            label: label,
                            insertText: insertText,
                            insertTextRules: 4,
                            detail: candidate.type,
                            range: range
                        };
                    });
                    //console.log(completionResults);
                    resolve({suggestions: completionResults});
                }
            };

            this.socket.addMessageListener(messages.CompletionsAt, receiveCompletions);
            this.socket.send(new messages.CompletionsAt(path, id, pos, []));
        });

        this.cellUI.addEventListener('ParamHintRequest', (evt) => {
            const id = evt.detail.cellId;
            const pos = evt.detail.pos;
            const resolve = evt.detail.resolve;
            const reject = evt.detail.reject;

            const receiveHints = (notebook: string, cell: number, receivedPos: number, signatures?: Signatures) => {
                if (notebook === path && cell === id && pos === receivedPos) {
                    this.socket.removeMessageListener([messages.ParametersAt, receiveHints]);
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

            this.socket.addMessageListener(messages.ParametersAt, receiveHints);
            this.socket.send(new messages.ParametersAt(path, id, pos))
        });

        this.cellUI.addEventListener("ReprDataRequest", evt => {
            const req = evt.detail;
            this.socket.listenOnceFor(messages.HandleData, (path, handleType, handleId, count, data) => {
                if (path === this.path && handleType === req.handleType && handleId === req.handleId) {
                    req.onComplete(data);
                    return false;
                } else return true;
            });
            this.socket.send(new messages.HandleData(path, req.handleType, req.handleId, req.count, []));
        });

        socket.addMessageListener(messages.NotebookCells, this.onCellsLoaded.bind(this));

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

        socket.addMessageListener(messages.NotebookUpdate, (update: messages.NotebookUpdate) => {
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
                            this.cellUI.insertCell(newCell, after)
                        })
                        .when(messages.DeleteCell, (p: string, g: number, l: number, id: number) => this.cellUI.removeCell(id))
                        .when(messages.UpdateConfig, (p: string, g: number, l: number, config: NotebookConfig) => this.cellUI.configUI.setConfig(config))
                        .when(messages.SetCellLanguage, (p: string, g: number, l: number, id, language: string) => this.cellUI.setCellLanguage(this.cellUI.getCell(id), language))
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
            if (socket.isClosed) {
                socket.reconnect(true);
            }
            // TODO: replace with `socket.request`
            socket.listenOnceFor(messages.NotebookVersion, (path, serverGlobalVersion) => {
                if (this.globalVersion !== serverGlobalVersion) {
                    // looks like there's been a change while we were disconnected, so reload.
                    document.location.reload();
                }
            });
            socket.send(new messages.NotebookVersion(path, this.globalVersion))
        };

        socket.addEventListener('close', evt => {
            this.cellUI.setDisabled(true);
            window.addEventListener('focus', reconnectOnWindowFocus);
        });

        socket.addEventListener('open', evt => {
            window.removeEventListener('focus', reconnectOnWindowFocus);
            this.socket.send(new messages.KernelStatus(path, new messages.KernelBusyState(false, false)));
            this.cellUI.setDisabled(false);
        });

        socket.addMessageListener(messages.CellResult, (path, id, result) => {
            if (path === this.path) {
                const cell = this.cellUI.getCell(id);
                this.handleResult(result, cell);
            }
        });
    }

    handleResult(result: Result, cell: Cell) {
        if (cell instanceof CodeCell) {
            if (result instanceof CompileErrors) {
                cell.setErrors(result.reports);
            } else if (result instanceof RuntimeError) {
                console.log(result.error);
                cell.setRuntimeError(result.error);
            } else if (result instanceof Output) {
                cell.addOutput(result.contentType, result.content);
            } else if (result instanceof ClearResults) {
                this.cellResults[cell.id] = {};
                cell.clearResult();
            } else if (result instanceof ExecutionInfo) {
                cell.setExecutionInfo(result);
            } else if (result instanceof ResultValue) {
                if (!this.cellResults[cell.id]) {
                    this.cellResults[cell.id] = {};
                }
                this.cellResults[cell.id][result.name] = result;
                cell.addResult(result);
            } else if (result instanceof ClientResult) {
                cell.addResult(result);
            }
        }

        if (result instanceof ResultValue) {
            this.kernelUI.symbols.addSymbol(result);
        }
    }

    getCellContext(ids: number[]) {
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
                        bestValue = new DataStream(this.path, streamingRepr as StreamingDataRepr, this.socket);
                    }
                }
                cellContext[key] = bestValue;
            }
        }
        return cellContext;
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
                            this.handleResult(result, cell);
                            if (result instanceof ClientResult) {
                                // notify the server of the MIME representation
                                result.toOutput().then(output => this.socket.send(new messages.SetCellOutput(this.path, this.globalVersion, this.localVersion++, id, output)));
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
        this.socket.send(new messages.RunCell(this.path, serverRunCells));
    }

    onCellLanguageSelected(setLanguage: string, path: string, id?: number) {
        if (path !== this.path) {
            return;
        }

        id = id || Cell.currentFocus!.id;
        if (id) {
            const cell = this.cellUI.getCell(id);
            if (cell.language !== setLanguage) {
                this.cellUI.setCellLanguage(cell, setLanguage);
                this.socket.send(new messages.SetCellLanguage(path, this.globalVersion, this.localVersion++, id, setLanguage));
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
            this.socket.removeMessageListener([messages.NotebookCells, this.onCellsLoaded]);
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

                this.cellUI.addCell(cell);
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
}