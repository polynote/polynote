import {div, span, textbox, para, TagElement} from "../util/tags";
import {CellContainer, TextCell} from "./cell"
import * as katex from "katex";
import {UIMessageTarget} from "../util/ui_event";

export class LaTeXEditor extends UIMessageTarget {
    private readonly editorParent: HTMLElement;
    readonly el: TagElement<"div">;
    private pointer: TagElement<"span">;
    private fakeEl: TagElement<"div">;
    private input: TagElement<"input">;
    private inputHandler: (evt: Event) => void;
    private keyHandler: (evt: Event) => void;
    private valid: boolean;

    constructor(readonly outputEl: HTMLElement, readonly parentEl: HTMLElement, readonly deleteOnCancel: boolean, readonly displayMode: boolean = false) {
        super();

        let editorParent = outputEl;
        while (!(editorParent as CellContainer).cell && editorParent !== parentEl) {
            editorParent = editorParent.parentNode as HTMLElement;
        }
        this.editorParent = editorParent;

        // TODO: should we put editor in an iframe to prevent it contaminating the document's undo history?
        this.el = div(['latex-editor'], [
            this.pointer = span(['pointer'], []),
            div(['bubble'], [
                this.fakeEl = div(['tex-display'], []),
                this.input = textbox([], 'TeX equation definition', ''),
            ])
        ]);



        this.inputHandler = evt => this.onInput();
        this.keyHandler = (evt: KeyboardEvent) => this.onKeyDown(evt);

        this.input.addEventListener('input', this.inputHandler);
        this.input.addEventListener('keydown', this.keyHandler);
        this.valid = false;

        if (outputEl.hasAttribute('data-tex-source')) {
            this.input.value = outputEl.getAttribute('data-tex-source')!;
            this.onInput();
        }
    }

    show() {
        let targetX = 0;
        let targetY = this.outputEl.offsetHeight;
        let pointerOffset = 24;
        let el = this.outputEl;

        while (el && el !== this.parentEl) {
            targetX += (el.offsetLeft || 0) + ((el.offsetWidth - el.clientWidth));
            targetY += (el.offsetTop || 0);
            el = el.offsetParent as HTMLElement;
        }
        const containerWidth = this.parentEl.offsetWidth;
        const width = Math.min(400, containerWidth - 64);
        this.el.style.width = width + 'px';

        const midpoint = containerWidth / 2;

        if (this.displayMode) {
            targetX = midpoint;
        }

        const left = Math.min(containerWidth - width, Math.max(0, Math.round(targetX - width / 2)));
        pointerOffset = targetX - left;

        this.el.style.top = targetY + 'px';
        this.el.style.left = left + "px";
        this.pointer.style.left = pointerOffset + 'px';

        this.parentEl.appendChild(this.el);
        this.input.focus();

        return this;
    }

    onInput() {
        const texSource = this.input.value;
        try {
            this.valid = false;
            try {
                katex.render(texSource, this.fakeEl, { displayMode: this.displayMode });
            } catch (e) {
                if (e instanceof katex.ParseError) {
                    katex.render(texSource, this.fakeEl, { throwOnError: false, displayMode: this.displayMode });
                }
                throw e;
            }
            this.valid = true;
        } catch (err) {
            // swallow katex errors during editing (they will be frequent!)
        }
    }

    onKeyDown(evt: KeyboardEvent) {
        this.onInput();
        if (!this.valid) {
            return;
        }
        const parent = this.outputEl.parentNode;
        if (evt.key === 'Enter') {
            evt.preventDefault();
            evt.cancelBubble = true;

            // TODO: This seems to insert a bunch of junk around the equation; it's contained a span with a bunch of
            //       crap inline styles that do nothing. That span doesn't make it into the notebook file, but it's
            //       still annoying and bad. Can it be fixed?
            const firstChild = this.fakeEl.childNodes[0];
            if (firstChild instanceof HTMLElement) {
                firstChild.setAttribute('data-tex-source', this.input.value);
                firstChild.setAttribute('contenteditable', 'false');
            }
            this.outputEl.innerHTML = this.fakeEl.innerHTML;
            if (this.outputEl.hasAttribute('data-tex-source')) {
                this.outputEl.setAttribute('data-tex-source', this.input.value);
            }

            // move caret to end of inserted equation
            const space = this.displayMode ? para([], [document.createElement('br')]) : span([], [" "]);
            this.outputEl.parentNode!.insertBefore(space, this.outputEl.nextSibling);

            const selection = document.getSelection();
            if (selection) {
                selection.setBaseAndExtent(space, 0, space, space.textContent!.length);
                selection.collapseToEnd();
            }

            const cell = this.editorParent && (this.editorParent as CellContainer).cell;

            if (cell && cell instanceof TextCell) {
                cell.onInput();
            }

            this.dispose();
        } else if (evt.key === 'Escape' || evt.key === 'Cancel') {
            if (parent && this.deleteOnCancel) {
                parent.removeChild(this.outputEl);
                parent.dispatchEvent(new CustomEvent('input'));
            }
            this.dispose();
        }
    }

    dispose() {
        this.input.removeEventListener('input', this.inputHandler);
        this.input.removeEventListener('keydown', this.keyHandler);
        this.el.innerHTML = '';
        if (this.el.parentNode)
            this.el.parentNode.removeChild(this.el);
    }

    static forSelection() {
        const selection = document.getSelection()!;

        // TODO: this should be a function
        let notebookParent = selection.anchorNode as HTMLElement;
        let currentKatexEl = null;
        while (notebookParent && (notebookParent.nodeType !== 1 || !(notebookParent.classList.contains('notebook-cells')))) {
            notebookParent = notebookParent.parentNode as HTMLElement;
            if (notebookParent.hasAttribute && notebookParent.hasAttribute('data-tex-source'))
                currentKatexEl = notebookParent;
        }

        if (!notebookParent) {
            console.log('Error: reached top of document without finding notebook');
            return;
        }

        let el = currentKatexEl;
        let deleteOnCancel = false;
        let displayMode = (!!el && (el.classList.contains('katex-block') || el.classList.contains('katex-display')));

        if (!el) {
            if (selection.focusNode instanceof HTMLElement && selection.focusNode.tagName && selection.focusNode.tagName.toLowerCase() === "p" && selection.focusNode.textContent === "") {
                displayMode = true;
                el = selection.focusNode as HTMLElement;
            } else {
                document.execCommand('insertHTML', false, `<span>&nbsp;</span>`);
                el = document.getSelection()!.anchorNode!.parentNode as HTMLElement;
            }

            deleteOnCancel = true;
        }
        return new LaTeXEditor(el, notebookParent, deleteOnCancel, displayMode);
    }
}