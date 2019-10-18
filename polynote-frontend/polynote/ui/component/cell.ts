"use strict";

import {blockquote, button, div, DropdownElement, iconButton, span, tag, TagElement} from "../util/tags";
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
// @ts-ignore (ignore use of non-public monaco api)
import {StandardKeyboardEvent} from 'monaco-editor/esm/vs/base/browser/keyboardEvent'
import {
    ClearResults,
    CompileErrors,
    KernelErrorWithCause,
    KernelReport,
    Output,
    PosRange,
    Result,
    ResultValue, RuntimeError
} from "../../data/result"
import {RichTextEditor} from "./text_editor";
import {SelectCell, UIMessageTarget} from "../util/ui_event"
import {Diff} from '../../util/diff'
import {details, dropdown} from "../util/tags";
import {ClientResult, ExecutionInfo} from "../../data/result";
import {preferences} from "../util/storage";
import {createVim} from "../util/vim";
import {KeyAction} from "../util/hotkeys";
import {clientInterpreters} from "../../interpreter/client_interpreter";
import {ValueInspector} from "./value_inspector";
import {Interpreters} from "./ui";
import {displayContent, parseContentType, prettyDuration} from "./display_content";
import {CellMetadata} from "../../data/data";
import {ContentEdit, Delete, Insert} from "../../data/content_edit";
import { editor, IDisposable, IKeyboardEvent, IPosition, KeyCode, languages } from "monaco-editor/esm/vs/editor/editor.api";
import CompletionList = languages.CompletionList;
import SignatureHelp = languages.SignatureHelp;
import IStandaloneCodeEditor = editor.IStandaloneCodeEditor;
import {FoldingController, SuggestController} from "../monaco/extensions";
import IModelContentChangedEvent = editor.IModelContentChangedEvent;
import IIdentifiedSingleEditOperation = editor.IIdentifiedSingleEditOperation;
import {CurrentNotebook} from "./current_notebook";
import SignatureHelpResult = languages.SignatureHelpResult;

export type CellContainer = TagElement<"div"> & {
    cell: Cell
}

export function isCellContainer(el: Element): el is CellContainer {
    return 'cell' in el;
}

export abstract class Cell extends UIMessageTarget {
    readonly container: CellContainer;
    readonly cellInput: TagElement<"div">;
    readonly cellInputTools: TagElement<"div">;
    readonly editorEl: TagElement<"div">;
    readonly statusLine: TagElement<"div">;
    readonly cellOutput: TagElement<"div">;
    readonly cellOutputDisplay: TagElement<"div">;
    readonly cellResultMargin: TagElement<"div">;
    readonly cellOutputTools: TagElement<"div">;
    readonly resultTabs: TagElement<"div">;
    protected keyMap: Map<KeyCode, KeyAction>;

    constructor(readonly id: number, public language: string, readonly path: string, public metadata?: CellMetadata) {
        super();
        if (!language) throw {message: `Attempted to create cell ${id} with empty language!`};

        const containerEl = div(['cell-container', language], [
            this.cellInput = div(['cell-input'], [
                this.cellInputTools = div(['cell-input-tools'], [
                    iconButton(['run-cell'], 'Run this cell (only)', '', 'Run').click((evt) => {
                        CurrentNotebook.get.runCells(this.id);
                    }),
                    //iconButton(['run-cell', 'refresh'], 'Run this cell and all dependent cells', '', 'Run and refresh')
                ]),
                this.editorEl = div(['cell-input-editor'], []),
                div(['cell-footer'], [
                    this.statusLine = div(["vim-status", "hide"], [])
                ])
            ]),
            this.cellOutput = div(['cell-output'], [
                div(['cell-output-margin'], []),
                // unfortunately we need this extra div for perf reasons (has to be a block)
                div(['cell-output-block'], [
                    div(['cell-output-container'], [
                        this.cellOutputDisplay = div(['cell-output-display'], []),
                    ])
                ]),
                // TODO: maybe a progress bar here?
                this.cellResultMargin = div(['cell-result-margin'], []),
                this.cellOutputTools = div(['cell-output-tools'], [
                    this.resultTabs = div(["result-tabs"], [])
                ]),
            ])
        ]).withId(`Cell${id}`);
        this.container = Object.assign(containerEl, { cell: this });

        // clicking anywhere in a cell should select it
        this.container.addEventListener('click', evt => this.makeActive());

        // TODO: some way to display the KeyMap to users
        // Map of Monaco KeyCode (an int) -> KeyAction
        this.keyMap = Cell.keyMap;
    }

    abstract setDisabled(disabled: boolean): void

    focus() {
        this.makeActive();
    }

    makeActive() {
        if (Cell.currentFocus && Cell.currentFocus !== this) {
            Cell.currentFocus.blur();
        }

        if (Cell.currentFocus !== this) {
            Cell.currentFocus = this;
            this.container.classList.add('active');

            if (!document.location.hash.includes(this.container.id)) {
                this.setUrl();
            }

            this.publish(new SelectCell(this));
        }
    }

    setUrl(maybeSelection?: monaco.Range) {
        const currentURL = new URL(document.location.toString());

        currentURL.hash = `${this.container.id}`;

        if (maybeSelection && !maybeSelection.isEmpty()) {
            if (maybeSelection.startLineNumber === maybeSelection.endLineNumber) {
                currentURL.hash += `,${maybeSelection.startLineNumber}`;
            } else {
                currentURL.hash += `,${maybeSelection.startLineNumber}-${maybeSelection.endLineNumber}`;
            }
        }

        window.history.replaceState(window.history.state, document.title, currentURL.href)
    }

    blur() {
        this.container.classList.remove('active');
        if (Cell.currentFocus === this) {
            Cell.currentFocus = null;
        }
    }

    dispose() {
        this.unsubscribeAll();
    }

    get content() {
        return "";
    }

    setLanguage(language: string) {
        this.container.classList.replace(this.language, language);
        this.language = language;
    }

    abstract applyEdits(edits: ContentEdit[]): void

    setMetadata(metadata: CellMetadata) {
        this.metadata = metadata;
    }

    // FIXME: this uses some private monaco APIs. If this ever ends up breaking after we update monaco it's a signal
    //        we'll need to rethink this stuff.
    onKeyDown(evt: IKeyboardEvent | KeyboardEvent) {
        let keybinding;
        if (evt instanceof StandardKeyboardEvent) {
            keybinding = (evt as StandardKeyboardEvent)._asKeybinding;
        } else {
            keybinding = new StandardKeyboardEvent(evt)._asKeybinding;
        }
        const action = this.keyMap.get(keybinding);
        if (action) {
            const runAction = () => {
                const pos = this.getPosition();
                const range = this.getRange();
                const selection = this.getCurrentSelection();
                action.fun(pos, range, selection, this);
                if (action.preventDefault) {
                    evt.stopPropagation();
                    evt.preventDefault();
                }
            };

            if (this instanceof CodeCell && action.ignoreWhenSuggesting) {
                // this is really ugly, is there a better way to tell whether the widget is visible??
                const suggestionsVisible = (this.editor.getContribution('editor.contrib.suggestController') as SuggestController)._widget._value.suggestWidgetVisible.get();
                if (!suggestionsVisible) { // don't do stuff when suggestions are visible
                    runAction()
                }
            } else {
                runAction()
            }
        }
    }

    getPosition(): IPosition {
        return {
            lineNumber: 0,
            column: 0
        }
    }

    getRange() {
        return {
            startLineNumber: 0,
            startColumn: 0,
            endLineNumber: 0,
            endColumn: 0
        }
    }

    abstract getCurrentSelection(): string

    // TODO: replace this global with something else - perhaps event driven?
    static currentFocus: Cell | null = null;

    static keyMap = new Map([
        [monaco.KeyCode.UpArrow, new KeyAction((pos, range, selection, cell) => {
            if (!selection && pos.lineNumber <= range.startLineNumber && pos.column <= range.startColumn) {
                CurrentNotebook.get.selectPrevCell(cell.id);
            }
        })],
        [monaco.KeyCode.DownArrow, new KeyAction((pos, range, selection, cell) => {
            if (!selection && pos.lineNumber >= range.endLineNumber && pos.column >= range.endColumn) {
                CurrentNotebook.get.selectNextCell(cell.id);
            }
        })],
        [monaco.KeyMod.Shift | monaco.KeyCode.Enter, new KeyAction((pos, range, selection, cell) => {
            CurrentNotebook.get.selectNextCell(cell.id);
        }).withPreventDefault(true).withDesc("Move to next cell. If there is no next cell, create it.")],
        [monaco.KeyMod.Shift | monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, new KeyAction((pos, range, selection, cell) => {
            CurrentNotebook.get.insertCell("below", cell.id);
        }).withPreventDefault(true).withDesc("Insert a cell after this one.")],
        [monaco.KeyMod.CtrlCmd | monaco.KeyCode.PageDown,
            new KeyAction((pos, range, selection, cell) => CurrentNotebook.get.selectNextCell(cell.id))
                .withDesc("Move to next cell. If there is no next cell, create it.")],
        [monaco.KeyMod.CtrlCmd | monaco.KeyCode.PageUp,
            new KeyAction((pos, range, selection, cell) => CurrentNotebook.get.selectPrevCell(cell.id))
                .withDesc("Move to previous cell.")],
        [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_A, // A for Above (from Zep)
            new KeyAction((pos, range, selection, cell) => CurrentNotebook.get.insertCell("above", cell.id))
                .withDesc("Insert cell above this cell.")],
        [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_B, // B for Below (from Zep)
            new KeyAction((pos, range, selection, cell) => CurrentNotebook.get.insertCell("below", cell.id))
                .withDesc("Insert a cell below this cell.")],
        [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_D, // D for Delete (from Zep)
            new KeyAction((pos, range, selection, cell) => CurrentNotebook.get.deleteCell(cell.id))
                .withDesc("Delete this cell.")],
    ]);


}

// TODO: it's a bit hacky to export this, should probably put this in some utils module
export function errorDisplay(error: KernelErrorWithCause, currentFile: string, maxDepth: number = 0, nested: boolean = false): {el: TagElement<"details"> | TagElement<"div">, messageStr: string, cellLine: number | null} {
    let cellLine: number | null = null;
    const traceItems: TagElement<"li">[] = [];
    const messageStr = `${error.message} (${error.className})`;

    let reachedIrrelevant = false;

    if (error.stackTrace && error.stackTrace.length) {
        error.stackTrace.forEach((traceEl, i) => {
            if (traceEl.file === currentFile && traceEl.line >= 0) {
                if (cellLine === null)
                    cellLine = traceEl.line;
                traceItems.push(tag('li', [], {}, [span(['error-link'], [`(Line ${traceEl.line})`])]))
            } else {
                if (traceEl.className === 'sun.reflect.NativeMethodAccessorImpl') { // TODO: seems hacky, maybe this logic fits better on the backend?
                    reachedIrrelevant = true;
                }
                const classes = reachedIrrelevant ? ['irrelevant'] : [];
                traceItems.push(tag('li', classes, {}, [`${traceEl.className}.${traceEl.method}(${traceEl.file}:${traceEl.line})`]))
            }
        });
    }

    const causeEl = (maxDepth > 0 && error.cause)
                    ? [errorDisplay(error.cause, currentFile, maxDepth - 1, true).el]
                    : [];
    const label = nested ? "Caused by: " : "Uncaught exception: ";
    const summaryContent = [span(['severity'], [label]), span(['message'], [messageStr])];
    const traceContent = [tag('ul', ['stack-trace'], {}, traceItems), ...causeEl];
    const el = traceItems.length
                ? details([], summaryContent, traceContent)
                : div([], summaryContent);

    return {el, messageStr, cellLine};
}

export type CodeCellModel = editor.ITextModel & { cellInstance: CodeCell };
export type MIMEElement = TagElement<"div", HTMLDivElement & { rel?: string, "mime-type"?: string}>;

export class CodeCell extends Cell {
    public editor: IStandaloneCodeEditor;

    readonly langSelector: DropdownElement;
    readonly execInfoEl: TagElement<"div">;
    readonly highlightLanguage: string;
    private lastLineTop: number;
    readonly lineHeight: number;
    readonly editListener: IDisposable;
    readonly onWindowResize: (evt: Event) => void;
    private applyingServerEdits: boolean;
    private stdOutEl: MIMEElement | null;
    private stdOutLines: number;
    private stdOutDetails: TagElement<"details"> | null;
    private highlightDecorations: string[];
    private execDurationUpdater: number;
    public vim: any | null;

    static keyMapOverrides = new Map([
        [monaco.KeyCode.DownArrow, new KeyAction((pos, range, selection, cell: CodeCell) => {
            if (cell.vim && !cell.vim.state.vim.insertMode) { // in normal/visual mode, the last column is never selected
                (range as any) // force mutability on endColumn (hacky)
                    .endColumn -= 1
            }
        })],
        // run cell on enter
        [monaco.KeyMod.Shift | monaco.KeyCode.Enter,
            new KeyAction((pos, range, selection, cell) => CurrentNotebook.get.runCells(cell.id))
                .withIgnoreWhenSuggesting(false)
                .withDesc("Run this cell and move to next cell. If there is no next cell, create it.")],
        [monaco.KeyMod.Shift | monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter,
            new KeyAction((pos, range, selection, cell) => CurrentNotebook.get.runCells(cell.id))
                .withIgnoreWhenSuggesting(false)
                .withDesc("Run this cell and insert a new cell below it.")]
    ]);

    constructor(id: number, initContent: string, language: string, path: string, metadata?: CellMetadata) {
        super(id, language, path, metadata);
        this.container.classList.add('code-cell');

        this.cellInputTools.appendChild(div(['cell-label'], [id + ""]));
        this.cellInputTools.appendChild(
            div(['lang-selector'], [
                this.langSelector = dropdown(['lang-selector'], Interpreters)
            ])
        );

        this.langSelector.setSelectedValue(language);
        this.langSelector.addEventListener('input', (evt) => {
            if (this.langSelector.getSelectedValue() !== this.language) {
                CurrentNotebook.get.onCellLanguageSelected(this.langSelector.getSelectedValue(), this.id);
            }
        });

        this.cellInputTools.appendChild(
            this.execInfoEl = div(["exec-info"], [])
        );

        this.cellInputTools.appendChild(
            div(['options'], [
                button(['toggle-code'], {title: 'Show/Hide Code'}, ['{}']).click(evt => this.toggleCode()),
                iconButton(['toggle-output'], 'Show/Hide Output', '', 'Show/Hide Output').click(evt => this.toggleOutput())
            ])
        );

        if (metadata) {
            if (metadata.hideOutput) {
                this.container.classList.add('hide-output');
            }

            if (metadata.hideSource) {
                this.container.classList.add('hide-code');
            }
        }

        const highlightLanguage = (clientInterpreters[language] && clientInterpreters[language].highlightLanguage) || language;
        this.highlightLanguage = highlightLanguage;


        // set up editor and content
        this.editor = monaco.editor.create(this.editorEl, {
            value: initContent,
            language: highlightLanguage,
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
            renderLineHighlight: "none"
        });

        this.editorEl.style.height = (this.editor.getScrollHeight()) + "px";
        this.editorEl.contentEditable = 'true'; // so right-click copy/paste can work.
        this.editorEl.setAttribute('spellcheck', 'false');  // so code won't be spellchecked
        this.editor.layout();

        this.editor.onDidFocusEditorWidget(() => {
            this.editor.updateOptions({ renderLineHighlight: "all" });
            this.makeActive();
        });

        this.editor.onDidBlurEditorWidget(() => {
            this.blur();
            this.editor.updateOptions({ renderLineHighlight: "none" });
        });

        this.editor.onDidChangeCursorSelection(evt => {
            // we only care if the user has selected more than a single character
            if ([0, 3].includes(evt.reason)) { // 0 -> NotSet, 3 -> Explicit
                this.setUrl(evt.selection);
            }

        });

        (this.editor.getContribution('editor.contrib.folding') as FoldingController).getFoldingModel()!.then(
            foldingModel => foldingModel!.onDidChange(() => this.updateEditorHeight())
        );

        this.lastLineTop = this.editor.getTopForLineNumber(this.editor.getModel()!.getLineCount());
        this.lineHeight = this.editor.getConfiguration().lineHeight;

        this.editListener = this.editor.onDidChangeModelContent(event => this.onChangeModelContent(event));

        (this.editor.getModel() as CodeCellModel).cellInstance = this;

        this.keyMap = CodeCell.keyMap;

        // actually bind keydown
        this.editor.onKeyDown((evt) => this.onKeyDown(evt));

        this.onWindowResize = (evt) => this.editor.layout();
        window.addEventListener('resize', this.onWindowResize);

        if (this.metadata && this.metadata.executionInfo) {
            this.setExecutionInfo(this.metadata.executionInfo);
        }
    }

    setDisabled(disabled: boolean) {
        const isDisabled = this.editor.getConfiguration().readOnly;
        if (disabled && !isDisabled) {
            this.editor.updateOptions({readOnly: true});
            [...this.cellInputTools.querySelectorAll('.run-cell')].forEach((button: HTMLButtonElement) => button.disabled = true);
        } else if (!disabled && isDisabled) {
            this.editor.updateOptions({readOnly: false});
            if (this.metadata && !this.metadata.disableRun) {
                [...this.cellInputTools.querySelectorAll('.run-cell')].forEach((button: HTMLButtonElement) => button.disabled = false);
            }
        }
    }

    setMetadata(metadata: CellMetadata) {
        const prevMetadata = this.metadata;
        super.setMetadata(metadata);
        if (metadata.hideSource) {
            this.container.classList.add('hide-code');
        } else if (prevMetadata && prevMetadata.hideSource) {
            this.container.classList.remove('hide-code');
            this.updateEditorHeight();
            this.editor.layout();
        }

        if (metadata.hideOutput) {
            this.container.classList.add('hide-output');
        } else {
            this.container.classList.remove('hide-output');
        }
    }

    toggleCode() {
        const prevMetadata = this.metadata || new CellMetadata();
        this.setMetadata(prevMetadata.copy({hideSource: !prevMetadata.hideSource}));
        CurrentNotebook.get.handleContentChange(this.id, [], this.metadata);
    }

    toggleOutput() {
        this.container.classList.toggle('hide-output');
        const prevMetadata = this.metadata || new CellMetadata();
        this.setMetadata(prevMetadata.copy({hideOutput: !prevMetadata.hideOutput}));
        CurrentNotebook.get.handleContentChange(this.id, [], this.metadata);
    }

    onChangeModelContent(event: IModelContentChangedEvent) {
        this.updateEditorHeight();
        if (this.applyingServerEdits)
            return;

        // clear the markers on edit
        // TODO: there might be non-error markers, or might otherwise want to be smarter about clearing markers
        monaco.editor.setModelMarkers(this.editor.getModel()!, this.id.toString(), []);
        const edits = event.changes.flatMap((contentChange) => {
            if (contentChange.rangeLength && contentChange.text.length) {
                return [new Delete(contentChange.rangeOffset, contentChange.rangeLength), new Insert(contentChange.rangeOffset, contentChange.text)];
            } else if (contentChange.rangeLength) {
              return [new Delete(contentChange.rangeOffset, contentChange.rangeLength)];
            } else if (contentChange.text.length) {
                return [new Insert(contentChange.rangeOffset, contentChange.text)];
            } else return [];
        });
        CurrentNotebook.get.handleContentChange(this.id, edits);
    }

    updateEditorHeight() {
        const lineCount = this.editor.getModel()!.getLineCount();
        const lastPos = this.editor.getTopForLineNumber(lineCount);
        if (lastPos !== this.lastLineTop) {
            this.lastLineTop = lastPos;
            this.editorEl.style.height = (lastPos + this.lineHeight) + "px";
            this.editor.layout();
        }
    }

    setLanguage(language: string) {
        super.setLanguage(language);
        this.langSelector.setSelectedValue(language);
    }


    setErrors(reports: KernelReport[]) {
        const model = this.editor.getModel()!;
        const reportInfos = reports.map((report) => {
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

        // clear the display if there was a compile error
        if (reportInfos.find(report => report.originalSeverity > 1)) {
            this.clearResult();
            this.container.classList.add('error');
        }


        monaco.editor.setModelMarkers(
            model,
            this.id.toString(),
            reportInfos
        );

        if (reports.length) {
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
    }

    setRuntimeError(error: KernelErrorWithCause) {
        const {el, messageStr, cellLine} = errorDisplay(error, this.container.id, 3);

        this.cellOutputDisplay.classList.add('errors');
        this.cellOutputDisplay.appendChild(
            div(['errors'], [
                blockquote(['error-report', 'Error'], [el])
            ])
        );

        this.container.classList.add('error');

        if (cellLine !== null && cellLine >= 0) {
            const model = this.editor.getModel()!;
            monaco.editor.setModelMarkers(
                model,
                this.id.toString(),
                [{
                    message: messageStr,
                    startLineNumber: cellLine,
                    endLineNumber: cellLine,
                    startColumn: model.getLineMinColumn(cellLine),
                    endColumn: model.getLineMaxColumn(cellLine),
                    severity: 8
                }]
            );
        }
    }

    mimeEl(mimeType: string, args: Record<string, string>, content: string): MIMEElement {
        const rel = args.rel || 'none';
        return (div(['output'], content) as MIMEElement).attr('rel', rel).attr('mime-type', mimeType);
    }

    buildOutput(mimeType: string, args: Record<string, string>, content: string | DocumentFragment) {
        return displayContent(mimeType, content, args).then(
            (result: TagElement<any>) => this.mimeEl(mimeType, args, result)
        ).catch(function(err: any) {
            return div(['output'], err);
        });
    }

    addOutput(contentType: string, content: string) {
        const [mimeType, args] = parseContentType(contentType);
        this.cellOutputDisplay.classList.add('output');
        if (!this.container.classList.contains('error')) {
            this.container.classList.add('success');
        }

        if (mimeType === 'text/plain' && args.rel === 'stdout') {
            // first, strip ANSI control codes
            // TODO: we probably want to parse & render the colors, but it would complicate things at the moment
            //       given that the folding logic relies on text nodes and that would require using elements
            content = content.replace(/\u001b\[\d+m/g, '');


            // if there are too many lines, fold some
            const lines = content.split(/\n/g);


            if (!this.stdOutEl || !this.stdOutEl.parentNode) {
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
                if (!this.stdOutDetails || !this.stdOutDetails.parentNode) {
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
                    while (script.childNodes.length) {
                        clone.appendChild(script.removeChild(script.childNodes[0]));
                    }
                    [...script.attributes].forEach(attr => clone.setAttribute(attr.name, attr.value));
                    script.parentNode!.replaceChild(clone, script);
                });
            })
        }
    }

    addResult(result: Result) {
        if (result instanceof CompileErrors) {
            this.setErrors(result.reports);
        } else if (result instanceof RuntimeError) {
            console.log(result.error);
            this.setRuntimeError(result.error);
        } else if (result instanceof Output) {
            this.addOutput(result.contentType, result.content);
        } else if (result instanceof ClearResults) {
            this.clearResult();
        } else if (result instanceof ExecutionInfo) {
            this.setExecutionInfo(result);
        } else if (result instanceof ResultValue) {
            this.displayResult(result);
        } else if (result instanceof ClientResult) {
            this.displayResult(result);
        }
    }

    displayResult(result: ResultValue | ClientResult) {
        if (result instanceof ResultValue) {
            // clear results
            this.resultTabs.innerHTML = '';

            if (result.name !== "Out" && result.reprs.length > 1) {
                // TODO: hover for result text?
                //       Note: tried a "content widget" to bring up the value inspector. It just kinda got in the way.
            } else if (result.reprs.length) {
                let inspectIcon: TagElement<"button">[] = [];
                if (result.reprs.length > 1) {
                    inspectIcon = [
                        iconButton(['inspect'], 'Inspect', '', 'Inspect').click(
                            evt => {
                                ValueInspector.get().setParent(this);
                                ValueInspector.get().inspect(result, this.path)
                            }
                        )
                    ]
                }

                const outLabel = div(['out-ident', 'with-reprs'], [...inspectIcon, 'Out:']);
                this.cellResultMargin.innerHTML = '';
                this.cellResultMargin.appendChild(outLabel);

                result.displayRepr(this, ValueInspector.get()).then(display => {
                    const [mime, content] = display;
                    const [mimeType, args] = parseContentType(mime);
                    this.buildOutput(mime, args, content).then((el: MIMEElement) => {
                        this.resultTabs.appendChild(el);
                        this.cellOutputTools.classList.add('output');
                    })
                });
            }
        } else {
            this.cellOutputTools.classList.add('output');
            this.resultTabs.innerHTML = '';
            result.display(this.resultTabs, this);
        }
    }

    setHighlight(pos?: {startPos: monaco.Position, endPos: monaco.Position} | PosRange | null, className?: string) {
        if (!className) {
            className = "currently-executing"
        }
        if (pos) {
            const oldExecutionPos = this.highlightDecorations || [];
            const model = this.editor.getModel()!;
            const startPos = pos instanceof PosRange ? model.getPositionAt(pos.start) : pos.startPos;
            const endPos = pos instanceof PosRange ? model.getPositionAt(pos.end) : pos.endPos;
            this.highlightDecorations = this.editor.deltaDecorations(oldExecutionPos, [
                {
                    range: monaco.Range.fromPositions(startPos, endPos),
                    options: { className: className }
                }
            ]);
        } else if (this.highlightDecorations) {
            this.editor.deltaDecorations(this.highlightDecorations, []);
            this.highlightDecorations = [];
        }
    }

    setExecutionInfo(result: ExecutionInfo) {
        const start = new Date(Number(result.startTs));
        const endTs = result.endTs || Date.now();
        const duration = Number(endTs) - Number(result.startTs);
        // clear display
        this.execInfoEl.innerHTML = '';
        window.clearInterval(this.execDurationUpdater);
        delete this.execDurationUpdater;

        // populate display
        this.execInfoEl.appendChild(span(['exec-start'], [start.toLocaleString("en-US", {timeZoneName: "short"})]));
        this.execInfoEl.appendChild(span(['exec-duration'], [prettyDuration(duration)]));
        this.execInfoEl.classList.add('output');
        if (result.endTs) {
            this.execInfoEl.classList.toggle("running", false);
        } else {
            this.execInfoEl.classList.toggle("running", true);
            // update exec info every so often
            if (!this.execDurationUpdater) {
                this.execDurationUpdater = window.setInterval(() => this.setExecutionInfo(result), 333)
            }
        }
    }

    setStatus(status: "running" | "queued" | "error" | "complete") {
        switch(status) {
            case "complete":
                this.container.classList.remove('running', 'queued', 'error');
                break;
            case "error":
                this.container.classList.remove('queued', 'running');
                this.container.classList.add('error');
                break;
            case "queued":
                this.container.classList.remove('running', 'error');
                this.container.classList.add('queued');
                break;
            case "running":
                this.container.classList.remove('queued', 'error');
                this.container.classList.add('running');
                break;
        }
    }

    isRunning() {
        return this.execInfoEl.classList.contains("running")
    }

    static colorize(content: string, lang: string) {
        return monaco.editor.colorize(content, lang, {}).then(function(result) {
            const node = div(['result'], []);
            node.innerHTML = result;
            return node
        });
    }

    static parseContent(content: string, mimeType: string, lang: string) {
        switch(mimeType) {
            case "text/plain":
                if (lang !== null) {
                    return this.colorize(content, lang)
                } else {
                    return Promise.resolve(document.createTextNode(content));
                }
            default:
                const node = div(['result'], []);
                node.innerHTML = content;
                return Promise.resolve(node);
        }
    }

    clearResult() {
        monaco.editor.setModelMarkers(this.editor.getModel()!, this.id.toString(), []);
        this.container.classList.remove('error', 'success');
        this.execInfoEl.classList.remove('output');
        this.cellOutputTools.innerHTML = '';
        this.cellOutputTools.appendChild(this.resultTabs);
        this.cellOutputTools.classList.remove('output');
        this.cellOutputDisplay.innerHTML = '';
        this.cellOutputDisplay.classList.remove('errors');
        this.cellOutput.classList.remove('output');
        this.stdOutDetails = null;
        this.stdOutEl = null;
    }

    requestCompletion(pos: number): Promise<CompletionList> {
        return new Promise(
            (resolve, reject) =>
                CurrentNotebook.get.completionRequest(this.id, pos, resolve, reject)
        ) //.catch();
    }

    requestSignatureHelp(pos: number): Promise<SignatureHelpResult> {
        return new Promise<SignatureHelp>((resolve, reject) =>
            CurrentNotebook.get.paramHintRequest(this.id, pos, resolve, reject)
        ).then(sigHelp => ({
            value: sigHelp,
            dispose(): void {}
        } as SignatureHelpResult)) //.catch();
    }

    makeActive() {
        super.makeActive();
        this.activateVim();
    }

    focus() {
        super.focus();
        this.editor.focus();
    }

    blur() {
        super.blur();
        this.hideVim();

        // clear highlights if not running
        if (!this.isRunning()) {
            this.setHighlight()
        }
    }

    dispose() {
        super.dispose();
        window.removeEventListener('resize', this.onWindowResize);
        this.editor.dispose();
        this.deactivateVim();
    }

    activateVim() {
        if (preferences.get(vimModeKey).value) {
            if (!this.vim) {
                this.vim = createVim(this.editor, this.statusLine);
                this.cellInput.querySelector(".cell-footer")!.appendChild(this.statusLine);
            }
            this.statusLine.classList.remove('hide');
            this.container.classList.add('vim-enabled');
        } else {
            this.deactivateVim();
        }
    }

    deactivateVim() {
        if (this.vim) {
            this.vim.dispose();
            delete this.vim;
        }
        this.hideVim();
    }

    hideVim() {
        this.statusLine.classList.add('hide');
    }

    get content() {
        return this.editor.getValue();
    }

    applyEdits(edits: ContentEdit[]) {
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

    getPosition() {
        return this.editor.getPosition()!;
    }

    getRange() {
        return this.editor.getModel()!.getFullModelRange();
    }

    getCurrentSelection() {
        return this.editor.getModel()!.getValueInRange(this.editor.getSelection()!)
    }
}

CodeCell.keyMap = new Map(Cell.keyMap);
(function () {

    const addNewAction = (key: number, newAction: KeyAction) => {
        const origAction = CodeCell.keyMap.get(key);
        if (origAction) {
            CodeCell.keyMap.set(key, origAction.runAfter(newAction));
        } else {
            CodeCell.keyMap.set(key, newAction);
        }
    };

    for (const [keycode, action] of CodeCell.keyMapOverrides) {
        addNewAction(keycode, action);
    }
})();

const vimModeKey = preferences.register("VIM", false, "Whether VIM input mode is enabled for CodeCells");

export class TextCell extends Cell {
    readonly editor: RichTextEditor;
    private lastContent: string;

    constructor(id: number, content: string, path: string, metadata?: CellMetadata) {
        super(id, 'text', path, metadata);
        this.container.classList.add('text-cell');
        this.editorEl.classList.add('markdown-body');
        this.container.cell = this;
        this.editor = new RichTextEditor(this.editorEl, content);
        this.lastContent = content;

        this.editor.element.addEventListener('focus', () => {
            this.makeActive();
        });

        this.editor.element.addEventListener('blur', () => {
            this.blur();
        });

        this.editor.element.addEventListener('keydown', (evt: KeyboardEvent) => this.onKeyDown(evt));

        this.editor.element.addEventListener('input', (evt: KeyboardEvent) => this.onInput());
    }

    // TODO: This can be improved to allow receiving & reconciling edits from the server, to allow multi-user editing.
    //       Same goes for the code cell.
    //       Otherwise, we should at least lock the cell so multiple users don't screw it up badly.
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
        const prevContent = this.lastContent;
        this.lastContent = newContent;

        if (edits.length > 0) {
            //console.log(edits);
            CurrentNotebook.get.handleContentChange(this.id, edits);
        }
    }

    setDisabled(disabled: boolean) {
        this.editor.disabled = disabled;
    }

    focus() {
        super.focus();
        this.editor.focus();
    }

    makeActive() {
        super.makeActive();
    }

    blur() {
        super.blur();
    }

    get content() {
        return this.editor.markdownContent;
    }

    applyEdits(edits: ContentEdit[]) {
        // TODO: implement applyEdits for TextCell once rich text editor is figured out
    }

    getContentNodes() {
        return Array.from(this.editorEl.childNodes)
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
        if (selectedNode === this.editorEl) { // this means we need to find the selected node using the offset
            selectedNode = this.editorEl.childNodes[selectionCol];
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

        const lastLine = (contentLines[contentLines.length - 1] && contentLines[contentLines.length - 1].textContent) || "";

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
}
