import {blockquote, div, iconButton, span, tag} from "./tags.js";
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import * as messages from "./messages.js";
import {RichTextEditor} from "./text_editor.js";
import {MainToolbar, mainToolbar} from "./ui.js";

export class Cell extends EventTarget {
    constructor(id, content, language) {
        super(id, content, language);
        this.listeners = [];
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
                div(['cell-output-tools'], []),
                this.cellOutputDisplay = div(['cell-output-display'], [])
            ])
        ]).withId(id);

        this.container.cell = this;

        // TODO: this is incomplete (hook up all the run buttons etc)
        this.cellInput.querySelector('.run-cell').onclick = (evt) => {
            this.dispatchEvent(new CustomEvent('RunCell', { detail: { cellId: this.id }}));
        }

    }

    // add an event listener that gets tracked and removed on dispose()
    listenTo(target, type, listener, option) {
        this.listeners.push([target, type, listener, option]);
        target.addEventListener(type, listener, option);
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

        // TODO: refactor this into an event instead
        MainToolbar.setCurrentCellType(this.language);
    }

    blur() {
        this.container.classList.remove('active');
        if (Cell.currentFocus === this) {
            Cell.currentFocus = null;
            mainToolbar.dispatchEvent(new CustomEvent('ContextChanged'));
        }
    }

    dispose() {
        for (const listener of this.listeners) {
            listener[0].removeEventListener(listener[1], listener[2], listener[3]);
        }
    }

    get content() {
        return "";
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

        // TODO: this should probably only send the changes...
        this.editor.onDidChangeModelContent(event => {

            const lineCount = this.editor.getModel().getLineCount();
            if (lineCount !== this.lastLineCount) {
                this.lastLineCount = lineCount;
                this.editorEl.style.height = (this.lineHeight * lineCount) + "px";
                this.editor.layout();
            }
            this.dispatchEvent(new CustomEvent(
                'ContentChange', {
                    detail: {
                        cellId: this.id,
                        edits: event.changes.map(contentChange => new messages.ContentEdit(contentChange.rangeOffset, contentChange.rangeLength, contentChange.text)),
                        newContent: this.editor.getValue()
                    }
                }));
        });

        this.editor.getModel().cellInstance = this;

        this.editor.addCommand(monaco.KeyMod.Shift | monaco.KeyCode.Enter, () => {
            this.dispatchEvent(new CustomEvent('RunCell', { detail: { cellId: this.id }}));
            this.dispatchEvent(new CustomEvent('AdvanceCell', { detail: { cellId: this.id }}));
        });

        this.editor.addCommand(monaco.KeyMod.Shift | monaco.KeyMod.WinCtrl | monaco.KeyCode.Enter, () => {
            this.dispatchEvent(new CustomEvent('InsertCellAfter', { detail: { cellId: this.id }}));
            this.dispatchEvent(new CustomEvent('RunCell', { detail: { cellId: this.id }}));
        });

        this.listenTo(window, 'resize', (evt) => this.editor.layout());
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

    addResult(contentType, content) {
        this.cellOutput.classList.add('output');
        const contentTypeParts = contentType.split(';').map(str => str.replace(/(^\s+|\s+$)/g));
        const mimeType = contentTypeParts.shift();
        const args = {};
        contentTypeParts.forEach(part => {
            const [k, v] = part.split('=');
            args[k] = v;
        });

        const rel = args.rel || 'none';
        this.cellOutputDisplay.appendChild(
            div(['output'], [CodeCell.parseContent(content, mimeType)]).attr('rel', rel).attr('mime-type', mimeType)
        );
    }

    static parseContent(content, mimeType) {
        switch(mimeType) {
            case "text/plain":
                return document.createTextNode(content);
            default:
                const node = div(['result'], []);
                node.innerHTML = content;
                return node;
        }
    }

    clearResult() {
        this.cellOutputDisplay.innerHTML = '';
        this.cellOutput.classList.remove('errors');
        this.cellOutput.classList.remove('output');
    }

    requestCompletion(pos) {
        return new Promise((resolve, reject) => {
            this.dispatchEvent(new CustomEvent('CompletionRequest', {
                detail: {
                    id: this.id,
                    pos: pos,
                    resolve: resolve,
                    reject: reject
                }
            }));
        }) //.catch();
    }

    requestSignatureHelp(pos) {
        return new Promise((resolve, reject) => {
            this.dispatchEvent(new CustomEvent('ParamHintRequest', {
                detail: {
                    id: this.id,
                    pos: pos,
                    resolve: resolve,
                    reject: reject
                }
            }));
        }) //.catch();
    }

    makeActive() {
        super.makeActive();
        mainToolbar.dispatchEvent(new CustomEvent('ContextChanged'));
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
        this.editor.dispose();
    }

    get content() {
        return this.editor.getValue();
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

            // the edits have to be applied in order - pos is stateful wrt the edits that have been seen.
            // we'll take at most one deletion and one addition as a single edit.
            let text = "";
            let len = 0;
            if (i < diff.length) {
                if (diff[i].added) {
                    while (i < diff.length && diff[i].added) {
                        text = text + diff[i].value;
                        i++;
                    }
                    edits.push(new messages.ContentEdit(pos, 0, text));
                    pos += text.length;
                } else if (diff[i].removed) {
                    while (i < diff.length && diff[i].removed) {
                        len += diff[i].value.length;
                        i++;
                    }
                    while (i < diff.length && diff[i].added) {
                        text = text + diff[i].value;
                        i++;
                    }
                    edits.push(new messages.ContentEdit(pos, len, text));
                    pos += text.length;
                }
            }
        }
        const prevContent = this.lastContent;
        this.lastContent = newContent;

        if (edits.length > 0) {
            //console.log(edits);
            this.dispatchEvent(new CustomEvent(
                'ContentChange', {
                    detail: {
                        cellId: this.id,
                        edits: edits,
                        newContent: newContent
                    }
                }));
        }
    }

    onKeyDown(evt) {
        if (evt.key === 'Enter' || evt.keyCode === 13) {
            if (evt.shiftKey) {
                evt.preventDefault();
                if (evt.ctrlKey) {
                    this.dispatchEvent(new CustomEvent('InsertCellAfter', {detail: {cellId: this.id}}));
                } else {
                    this.dispatchEvent(new CustomEvent('AdvanceCell', {detail: {cellId: this.id}}));
                }
            }
        }
    }

    focus() {
        super.focus();
        this.editor.focus();
    }

    makeActive() {
        super.makeActive();
        mainToolbar.dispatchEvent(new CustomEvent('ContextChanged'));
    }

    blur() {
        super.blur();
    }

    get content() {
        return this.editor.markdownContent;
    }
}