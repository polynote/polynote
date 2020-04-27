import {
    DownloadNotebook,
    NotebookMessageDispatcher, RequestCancelTasks,
    RequestCellRun,
    RequestClearOutput,
    ServerMessageDispatcher, UIAction, ViewAbout
} from "../messaging/dispatcher";
import {button, div, fakeSelectElem, h3, iconButton, TagElement} from "../../util/tags";
import {ServerStateHandler} from "../state/server_state";
import {Observer, StateHandler} from "../state/state_handler";
import {NotebookState, NotebookStateHandler} from "../state/notebook_state";
import {LaTeXEditor} from "../latex_editor";
import {FakeSelect} from "../fake_select";

/**
 * The Toolbar. Its contents change depending on the current cell selected, and buttons are disabled when there is
 * no connection.
 */
export class ToolbarComponent {
    readonly el: TagElement<"div">;
    constructor(dispatcher: ServerMessageDispatcher) {

        const connectionStatus = ServerStateHandler.get.view("connectionStatus");

        let nb = new NotebookToolbar(connectionStatus);
        let cell = new CellToolbar(connectionStatus);
        let code = new CodeToolbar(connectionStatus);
        let text = new TextToolbar(connectionStatus);
        const settings = new SettingsToolbar(connectionStatus);

        this.el = div(['toolbar-container'], [nb.el, cell.el, code.el, text.el, settings.el])
            .listener('mousedown', (evt: Event) => evt.preventDefault());

        let cellSelectionListener: Observer<NotebookState> | undefined;
        let currentNotebookHandler: NotebookStateHandler | undefined;

        // Change the toolbar to reflect the currently selected notebook and cell
        ServerStateHandler.get.view("currentNotebook").addObserver((_, path) => {
            if (path) {
                const nbInfo = ServerStateHandler.get.getState().notebooks[path].info;
                if (nbInfo) {
                    const newListener = nbInfo.handler.addObserver((_, state) => {
                        if (state.activeCell) {
                            if (state.activeCell.language === "text") {
                                this.el.classList.remove('editing-code');
                                this.el.classList.add('editing-text');
                            } else {
                                this.el.classList.remove('editing-text');
                                this.el.classList.add('editing-code');
                            }
                        }
                    });
                    if (currentNotebookHandler && cellSelectionListener) {
                        currentNotebookHandler.removeObserver(cellSelectionListener);
                        cellSelectionListener = newListener;
                        currentNotebookHandler = nbInfo.handler;
                        nb.enable(nbInfo.dispatcher);
                        cell.enable(nbInfo.dispatcher);
                        code.enable(nbInfo.dispatcher);
                        text.enable();
                        settings.enable(dispatcher);
                    }
                }
            } else {
                cellSelectionListener = undefined;
                currentNotebookHandler = undefined;
                this.el.classList.remove('editing-text');
                this.el.classList.remove('editing-code');
                nb.disable();
                cell.disable();
                code.disable();
                text.disable();
                settings.disable();
            }
        })
    }
}

interface FancyButtonConfig {
    classes: string[],
    elems: TagElement<any>[]
}

abstract class ToolbarElement {
    el: TagElement<"div">;

    protected constructor(connectionStatus: StateHandler<"disconnected" | "connected">) {
        connectionStatus.addObserver((_, status) => {
            this.setDisabled(status === "disconnected")
        })
    }

    protected toolbarElem(name: string, buttonGroups: (TagElement<any>[] | FancyButtonConfig)[]) {
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

    protected setDisabled(disable: boolean): void {
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

class NotebookToolbar extends ToolbarElement {
    private dispatcher?: NotebookMessageDispatcher;
    constructor(connectionStatus: StateHandler<"disconnected" | "connected">) {
        super(connectionStatus);

        this.el = this.toolbarElem("notebook", [
            [
                iconButton(["run-cell", "run-all"], "Run all cells", "forward", "Run all")
                    .click(() => this.dispatch(new RequestCellRun([]))),
                iconButton(["branch"], "Create branch", "code-branch", "Branch").disable().withKey('alwaysDisabled', true),
                iconButton(["download"], "Download", "download", "Download").click(() => this.dispatch(new DownloadNotebook())),
                iconButton(["clear"], "Clear notebook output", "minus-circle", "Clear").click(() => this.dispatch(new RequestClearOutput()))
            ], [
                iconButton(["schedule-notebook"], "Schedule notebook", "clock", "Schedule").disable().withKey('alwaysDisabled', true),
            ]
        ]);
    }

    private dispatch(action: UIAction) {
        if (this.dispatcher) this.dispatcher.dispatch(action)
    }

    enable(dispatcher: NotebookMessageDispatcher) {
        this.dispatcher = dispatcher;
        this.setDisabled(false);
    }

    disable() {
        this.dispatcher = undefined;
        this.setDisabled(true);
    }
}

class CellToolbar extends ToolbarElement {
    private dispatcher?: NotebookMessageDispatcher;
    constructor(connectionStatus: StateHandler<"disconnected" | "connected">) {
        super(connectionStatus);

        const selectEl = fakeSelectElem(["cell-language"], [
            button(["selected"], {value: "text"}, ["Text"])
        ]);
        this.el = this.toolbarElem("cell", [
            [
                selectEl
            ], [
                iconButton(["insert-cell-above"], "Insert cell above current", "arrow-up", "Insert above")
                    .click(() => {
                        if(this.dispatcher) this.dispatcher.insertCell('above')
                    }),
                iconButton(["insert-cell-below"], "Insert cell below current", "arrow-down", "Insert below")
                    .click(() => {
                        if (this.dispatcher) this.dispatcher.insertCell('below')
                    }),
                iconButton(["delete-cell"], "Delete current cell", "trash-alt", "Delete")
                    .click(() => {
                        if (this.dispatcher) this.dispatcher.deleteCell()
                    })
                // iconButton(['undo'], 'Undo', 'undo-alt', 'Undo')
                //     .click(() => this.dispatchEvent(new ToolbarEvent('Undo'))),
            ]
        ]);
    }

    enable(dispatcher: NotebookMessageDispatcher) {
        this.dispatcher = dispatcher;
        this.setDisabled(false);
    }

    disable() {
        this.dispatcher = undefined;
        this.setDisabled(true);
    }
}

class CodeToolbar extends ToolbarElement {
    private dispatcher?: NotebookMessageDispatcher;
    constructor(connectionStatus: StateHandler<"disconnected" | "connected">) {
        super(connectionStatus);

        this.el = this.toolbarElem("code", [
            [
                iconButton(["run-cell"], "Run this cell (only)", "play", "Run")
                    .click(() => {
                        if (this.dispatcher) this.dispatcher.runActiveCell();
                    }),
                iconButton(["run-cell", "to-cursor"], "Run all cells above, then this cell", "fast-forward", "Run to cursor")
                    .click(() => {
                        if (this.dispatcher) this.dispatcher.runToActiveCell()
                    }),
                iconButton(["stop-cell"], "Stop/cancel this cell", "stop", "Cancel")
                    .click(() => {
                        if (this.dispatcher) this.dispatcher.dispatch(new RequestCancelTasks())
                    }),
            ]
        ]);
    }

    enable(dispatcher: NotebookMessageDispatcher) {
        this.dispatcher = dispatcher;
        this.setDisabled(false);
    }

    disable() {
        this.dispatcher = undefined;
        this.setDisabled(true);
    }
}

type CommandButton = TagElement<"button"> & {getState: () => string};

class TextToolbar extends ToolbarElement {
    private blockTypeSelector: FakeSelect;
    private codeButton: CommandButton;
    private equationButton: CommandButton;
    private buttons: CommandButton[];

    constructor(connectionStatus: StateHandler<"disconnected" | "connected">) {
        super(connectionStatus);

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

        this.el = this.toolbarElem("text", [
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
                        .click(() => LaTeXEditor.forSelection()!.show())
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

    enable() {
        this.setDisabled(false);
    }

    disable() {
        this.setDisabled(true);
    }
}

class SettingsToolbar extends ToolbarElement {
    private dispatcher?: ServerMessageDispatcher;
    private floatingMenu: TagElement<"div">;

    constructor(connectionStatus: StateHandler<"disconnected" | "connected">) {
        super(connectionStatus);

        this.el = this.toolbarElem("about", [[
            iconButton(["preferences"], "View UI Preferences", "cogs", "Preferences")
                .click(() => {
                    if (this.dispatcher) this.dispatcher.dispatch(new ViewAbout("Preferences"))
                })
                .withKey('neverDisabled', true),
            iconButton(["help"], "help", "question", "Help")
                .click(() => {
                    if (this.dispatcher) this.dispatcher.dispatch(new ViewAbout("Hotkeys"))
                })
                .withKey('neverDisabled', true),
        ]]);

        this.floatingMenu = div(['floating-menu'], []);

        this.el.appendChild(this.floatingMenu)
    }

    enable(dispatcher: ServerMessageDispatcher) {
        this.dispatcher = dispatcher;
        this.setDisabled(false);
    }

    disable() {
        this.dispatcher = undefined;
        this.setDisabled(true);
    }
}
