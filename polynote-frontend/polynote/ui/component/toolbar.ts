import {NotebookMessageDispatcher, ServerMessageDispatcher,} from "../../messaging/dispatcher";
import {button, div, fakeSelectElem, h3, iconButton, TagElement} from "../tags";
import {Disposable, IDisposable, OptionalStateView, StateView} from "../../state";
import {CellState, NotebookStateHandler} from "../../state/notebook_state"
import {ServerStateHandler} from "../../state/server_state";
import {FakeSelect} from "../display/fake_select";
import {LaTeXEditor} from "../input/latex_editor";
import {ClientInterpreters} from "../../interpreter/client_interpreter";
import {UserPreferencesHandler} from "../../state/preferences";

/**
 * The Toolbar. Its contents change depending on the current cell selected, and buttons are disabled when there is
 * no connection.
 */
export class Toolbar extends Disposable {
    readonly el: TagElement<"div">;
    constructor(dispatcher: ServerMessageDispatcher) {
        super()

        const connectionStatus = ServerStateHandler.get.view("connectionStatus");

        let nb = new NotebookToolbar(connectionStatus);
        let cell = new CellToolbar(connectionStatus);
        let code = new CodeToolbar(connectionStatus);
        let text = new TextToolbar(connectionStatus);
        const settings = new SettingsToolbar(dispatcher, connectionStatus);

        this.el = div(['toolbar-container'], [nb.el, cell.el, code.el, text.el, settings.el])
            .listener('mousedown', (evt: Event) => evt.preventDefault());

        let cellSelectionListener: IDisposable | undefined;
        let currentNotebookHandler: NotebookStateHandler | undefined;

        // Change the toolbar to reflect the currently selected notebook and cell
        const updateToolbar = (path?: string) => {
            if (path) {
                const nbInfo = ServerStateHandler.getOrCreateNotebook(path);
                if (nbInfo?.info) {
                    currentNotebookHandler = nbInfo.handler
                    const newListener = currentNotebookHandler.activeCellView.addObserver(activeCell => {
                        if (activeCell !== undefined) {
                            const lang = activeCell.language
                            if (lang === "text") {
                                this.el.classList.remove('editing-code');
                                if (!UserPreferencesHandler.state.markdown.value)
                                    this.el.classList.add('editing-text');
                            } else {
                                this.el.classList.remove('editing-text');
                                this.el.classList.add('editing-code');
                            }
                        } else {
                            this.el.classList.remove('editing-code');
                            this.el.classList.remove('editing-text');
                        }
                    }).disposeWith(this);

                    if (cellSelectionListener !== undefined)
                        cellSelectionListener.tryDispose();
                    cellSelectionListener = newListener;
                    nb.enable(currentNotebookHandler, nbInfo.info.dispatcher);
                    cell.enable(currentNotebookHandler);
                    code.enable(nbInfo.info.dispatcher, currentNotebookHandler.activeCellView);
                    text.enable();
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
            }
        }
        updateToolbar(ServerStateHandler.state.currentNotebook)
        ServerStateHandler.get.observeKey("currentNotebook", path => updateToolbar(path)).disposeWith(this)
    }
}

interface FancyButtonConfig {
    classes: string[],
    elems: TagElement<any>[]
}

abstract class ToolbarElement extends Disposable {
    el: TagElement<"div">;

    protected constructor(connectionStatus: StateView<"disconnected" | "connected">, disableOnDisconnect: boolean = true) {
        super()

        if (disableOnDisconnect ) {
            connectionStatus.addObserver(currentStatus => {
                if (currentStatus === "disconnected") {
                    this.el.classList.add("disabled")
                } else if (currentStatus === "connected") {
                    this.el.classList.remove("disabled")
                }
            }).disposeWith(this)
        }
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
    private handler?: NotebookStateHandler;
    private cancelButton: TagElement<'button'>;
    constructor(connectionStatus: StateView<"disconnected" | "connected">) {
        super(connectionStatus);

        this.el = this.toolbarElem("notebook", [
            [
                iconButton(["run-cell", "run-all"], "Run all cells", "forward", "Run all")
                    .click(() => this.dispatcher?.runCells([])),
                this.cancelButton = iconButton(["stop-cell"], "Cancel all tasks", "stop", "Cancel All")
                    .click(_ => this.dispatcher?.cancelTasks()),
                iconButton(["branch"], "Create branch", "code-branch", "Branch").disable().withKey('alwaysDisabled', true),
                iconButton(["download"], "Download", "download", "Download").click(() => this.dispatcher?.downloadNotebook()),
                iconButton(["clear"], "Clear notebook output", "minus-circle", "Clear").click(() => this.dispatcher?.clearOutput())
            ], [
                iconButton(["schedule-notebook"], "Schedule notebook", "clock", "Schedule").disable().withKey('alwaysDisabled', true),
            ]
        ]);
    }

    enable(handler: NotebookStateHandler, dispatcher: NotebookMessageDispatcher) {
        if (this.handler && handler !== this.handler) {
            this.handler.dispose();
            this.handler = undefined;
        }

        if (!this.handler) {
            this.handler = handler.fork();
            this.handler.view("kernel").view("tasks").addObserver((tasks, updateResult) => {
                // only when there are newly added or removed tasks, not on any progress update
                if (Object.keys(updateResult.addedValues ?? {}).length || Object.keys(updateResult.removedValues ?? {}).length) {
                    this.cancelButton.disabled = !Object.keys(tasks).find(taskId => taskId.startsWith("Cell"));
                }
            });
        }

        this.cancelButton.disabled = !Object.keys(this.handler.state.kernel.tasks).find(taskId => taskId.startsWith("Cell"));

        this.dispatcher = dispatcher;
        this.setDisabled(false);
    }

    disable() {
        this.dispatcher = undefined;
        this.handler?.dispose();
        this.handler = undefined;
        this.setDisabled(true);
    }
}

class CellToolbar extends ToolbarElement {
    private nbHandler?: NotebookStateHandler;
    private enabled = new Disposable()
    private langSelector: FakeSelect;
    private disabledLangSelector: FakeSelect;
    constructor(connectionStatus: StateView<"disconnected" | "connected">) {
        super(connectionStatus);

        const selectEl = fakeSelectElem(["cell-language"], [
            button(["selected"], {value: "text"}, ["Text"])
        ]);
        const disabledSelectEl = fakeSelectElem(["cell-language"], [
            button(["selected"], {}, "")
        ]);
        this.el = this.toolbarElem("cell", [
            [
                selectEl
            ], [
                iconButton(["insert-cell-above"], "Insert cell above current", "arrow-up", "Insert above")
                    .click(() => {
                        if(this.nbHandler) this.nbHandler.insertCell('above').then(id => this.nbHandler?.selectCell(id, {editing: true}))
                    }),
                iconButton(["insert-cell-below"], "Insert cell below current", "arrow-down", "Insert below")
                    .click(() => {
                        if (this.nbHandler) this.nbHandler.insertCell('below').then(id => this.nbHandler?.selectCell(id, {editing: true}))
                    }),
                iconButton(["delete-cell"], "Delete current cell", "trash-alt", "Delete")
                    .click(() => {
                        if (this.nbHandler) this.nbHandler.deleteCell()
                    })
                // iconButton(['undo'], 'Undo', 'undo-alt', 'Undo')
                //     .click(() => this.dispatchEvent(new ToolbarEvent('Undo'))),
            ]
        ]);

        this.langSelector = new FakeSelect(selectEl);
        this.disabledLangSelector = new FakeSelect(disabledSelectEl);
        this.disabledLangSelector.disabled = true;

        const updateSelectorLanguages = (langs: Record<string, string>) => {
            const langEntries = Object.entries(langs)
            if (langEntries.length > 0) {
                // clear all but option 0, which we set earlier to be 'Text'
                while (this.langSelector.options.length > 1) {
                    this.langSelector.removeOption(this.langSelector.options[1]);
                }
                langEntries.forEach(([lang, id]) => {
                    this.langSelector.addOption(id, lang)
                });
            }
        }
        updateSelectorLanguages(ServerStateHandler.state.interpreters)
        ServerStateHandler.get.observeKey("interpreters", langs => updateSelectorLanguages(langs)).disposeWith(this)

        this.langSelector.addListener(change => {
            const id = this.nbHandler?.activeCellView?.state?.id;
            // Check the handler and current cell are defined and that the "text" option was not selected on a split cell
            if (this.nbHandler && id !== undefined && !(change.newValue == "text" && this.nbHandler?.activeCellView.state?.metadata?.splitDisplay)) {
                this.nbHandler.setCellLanguage(id, change.newValue, this)
            }
        })
    }

    enable(currentNotebookHandler: NotebookStateHandler) {
        this.enabled = new Disposable()
        this.nbHandler = currentNotebookHandler;
        this.nbHandler.activeCellView.viewOpt("language").addObserver((lang, updateResult, source) => {
            if (lang !== undefined) {
                if (ClientInterpreters[lang] && ClientInterpreters[lang].hidden) {
                    this.disabledLangSelector.element.querySelector('button')!.innerHTML = ClientInterpreters[lang].languageTitle;
                    if (this.langSelector.element.parentNode) {
                        this.disabledLangSelector.element.style.width = `${this.langSelector.element.offsetWidth}px`;
                        this.langSelector.element.parentNode.replaceChild(this.disabledLangSelector.element, this.langSelector.element);
                    }
                } else {
                    if (this.disabledLangSelector.element.parentNode) {
                        this.disabledLangSelector.element.parentNode.replaceChild(this.langSelector.element, this.disabledLangSelector.element);
                    }
                }
                if (source !== this) {
                    this.langSelector.setState(lang);
                }
            }
        }).disposeWith(this.enabled);

        this.setDisabled(false);
    }

    disable() {
        this.nbHandler = undefined;
        this.enabled.dispose();
        this.setDisabled(true);
    }
}

class CodeToolbar extends ToolbarElement {
    private dispatcher?: NotebookMessageDispatcher;
    private activeCellView?: OptionalStateView<CellState>;
    private activeObserver: IDisposable;
    private runButton: TagElement<'button'>;
    private runToButton: TagElement<'button'>;
    private cancelButton: TagElement<'button'>;

    constructor(connectionStatus: StateView<"disconnected" | "connected">) {
        super(connectionStatus);

        this.el = this.toolbarElem("code", [
            [
                this.runButton = iconButton(["run-cell"], "Run this cell (only)", "play", "Run")
                    .click(() => {
                        if (this.dispatcher) this.dispatcher.runActiveCell();
                    }),
                this.runToButton = iconButton(["run-cell", "to-cursor"], "Run all cells above, then this cell", "run-to", "Run to cursor")
                    .click(() => {
                        if (this.dispatcher) this.dispatcher.runToActiveCell()
                    }),
                this.runToButton = iconButton(["run-cell", "from-cursor"], "Run this cell, then all cells below", "run-from", "Run from cursor")
                    .click(() => {
                        if (this.dispatcher) this.dispatcher.runFromActiveCell()
                    }),
                this.cancelButton = iconButton(["stop-cell"], "Stop/cancel this cell", "stop", "Cancel")
                    .click(() => {
                        const activeId = this.activeCellView?.state?.id;
                        if (activeId !== undefined) {
                            this.dispatcher?.cancelTask(`Cell ${activeId}`);
                        }
                    }),
            ]
        ]);
    }

    enable(dispatcher: NotebookMessageDispatcher, activeCellView: OptionalStateView<CellState>) {
        this.dispatcher = dispatcher;
        this.activeCellView = activeCellView;
        if (this.activeObserver) {
            this.activeObserver.dispose();
        }

        const updateActive: (state: CellState | undefined) => void = cellState => {
            if (cellState) {
                const isRunningOrQueued = cellState.running || cellState.queued;
                this.runButton.disabled = this.runToButton.disabled = isRunningOrQueued;
                this.cancelButton.disabled = !isRunningOrQueued;
            }
        };

        this.activeObserver = activeCellView.addObserver(updateActive);
        updateActive(activeCellView.state);
        this.setDisabled(false);
    }

    disable() {
        if (this.activeObserver) {
            this.activeObserver.dispose();
        }
        this.dispatcher = undefined;
        this.setDisabled(true);
    }
}

type CommandButton = TagElement<"button"> & {getState: () => string};

class TextToolbar extends ToolbarElement {
    private blockTypeSelector: FakeSelect;
    private codeButton: CommandButton;
    private equationButton: CommandButton;
    private unlinkButton: CommandButton;
    private buttons: CommandButton[];

    constructor(connectionStatus: StateView<"disconnected" | "connected">) {
        super(connectionStatus);

        let buttons = [];

        function commandButton(cmd: string, title: string, icon: string, alt: string, arg?: () => string | undefined): CommandButton {
            const button = iconButton([cmd], title, icon, alt)
                // .attr('command', cmd)
                .click(() => document.execCommand(cmd, false, arg?.()))
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
                    commandButton("createLink", "Link", "link", "Link", () => document.getSelection()?.toString())
                        .withKey('getState', () => {
                            const selection = document.getSelection();
                            return selection?.anchorNode?.parentElement instanceof HTMLAnchorElement
                        }),
                    this.unlinkButton = iconButton(["unlink"], "Unlink", "unlink", "Unlink")
                        .click(() => {
                            const selection = document.getSelection();
                            if (selection?.anchorNode?.parentElement instanceof HTMLAnchorElement) {
                                selection.selectAllChildren(selection.anchorNode.parentNode!);
                                document.execCommand("unlink")
                                selection.removeAllRanges()
                            }
                        })
                        .withKey('getState', () => {
                            const selection = document.getSelection();
                            return selection?.anchorNode?.parentElement instanceof HTMLAnchorElement
                        }) as CommandButton
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
                        .click(() => LaTeXEditor.forSelection()?.show())
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
        buttons.push(this.unlinkButton);
        buttons.push(this.equationButton);
        this.buttons = buttons;

        // listen for selection changes to properly set button state
        document.addEventListener('selectionchange', () => this.onSelectionChange());
    }

    onSelectionChange() {
        for (const button of this.buttons) {

            let state = button.getState();

            if (state.toString() !== 'false') {
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
    private floatingMenu: TagElement<"div">;

    constructor(private dispatcher: ServerMessageDispatcher, connectionStatus: StateView<"disconnected" | "connected">) {
        super(connectionStatus, false); // this section is not disabled on disconnect.

        this.el = this.toolbarElem("about", [[
            iconButton(["preferences"], "View UI Preferences", "cogs", "Preferences")
                .click(() => {
                    this.dispatcher.viewAbout("Preferences")
                })
                .withKey('neverDisabled', true),
            iconButton(["help"], "help", "question", "Help")
                .click(() => {
                    this.dispatcher.viewAbout("Hotkeys")
                })
                .withKey('neverDisabled', true),
        ]]);

        this.floatingMenu = div(['floating-menu'], []);

        this.el.appendChild(this.floatingMenu)

        this.enable();
    }

    enable() {
        this.setDisabled(false);
    }

    disable() {
        this.setDisabled(true);
    }
}
