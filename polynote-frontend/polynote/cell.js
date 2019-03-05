import {blockquote, div, iconButton, span, tag, button} from "./tags.js";
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import * as messages from "./messages.js";
import { ResultValue } from "./result.js"
import {RichTextEditor} from "./text_editor.js";
import {UIEvent, UIEventTarget} from "./ui_event.js"
import { default as Diff } from './diff.js'
import {ReprUI} from "./repr_ui";

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
    constructor(cellId, lang) {
        super('SelectCell', cellId, {lang: lang});
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
    constructor(id, content, language) {
        super(id, content, language);
        this.id = id;
        this.language = language;

        this.container = div(['cell-container', language], [
            this.cellInput = div(['cell-input'], [
                div(['cell-input-tools'], [
                    iconButton(['run-cell'], 'Run this cell (only)', '', 'Run'),
                    //iconButton(['run-cell', 'refresh'], 'Run this cell and all dependent cells', '', 'Run and refresh')
                ]),
                this.editorEl = div(['cell-input-editor'], [])
            ]),
            this.cellOutput = div(['cell-output'], [
                this.cellOutputTools = div(['cell-output-tools'], []),
                this.cellOutputDisplay = div(['cell-output-display'], [])
            ])
        ]).withId(`Cell${id}`);

        this.container.cell = this;

        // TODO: this is incomplete (hook up all the run buttons etc)
        this.cellInput.querySelector('.run-cell').onclick = (evt) => {
            this.dispatchEvent(new RunCellEvent(this.id));
        }
    }

    static extractId(cellRef) {
        if (typeof cellRef === 'string') {
            return cellRef.match(/^Cell(\d+)$/)[1];
        } else if (typeof cellRef === 'number') {
            return cellRef;
        } else {
            throw { message: `Unable to parse cell reference ${cellRef}` };
        }
    }

    focus() {
        this.makeActive();
    }

    makeActive() {
        if (Cell.currentFocus && Cell.currentFocus !== this) {
            Cell.currentFocus.blur();
        }
        Cell.currentFocus = this;
        this.container.classList.add('active');

        this.dispatchEvent(new SelectCellEvent(this.id, this.language));
    }

    blur() {
        this.container.classList.remove('active');
        if (Cell.currentFocus === this) {
            Cell.currentFocus = null;
            this.dispatchEvent(new SelectCellEvent(this.id, this.language)); // technically this is deselection but we do the same thing anyways...
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

export class CodeCell extends Cell {
    constructor(id, content, language) {
        super(id, content, language);
        this.container.classList.add('code-cell');
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
        });

        this.editorEl.style.height = (this.editor.getScrollHeight()) + "px";
        this.editor.layout();

        this.editor.onDidFocusEditorWidget(() => {
            this.makeActive();
        });

        this.editor.onDidBlurEditorWidget(() => {
            this.blur();
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

        // clear the display
        this.cellOutputDisplay.innerHTML = '';
        if (reports.length) {
            this.container.classList.add('error');
            this.cellOutput.classList.add('errors');
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
        } else {
            this.cellOutput.classList.remove('errors');
            this.cellOutput.classList.remove('output');
        }
    }

    setRuntimeError(error) {
        let tracePos = 0;
        let cellLine = null;
        const traceItems = [];
        const messageStr = `${error.message} (${error.className})`;

        let reachedIrrelevant = false;

        if (error.stackTrace && error.stackTrace.length) {
            error.stackTrace.forEach((traceEl, i) => {
                if (traceEl.file === this.id && traceEl.line >= 0) {
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

        // TODO: should show nested causes, maybe collapsed by default

        // clear the display
        // actually, don't - for a runtime error we still might want to see any prior output //this.cellOutputDisplay.innerHTML = '';
        this.cellOutput.classList.add('errors');
        this.cellOutputDisplay.appendChild(
            div(['errors'], [
                blockquote(['error-report', 'Error'], [
                    span(['severity'], ['Uncaught exception']),
                    span(['message'], [messageStr]),
                    tag('ul', ['stack-trace'], {}, traceItems)
                ])
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

    addOutput(contentType, content) {
        this.cellOutput.classList.add('output');
        if (!this.container.classList.contains('error')) {
            this.container.classList.add('success');
        }
        const contentTypeParts = contentType.split(';').map(str => str.replace(/(^\s+|\s+$)/g, ""));
        const mimeType = contentTypeParts.shift();
        const args = {};
        contentTypeParts.forEach(part => {
            const [k, v] = part.split('=');
            args[k] = v;
        });

        const rel = args.rel || 'none';
        const lang = args.lang || null;
        const self = this;
        return CodeCell.parseContent(content, mimeType, lang).then(function(result) {
            const el = div(['output'], result).attr('rel', rel).attr('mime-type', mimeType);
            self.cellOutputDisplay.appendChild(el);
            return el;
        }).catch(function(err) {
            self.cellOutputDisplay.appendChild(
                div(['output'], err)
            );
        });
    }

    addResult(result) {
        if (result instanceof ResultValue) {
            // TODO: keep "result" and "output" separate for UI... have a way to show declarations, results, outputs, etc. separately
            if (result.name !== "Out") {
                // don't display this; it's a named declaration
                // TODO: have a way to display these if desired
            } else {
                const [mime, content] = result.displayRepr;
                const outLabel = result.reprs.length <= 1
                    ? div(['out-ident'], `Out:`)
                    : div(['out-ident', 'with-reprs'], `Out:`).click(evt => {
                        const reprUi = new ReprUI(result.name, result.reprs);
                        reprUi.setEventParent(this);
                        reprUi.show();
                    });

                this.addOutput(mime, content).then(el => el.insertBefore(outLabel, el.childNodes[0]));
            }
        } else {
            throw "Result must be a ResultValue"
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
        this.setErrors([]);
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
    }

    focus() {
        super.focus();
        this.editor.focus();
    }

    blur() {
        super.blur();
    }

    dispose() {
        super.dispose();
        window.removeEventListener('resize', this.onWindowResize);
        this.editor.dispose();
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
    constructor(id, content, language) {
        super(id, content, language);
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