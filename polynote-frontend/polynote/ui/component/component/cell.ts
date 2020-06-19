import {blockquote, button, details, div, dropdown, h4, iconButton, span, tag, TagElement} from "../../util/tags";
import {
    ClearCellEdits,
    CurrentSelection,
    NotebookMessageDispatcher,
    RequestCellRun,
    RequestCompletions,
    RequestSignature,
    SetCellLanguage,
    SetSelectedCell,
    UpdateCell, ShowValueInspector, DeselectCell
} from "../messaging/dispatcher";
import {StateHandler} from "../state/state_handler";
import {CellState, CompletionHint, SignatureHint} from "../state/notebook_state";
import * as monaco from "monaco-editor";
// @ts-ignore (ignore use of non-public monaco api)
import {StandardKeyboardEvent} from 'monaco-editor/esm/vs/base/browser/keyboardEvent.js'
import {
    editor,
    IKeyboardEvent,
    IPosition,
    IRange,
    languages,
    MarkerSeverity,
    Range,
    SelectionDirection
} from "monaco-editor";
import {
    ClearResults,
    ClientResult,
    CompileErrors,
    ExecutionInfo,
    Output,
    PosRange,
    Result,
    ResultValue,
    RuntimeError,
    ServerErrorWithCause
} from "../../../data/result";
import {ServerStateHandler} from "../state/server_state";
import {CellMetadata} from "../../../data/data";
import {clientInterpreters} from "../../../interpreter/client_interpreter";
import {FoldingController, SuggestController} from "../../monaco/extensions";
import {ContentEdit, Delete, Insert} from "../../../data/content_edit";
import {CodeCellModel, MIMEElement} from "../cell";
import {displayContent, displayData, displaySchema, parseContentType, prettyDuration} from "../display_content";
import match, {matchS} from "../../../util/match";
import IStandaloneCodeEditor = editor.IStandaloneCodeEditor;
import CompletionList = languages.CompletionList;
import SignatureHelp = languages.SignatureHelp;
import SignatureHelpResult = languages.SignatureHelpResult;
import EditorOption = editor.EditorOption;
import IModelContentChangedEvent = editor.IModelContentChangedEvent;
import IIdentifiedSingleEditOperation = editor.IIdentifiedSingleEditOperation;
import { CommentHandler } from "./comment";
import {RichTextEditor} from "./text_editor";
import {Diff} from "../../../util/diff";
import {DataRepr, MIMERepr, StreamingDataRepr} from "../../../data/value_repr";
import {DataReader} from "../../../data/codec";
import {StructType} from "../../../data/data_type";
import {FaviconHandler} from "../state/favicon_handler";
import {NotificationHandler} from "../state/notification_handler";
import {VimStatus} from "./vim_status";
import TrackedRangeStickiness = editor.TrackedRangeStickiness;

export class CellContainerComponent {
    readonly el: TagElement<"div">;
    private readonly cellId: string;
    private cell: CellComponent;

    constructor(private dispatcher: NotebookMessageDispatcher, private cellState: StateHandler<CellState>, private path: string) {
        this.cellId = `Cell${cellState.getState().id}`;
        this.cell = this.cellFor(cellState.getState().language);
        this.el = div(['cell-component'], [this.cell.el]);
        this.el.click(evt => this.cell.doSelect());
        cellState.view("language").addObserver((newLang, oldLang) => {
            // Need to create a whole new cell if the language switches between code and text
            if (oldLang === "text" || newLang === "text") {
                const newCell = this.cellFor(newLang);
                this.cell.el.replaceWith(newCell.el);
                this.cell = newCell;
            }
        });
    }

    private cellFor(lang: string) {
        return lang === "text" ? new TextCellComponent(this.dispatcher, this.cellState, this.path) : new CodeCellComponent(this.dispatcher, this.cellState, this.path);
    }

    delete() {
        this.cellState.clearObservers();
        this.cell.delete()
    }
}

export const cellHotkeys = {
    [monaco.KeyCode.UpArrow]: ["MoveUp", "Move to previous cell."],
    [monaco.KeyCode.DownArrow]: ["MoveDown", "Move to next cell. If there is no cell below, create it."],
    [monaco.KeyMod.Shift | monaco.KeyCode.Enter]: ["RunAndSelectNext", "Run cell and select the next cell. If there is no cell, create one."],
    [monaco.KeyMod.Shift | monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter]: ["RunAndInsertBelow", "Run cell and insert a new cell below it."],
    [monaco.KeyMod.CtrlCmd | monaco.KeyCode.PageUp]: ["SelectPrevious", "Move to previous."],
    [monaco.KeyMod.CtrlCmd | monaco.KeyCode.PageDown]: ["SelectNext", "Move to next cell. If there is no cell below, create it."],
    [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_A]: ["InsertAbove", "Insert a cell above this cell"],
    [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_B]: ["InsertBelow", "Insert a cell below this cell"],
    [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_D]: ["Delete", "Delete this cell"],
    [monaco.KeyMod.Shift | monaco.KeyCode.F10]: ["RunAll", "Run all cells."],
    [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Alt | monaco.KeyCode.F9]: ["RunToCursor", "Run to cursor."],
    // Special hotkeys to support VIM movement across cells. They are not displayed in the hotkey list
    [monaco.KeyCode.KEY_J]: ["MoveDownJ", ""],
    [monaco.KeyCode.KEY_K]: ["MoveUpK", ""],
};

abstract class CellComponent {
    protected id: number;
    protected cellId: string;
    public el: TagElement<"div">;

    protected constructor(protected dispatcher: NotebookMessageDispatcher, protected cellState: StateHandler<CellState>) {
        this.id = cellState.getState().id;
        this.cellId = `Cell${this.id}`;

        const updateSelected = (selected: boolean | undefined, prevSelected?: boolean) => {
            if (selected && ! prevSelected) {
                this.onSelected()
            } else if (! selected && prevSelected){
                this.onDeselected()
            }
        }
        updateSelected(this.state.selected)
        cellState.view("selected").addObserver((selected, prevSelected) => updateSelected(selected, prevSelected));
    }

    doSelect(){
        this.dispatcher.dispatch(new SetSelectedCell(this.id))
    }

    doDeselect(){
        if (document.body.contains(this.el)) { // prevent a blur call when a cell gets deleted.
            if (this.cellState.getState().selected) { // prevent blurring a different cell
                this.dispatcher.dispatch(new DeselectCell(this.id))
            }
        }
    }

    protected onSelected() {
        this.el?.classList.add("active");
        this.el.focus()
        this.scroll()
        if (!document.location.hash.includes(this.cellId)) {
            this.setUrl();
        }
    }

    protected onDeselected() {
        this.el?.classList.remove("active");
    }

    protected scroll() {
        const viewport = this.el.closest('.notebook-cells');
        if (viewport instanceof HTMLElement) {
            const viewportScrollTop = viewport.scrollTop;
            const viewportScrollBottom = viewportScrollTop + viewport.clientHeight;

            const elTop = this.el.offsetTop - viewport.offsetTop;
            const elBottom = elTop + this.el.offsetHeight;

            const needToScrollUp = elTop < viewportScrollTop;
            const needToScrollDown = elBottom > viewportScrollBottom;

            if (needToScrollUp && !needToScrollDown) {
                this.el.scrollIntoView({behavior: "auto", block: "start", inline: "nearest"})
                console.log("scrolled up")
            } else if(!needToScrollUp && needToScrollDown) {
                this.el.scrollIntoView({behavior: "auto", block: "end", inline: "nearest"})
                console.log("scrolled down")
            }
        }
    }

    protected calculateHash(maybeSelection?: monaco.Range): URL {
        const currentURL = new URL(document.location.toString());

        currentURL.hash = `${this.cellId}`;
        return currentURL
    }
    protected setUrl(maybeSelection?: monaco.Range) {
        const currentURL = this.calculateHash(maybeSelection)

        window.history.replaceState(window.history.state, document.title, currentURL.href)
    }

    protected get state() {
        return this.cellState.getState()
    }

    protected onKeyDown(evt: IKeyboardEvent | KeyboardEvent) {
        let keybinding;
        if (evt instanceof StandardKeyboardEvent) {
            keybinding = (evt as typeof StandardKeyboardEvent)._asKeybinding;
        } else {
            keybinding = new StandardKeyboardEvent(evt)._asKeybinding;
        }
        const hotkey = cellHotkeys[keybinding]
        if (hotkey && hotkey.length > 0) {
            const key = hotkey[0];
            const pos = this.getPosition();
            const range = this.getRange();
            const selection = this.getCurrentSelection();

            const preventDefault = this.keyAction(key, pos, range, selection)
            if (preventDefault) {
                evt.stopPropagation();
                evt.preventDefault();
            }
        }
    }
    protected abstract keyAction(key: string, pos: IPosition, range: IRange, selection: string): boolean | undefined

    protected abstract getPosition(): IPosition

    protected abstract getRange(): IRange

    protected abstract getCurrentSelection(): string

    delete() {}
}

class CodeCellComponent extends CellComponent {

    private readonly editor: IStandaloneCodeEditor;
    private readonly editorEl: TagElement<"div">;
    private applyingServerEdits: boolean;
    private execDurationUpdater: number;
    private highlightDecorations: string[] = [];
    private vim?: any;

    constructor(dispatcher: NotebookMessageDispatcher, cellState: StateHandler<CellState>, private path: string) {
        super(dispatcher, cellState);

        const langSelector = dropdown(['lang-selector'], ServerStateHandler.get.getState().interpreters);
        langSelector.setSelectedValue(this.state.language);
        langSelector.addEventListener("input", evt => {
            const selectedLang = langSelector.getSelectedValue();
            if (selectedLang !== this.state.language) {
                dispatcher.dispatch(new SetCellLanguage(this.id, selectedLang))
            }
        });

        const execInfoEl = div(["exec-info"], []);

        this.editorEl = div(['cell-input-editor'], [])

        const highlightLanguage = clientInterpreters[this.state.language]?.highlightLanguage ?? this.state.language;
        // set up editor and content
        this.editor = monaco.editor.create(this.editorEl, {
            value: this.state.content,
            language: highlightLanguage,
            automaticLayout: true, // this used to poll but it looks like it doesn't any more? https://github.com/microsoft/vscode/pull/90111/files
            codeLens: false,
            dragAndDrop: true,
            minimap: { enabled: false },
            parameterHints: {enabled: true},
            scrollBeyondLastLine: false,
            theme: 'polynote',
            fontFamily: 'Hasklig, Fira Code, Menlo, Monaco, fixed',
            fontSize: 15,
            fontLigatures: true,
            contextmenu: false,
            fixedOverflowWidgets: true,
            lineNumbers: 'on',
            lineNumbersMinChars: 1,
            lineDecorationsWidth: 0,
            renderLineHighlight: "none",
            scrollbar: {
                alwaysConsumeMouseWheel: false
            }
        });

        this.editorEl.style.height = (this.editor.getScrollHeight()) + "px";
        this.editorEl.contentEditable = 'true'; // so right-click copy/paste can work.
        this.editorEl.setAttribute('spellcheck', 'false');  // so code won't be spellchecked
        this.editor.layout();

        this.editor.onDidFocusEditorWidget(() => {
            this.editor.updateOptions({ renderLineHighlight: "all" });
        });
        this.editor.onDidBlurEditorWidget(() => {
            this.editor.updateOptions({ renderLineHighlight: "none" });
            this.doDeselect();
        });
        this.editor.onDidChangeCursorSelection(evt => {
            // deep link - we only care if the user has selected more than a single character
            if ([0, 3].includes(evt.reason)) { // 0 -> NotSet, 3 -> Explicit
                this.setUrl(evt.selection);
            }

            const model = this.editor.getModel();
            if (model) {
                const range = new PosRange(model.getOffsetAt(evt.selection.getStartPosition()), model.getOffsetAt(evt.selection.getEndPosition()));
                if (evt.selection.getDirection() === SelectionDirection.RTL) {
                    this.dispatcher.dispatch(new CurrentSelection(this.id, range.reversed));
                } else {
                    this.dispatcher.dispatch(new CurrentSelection(this.id, range));
                }
            }
        });
        (this.editor.getContribution('editor.contrib.folding') as FoldingController).getFoldingModel()!.then(
            foldingModel => foldingModel!.onDidChange(() => this.layout())
        );
        this.editor.onDidChangeModelContent(event => this.onChangeModelContent(event));

        // we need to do this hack in order for completions to work :(
        (this.editor.getModel() as CodeCellModel).requestCompletion = this.requestCompletion.bind(this);
        (this.editor.getModel() as CodeCellModel).requestSignatureHelp = this.requestSignatureHelp.bind(this);

        // NOTE: this uses some private monaco APIs. If this ever ends up breaking after we update monaco it's a signal
        //       we'll need to rethink this stuff.
        this.editor.onKeyDown((evt: IKeyboardEvent | KeyboardEvent) => this.onKeyDown(evt))

        const compileErrorsState = cellState.mapView<"compileErrors", CellErrorMarkers[][]>("compileErrors", (errors: CompileErrors[]) => {
            if (errors.length > 0) {
                return errors.map(error => {
                    const model = this.editor.getModel()!;
                    const reportInfos = error.reports.map((report) => {
                        const startPos = model.getPositionAt(report.position.start);
                        const endPos = model.getPositionAt(report.position.end);
                        const severity = report.severity * 4;
                        return {
                            message: report.message,
                            startLineNumber: startPos.lineNumber,
                            startColumn: startPos.column,
                            endLineNumber: endPos.lineNumber,
                            endColumn: endPos.column,
                            severity: severity,
                            originalSeverity: report.severity
                        };
                    });

                    monaco.editor.setModelMarkers(
                        model,
                        this.id.toString(),
                        reportInfos
                    );

                    return reportInfos
                })
            } else return []
        })
        const runtimeErrorState = cellState.mapView<"runtimeError", ServerErrorTrace | undefined>("runtimeError", (runtimeError?: RuntimeError) => {
            if (runtimeError) {
                const err = this.unrollServerError(runtimeError.error)
                const cellLine = err.errorLine;
                if (cellLine !== undefined && cellLine >= 0) {
                    const model = this.editor.getModel()!;
                    monaco.editor.setModelMarkers(
                        model,
                        this.id.toString(),
                        [{
                            message: err.summary.message,
                            startLineNumber: cellLine,
                            endLineNumber: cellLine,
                            startColumn: model.getLineMinColumn(cellLine),
                            endColumn: model.getLineMaxColumn(cellLine),
                            severity: 8
                        }]
                    );
                }
                return err
            } else {
                monaco.editor.setModelMarkers(this.editor.getModel()!, this.id.toString(), []);
                return undefined
            }
        })
        let cellOutput = new CodeCellOutput(dispatcher, cellState, compileErrorsState, runtimeErrorState);

        this.el = div(['cell-container', this.state.language, 'code-cell'], [
            div(['cell-input'], [
                div(['cell-input-tools'], [
                    iconButton(['run-cell'], 'Run this cell (only)', 'play', 'Run').click((evt) => {
                        dispatcher.dispatch(new RequestCellRun([this.state.id]))
                    }),
                    div(['cell-label'], [this.state.id.toString()]),
                    div(['lang-selector'], [langSelector]),
                    execInfoEl,
                    div(["options"], [
                        button(['toggle-code'], {title: 'Show/Hide Code'}, ['{}']).click(evt => this.toggleCode()),
                        iconButton(['toggle-output'], 'Show/Hide Output', 'align-justify', 'Show/Hide Output').click(evt => this.toggleOutput())
                    ])
                ]),
                this.editorEl
            ]),
            cellOutput.el
        ]);

        cellState.view("language").addObserver((newLang, oldLang) => {
            this.el.classList.replace(oldLang, newLang);
            langSelector.setSelectedValue(newLang);
        });

        const updateMetadata = (metadata: CellMetadata) => {
            if (metadata.hideSource) {
                this.el.classList.add("hide-code")
            } else {
                this.el.classList.remove("hide-code");
                this.layout();
            }

            if (metadata.hideOutput) {
                this.el.classList.add("hide-output");
            } else {
                this.el.classList.remove("hide-output");
            }

            if (metadata.executionInfo) {
                this.setExecutionInfo(execInfoEl, metadata.executionInfo)
            }
        }
        updateMetadata(this.state.metadata);
        cellState.view("metadata").addObserver(metadata => updateMetadata(metadata));

        cellState.view("pendingEdits").addObserver(edits => {
            if (edits.length > 0) {
                this.applyEdits(edits);
                dispatcher.dispatch(new ClearCellEdits(this.id));
            }
        });

        const updateError = (error: boolean | undefined) => {
            if (error) {
                this.el.classList.add("error");
            } else {
                this.el.classList.remove("error");
            }
        }
        updateError(this.state.error)
        cellState.view("error").addObserver(error => updateError(error));

        const updateRunning = (running: boolean | undefined, previously?: boolean) => {
            if (running) {
                this.el.classList.add("running");
            } else {
                this.el.classList.remove("running");
                if (previously) {
                    const status = this.state.error ? "Error" : "Complete"
                    NotificationHandler.get.notify(this.path, `Cell ${this.id} ${status}`).then(() => {
                        this.dispatcher.dispatch(new SetSelectedCell(this.id))
                    })
                }
            }
        }
        updateRunning(this.state.running)
        cellState.view("running").addObserver((curr, prev) => updateRunning(curr, prev));

        const updateQueued = (queued: boolean | undefined, previously?: boolean) => {
            if (queued) {
                this.el.classList.add("queued");
                if (!previously) {
                    FaviconHandler.inc()
                }
            } else {
                this.el.classList.remove("queued");
                if (previously) {
                    FaviconHandler.dec()
                }
            }
        }
        updateQueued(this.state.queued)
        cellState.view("queued").addObserver((curr, prev) => updateQueued(curr, prev));

        const updateHighlight = (h: { range: PosRange , className: string} | undefined) => {
            if (h) {
                const oldExecutionPos = this.highlightDecorations ?? [];
                const model = this.editor.getModel()!;
                const startPos = model.getPositionAt(h.range.start);
                const endPos = model.getPositionAt(h.range.end);
                this.highlightDecorations = this.editor.deltaDecorations(oldExecutionPos, [
                    {
                        range: monaco.Range.fromPositions(startPos, endPos),
                        options: { className: h.className }
                    }
                ]);
            } else if (this.highlightDecorations.length > 0) {
                this.editor.deltaDecorations(this.highlightDecorations, []);
                this.highlightDecorations = [];
            }
        }
        updateHighlight(this.state.currentHighlight)
        cellState.view("currentHighlight").addObserver(h => updateHighlight(h))

        const presenceMarkers: Record<number, string[]> = {};
        const updatePresence = (id: number, name: string, color: string, range: PosRange) => {
            const model = this.editor.getModel();
            if (model) {
                const old = presenceMarkers[id] ?? [];
                const startPos = model.getPositionAt(range.start);
                const endPos = model.getPositionAt(range.end);
                const newDecorations = [
                    {
                        range: monaco.Range.fromPositions(endPos, endPos),
                        options: {
                            className: `ppc ${color}`,
                            stickiness: TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
                            hoverMessage: { value: name }
                        }
                    }
                ];
                if (range.start != range.end) {
                    newDecorations.unshift({
                        range: monaco.Range.fromPositions(startPos, endPos),
                        options: {
                            className: `${color}`,
                            stickiness: TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
                            hoverMessage: { value: name }
                        }
                    });
                }
                presenceMarkers[id] = this.editor.deltaDecorations(old, newDecorations);
            }
        }
        cellState.getState().presence.forEach(p => updatePresence(p.id, p.name, p.color, p.range))
        cellState.view("presence").addObserver(presence => presence.forEach(p => updatePresence(p.id, p.name, p.color, p.range)))

        // make sure to create the comment handler.
        const commentHandler = new CommentHandler(dispatcher, cellState.view("comments"), cellState.view("currentSelection"), this.editor, this.id);
    }

    // TODO: comment markers need to be updated here.
    private onChangeModelContent(event: IModelContentChangedEvent) {
        this.layout();
        if (this.applyingServerEdits)
            return;

        // clear the markers on edit
        // TODO: there might be non-error markers, or might otherwise want to be smarter about clearing markers
        monaco.editor.setModelMarkers(this.editor.getModel()!, this.id.toString(), []);
        const edits = event.changes.flatMap((contentChange) => {
            if (contentChange.rangeLength > 0 && contentChange.text.length > 0) {
                return [new Delete(contentChange.rangeOffset, contentChange.rangeLength), new Insert(contentChange.rangeOffset, contentChange.text)];
            } else if (contentChange.rangeLength > 0) {
                return [new Delete(contentChange.rangeOffset, contentChange.rangeLength)];
            } else if (contentChange.text.length > 0) {
                return [new Insert(contentChange.rangeOffset, contentChange.text)];
            } else return [];
        });
        this.dispatcher.dispatch(new UpdateCell(this.id, edits, this.editor.getValue()));
    }

    requestCompletion(pos: number): Promise<CompletionList> {
        return new Promise<CompletionHint>(
            (resolve, reject) =>
                this.dispatcher.dispatch(new RequestCompletions(this.id, pos, resolve, reject))
        ).then(({cell, offset, completions}) => {
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
                const model = this.editor.getModel()!;
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
            return {suggestions: completionResults}
        })
    }

    requestSignatureHelp(pos: number): Promise<SignatureHelpResult> {
        return new Promise<SignatureHint>((resolve, reject) =>
            this.dispatcher.dispatch(new RequestSignature(this.id, pos, resolve, reject))
        ).then(({cell, offset, signatures}) => {
            let sigHelp: SignatureHelp;
            if (signatures) {
                sigHelp = {
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
                }
            } else {
                sigHelp = {activeSignature: 0, activeParameter: 0, signatures: []}
            }

            return {
                value: sigHelp,
                dispose(): void {}
            }
        })
    }

    private toggleCode() {
        const prevMetadata = this.state.metadata;
        const newMetadata = prevMetadata.copy({hideSource: !prevMetadata.hideSource})
        this.dispatcher.dispatch(new UpdateCell(this.id, [], undefined, newMetadata))
    }

    private toggleOutput() {
        const prevMetadata = this.state.metadata;
        const newMetadata = prevMetadata.copy({hideOutput: !prevMetadata.hideOutput})
        this.dispatcher.dispatch(new UpdateCell(this.id, [], undefined, newMetadata))
    }

    private layout() {
        const lineCount = this.editor.getModel()!.getLineCount();
        const lastPos = this.editor.getTopForLineNumber(lineCount);
        const lineHeight = this.editor.getOption(EditorOption.lineHeight);
        this.editorEl.style.height = (lastPos + lineHeight) + "px";
        this.editor.layout();
    }

    private setExecutionInfo(el: TagElement<"div">, executionInfo: ExecutionInfo) {
        const start = new Date(Number(executionInfo.startTs));
        const endTs = executionInfo.endTs ?? Date.now();
        const duration = Number(endTs) - Number(executionInfo.startTs);
        // clear display
        el.innerHTML = '';
        window.clearInterval(this.execDurationUpdater);
        delete this.execDurationUpdater;

        // populate display
        el.appendChild(span(['exec-start'], [start.toLocaleString("en-US", {timeZoneName: "short"})]));
        el.appendChild(span(['exec-duration'], [prettyDuration(duration)]));
        el.classList.add('output');
        if (executionInfo.endTs !== undefined) {
            el.classList.remove("running");
        } else {
            el.classList.add("running");
            // update exec info every so often
            if (this.execDurationUpdater !== undefined) {
                this.execDurationUpdater = window.setInterval(() => this.setExecutionInfo(el, executionInfo), 333)
            }
        }
    }

    private unrollServerError(error: ServerErrorWithCause, maxDepth: number = 0, nested: boolean = false): ServerErrorTrace {
        let errorLine: number | undefined = undefined;
        const items: ServerErrorTrace["trace"]["items"] = [];
        const message = `${error.message} (${error.className})`;

        let reachedIrrelevant = false;

        if (error.stackTrace?.length > 0) {
            error.stackTrace.forEach((traceEl, i) => {
                if (traceEl.file === this.cellId && traceEl.line >= 0) {
                    if (errorLine === undefined)
                        errorLine = traceEl.line ?? undefined;
                    items.push({content: `(Line ${traceEl.line})`, type: "link"});
                } else {
                    if (traceEl.className === 'sun.reflect.NativeMethodAccessorImpl') { // TODO: seems hacky, maybe this logic fits better on the backend?
                        reachedIrrelevant = true;
                    }
                    items.push({
                        content: `${traceEl.className}.${traceEl.method}(${traceEl.file}:${traceEl.line})`,
                        type: reachedIrrelevant ? 'irrelevant' : undefined})
                }
            });
        }

        const cause = (maxDepth > 0 && error.cause)
            ? this.unrollServerError(error.cause, maxDepth - 1, true)
            : undefined;
        const label = nested ? "Caused by: " : "Uncaught exception: ";
        const summary = {label, message};
        const trace = {items, cause};
        return {summary, trace, errorLine}
    }

    private applyEdits(edits: ContentEdit[]) {
        // can't listen to these edits or they'll be sent to the server again
        // TODO: is there a better way to silently apply these edits? This seems like a hack; only works because of
        //       single-threaded JS, which I don't know whether workers impact that assumption (JS)
        this.applyingServerEdits = true;

        try {
            const monacoEdits: IIdentifiedSingleEditOperation[] = [];
            const model = this.editor.getModel()!;
            edits.forEach((edit) => {
                if (edit.isEmpty()) {
                    return;
                }

                const pos = model.getPositionAt(edit.pos);
                if (edit instanceof Delete) {
                    const endPos = model.getPositionAt(edit.pos + edit.length);
                    monacoEdits.push({
                        range: new monaco.Range(pos.lineNumber, pos.column, endPos.lineNumber, endPos.column),
                        text: null
                    });
                } else if (edit instanceof Insert) {
                    monacoEdits.push({
                        range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column),
                        text: edit.content,
                        forceMoveMarkers: true
                    });
                }
            });

            //this.editor.getModel().applyEdits(monacoEdits);
            // TODO: above API call won't put the edits on the undo stack. Should other people's edits go on my undo stack?
            //       below calls implement that. It is weird to have other peoples' edits in your undo stack, but it
            //       also gets weird when they aren't – your undos get out of sync with the document.
            //       Maybe there's something different that can be done with undo stops – I don't really know what they
            //       are because it's not well documented (JS)
            this.editor.pushUndoStop();
            this.editor.executeEdits("Anonymous", monacoEdits);
            this.editor.pushUndoStop();
            // TODO: should show some UI thing to indicate whose edits they are, rather than just having them appear
        } finally {
            this.applyingServerEdits = false;
        }
        // this.editListener = this.editor.onDidChangeModelContent(event => this.onChangeModelContent(event));
    }

    protected keyAction(key: string, pos: IPosition, range: IRange, selection: string) {
        const ifNoSuggestion = (fun: () => void) => () => {
            // this is really ugly, is there a better way to tell whether the widget is visible??
            const suggestionsVisible = (this.editor.getContribution('editor.contrib.suggestController') as SuggestController).widget._value.suggestWidgetVisible.get();
            if (!suggestionsVisible) { // don't do stuff when suggestions are visible
                fun()
            }
        }
        return matchS<boolean>(key)
            .when("MoveUp", ifNoSuggestion(() => {
                if (!selection && pos.lineNumber <= range.startLineNumber && pos.column <= range.startColumn) {
                    this.dispatcher.dispatch(new SetSelectedCell(this.id, "above", true))
                }
            }))
            .when("MoveDown", ifNoSuggestion(() => {
                let lastColumn = range.endColumn;
                if (!this.vim?.state.vim.insertMode) { // in normal/visual mode, the last column is never selected.
                    lastColumn -= 1
                }
                if (!selection && pos.lineNumber >= range.endLineNumber && pos.column >= lastColumn) {
                    this.dispatcher.dispatch(new SetSelectedCell(this.id, "below", true))
                }
            }))
            .when("RunAndSelectNext", () => {
                this.dispatcher.runActiveCell()
                this.dispatcher.dispatch(new SetSelectedCell(this.id, "below"))
                return true // preventDefault
            })
            .when("RunAndInsertBelow", () => {
                this.dispatcher.runActiveCell()
                this.dispatcher.insertCell("below")
                return true // preventDefault
            })
            .when("SelectPrevious", ifNoSuggestion(() => {
                this.dispatcher.dispatch(new SetSelectedCell(this.id, "above"))
            }))
            .when("SelectNext", ifNoSuggestion(() => {
                this.dispatcher.dispatch(new SetSelectedCell(this.id, "below"))
            }))
            .when("InsertAbove", ifNoSuggestion(() => {
                this.dispatcher.insertCell("above")
            }))
            .when("InsertBelow", ifNoSuggestion(() => {
                this.dispatcher.insertCell("below")
            }))
            .when("Delete", ifNoSuggestion(() => {
                this.dispatcher.deleteCell()
            }))
            .when("RunAll", ifNoSuggestion(() => {
                this.dispatcher.dispatch(new RequestCellRun([]))
            }))
            .when("RunToCursor", ifNoSuggestion(() => {
                this.dispatcher.runToActiveCell()
            }))
            .when("MoveUpK", ifNoSuggestion(() => {
                if (!this.vim?.state.vim.insertMode) {
                    if (!selection && pos.lineNumber <= range.startLineNumber && pos.column <= range.startColumn) {
                        this.dispatcher.dispatch(new SetSelectedCell(this.id, "above", true))
                    }
                }
            }))
            .when("MoveDownJ", ifNoSuggestion(() => {
                if (!this.vim?.state.vim.insertMode) { // in normal/visual mode, the last column is never selected.
                    let lastColumn = range.endColumn - 1;
                    if (!selection && pos.lineNumber >= range.endLineNumber && pos.column >= lastColumn) {
                        this.dispatcher.dispatch(new SetSelectedCell(this.id, "below", true))
                    }
                }
            }))
            .otherwiseThrow ?? undefined
    }

    getPosition() {
        return this.editor.getPosition()!;
    }

    getRange() {
        return this.editor.getModel()!.getFullModelRange();
    }

    getCurrentSelection() {
        return this.editor.getModel()!.getValueInRange(this.editor.getSelection()!)
    }

    protected onSelected() {
        super.onSelected()
        this.vim = VimStatus.get.activate(this.editor)
        this.editor.focus()
    }

    delete() {
        super.delete()
        VimStatus.get.deactivate(this.editor.getId())
    }

    protected calculateHash(maybeSelection?: Range): URL {
        const currentURL = super.calculateHash(maybeSelection);

        const model = this.editor.getModel()
        if (model && maybeSelection && !maybeSelection.isEmpty()) {
            const pos = PosRange.fromRange(maybeSelection, model)
            currentURL.hash += `,${pos.toString}`
        }
        return currentURL
    }
}

type CellErrorMarkers = editor.IMarkerData & { originalSeverity: MarkerSeverity}
type ServerErrorTrace = {
    summary: {
        label: string,
        message: string
    },
    trace: {
        items: {content: string, type?: "link" | "irrelevant"}[]
        cause?: ServerErrorTrace
    },
    errorLine?: number
}

class CodeCellOutput {
    readonly el: TagElement<"div">;
    private stdOutEl: MIMEElement | null;
    private stdOutLines: number;
    private stdOutDetails: TagElement<"details"> | null;
    private readonly cellOutputDisplay: TagElement<"div">;
    private readonly cellResultMargin: TagElement<"div">;
    private readonly cellOutputTools: TagElement<"div">;
    private readonly resultTabs: TagElement<"div">;

    constructor(
        private dispatcher: NotebookMessageDispatcher,
        private cellState: StateHandler<CellState>,
        compileErrorsHandler: StateHandler<CellErrorMarkers[][] | undefined>,
        runtimeErrorHandler: StateHandler<ServerErrorTrace | undefined>) {

        const outputHandler = cellState.view("output");
        const resultsHandler = cellState.view("results");

        this.el = div(['cell-output'], [
            div(['cell-output-margin'], []),
            // unfortunately we need this extra div for perf reasons (has to be a block)
            div(['cell-output-block'], [
                div(['cell-output-container'], [
                    this.cellOutputDisplay = div(['cell-output-display'], []),
                ])
            ]),
            this.cellResultMargin = div(['cell-result-margin'], []),
            this.cellOutputTools = div(['cell-output-tools'], [
                this.resultTabs = div(["result-tabs"], []),
            ]),
        ]);

        const handleResults = (results: (ClientResult | ResultValue)[]) => {
            results.forEach(res => this.displayResult(res))
        }
        handleResults(resultsHandler.getState())
        resultsHandler.addObserver(results => handleResults(results));

        compileErrorsHandler.addObserver(errors => this.setErrors(errors));

        const handleRuntimeError = (error?: ServerErrorTrace) => {
            this.setRuntimeError(error)
        }
        handleRuntimeError(runtimeErrorHandler.getState())
        runtimeErrorHandler.addObserver(error => handleRuntimeError(error));

        const handleOutput = (output: Output[]) => {
            if (output.length > 0) {
                output.forEach(o => {
                    this.addOutput(o.contentType, o.content.join(''))
                })
            } else {
                this.clearOutput()
            }
        }
        handleOutput(outputHandler.getState())
        outputHandler.addObserver(output => handleOutput(output))
    }

    private displayResult(result: ResultValue | ClientResult) {
        if (result instanceof ResultValue) {
            // clear results
            this.resultTabs.innerHTML = '';

            if (result.name !== "Out" && result.reprs.length > 1) {
                // TODO: hover for result text?
                //       Note: tried a "content widget" to bring up the value inspector. It just kinda got in the way.
            } else if (result.reprs.length > 0) {
                let inspectIcon: TagElement<"button">[] = [];
                if (result.reprs.length > 1) {
                    inspectIcon = [
                        iconButton(['inspect'], 'Inspect', 'search', 'Inspect').click(
                            evt => {
                                this.dispatcher.dispatch(new ShowValueInspector(result))
                            }
                        )
                    ]
                }

                const outLabel = div(['out-ident', 'with-reprs'], [...inspectIcon, 'Out:']);
                this.cellResultMargin.innerHTML = '';
                this.cellResultMargin.appendChild(outLabel);


                this.displayRepr(result).then(display => {
                    const [mime, content] = display;
                    const [mimeType, args] = parseContentType(mime);
                    this.buildOutput(mime, args, content).then((el: MIMEElement) => {
                        this.resultTabs.appendChild(el);
                        this.cellOutputTools.classList.add('output');
                    })
                })
            }
        } else {
            this.cellOutputTools.classList.add('output');
            this.resultTabs.innerHTML = '';
            // TODO: add support for ClientResults!
            // result.display(this.resultTabs, this);
        }
    }

    // TODO: move this back to `ResultValue`
    private displayRepr(result: ResultValue): Promise<[string, string | DocumentFragment]> {
        // We're searching for the best MIME type and representation for this result by going in order of most to least
        // useful (kind of arbitrarily defined...)
        // TODO: make this smarter
        // TODO: for lazy data repr, inform that it can't be displayed immediately

        let index = -1;

        // First, check to see if there's a special DataRepr or StreamingDataRepr
        index = result.reprs.findIndex(repr => repr instanceof DataRepr);
        if (index >= 0) {
            return monaco.editor.colorize(result.typeName, "scala", {}).then(typeHTML => {
                const dataRepr = result.reprs[index] as DataRepr;
                const frag = document.createDocumentFragment();
                const resultType = span(['result-type'], []).attr("data-lang" as any, "scala");
                resultType.innerHTML = typeHTML;
                frag.appendChild(div([], [
                    h4(['result-name-and-type'], [span(['result-name'], [result.name]), ': ', resultType]),
                    displayData(dataRepr.dataType.decodeBuffer(new DataReader(dataRepr.data)), undefined, 1)
                ]));
                return ["text/html", frag];
            })
        }

        index = result.reprs.findIndex(repr => repr instanceof StreamingDataRepr);
        if (index >= 0) {
            const repr = result.reprs[index] as StreamingDataRepr;
            // surprisingly using monaco.editor.colorizeElement breaks the theme of the whole app! WAT?
            return monaco.editor.colorize(result.typeName, this.cellState.getState().language, {}).then(typeHTML => {
                const streamingRepr = result.reprs[index] as StreamingDataRepr;
                const frag = document.createDocumentFragment();
                const resultType = span(['result-type'], []).attr("data-lang" as any, "scala");
                resultType.innerHTML = typeHTML;
                // Why do they put a <br> in there?
                [...resultType.getElementsByTagName("br")].forEach(br => {
                    br?.parentNode?.removeChild(br)
                });

                const el = div([], [
                    h4(['result-name-and-type'], [
                        span(['result-name'], [result.name]), ': ', resultType,
                        iconButton(['view-data'], 'View data', 'table', '[View]')
                            .click(_ => this.dispatcher.dispatch(new ShowValueInspector(result, 'View data'))),
                        repr.dataType instanceof StructType
                            ? iconButton(['plot-data'], 'Plot data', 'chart-bar', '[Plot]')
                                .click(_ => {
                                    this.dispatcher.dispatch(new ShowValueInspector(result, 'Plot data'))
                                })
                            : undefined
                    ]),
                    repr.dataType instanceof StructType ? displaySchema(streamingRepr.dataType) : undefined
                ]);
                frag.appendChild(el);
                return ["text/html", frag];
            })
        }

        // next, if it's a MIMERepr we want to follow this order
        const mimeOrder = [
            "image/",
            "application/x-latex",
            "text/html",
            "text/",
        ];

        for (const partialMime of mimeOrder) {
            index = result.reprs.findIndex(repr => repr instanceof MIMERepr && repr.mimeType.startsWith(partialMime));
            if (index >= 0) return Promise.resolve(MIMERepr.unapply(result.reprs[index] as MIMERepr));
        }

        // ok, maybe there's some other mime type we didn't expect?
        index = result.reprs.findIndex(repr => repr instanceof MIMERepr);
        if (index >= 0) return Promise.resolve(MIMERepr.unapply(result.reprs[index] as MIMERepr));

        // just give up and show some plaintext...
        return Promise.resolve(["text/plain", result.valueText]);
    }

    private setErrors(errors?: CellErrorMarkers[][]) {
        if (errors && errors.length > 0 && errors[0].length > 0 ) {
            errors.forEach(reportInfos => {
                if (reportInfos.length > 0) {
                    this.cellOutputDisplay.classList.add('errors');
                    this.cellOutputDisplay.appendChild(
                        div(
                            ['errors'],
                            reportInfos.map((report) => {
                                const severity = (['Info', 'Warning', 'Error'])[report.originalSeverity];
                                return blockquote(['error-report', severity], [
                                    span(['severity'], [severity]),
                                    span(['message'], [report.message]),
                                    ' ',
                                    span(['error-link'], [`(Line ${report.startLineNumber})`])
                                ]);
                            })
                        )
                    );
                }
            })
        } else { // clear errors
            this.clearErrors()
        }
    }

    setRuntimeError(error?: ServerErrorTrace) {
        if (error) {
            function rollupError(err: ServerErrorTrace): TagElement<"div" | "details"> {
                const traceItems = err.trace.items.map(item => {
                    if (item.type === "link") {
                        return tag('li', [], {}, [span(['error-link'], [item.content])])
                    } else if (item.type === "irrelevant") {
                        return tag('li', ['irrelevant'], {}, [item.content])
                    } else {
                        return tag('li', [], {}, [item.content])
                    }
                });

                const causeEl = err.trace.cause && rollupError(err.trace.cause);
                const summaryContent = [span(['severity'], [err.summary.label]), span(['message'], [err.summary.message])];
                if (traceItems.length > 0) {
                    const traceContent = [tag('ul', ['stack-trace'], {}, traceItems), causeEl];
                    return details([], summaryContent, traceContent)
                } else {
                    return div([], summaryContent)
                }
            }

            const el = rollupError(error);

            this.cellOutputDisplay.classList.add('errors');
            this.cellOutputDisplay.appendChild(
                div(['errors'], [
                    blockquote(['error-report', 'Error'], [el])
                ])
            );
        } else {
            this.clearErrors()
        }
    }

    private clearOutput() {
        this.cellOutputDisplay.innerHTML = "";
    }

    private clearErrors() {
        this.cellOutputDisplay.classList.remove('errors');
        this.clearOutput() // TODO: should we just clear errors?
    }

    private mimeEl(mimeType: string, args: Record<string, string>, content: string): MIMEElement {
        const rel = args.rel || 'none';
        return (div(['output'], content) as MIMEElement).attr('rel', rel).attr('mime-type', mimeType);
    }

    private buildOutput(mimeType: string, args: Record<string, string>, content: string | DocumentFragment) {
        return displayContent(mimeType, content, args).then(
            (result: TagElement<any>) => this.mimeEl(mimeType, args, result)
        ).catch(function(err: any) {
            return div(['output'], err);
        });
    }

    private addOutput(contentType: string, content: string) {
        const [mimeType, args] = parseContentType(contentType);
        this.cellOutputDisplay.classList.add('output');

        if (mimeType === 'text/plain' && args.rel === 'stdout') {
            // first, strip ANSI control codes
            // TODO: we probably want to parse & render the colors, but it would complicate things at the moment
            //       given that the folding logic relies on text nodes and that would require using elements
            content = content.replace(/\u001b\[\d+m/g, '');


            // if there are too many lines, fold some
            const lines = content.split(/\n/g);


            if (! this.stdOutEl?.parentNode) {
                this.stdOutEl = this.mimeEl(mimeType, args, "");
                this.stdOutLines = lines.length;
                this.cellOutputDisplay.appendChild(this.stdOutEl);
            } else {
                this.stdOutLines += lines.length - 1;
            }

            if (this.stdOutLines > 12) { // TODO: user-configurable number?

                const splitAtLine = (textNode: Text, line: number) => {
                    const lf = /\n/g;
                    const text = textNode.nodeValue || "";
                    let counted = 1;
                    let splitPos = 0;
                    while (counted < line) {
                        counted++;
                        const match = lf.exec(text);
                        if (match === null) {
                            return null; // TODO: What do we do if this happens?
                        }
                        splitPos = match.index + 1;
                    }
                    return textNode.splitText(splitPos);
                };

                // fold all but the first 5 and last 5 lines into an expandable thingy
                const numHiddenLines = this.stdOutLines - 11;
                if (! this.stdOutDetails?.parentNode) {
                    this.stdOutDetails = tag('details', [], {}, [
                        tag('summary', [], {}, [span([], '')])
                    ]);

                    // collapse into single node
                    this.stdOutEl.normalize();
                    // split the existing text node into first 5 lines and the rest
                    let textNode = this.stdOutEl.childNodes[0] as Text;
                    if (!textNode) {
                        textNode = document.createTextNode(content);
                        this.stdOutEl.appendChild(textNode);
                    } else {
                        // add the current content to the text node before folding
                        textNode.nodeValue += content;
                    }
                    const hidden = splitAtLine(textNode, 6);
                    if (hidden) {
                        const after = splitAtLine(hidden, numHiddenLines);

                        this.stdOutDetails.appendChild(hidden);
                        this.stdOutEl.insertBefore(this.stdOutDetails, after);
                    }
                } else {
                    const textNode = this.stdOutDetails.nextSibling! as Text;
                    textNode.nodeValue += content;
                    const after = splitAtLine(textNode, lines.length);
                    if (after) {
                        this.stdOutDetails.appendChild(textNode);
                        this.stdOutEl.appendChild(after);
                    }
                }
                // update summary
                this.stdOutDetails.querySelector('summary span')!.setAttribute('line-count', numHiddenLines.toString());
            } else {
                // no folding (yet) - append to the existing stdout container
                this.stdOutEl.appendChild(document.createTextNode(content));
            }

            // collapse the adjacent text nodes
            this.stdOutEl.normalize();

            // handle carriage returns in the last text node – they erase back to the start of the line
            const lastTextNode = [...this.stdOutEl.childNodes].filter(node => node.nodeType === 3).pop();
            if (lastTextNode) {
                const eat = /^(?:.|\r)+\r(.*)$/gm; // remove everything before the last CR in each line
                lastTextNode.nodeValue = lastTextNode.nodeValue!.replace(eat, '$1');
            }

        } else {
            this.buildOutput(mimeType, args, content).then((el: MIMEElement) => {
                this.cellOutputDisplay.appendChild(el);

                // <script> tags won't be executed when they come in through `innerHTML`. So take them out, clone them, and
                // insert them as DOM nodes instead
                const scripts = el.querySelectorAll('script');
                scripts.forEach(script => {
                    const clone = document.createElement('script');
                    while (script.childNodes.length > 0) {
                        clone.appendChild(script.removeChild(script.childNodes[0]));
                    }
                    [...script.attributes].forEach(attr => clone.setAttribute(attr.name, attr.value));
                    script.replaceWith(clone);
                });
            })
        }
    }
}


export class TextCellComponent extends CellComponent {
    private editor: RichTextEditor;
    private lastContent: string;

    constructor(dispatcher: NotebookMessageDispatcher, stateHandler: StateHandler<CellState>, private path: string) {
        super(dispatcher, stateHandler)

        const editorEl = div(['cell-input-editor', 'markdown-body'], [])

        const content = stateHandler.getState().content;
        this.editor = new RichTextEditor(this, editorEl, content)
        this.lastContent = content;

        this.el = div(['cell-container', 'text-cell'], [
            div(['cell-input'], [editorEl])
        ])


        this.editor.element.addEventListener('focus', () => {
            this.doSelect();
        });

        this.editor.element.addEventListener('blur', () => {
            this.doDeselect();
        });

        this.editor.element.addEventListener('keydown', (evt: KeyboardEvent) => this.onKeyDown(evt));

        this.editor.element.addEventListener('input', (evt: KeyboardEvent) => this.onInput());
    }

    // not private because it is also used by latex-editor
    onInput() {
        const newContent = this.editor.markdownContent;
        const diff = Diff.diff(this.lastContent, newContent);
        const edits = [];
        let i = 0;
        let pos = 0;
        while (i < diff.length) {
            // skip through any untouched pieces
            while (i < diff.length && !diff[i].added && !diff[i].removed) {
                pos += diff[i].value.length;
                i++;
            }

            if (i < diff.length) {
                const d = diff[i];
                const text = d.value;
                if (d.added) {
                    edits.push(new Insert(pos, text));
                    pos += text.length;
                } else if (d.removed) {
                    edits.push(new Delete(pos, text.length));
                }
                i++;
            }
        }
        this.lastContent = newContent;

        if (edits.length > 0) {
            //console.log(edits);
            this.dispatcher.dispatch(new UpdateCell(this.id, edits, this.editor.markdownContent))
        }
    }

    getContentNodes() {
        return Array.from(this.editor.element.childNodes)
            // there are a bunch of text nodes with newlines we don't care about.
            .filter(node => !(node.nodeType === Node.TEXT_NODE && node.textContent === '\n'))
    }

    // Note: lines in contenteditable are inherently weird, don't rely on this for anything aside from beginning and end
    getPosition() {
        // get selection
        const selection = document.getSelection()!;
        // ok, now we just care about the current cursor location
        let selectedNode = selection.focusNode;
        let selectionCol = selection.focusOffset;
        if (selectedNode === this.editor.element) { // this means we need to find the selected node using the offset
            selectedNode = this.editor.element.childNodes[selectionCol];
            selectionCol = 0;
        }
        // ok, now which line number?
        let selectionLineNum = -1;
        const contentNodes = this.getContentNodes();
        contentNodes.forEach((node, idx) => {
            if (node === selectedNode || node.contains(selectedNode)) {
                selectionLineNum = idx;
            }
        });
        return {
            lineNumber: selectionLineNum + 1, // lines start at 1 like Monaco
            column: selectionCol
        };
    }

    getRange() {
        const contentLines = this.getContentNodes();

        const lastLine = contentLines[contentLines.length - 1]?.textContent || "";

        return {
            startLineNumber: 1, // start at 1 like Monaco
            startColumn: 0,
            endLineNumber: contentLines.length,
            endColumn: lastLine.length
        };

    }

    getCurrentSelection() {
        return document.getSelection()!.toString()
    }

    protected keyAction(key: string, pos: IPosition, range: IRange, selection: string) {
        return matchS<boolean>(key)
            .when("MoveUp", () => {
                if (!selection && pos.lineNumber <= range.startLineNumber && pos.column <= range.startColumn) {
                    this.dispatcher.dispatch(new SetSelectedCell(this.id, "above"))
                }
            })
            .when("MoveDown", () => {
                if (!selection && pos.lineNumber >= range.endLineNumber && pos.column >= range.endColumn) {
                    this.dispatcher.dispatch(new SetSelectedCell(this.id, "below"))
                }
            })
            .when("RunAndSelectNext", () => {
                this.dispatcher.dispatch(new SetSelectedCell(this.id, "below"))
                return true // preventDefault
            })
            .when("RunAndInsertBelow", () => {
                this.dispatcher.insertCell("below")
                return true // preventDefault
            })
            .when("SelectPrevious", () => {
                this.dispatcher.dispatch(new SetSelectedCell(this.id, "above"))
            })
            .when("SelectNext", () => {
                this.dispatcher.dispatch(new SetSelectedCell(this.id, "below"))
            })
            .when("InsertAbove", () => {
                this.dispatcher.insertCell("above")
            })
            .when("InsertBelow", () => {
                this.dispatcher.insertCell("below")
            })
            .when("Delete", () => {
                this.dispatcher.deleteCell()
            })
            .when("RunAll", () => {
                this.dispatcher.dispatch(new RequestCellRun([]))
            })
            .when("RunToCursor", () => {
                this.dispatcher.runToActiveCell()
            })
            .otherwise(null) ?? undefined
    }

    protected onSelected() {
        super.onSelected()
        this.editor.focus()
    }
}
