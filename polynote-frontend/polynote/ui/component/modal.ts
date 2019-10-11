"use strict";

import {UIMessage, UIMessageTarget} from "../util/ui_event"
import {div, button, iconButton, TagElement} from "../util/tags"

interface ModalOptions {
    title?: string | string[],
    windowClasses?: string[]
}

export class Modal extends UIMessageTarget {
    private container: TagElement<"div">;
    private background: TagElement<"div">;
    private window: TagElement<"div">;
    private titleBar: TagElement<"div">;
    private titleContent: TagElement<"div">;
    readonly content: TagElement<"div">;

    constructor(content: TagElement<"div">, opts: ModalOptions) {
        super();
        if (!content) {
            content = div([], []);
        }

        if (!opts) {
            opts = {};
        }

        let title = opts.title || '';
        if (!(title instanceof Array)) {
            title = [title];
        }

        const windowClasses = opts.windowClasses || [];

        this.container = div(['modal-container'], [
            this.background = div(['modal-background'], []).click(evt => this.hide()),
            this.window = div(['modal-window', ...windowClasses], [
                this.titleBar = div(['modal-titlebar'], [
                    this.titleContent = div(['modal-titlebar-content'], title),
                    div(['modal-titlebar-controls'], [
                        iconButton(['modal-close'], 'Close', '', 'Close').click(evt => this.hide())
                    ])
                ]),
                this.content = div(['modal-content'], [content])
            ])
        ]);
    }

    setTitle(title: string) {
        this.titleContent.innerHTML = "";
        this.titleContent.appendChild(div([], title));
    }

    show() {
        document.body.appendChild(this.container);
    }

    hide() {
        if (this.container.parentNode) {
            this.container.parentNode.removeChild(this.container);
        }
    }
}

export class FullScreenModal extends Modal {
    constructor(content: TagElement<"div">, opts: ModalOptions = {}) {
        opts.windowClasses = [...(opts.windowClasses || []), 'full-screen'];
        super(
            content,
            opts
        );
    }
}