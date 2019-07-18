import {button, div, fakeSelectElem, h3, iconButton} from "./tags";
import {FakeSelect} from "./fake_select";
import {Cell, CodeCell, TextCell} from "./cell";
import {LaTeXEditor} from "./latex_editor";
import {UIEvent, UIEventTarget} from "./ui_event";
import {prefs} from "./prefs";

export class ToolbarEvent extends UIEvent {
    constructor(eventId, details) {
        super(eventId, details || {});
    }
}

export class ToolbarUI extends UIEventTarget {
    constructor() {
        super();
        this.notebookToolbar = new NotebookToolbarUI().setEventParent(this);
        this.cellToolbar = new CellToolbarUI().setEventParent(this);
        this.codeToolbar = new CodeToolbarUI().setEventParent(this);
        this.textToolbar = new TextToolbarUI().setEventParent(this);
        this.settingsToolbar = new SettingsToolbarUI().setEventParent(this);

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

    setDisabled(disable) {
        if (disable) {
            [...this.el.querySelectorAll('button')].forEach(button => {
                button.disabled = true;
            });
        } else {
            [...this.el.querySelectorAll('button')].forEach(button => button.disabled = button.alwaysDisabled || false);
        }
    }
}

function toolbarElem(name, buttonGroups) {
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

class NotebookToolbarUI extends UIEventTarget {
    constructor() {
        super();
        this.el = toolbarElem("notebook", [
            [
                iconButton(["run-cell", "run-all"], "Run all cells", "", "Run all")
                    .click(() => this.dispatchEvent(new ToolbarEvent("RunAll"))),
                iconButton(["branch"], "Create branch", "", "Branch").disable().withKey('alwaysDisabled', true),
                iconButton(["download"], "Download", "", "Download").click(() => this.dispatchEvent(new ToolbarEvent("DownloadNotebook"))),
                iconButton(["clear"], "Clear notebook output", "", "Clear").click(() => this.dispatchEvent(new ToolbarEvent("ClearOutput")))
            ], [
                iconButton(["schedule-notebook"], "Schedule notebook", "", "Schedule").disable().withKey('alwaysDisabled', true),
            ]
        ]);
    }
}

class CellToolbarUI extends UIEventTarget {
    constructor() {
        super();
        this.el = toolbarElem("cell", [
            [
                this.cellTypeSelector = fakeSelectElem(["cell-language"], [
                    button(["selected"], {value: "text"}, ["Text"])
                ])
            ], [
                iconButton(["insert-cell-above"], "Insert cell above current", "", "Insert above")
                    .click(() => this.dispatchEvent(new ToolbarEvent(("InsertAbove")))),
                iconButton(["insert-cell-below"], "Insert cell below current", "", "Insert below")
                    .click(() => this.dispatchEvent(new ToolbarEvent(("InsertBelow")))),
                iconButton(["delete-cell"], "Delete current cell", "", "Delete")
                    .click(() => this.dispatchEvent(new ToolbarEvent(("DeleteCell")))),
                // iconButton(['undo'], 'Undo', '', 'Undo')
                //     .click(() => this.dispatchEvent(new ToolbarEvent('Undo'))),
            ]
        ]);

        this.cellTypeSelector = new FakeSelect(this.cellTypeSelector)

    }

    setInterpreters(interpreters) {
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

class CodeToolbarUI extends UIEventTarget {
    constructor() {
        super();
        this.el = toolbarElem("code", [
            [
                iconButton(["run-cell"], "Run this cell (only)", "", "Run")
                    .click(() => this.dispatchEvent(new ToolbarEvent(("RunCurrentCell")))),
                iconButton(["run-cell", "to-cursor"], "Run all cells above, then this cell", "", "Run to cursor")
                    .click(() => this.dispatchEvent(new ToolbarEvent(("RunToCursor")))),
                iconButton(["stop-cell"], "Stop/cancel this cell", "", "Cancel")
                    .click(() => this.dispatchEvent(new ToolbarEvent("CancelTasks"))),
            ]
        ]);
    }
}

class TextToolbarUI extends UIEventTarget {
    constructor() {
        super();
        let buttons = [];

        function commandButton(cmd, title, icon, alt) {
            const button = iconButton([cmd], title, icon, alt)
                .attr('command', cmd)
                .click(() => document.execCommand(cmd, false))
                .withKey('getState', () => document.queryCommandValue(cmd));

            buttons.push(button);
            return button
        }

        this.el = toolbarElem("text", [
            [
                this.blockTypeSelector = fakeSelectElem(["blockType"], [
                    button(["selected"], {value: "p"}, ["Paragraph"]),
                    button([], {value: "h1"}, ["Heading 1"]),
                    button([], {value: "h2"}, ["Heading 2"]),
                    button([], {value: "h3"}, ["Heading 3"]),
                    button([], {value: "h4"}, ["Heading 4"]),
                    button([], {value: "blockquote"}, ["Quote"]),
                ]).attr("command", "formatBlock").click(evt => {
                    document.execCommand("formatBlock", false, `<${evt.target.value}>`)
                })
            ], {
            classes: ["font"],
            elems: [
                commandButton("bold", "Bold", "", "Bold"),
                commandButton("italic", "Italic", "", "Italic"),
                commandButton("underline", "underline", "", "underline"),
                commandButton("strikethrough", "Strikethrough", "", "Strikethrough"),
                this.codeButton = iconButton(["code"], "Inline code", "", "Code")
                    .click(() => {
                        const selection = document.getSelection();
                        if (selection.baseNode &&
                            selection.baseNode.parentNode &&
                            selection.baseNode.parentNode.tagName &&
                            selection.baseNode.parentNode.tagName.toLowerCase() === "code") {

                            if (selection.anchorOffset === selection.focusOffset) {
                                // expand selection to the whole element
                                document.getSelection().selectAllChildren(document.getSelection().anchorNode.parentNode);
                            }
                            document.execCommand('removeFormat');
                        } else {
                            document.execCommand('insertHTML', false, '<code>' + selection.toString() + '</code>');
                        }
                    }).withKey('getState', () => {
                        const selection = document.getSelection();
                        return (
                            selection.baseNode &&
                            selection.baseNode.parentNode &&
                            selection.baseNode.parentNode.tagName &&
                            selection.baseNode.parentNode.tagName.toLowerCase() === "code"
                        )
                    }),
            ]}, {
            classes: ["lists"],
            elems: [
                commandButton("insertUnorderedList", "Bulleted list", "", "Bulleted list"),
                commandButton("insertOrderedList", "Numbered list", "", "Numbered list"),
                commandButton("indent", "Indent", "", "Indent"),
                commandButton("outdent", "Outdent", "", "Outdent"),
            ]}, {
            classes: ["objects"],
            elems: [
                iconButton(["image"], "Insert image", "", "Image").disable().withKey('alwaysDisabled', true),
                this.equationButton = iconButton(["equation"], "Insert/edit equation", "𝝨", "Equation")
                    .click(() => LaTeXEditor.forSelection().show())
                    .withKey('getState', () => {
                        const selection = document.getSelection();
                        if (selection.focusNode && selection.focusNode.childNodes) {
                            for (let i = 0; i < selection.focusNode.childNodes.length; i++) {
                                const node = selection.focusNode.childNodes[i];
                                if (node.nodeType === 1 && selection.containsNode(node, false) && (node.classList.contains('katex') || node.classList.contains('katex-block'))) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    }),
                iconButton(["table"], "Insert data table", "", "Table").disable().withKey('alwaysDisabled', true),
            ]}
        ]);

        this.blockTypeSelector = new FakeSelect(this.blockTypeSelector);

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
    }

}

class SettingsToolbarUI extends UIEventTarget {
    constructor() {
        super();
        this.el = toolbarElem("settings", [[
            // TODO: better icon
            this.vimButton = iconButton(["vim"], "Toggle VIM mode", "Vim", "VIM")
                .click(() => this.dispatchEvent(new ToolbarEvent("ToggleVIM"))),
            this.viewButton = iconButton(["view"], "View UI Preferences", "", "View")
                .click(() => this.dispatchEvent(new ToolbarEvent("ViewPrefs", {elem: this.floatingMenu, anchor: this.viewButton}))),
            iconButton(["reset"], "Reset UI Preferences", "", "Reset")
                .click(() => this.dispatchEvent(new ToolbarEvent(("ResetPrefs")))),
        ]]);

        this.floatingMenu = div(['floating-menu'], []);

        this.el.appendChild(this.floatingMenu)

        this.colorVim();
    }

    colorVim() {
        const vimSetting = prefs.get('VIM') || false;
        this.vimButton.classList.toggle('enabled', vimSetting)
    }
}
