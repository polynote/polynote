import {htmlToMarkdown} from "./html_to_md";
import {Toolbar} from "./toolbar.js";

export class RichTextEditor {
    constructor(elem, content) {
        this.element = elem;

        if (content)
            elem.innerHTML = MarkdownIt.render(content);

        this.element.contentEditable = true;

        this.element.addEventListener('keydown', (evt) => {
            if (evt.key === 'Tab') {
                evt.preventDefault();
                if (document.queryCommandValue('insertUnorderedList') || document.queryCommandValue('insertOrderedList')) {
                    if (evt.shiftKey)
                        document.execCommand('outdent', false);
                    else
                        document.execCommand('indent', false);
                }
            }
        });
    }

    focus() {
        this.element.focus();
    }

    get markdownContent() {
        return htmlToMarkdown(this.element);
    }
}

export class TextToolbar extends Toolbar {
    constructor(id, actions) {
        super(id, actions);
    }

    action(event) {
        super.action(event);
        for (const button of this.buttons) {
            if (button.command) {
                button.setState(document.queryCommandValue(button.command));
            } else if (button.getState) {
                button.setState(button.getState(document.getSelection()))
            }
        }
    }
}