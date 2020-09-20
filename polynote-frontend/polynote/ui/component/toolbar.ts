import {button, div, fakeSelectElem, h3, iconButton, tag, TagElement} from "../util/tags";
import {FakeSelect} from "./fake_select";
import {Cell, CodeCell, TextCell} from "./cell";
import {LaTeXEditor} from "./latex_editor";
import {CancelTasks, ClearOutput, DownloadNotebook, UIMessageTarget, ViewAbout} from "../util/ui_event";
import {preferences, storage} from "../util/storage";
import {CurrentNotebook} from "./current_notebook";

export class ToolbarUI extends UIMessageTarget {
    private notebookToolbar: NotebookToolbarUI;
    cellToolbar: CellToolbarUI;
    private codeToolbar: CodeToolbarUI;
    private textToolbar: TextToolbarUI;
    private settingsToolbar: SettingsToolbarUI;
    readonly el: TagElement<"div">;
    constructor() {
        super();
        this.notebookToolbar = new NotebookToolbarUI(this);
        this.cellToolbar = new CellToolbarUI(this);
        this.codeToolbar = new CodeToolbarUI(this);
        this.textToolbar = new TextToolbarUI(this);
        this.settingsToolbar = new SettingsToolbarUI(this);

        this.el = div(['toolbar-container'], [
            this.notebookToolbar.el,
            this.cellToolbar.el,
            this.codeToolbar.el,
            this.textToolbar.el,
            this.settingsToolbar.el,
        ]).listener('mousedown', (evt) => evt.preventDefault())
    }

    onContextChanged() {
        if (Cell.currentFocus instanceof TextCell) {
            this.el.classList.remove('editing-code');
            this.el.classList.add('editing-text');
        } else if (Cell.currentFocus instanceof CodeCell) {
            this.el.classList.remove('editing-text');
            this.el.classList.add('editing-code');
        }
    }

    setDisabled(disable: boolean) {
        if (disable) {
            [...this.el.querySelectorAll('button')].forEach(button => {
                function hasNeverDisabled(button: HTMLButtonElement): button is HTMLButtonElement & {neverDisabled: boolean} {
                    return 'neverDisabled' in button
                }
                let disable = true;
                if (hasNeverDisabled(button)) {
                    disable = false
                }
                button.disabled = disable;
            });
        } else {
            [...this.el.querySelectorAll('button')].forEach(button => {
                function hasAlwaysDisabled(button: HTMLButtonElement): button is HTMLButtonElement & {alwaysDisabled: boolean} {
                    return 'alwaysDisabled' in button
                }
                let disable = false;
                if (hasAlwaysDisabled(button)) {
                    disable = button.alwaysDisabled || false
                }
                button.disabled = disable;
            });
        }
    }
}

interface FancyButtonConfig {
    classes: string[],
    elems: TagElement<any>[]
}

function toolbarElem(name: string, buttonGroups: (TagElement<any>[] | FancyButtonConfig)[]) {
    const contents = [h3([], [name])].concat(
        buttonGroups.map(group => {
            if (group instanceof Array) {
                return div(["tool-group"], group)
            } else {
                return div(["tool-group"].concat(group.classes), group.elems)
            }
        }));
    return div(["toolbar", name], contents)
}

class NotebookToolbarUI extends UIMessageTarget {
    readonly el: TagElement<"div">;
    constructor(parent: UIMessageTarget) {
        super(parent);
        this.el = toolbarElem("notebook", [
            [
                iconButton(["run-cell", "run-all"], "Run all cells", "forward", "Run all")
                    .click(() => CurrentNotebook.get.runAllCells()),
                iconButton(["branch"], "Create branch", "code-branch", "Branch").disable().withKey('alwaysDisabled', true),
                iconButton(["download"], "Download", "download", "Download").click(() => this.publish(new DownloadNotebook(CurrentNotebook.get.path))),
                iconButton(["clear"], "Clear notebook output", "minus-circle", "Clear").click(() => this.publish(new ClearOutput(CurrentNotebook.get.path)))
            ], [
                iconButton(["schedule-notebook"], "Schedule notebook", "clock", "Schedule").disable().withKey('alwaysDisabled', true),
            ]
        ]);
    }
}

class CellToolbarUI extends UIMessageTarget {
    readonly el: TagElement<"div">;
    cellTypeSelector: FakeSelect;

    constructor(parent?: UIMessageTarget) {
        super(parent);
        let selectEl: TagElement<"div">;
        this.el = toolbarElem("cell", [
            [
                selectEl = fakeSelectElem(["cell-language"], [
                    button(["selected"], {value: "text"}, ["Text"])
                ])
            ], [
                iconButton(["insert-cell-above"], "Insert cell above current", "arrow-up", "Insert above")
                    .click(() => CurrentNotebook.get.insertCell('above')),
                iconButton(["insert-cell-below"], "Insert cell below current", "arrow-down", "Insert below")
                    .click(() => CurrentNotebook.get.insertCell('below')),
                iconButton(["delete-cell"], "Delete current cell", "trash-alt", "Delete")
                    .click(() => CurrentNotebook.get.deleteCell())
                // iconButton(['undo'], 'Undo', 'undo-alt', 'Undo')
                //     .click(() => this.dispatchEvent(new ToolbarEvent('Undo'))),
            ]
        ]);

        this.cellTypeSelector = new FakeSelect(selectEl);

        this.cellTypeSelector.addListener(change => {
            CurrentNotebook.get.onCellLanguageSelected(change.newValue)
        })

    }

    setInterpreters(interpreters: Record<string, string>) {
        while (this.cellTypeSelector.options.length > 1) {
            this.cellTypeSelector.removeOption(this.cellTypeSelector.options[1]);
        }

        for (const languageId in interpreters) {
            if (interpreters.hasOwnProperty(languageId)) {
                this.cellTypeSelector.addOption(interpreters[languageId], languageId);
            }
        }
    }
}

class CodeToolbarUI extends UIMessageTarget {
    readonly el: TagElement<"div">;
    constructor(parent?: UIMessageTarget) {
        super(parent);
        this.el = toolbarElem("code", [
            [
                iconButton(["run-cell"], "Run this cell (only)", "play", "Run")
                    .click(() => CurrentNotebook.get.runCurrentCell()),
                iconButton(["run-cell", "to-cursor"], "Run all cells above, then this cell", "fast-forward", "Run to cursor")
                    .click(() => CurrentNotebook.get.runToCursor()),
                iconButton(["stop-cell"], "Stop/cancel this cell", "stop", "Cancel")
                    .click(() => this.publish(new CancelTasks(CurrentNotebook.get.path))),
            ]
        ]);
    }
}

type CommandButton = TagElement<"button"> & {getState: () => string};

class TextToolbarUI extends UIMessageTarget {
    readonly el: TagElement<"div">;
    private blockTypeSelector: FakeSelect;
    private codeButton: CommandButton;
    private equationButton: CommandButton;
    private buttons: CommandButton[];
    constructor(parent?: UIMessageTarget) {
        super(parent);
        let buttons = [];

        function commandButton(cmd: string, title: string, icon: string, alt: string): CommandButton {
            const button = iconButton([cmd], title, icon, alt)
                // .attr('command', cmd)
                .click(() => document.execCommand(cmd, false))
                .withKey('getState', () => document.queryCommandValue(cmd)) as CommandButton;

            buttons.push(button);
            return button
        }
        let blockTypeSelectorEl: TagElement<"div">;

        this.el = toolbarElem("text", [
            [
                blockTypeSelectorEl = fakeSelectElem(["blockType"], [
                    button(["selected"], {value: "p"}, ["Paragraph"]),
                    button([], {value: "h1"}, ["Heading 1"]),
                    button([], {value: "h2"}, ["Heading 2"]),
                    button([], {value: "h3"}, ["Heading 3"]),
                    button([], {value: "h4"}, ["Heading 4"]),
                    button([], {value: "blockquote"}, ["Quote"]),
                ]).click(evt => {
                    document.execCommand("formatBlock", false, `<${(evt.target as HTMLButtonElement).value}>`)
                })
            ], {
            classes: ["font"],
            elems: [
                commandButton("bold", "Bold", "bold", "Bold"),
                commandButton("italic", "Italic", "italic", "Italic"),
                commandButton("underline", "underline", "underline", "underline"),
                commandButton("strikethrough", "Strikethrough", "strikethrough", "Strikethrough"),
                this.codeButton = iconButton(["code"], "Inline code", "code", "Code")
                    .click(() => {
                        const selection = document.getSelection();
                        if ((selection?.anchorNode?.parentNode as HTMLElement)?.tagName?.toLowerCase() === "code") {

                            if (selection?.anchorOffset === selection?.focusOffset) {
                                // expand selection to the whole element
                                document.getSelection()!.selectAllChildren(document.getSelection()!.anchorNode!.parentNode!);
                            }
                            document.execCommand('removeFormat');
                        } else {
                            document.execCommand('insertHTML', false, '<code>' + selection!.toString() + '</code>');
                        }
                    }).withKey('getState', () => {
                        const selection = document.getSelection()!;
                        return (
                            (selection?.anchorNode?.parentNode as HTMLElement)?.tagName?.toLowerCase() === "code"
                        )
                    }) as CommandButton,
            ]}, {
            classes: ["lists"],
            elems: [
                commandButton("insertUnorderedList", "Bulleted list", "list-ul", "Bulleted list"),
                commandButton("insertOrderedList", "Numbered list", "list-ol", "Numbered list"),
                commandButton("indent", "Indent", "indent", "Indent"),
                commandButton("outdent", "Outdent", "outdent", "Outdent"),
            ]}, {
            classes: ["objects"],
            elems: [
                iconButton(["image"], "Insert image", "image", "Image").disable().withKey('alwaysDisabled', true),
                this.equationButton = button(["equation"], {title: "Insert/edit equation"}, "𝝨")
                    .click(() => {
                        this.equationButton.disabled = true;
                        LaTeXEditor.forSelection()!.show()
                    })
                    .withKey('getState', () => {
                        const selection = document.getSelection()!;
                        if (selection?.focusNode?.childNodes) {
                            for (let i = 0; i < selection.focusNode.childNodes.length; i++) {
                                const node = selection.focusNode.childNodes[i];
                                if (node.nodeType === 1 && selection.containsNode(node, false) && ((node as HTMLElement).classList.contains('katex') || (node as HTMLElement).classList.contains('katex-block'))) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    }) as CommandButton,
                iconButton(["table"], "Insert data table", "table", "Table").disable().withKey('alwaysDisabled', true),
            ]}
        ]);

        this.blockTypeSelector = new FakeSelect(blockTypeSelectorEl);

        buttons.push(this.codeButton);
        buttons.push(this.equationButton);
        this.buttons = buttons;

        // listen for selection changes to properly set button state
        document.addEventListener('selectionchange', () => this.onSelectionChange());
    }

    onSelectionChange() {
        for (const button of this.buttons) {

            let state = button.getState();

            if (state && state !== 'false') {
                button.classList.add('active');
            } else {
                button.classList.remove('active');
            }
        }
        const blockType = document.queryCommandValue('formatBlock').toLocaleLowerCase();
        const blockTypeIndex = this.blockTypeSelector.options.findIndex(el => el.value.toLowerCase() === blockType);
        if (blockTypeIndex !== -1) {
            this.blockTypeSelector.selectedIndex = blockTypeIndex;
        }
    }

}

class SettingsToolbarUI extends UIMessageTarget {
    readonly el: TagElement<"div">;

    constructor(parent?: UIMessageTarget) {
        super(parent);
        this.el = toolbarElem("about", [[
            iconButton(["preferences"], "View UI Preferences", "cogs", "Preferences")
                .click(() => this.publish(new ViewAbout("Preferences")))
                .withKey('neverDisabled', true),
            iconButton(["help"], "help", "question", "Help")
                .click(() => this.publish(new ViewAbout("Hotkeys")))
                .withKey('neverDisabled', true),
        ]]);
    }
}
