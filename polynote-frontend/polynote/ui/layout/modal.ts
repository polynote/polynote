"use strict";

import {button, div, dropdown, helpIconButton, iconButton, label, para, TagElement, textbox} from "../tags"
import {ServerStateHandler} from "../../state/server_state";

interface ModalOptions {
    title?: string | string[],
    windowClasses?: string[]
}

export class Modal {
    protected container: TagElement<"div">;
    private background: TagElement<"div">;
    private window: TagElement<"div">;
    private titleBar: TagElement<"div">;
    private titleContent: TagElement<"div">;
    readonly content: TagElement<"div">;

    constructor(content: TagElement<"div">, opts: ModalOptions) {
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

        const windowClasses = opts.windowClasses ?? [];

        this.container = div(['modal-container'], [
            this.background = div(['modal-background'], []).click(evt => this.hide()),
            this.window = div(['modal-window', ...windowClasses], [
                this.titleBar = div(['modal-titlebar'], [
                    this.titleContent = div(['modal-titlebar-content'], title),
                    div(['modal-titlebar-controls'], [
                        iconButton(['modal-close'], 'Close', 'times-circle', 'Close').click(evt => this.hide())
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
        opts.windowClasses = [...(opts.windowClasses ?? []), 'full-screen'];
        super(content, opts);
    }
}

export class DialogModalResult {
    constructor(readonly path: string, readonly template?: string) {}
}

export class DialogModal extends Modal {
    private pathInput: TagElement<"input">;
    private onSubmit: (value: DialogModalResult) => void;

    constructor(title: string, inputPlaceholder: string, okButtonText: string) {
        const input = textbox(['create-notebook-section'], "", inputPlaceholder)
        input.select();
        input.addEventListener('Accept', evt => this.submit(selectedNotebookTemplate));
        input.addEventListener('Cancel', evt => this.hide());

        // Convert list of notebook templates to an object for the dropdown
        let selectedNotebookTemplate = '';
        let options = ServerStateHandler.state.notebookTemplates.reduce((obj, v) => ({...obj, [v]: v}), {})

        // Adds a blank string as the first (default) option
        const notebookTemplateEl = dropdown(['notebook-templates'], Object.assign({'': 'None'}, options), '')
            .change(() => {
                selectedNotebookTemplate = notebookTemplateEl.options[notebookTemplateEl.selectedIndex].value;
            })

        const wrapper = div(['input-dialog'], [
            label([], 'Notebook Name', input, true),
            title === 'Create Notebook' ?
                label([], 'Notebook Template', notebookTemplateEl, true) : '',
            para(['create-notebook-section', 'help-text'], 'To add templates, edit your config.yml file.'),
            helpIconButton([], "https://polynote.org/latest/docs/server-configuration/#templates"),
            div(['buttons'], [
                button(['dialog-button'], {}, 'Cancel').click(evt => this.hide()),
                ' ',
                button(['dialog-button'], {}, okButtonText).click(evt => this.submit(selectedNotebookTemplate))])
        ]);
        super(wrapper, { title });

        this.pathInput = input;
    }

    show(): Promise<DialogModalResult> {
        super.show();
        return new Promise(resolve => {
            this.pathInput.focus();
            this.onSubmit = resolve
        })
    }

    private submit(selectedNotebookTemplate: string) {
        this.hide()
        this.onSubmit(new DialogModalResult(this.pathInput.value, selectedNotebookTemplate === "" ? undefined : selectedNotebookTemplate));
    }
}