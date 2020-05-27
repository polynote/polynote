import {htmlToMarkdown} from "../util/html_to_md";
import {LaTeXEditor} from "./latex_editor";
import {MarkdownIt} from "../../util/markdown-it";
import {TagElement} from "../util/tags";

export class RichTextEditor {
    constructor(readonly element: TagElement<"div">, content: string) {
        if (content)
            this.element.innerHTML = MarkdownIt.render(content);

        this.element.contentEditable = 'true';

        // apparently FF already allows pasting images and getting a data URI.
        // This emulates it for chrome. Inspiration from https://codecorner.galanter.net/2017/09/11/copypaste-image-into-an-editable-dom-element-in-chrome/
        if ((window as any).chrome) {
            this.element.addEventListener('paste', event => {
                const items = event.clipboardData?.items;
                if (items && items.length === 1 && items[0].kind === "file") {
                    const item = items[0];
                    const mimeParts = item.type.split('/');
                    if (mimeParts[0] === "image") {
                        const file = item.getAsFile();
                        if (file) {
                            const reader = new FileReader();
                            reader.onload = () => {
                                const result = reader.result;
                                document.execCommand('insertImage', false, result as string);
                            };
                            reader.readAsDataURL(file);
                            event.preventDefault();
                        }
                    }
                }
            })
        }

        this.element.addEventListener('keydown', (evt) => {
            if (evt.key === 'Tab') {
                evt.preventDefault();
                if (document.queryCommandValue('insertUnorderedList') || document.queryCommandValue('insertOrderedList')) {
                    if (evt.shiftKey)
                        document.execCommand('outdent', false);
                    else
                        document.execCommand('indent', false);
                }
            } else if (evt.metaKey) {
                if (evt.key === 'h') {
                    evt.preventDefault();
                    const blockType = document.queryCommandValue('formatBlock').toLowerCase();
                    const currentHeaderMatch = /^h([1-6])/.exec(blockType);
                    let currentHeader = 0;
                    let nextHeader = 1;
                    if (currentHeaderMatch?.[1]) {
                        currentHeader = parseInt(currentHeaderMatch[1]);
                    }
                    if (currentHeader) {
                        nextHeader = currentHeader + 1;
                        if (nextHeader > 6) {
                            nextHeader = nextHeader % 6;
                        }
                    }
                    document.execCommand('formatBlock', false, `h${nextHeader}`);
                } else if (evt.key === 'e') {
                    evt.preventDefault();
                    LaTeXEditor.forSelection()!.show();
                }
            }
        });
    }

    set disabled(disable: boolean) {
        this.element.contentEditable = (!disable).toString();
    }

    focus() {
        this.element.focus();
    }

    get markdownContent() {
        return htmlToMarkdown(this.element);
    }
}
