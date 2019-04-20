import {blockquote, div, iconButton, span, tag, button} from "./tags.js";
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import * as messages from "./messages.js";
import { ResultValue } from "./result.js"
import {RichTextEditor} from "./text_editor.js";
import {UIEvent, UIEventTarget} from "./ui_event.js"
import { default as Diff } from './diff.js'
import {ReprUI} from "./repr_ui";
import {details} from "./tags";
import {ExecutionInfo} from "./result";
import {prefs} from "./prefs";
import {createVim} from "./vim";

const JsDiff = new Diff();

export class CellEvent extends UIEvent {
    constructor(eventId, cellId, otherDetails) {
        const allDetails = otherDetails || {};
        allDetails.cellId = cellId;
        super(eventId, allDetails);
    }

    get cellId() { return this.detail.cellId }
}

export class SelectCellEvent extends CellEvent {
    constructor(cell) {
        super('SelectCell', cell.id, {cell});
    }
}

export class RunCellEvent extends CellEvent {
    constructor(cellId) {
        super('RunCell', cellId);
    }
}

export class BeforeCellRunEvent extends CellEvent {
    constructor(cellId) {
        super('BeforeCellRun', cellId);
    }
}

export class ContentChangeEvent extends CellEvent {
    constructor(cellId, edits, newContent) {
        super('ContentChange', cellId, {edits: edits, newContent: newContent});
    }

    get edits() { return this.detail.edits }
    get newContent() { return this.detail.newContent }
}

export class AdvanceCellEvent extends CellEvent {
    constructor(cellId, backward) {
        super('AdvanceCell', cellId, {backward: backward || false});
    }

    get backward() { return this.detail.backward; }
}

export class InsertCellEvent extends CellEvent {
    constructor(cellId) {
        super('InsertCellAfter', cellId);
    }
}

export class CompletionRequest extends CellEvent {
    constructor(cellId, pos, resolve, reject) {
        super('CompletionRequest', cellId, {id: cellId, pos: pos, resolve: resolve, reject: reject});
    }

    get pos() { return this.detail.pos }
    get resolve() { return this.detail.resolve }
    get reject() { return this.detail.reject }
}

export class ParamHintRequest extends CellEvent {
    constructor(cellId, pos, resolve, reject) {
        super('ParamHintRequest', cellId, {id: cellId, pos: pos, resolve: resolve, reject: reject});
    }

    get pos() { return this.detail.pos }
    get resolve() { return this.detail.resolve }
    get reject() { return this.detail.reject }
}

export class Cell extends UIEventTarget {
    constructor(id, content, language, path, metadata) {
        super(id, content, language);
        this.id = id;
        this.language = language;
        this.path = path;
        this.metadata = metadata;
        if (!language) throw {message: `Attempted to create cell ${id} with empty language!`};

        this.container = div(['cell-container', language], [
            this.cellInput = div(['cell-input'], [
                this.cellInputTools = div(['cell-input-tools'], [
                    iconButton(['run-cell'], 'Run this cell (only)', '', 'Run').click((evt) => {
                        this.dispatchEvent(new RunCellEvent(this.id));
                    }),
                    //iconButton(['run-cell', 'refresh'], 'Run this cell and all dependent cells', '', 'Run and refresh')
                ]),
                this.editorEl = div(['cell-input-editor'], []),
                div(['cell-footer'], [
                    this.statusLine = div(["vim-status", "hide"], []),
                    this.execInfoEl = div(["exec-info"], []),
                ])
            ]),
            this.cellOutput = div(['cell-output'], [
                div(['cell-output-margin'], []),
                div(['cell-output-container'], [
                    this.cellOutputDisplay = div(['cell-output-display'], []),
                ]),
                // TODO: maybe a progress bar here?
                this.cellResultMargin = div(['cell-result-margin']),
                this.cellOutputTools = div(['cell-output-tools'], [
                    this.resultTabs = div(["result-tabs"], [])
                ]),
            ])
        ]).withId(`Cell${id}`);

        this.container.cell = this;

        // clicking anywhere in a cell should select it
        this.container.addEventListener('click', evt => this.makeActive());
    }

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

            this.dispatchEvent(new SelectCellEvent(this));
        }
    }

    blur() {
        this.container.classList.remove('active');
        if (Cell.currentFocus === this) {
            Cell.currentFocus = null;
        }
    }

    dispose() {
        this.removeAllListeners();
    }

    get content() {
        return "";
    }

    setLanguage(language) {
        this.container.classList.replace(this.language, language);
        this.language = language;
    }

    applyEdits(edits) {
        throw "applyEdits not implemented for this cell type";
    }
}

// TODO: it's a bit hacky to export this, should probably put this in some utils module
export function errorDisplay(error, currentFile, maxDepth, nested) {
    maxDepth = maxDepth || 0;
    nested = nested || false;
    let cellLine = null;
    const traceItems = [];
    const messageStr = `${error.message} (${error.className})`;

    let reachedIrrelevant = false;

    if (error.stackTrace && error.stackTrace.length) {
        error.stackTrace.forEach((traceEl, i) => {
            if (traceEl.file === currentFile && traceEl.line >= 0) {
                if (cellLine === null)
                    cellLine = traceEl.line;
                traceItems.push(tag('li', [], {}, [span(['error-link'], [`(Line ${traceEl.line})`])]))
            } else {
                if (traceEl.className === 'sun.reflect.NativeMethodAccessorImpl') {
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

export class CodeCell extends Cell {
    constructor(id, content, language, path, metadata) {
        super(id, content, language, path, metadata);
        this.container.classList.add('code-cell');

        this.cellInputTools.appendChild(div(['cell-label'], [id + ""]), this.cellInputTools.childNodes[0]);

        // set up editor and content
        this.editor = monaco.editor.create(this.editorEl, {
            value: content,
            language: language,
            codeLens: false,
            dragAndDrop: true,
            minimap: { enabled: false },
            parameterHints: true,
            scrollBeyondLastLine: false,
            theme: 'polynote',
            fontFamily: 'Hasklig, Fira Code, Menlo, Monaco, fixed',
            fontSize: 15,
            fontLigatures: true,
            contextmenu: false,
            fixedOverflowWidgets: true,
            lineNumbers: true,
            lineNumbersMinChars: 1,
            lineDecorationsWidth: 0,
            renderLineHighlight: "none"
        });

        this.editorEl.style.height = (this.editor.getScrollHeight()) + "px";
        this.editor.layout();

        this.editor.onDidFocusEditorWidget(() => {
            this.editor.updateOptions({ renderLineHighlight: "all" });
            this.makeActive();
        });

        this.editor.onDidBlurEditorWidget(() => {
            this.blur();
            this.editor.updateOptions({ renderLineHighlight: "none" });
        });

        this.lastLineCount = this.editor.getModel().getLineCount();
        this.lineHeight = this.editor.getConfiguration().lineHeight;

        this.editListener = this.editor.onDidChangeModelContent(event => this.onChangeModelContent(event));

        this.editor.getModel().cellInstance = this;

        this.editor.addCommand(monaco.KeyMod.Shift | monaco.KeyCode.Enter, () => {
            this.dispatchEvent(new RunCellEvent(this.id));
            this.dispatchEvent(new AdvanceCellEvent(this.id));
        });

        this.editor.addCommand(monaco.KeyMod.Shift | monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => {
            this.dispatchEvent(new InsertCellEvent(this.id));
            this.dispatchEvent(new RunCellEvent(this.id));
        });

        this.editor.addCommand(
            monaco.KeyMod.CtrlCmd | monaco.KeyCode.PageDown,
            () => this.dispatchEvent(new AdvanceCellEvent(this.id, false)));

        this.editor.addCommand(
            monaco.KeyMod.CtrlCmd | monaco.KeyCode.PageUp,
            () => this.dispatchEvent(new AdvanceCellEvent(this.id, true)));

        this.onWindowResize = (evt) => this.editor.layout();
        window.addEventListener('resize', this.onWindowResize);

        if (this.metadata && this.metadata.executionInfo) {
            this.setExecutionInfo(this.metadata.executionInfo);
        }
    }

    onChangeModelContent(event) {
        this.updateEditorHeight();
        if (this.applyingServerEdits)
            return;

        // clear the markers on edit
        // TODO: there might be non-error markers, or might otherwise want to be smarter about clearing markers
        monaco.editor.setModelMarkers(this.editor.getModel(), this.id, []);
        const edits = event.changes.flatMap((contentChange) => {
            if (contentChange.rangeLength && contentChange.text.length) {
                return [new messages.Delete(contentChange.rangeOffset, contentChange.rangeLength), new messages.Insert(contentChange.rangeOffset, contentChange.text)];
            } else if (contentChange.rangeLength) {
              return [new messages.Delete(contentChange.rangeOffset, contentChange.rangeLength)];
            } else if (contentChange.text.length) {
                return [new messages.Insert(contentChange.rangeOffset, contentChange.text)];
            } else return [];
        });
        this.dispatchEvent(new ContentChangeEvent(this.id, edits, this.editor.getValue()));
    }

    updateEditorHeight() {
        const lineCount = this.editor.getModel().getLineCount();
        if (lineCount !== this.lastLineCount) {
            this.lastLineCount = lineCount;
            this.editorEl.style.height = (this.lineHeight * lineCount) + "px";
            this.editor.layout();
        }
    }


    setErrors(reports) {
        const model = this.editor.getModel();
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

        monaco.editor.setModelMarkers(
            model,
            this.id,
            reportInfos
        );

        // clear the display if there was a compile error
        if (reportInfos.find(report => report.originalSeverity > 1)) {
            this.clearResult();
            this.container.classList.add('error');
        }

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

    setRuntimeError(error) {
        const {el, messageStr, cellLine} = errorDisplay(error, this.container.id, 3);

        this.cellOutputDisplay.classList.add('errors');
        this.cellOutputDisplay.appendChild(
            div(['errors'], [
                blockquote(['error-report', 'Error'], [el])
            ])
        );

        this.container.classList.add('error');

        if (cellLine !== null && cellLine >= 0) {
            const model = this.editor.getModel();
            monaco.editor.setModelMarkers(
                model,
                this.id,
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

    parseContentType(contentType) {
        const contentTypeParts = contentType.split(';').map(str => str.replace(/(^\s+|\s+$)/g, ""));
        const mimeType = contentTypeParts.shift();
        const args = {};
        contentTypeParts.forEach(part => {
            const [k, v] = part.split('=');
            args[k] = v;
        });

        return [mimeType, args];
    }

    mimeEl(mimeType, args, content) {
        const rel = args.rel || 'none';
        return div(['output'], content).attr('rel', rel).attr('mime-type', mimeType);
    }

    buildOutput(mimeType, args, content) {
        const lang = args.lang || null;
        return CodeCell.parseContent(content, mimeType, lang).then(
            result => this.mimeEl(mimeType, args, result)
        ).catch(function(err) {
            return div(['output'], err);
        });
    }

    addOutput(contentType, content) {
        const [mimeType, args] = this.parseContentType(contentType);
        this.cellOutputDisplay.classList.add('output');
        if (!this.container.classList.contains('error')) {
            this.container.classList.add('success');
        }

        if (mimeType === 'text/plain' && args.rel === 'stdout') {

            // if there are too many lines, fold some
            const lines = content.split(/\r?\n/g);


            if (!this.stdOutEl || !this.stdOutEl.parentNode) {
                this.stdOutEl = this.mimeEl(mimeType, args, []);
                this.stdOutLines = lines.length;
                this.cellOutputDisplay.appendChild(this.stdOutEl);
            } else {
                this.stdOutLines += lines.length - 1;
            }

            if (this.stdOutLines > 12) { // TODO: user-configurable number?

                const splitAtLine = (textNode, line) => {
                    const lf = /\n/g;
                    const text = textNode.nodeValue;
                    let counted = 1;
                    let splitPos = 0;
                    while (counted < line) {
                        counted++;
                        const match = lf.exec(text);
                        if (match === null) {
                            return null;
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

                    // split the existing text node into first 5 lines and the rest
                    let textNode = this.stdOutEl.childNodes[0];
                    if (!textNode) {
                        textNode = document.createTextNode(content);
                        this.stdOutEl.appendChild(textNode);
                    }
                    const hidden = splitAtLine(textNode, 6);
                    const after = splitAtLine(hidden, numHiddenLines);

                    this.stdOutDetails.appendChild(hidden);
                    this.stdOutEl.insertBefore(this.stdOutDetails, after);
                } else {
                    const textNode = this.stdOutDetails.nextSibling;
                    const after = splitAtLine(textNode, lines.length);
                    after.nodeValue += content;
                    this.stdOutDetails.appendChild(textNode);
                    this.stdOutEl.appendChild(after);
                }
                // update summary
                this.stdOutDetails.querySelector('summary span').setAttribute('line-count', numHiddenLines.toString());
            } else {
                // no folding (yet) - append to the existing stdout container
                this.stdOutEl.appendChild(document.createTextNode(content));
            }

            // collapse the adjacent text nodes
            this.stdOutEl.normalize();

        } else {
            this.buildOutput(mimeType, args, content).then(el => {
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
                    script.parentNode.replaceChild(clone, script);
                });
            })
        }
    }

    addResult(result) {
        if (result instanceof ResultValue) {
            // clear results
            this.resultTabs.innerHTML = '';

            // TODO: keep "result" and "output" separate for UI... have a way to show declarations, results, outputs, etc. separately
            if (result.name !== "Out") {
                // don't display this; it's a named declaration
                // TODO: have a way to display these if desired
            } else if (result.reprs.length) {
                const outLabel = div(['out-ident', 'with-reprs'], `Out:`);
                this.cellResultMargin.innerHTML = '';
                this.cellResultMargin.appendChild(outLabel);

                const [mime, content] = result.displayRepr;
                const [mimeType, args] = this.parseContentType(mime);
                const self = this;
                this.buildOutput(mime, args, content).then(function(el) {
                    self.resultTabs.appendChild(el);
                    const reprUi = new ReprUI(`Cell${self.id}`, self.path, result.reprs, el);
                    reprUi.setEventParent(self);
                    reprUi.show();
                    self.cellOutputTools.classList.add('output');
                });

            }
        } else {
            throw "Result must be a ResultValue"
        }
    }

    setExecutionPos(pos) {
        if (pos) {
            const oldExecutionPos = this.executionDecorations || [];
            const model = this.editor.getModel();
            const startPos = model.getPositionAt(pos.start);
            const endPos = model.getPositionAt(pos.end);
            this.executionDecorations = this.editor.deltaDecorations(oldExecutionPos, [
                {
                    range: monaco.Range.fromPositions(startPos, endPos),
                    options: { className: "currently-executing" }
                }
            ]);
        } else if (this.executionDecorations) {
            this.editor.deltaDecorations(this.executionDecorations, []);
            this.executionDecorations = [];
        }
    }

    // move this somewhere else if it's useful outside Cell...
    static prettyDuration(milliseconds) {
        function quotRem(dividend, divisor) {
            const quotient = Math.floor(dividend / divisor);
            const remainder = dividend % divisor;
            return [quotient, remainder];
        }

        const [durationDays, leftOverHrs] = quotRem(milliseconds, 1000 * 60 * 60 * 24);
        const [durationHrs, leftOverMin] = quotRem(leftOverHrs, 1000 * 60 * 60);
        const [durationMin, leftOverSec] = quotRem(leftOverMin, 1000 * 60);
        const [durationSec, durationMs] = quotRem(leftOverSec, 1000);

        const duration = [];
        if (durationDays) {
            duration.push(`${durationDays}d`)
        }
        if (durationHrs) {
            duration.push(`${durationHrs}h`)
        }
        if (durationMin) {
            duration.push(`${durationMin}m`)
        }
        if (durationSec) {
            duration.push(`${durationSec}s`)
        }
        if (durationMs) {
            duration.push(`${durationMs}ms`)
        }

        return duration.join(":")
    }

    setExecutionInfo(result) {
        if (result instanceof ExecutionInfo) {
            const date = new Date(Number(result.timestamp));
            // clear display
            this.execInfoEl.innerHTML = '';

            // populate display
            this.execInfoEl.appendChild(span(['exec-timestamp'], [date.toLocaleString("en-US", {timeZoneName: "short"})]));
            this.execInfoEl.appendChild(span(['exec-duration'], [CodeCell.prettyDuration(result.durationMs)]));
            this.execInfoEl.classList.add('output');
        } else {
            throw "Result must be an ExecutionInfo"
        }
    }

    static colorize(content, lang) {
        return monaco.editor.colorize(content, lang, {}).then(function(result) {
            const node = div(['result'], []);
            node.innerHTML = result;
            return node
        });
    }

    static parseContent(content, mimeType, lang) {
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
        this.container.classList.remove('error', 'success');
        this.execInfoEl.classList.remove('output');
        this.cellOutputTools.innerHTML = '';
        this.cellOutputTools.classList.remove('output');
        this.cellOutputDisplay.innerHTML = '';
        this.cellOutputDisplay.classList.remove('errors');
        this.cellOutput.classList.remove('output');
        this.stdOutDetails = null;
        this.stdOutEl = null;
    }

    requestCompletion(pos) {
        return new Promise(
            (resolve, reject) => this.dispatchEvent(new CompletionRequest(this.id, pos, resolve, reject))
        ) //.catch();
    }

    requestSignatureHelp(pos) {
        return new Promise((resolve, reject) =>
            this.dispatchEvent(new ParamHintRequest(this.id, pos, resolve, reject))
        ) //.catch();
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
    }

    dispose() {
        super.dispose();
        window.removeEventListener('resize', this.onWindowResize);
        this.editor.dispose();
        this.deactivateVim();
    }

    activateVim() {
        if (prefs.get('VIM')) {
            if (!this.vim) {
                console.log("init vim for cell", this.id)
                if (!this.statusLine) {
                    this.statusLine = div(["vim-status"], []);
                }
                this.vim = createVim(this.editor, this.statusLine);
                this.cellInput.querySelector(".cell-footer").insertBefore(this.statusLine, this.execInfoEl);
            }
            this.statusLine.classList.toggle('hide', false);
        } else {
            this.deactivateVim();
        }
    }

    deactivateVim() {
        if (this.vim) {
            this.vim.dispose();
            this.statusLine.innerHTML = '';
            delete this.vim;
        }
        this.hideVim();
    }

    hideVim() {
        if (this.statusLine && !this.statusLine.contains(document.activeElement)) {
            this.statusLine.classList.toggle('hide', true);
        }
    }

    get content() {
        return this.editor.getValue();
    }

    applyEdits(edits) {
        // can't listen to these edits or they'll be sent to the server again
        // TODO: is there a better way to silently apply these edits? This seems like a hack; only works because of
        //       single-threaded JS, which I don't know whether workers impact that assumption (JS)
        this.applyingServerEdits = true;

        try {
            const monacoEdits = [];
            const model = this.editor.getModel();
            edits.forEach((edit) => {
                if (edit.isEmpty()) {
                    return;
                }

                const pos = model.getPositionAt(edit.pos);
                if (edit instanceof messages.Delete) {
                    const endPos = model.getPositionAt(edit.pos + edit.length);
                    monacoEdits.push({
                        range: new monaco.Range(pos.lineNumber, pos.column, endPos.lineNumber, endPos.column),
                        text: null
                    });
                } else if (edit instanceof messages.Insert) {
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
}

export class TextCell extends Cell {
    constructor(id, content, path, metadata) {
        super(id, content, 'text', path, metadata);
        this.container.classList.add('text-cell');
        this.editorEl.classList.add('markdown-body');
        this.editorEl.cell = this;
        this.editor = new RichTextEditor(this.editorEl, content);
        this.lastContent = content;

        this.editor.element.addEventListener('focus', () => {
            this.makeActive();
        });

        this.editor.element.addEventListener('blur', () => {
            this.blur();
        });

        this.editor.element.addEventListener('keydown', evt => this.onKeyDown(evt));

        this.editor.element.addEventListener('input', evt => this.onInput(evt));
    }

    // evt can be null, if being invoked from elsewhere
    // TODO: This can be improved to allow receiving & reconciling edits from the server, to allow multi-user editing.
    //       Same goes for the code cell.
    //       Otherwise, we should at least lock the cell so multiple users don't screw it up badly.
    onInput(evt) {
        const newContent = this.editor.markdownContent;
        const diff = JsDiff.diff(this.lastContent, newContent);
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
                    edits.push(new messages.Insert(pos, text));
                    pos += text.length;
                } else if (d.removed) {
                    edits.push(new messages.Delete(pos, text.length));
                }
                i++;
            }
        }
        const prevContent = this.lastContent;
        this.lastContent = newContent;

        if (edits.length > 0) {
            //console.log(edits);
            this.dispatchEvent(new ContentChangeEvent(this.id, edits, newContent));
        }
    }

    onKeyDown(evt) {
        if (evt.key === 'Enter' || evt.keyCode === 13) {
            if (evt.shiftKey) {
                evt.preventDefault();
                if (evt.ctrlKey) {
                    this.dispatchEvent(new InsertCellEvent(this.id));
                } else {
                    this.dispatchEvent(new AdvanceCellEvent(this.id));
                }
            }
        } else if (evt.metaKey) {
            if (evt.key === 'PageDown') {
                this.dispatchEvent(new AdvanceCellEvent(this.id, false));
            } else if (evt.key === 'PageUp') {
                this.dispatchEvent(new AdvanceCellEvent(this.id, true));
            }
        }
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

    applyEdits(edits) {
        // TODO: implement applyEdits for TextCell once rich text editor is figured out
        super.applyEdits(edits);
    }
}
