import {div, para, span, TagElement, textbox} from "../tags";
import * as katex from "katex";
import {Disposable} from "../../state";

export class LaTeXEditor extends Disposable {
    readonly el: TagElement<"div">;
    private pointer: TagElement<"span">;
    private fakeEl: TagElement<"div">;
    private input: TagElement<"input">;
    private inputHandler: (evt: Event) => void;
    private keyHandler: (evt: Event) => void;
    private valid: boolean;

    static editorCells: HTMLElement[] = [];

    constructor(readonly outputEl: HTMLElement, readonly parentEl: HTMLElement, readonly deleteOnCancel: boolean, readonly displayMode: boolean = false) {
        super()
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
        this.input.addEventListener('blur', this.cancel)
        this.valid = false;

        if (outputEl.hasAttribute('data-tex-source')) {
            this.input.value = outputEl.getAttribute('data-tex-source')!;
            this.onInput();
        }

        this.onDispose.then(() => {
            this.input.removeEventListener('input', this.inputHandler);
            this.input.removeEventListener('keydown', this.keyHandler);
            this.input.removeEventListener('blur', this.cancel)
            this.el.innerHTML = '';
            if (this.el.parentNode)
                this.el.parentNode.removeChild(this.el);
        })
    }

    show() {
        this.parentEl.appendChild(this.el);

        const containerWidth = this.parentEl.offsetWidth;
        const width = Math.min(400, containerWidth - 64);
        this.el.style.width = width + 'px';

        const rect = this.outputEl.getBoundingClientRect()
        const outputElLeft = rect.left - this.parentEl.offsetLeft;
        const outputElCenter = outputElLeft + rect.width / 2;
        const outputElBottom = rect.bottom + this.parentEl.scrollTop - this.parentEl.offsetTop;

        const left = Math.min(containerWidth - width, Math.max(0, Math.round(outputElCenter - width / 2)));

        this.el.style.top = outputElBottom + 'px';
        this.el.style.left = left + "px";
        // the pointer's left is relative to `left`, so we need to subtract it.
        this.pointer.style.left = outputElCenter - left - (this.pointer.offsetWidth / 2) + 'px';

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
            this.outputEl.outerHTML = this.fakeEl.innerHTML;
            if (this.outputEl.hasAttribute('data-tex-source')) {
                this.outputEl.setAttribute('data-tex-source', this.input.value);
            }

            // move caret to end of inserted equation
            const space = this.displayMode ? para([], [document.createElement('br')]) : span([], [" "]);
            parent?.insertBefore(space, this.outputEl.nextSibling);

            const selection = document.getSelection();
            if (selection) {
                selection.setBaseAndExtent(space, 0, space, space.textContent!.length);
                selection.collapseToEnd();
            }

            // clean up space element
            parent?.removeChild(space)

            this.textEditorEl?.dispatchEvent(new CustomEvent('input'));
            this.dispose();
        } else if (evt.key === 'Escape' || evt.key === 'Cancel') {
            this.cancel()
        }
    }

    get textEditorEl() {
        return this.outputEl.closest(".markdown-body")
    }

    delete() {
        this.outputEl.remove()
        this.textEditorEl?.dispatchEvent(new CustomEvent("input"))
        this.dispose()
    }

    cancel() {
        if (this.deleteOnCancel) {
            this.delete()
        } else this.dispose();
    }

    static isLatexEl(el: Element): boolean {
        return el.closest("[data-tex-source]") !== null
    }

    static forSelection() {
        const selection = document.getSelection()!;

        const selectionNode = selection.focusNode || selection.anchorNode;
        if (! selectionNode) {
            console.error('No selection');
            return;
        }

        // TODO: this should be a function
        let notebookParent = selection.anchorNode as HTMLElement;
        let currentKatexEl = null;
        let cellContainer: HTMLElement | null = null;
        while (notebookParent && (notebookParent.nodeType !== 1 || !(notebookParent.classList.contains('notebook-cells')))) {
            notebookParent = notebookParent.parentNode as HTMLElement;
            if (notebookParent.hasAttribute?.('data-tex-source'))
                currentKatexEl = notebookParent;
            if (notebookParent.classList.contains('text-cell'))
                cellContainer = notebookParent;
        }

        if (!notebookParent) {
            console.error('Error: reached top of document without finding notebook');
            return;
        }

        if (!cellContainer || this.editorCells.includes(cellContainer)) return; // prevent duplicates.

        let el = currentKatexEl;
        let deleteOnCancel = false;
        let displayMode = false;

        if (!el) {
            // check selection siblings from selection.anchorNode -> selection.focusNode
            if (!currentKatexEl) {
                let node = selection.anchorNode;
                while (currentKatexEl === null && node instanceof Node && node !== selection.focusNode) {
                    if (node instanceof HTMLElement) {
                        currentKatexEl = node.hasAttribute('data-tex-source') ? node : node.querySelector("[data-tex-source]")
                    }
                    node = node.nextSibling
                }
            }

            if (currentKatexEl) {
                el = currentKatexEl as HTMLElement;
            } else {
                document.execCommand('insertHTML', false, `<span id="tmp-katex">&nbsp;</span>`);
                el = document.getSelection()!.anchorNode!.parentNode as HTMLElement;

                // deleteOnCancel if we're starting fresh
                deleteOnCancel = true;

                // displayMode if the content isn't inline (that is, if it's the only thing on the line)
                if (el.parentElement?.textContent === null || el.parentElement?.textContent.trim() === "") {
                    displayMode = true;
                }
            }
        }
        if (! (el.id === "tmp-katex" || el.hasAttribute("data-tex-source"))) {
            console.log("error finding/generating proper katex element!")
        }

        displayMode = displayMode || (el?.classList.contains('katex-block') || el?.classList.contains('katex-display'));

        // center the temporary span if we're in displayMode.
        if (displayMode && el.id === "tmp-katex") {
            el.style.display = "block"
            el.style.textAlign = "center"
        }

        const editor = new LaTeXEditor(el, notebookParent, deleteOnCancel, displayMode);
        this.editorCells.push(cellContainer)
        editor.onDispose.then(() => {
            this.editorCells = this.editorCells.filter(x => x !== cellContainer)
        })
        return editor
    }
}