import {blockquote, button, div, dropdown, h4, iconButton, img, span, tag, TagElement} from "../../tags";
import {NotebookMessageDispatcher,} from "../../../messaging/dispatcher";
import {
    clearArray,
    Disposable,
    EditString,
    editString,
    ImmediateDisposable,
    moveArrayValue,
    removeFromArray,
    SetValue,
    setValue,
    StateHandler,
    StateView,
    UpdateLike,
    updateProperty,
    UpdateResult,
} from "../../../state";
import * as monaco from "monaco-editor";
import {
    editor,
    IKeyboardEvent,
    IPosition,
    IRange,
    languages,
    MarkerSeverity,
    Range,
    SelectionDirection
} from "monaco-editor";
// @ts-ignore (ignore use of non-public monaco api)
import {StandardKeyboardEvent} from 'monaco-editor/esm/vs/base/browser/keyboardEvent.js';
import {
    ClientResult,
    CompileErrors,
    ExecutionInfo,
    KernelReport,
    MIMEClientResult,
    Output,
    Position,
    PosRange,
    ResultValue,
    RuntimeError
} from "../../../data/result";
import {CellMetadata} from "../../../data/data";
import {FoldingController} from "../../input/monaco/extensions";
import {ContentEdit, Delete, diffEdits, Insert} from "../../../data/content_edit";
import {
    displayContent,
    displayData,
    displaySchema,
    mimeEl,
    MIMEElement,
    parseContentType,
    prettyDuration
} from "../../display/display_content";
import match, {matchS, purematch} from "../../../util/match";
import {CommentHandler} from "./comment";
import {RichTextEditor} from "../../input/text_editor";
import {DataRepr, MIMERepr, StreamingDataRepr, StringRepr} from "../../../data/value_repr";
import {DataReader} from "../../../data/codec";
import {StructType} from "../../../data/data_type";
import {FaviconHandler} from "../../../notification/favicon_handler";
import {NotificationHandler} from "../../../notification/notifications";
import {VimStatus} from "./vim_status";
import {availableResultValues, cellContext, ClientInterpreters} from "../../../interpreter/client_interpreter";
import {ErrorEl, getErrorLine} from "../../display/error";
import {Error, TaskInfo, TaskStatus} from "../../../data/messages";
import {collect, collectInstances, deepCopy, deepEquals, findInstance, linePosAt} from "../../../util/helpers";
import {
    CellPresenceState,
    CellState,
    CompletionHint,
    NotebookStateHandler,
    SignatureHint
} from "../../../state/notebook_state";
import {ServerStateHandler} from "../../../state/server_state";
import {isViz, parseMaybeViz, saveViz, Viz, VizSelector} from "../../input/viz_selector";
import {VegaClientResult, VizInterpreter} from "../../../interpreter/vega_interpreter";
import IStandaloneCodeEditor = editor.IStandaloneCodeEditor;
import CompletionList = languages.CompletionList;
import SignatureHelp = languages.SignatureHelp;
import SignatureHelpResult = languages.SignatureHelpResult;
import IModelContentChangedEvent = editor.IModelContentChangedEvent;
import IIdentifiedSingleEditOperation = editor.IIdentifiedSingleEditOperation;
import TrackedRangeStickiness = editor.TrackedRangeStickiness;
import IMarkerData = editor.IMarkerData;
import {UserPreferencesHandler} from "../../../state/preferences";
import {plotToVegaCode, validatePlot} from "../../input/plot_selector";


export type CodeCellModel = editor.ITextModel & {
    requestCompletion(pos: number): Promise<CompletionList>,
    requestSignatureHelp(pos: number): Promise<SignatureHelpResult>
};

class CellDragHandle extends ImmediateDisposable {
    private onRelease?: () => void;
    readonly el: TagElement<'div'>;
    private placeholderEl?: TagElement<'div'>;
    private draggingEl?: HTMLElement;
    private _disabled: boolean = false;
    private scrollInterval: number = 0;

    constructor(parent: CellContainer, notifyMoved: (after: string | null) => Promise<void>) {
        super(() => {
            if (this.onRelease) {
                this.onRelease();
            }
            if (this.el.parentNode) {
                this.el.parentNode.removeChild(this.el);
            }
            this.el.removeEventListener('mousedown', onDragStart);
        });
        this.disposeWith(parent);

        let initialDragY = 0;
        let initialDragX = 0;
        let initialY = 0;
        let currentMouseY = 0;
        let initialScroll = 0;
        let animFrame = 0;
        let initialPrev: Element | null | undefined = undefined;
        let bounds: DOMRect = new DOMRect(0, 0, 0, 0);

        const getCellAtPoint: (x: number, y: number) => (HTMLElement | undefined) = (x, y) => {
            const el = document.elementsFromPoint(x, y)
                .filter(el => el !== this.draggingEl && el.classList.contains('cell-and-divider'))[0];
            if (el)
                return el as HTMLElement;
            return undefined;
        }

        const reposition = () => {
            const dY = currentMouseY - initialDragY;
            const draggingEl = this.draggingEl!;

            if (animFrame) {
                window.cancelAnimationFrame(animFrame);
            }
            animFrame = window.requestAnimationFrame(() => {
                animFrame = 0;
                draggingEl.style.top = (initialY + dY) + (draggingEl.parentElement!.scrollTop - initialScroll) + 'px';
            });
        }

        const movePlaceholder = () => {
            const placeholder = this.placeholderEl!;
            const draggingOver = getCellAtPoint(initialDragX, currentMouseY);
            if (draggingOver) {
                const scrollTop = placeholder.parentElement!.scrollTop;
                const dims = draggingOver.getBoundingClientRect();
                const mid = dims.y + (dims.height / 2);
                const next = currentMouseY < mid ? draggingOver : draggingOver.nextElementSibling;
                if (placeholder.nextSibling !== next) {
                    placeholder.parentElement!.insertBefore(placeholder, next);
                    if (placeholder.offsetTop < scrollTop || placeholder.offsetTop + placeholder.offsetHeight > scrollTop + bounds.height) {
                        placeholder.scrollIntoView();
                    }
                }
            }
        }

        const onMove = (evt: MouseEvent) => {
            evt.preventDefault();
            currentMouseY = evt.clientY;
            reposition();
            movePlaceholder();
        }

        const startScrolling = () => {
            if (!this.scrollInterval) {
                this.scrollInterval = window.setInterval(() => {
                    const scrollTop = parent.el.parentElement!.scrollTop;
                    const scrollUpAmount = bounds.y + 40 - currentMouseY;
                    const scrollDownAmount = currentMouseY - bounds.bottom + 40;
                    if (scrollUpAmount > 0 && parent.el.offsetTop < scrollTop) {
                        parent.el.parentElement!.scrollBy(0, -scrollUpAmount);
                    } else if (scrollDownAmount > 0 && parent.el.offsetTop + parent.el.offsetHeight > scrollTop + bounds.height) {
                        parent.el.parentElement!.scrollBy(0, scrollDownAmount);
                    }
                }, 40)
            }
        }

        const stopScrolling = () => {
            if (this.scrollInterval) {
                window.clearInterval(this.scrollInterval);
                this.scrollInterval = 0;
            }
        }

        const removeListeners = () => {
            stopScrolling();
            window.removeEventListener("mousemove", onMove);
            window.removeEventListener("mouseup", onRelease);
            window.removeEventListener("blur", onRelease);
        }

        const onRelease = () => {
            removeListeners();
            const placeholder = this.placeholderEl!;
            const draggingEl = this.draggingEl!;
            draggingEl.parentElement!.removeEventListener('scroll', reposition);
            const newPrev = placeholder.previousElementSibling === draggingEl ? draggingEl.previousElementSibling : placeholder.previousElementSibling;
            placeholder.parentNode!.removeChild(placeholder);

            const notified = newPrev !== initialPrev ? notifyMoved(newPrev?.getAttribute('data-cellid') ?? null) : Promise.resolve();
            notified.then(() => {
                draggingEl.classList.remove('dragging');
                draggingEl.style.top = '';
                draggingEl.style.left = '';
                draggingEl.style.width = '';
            });

            this.draggingEl = undefined;
            this.placeholderEl = undefined;
            this.onRelease = undefined;
        }

        const onDragStart = (evt: MouseEvent) => {
            if (evt.button !== 0)   // only the main mouse button
                return;
            evt.preventDefault();
            startScrolling();
            if (this.disabled || evt.button !== 0) {
                return;
            }
            initialDragX = evt.clientX + 40;
            initialDragY = evt.clientY;
            window.addEventListener("mousemove", onMove);
            window.addEventListener("mouseup", onRelease);
            window.addEventListener("blur", onRelease);
            this.onRelease = onRelease;
            const draggingEl = this.draggingEl = parent.el;
            draggingEl.parentElement!.addEventListener('scroll', reposition);
            bounds = draggingEl.parentElement!.getBoundingClientRect();
            initialScroll = draggingEl.parentElement!.scrollTop;
            initialPrev = draggingEl.previousElementSibling;
            initialY = draggingEl.offsetTop;
            this.placeholderEl = div(['cell-drag-placeholder'], []);
            this.placeholderEl.style.height = draggingEl.offsetHeight + 'px';
            draggingEl.style.top = draggingEl.offsetTop + 'px';
            draggingEl.style.left = draggingEl.offsetLeft + 'px';
            draggingEl.style.width = draggingEl.offsetWidth + 'px';
            draggingEl.classList.add('dragging');
            draggingEl.parentElement!.insertBefore(this.placeholderEl, draggingEl);
        }

        this.el = div(['cell-dragger'], [div(['inner'], [])]);
        this.el.addEventListener('mousedown', onDragStart);

    }

    get disabled(): boolean {
        return this._disabled;
    }

    set disabled(disabled: boolean) {
        if (disabled === this.disabled)
            return;
        this._disabled = disabled;
        if (disabled) {
            this.el.classList.add("disabled");
        } else {
            this.el.classList.remove("disabled");
        }
    }
}

export class CellContainer extends Disposable {
    readonly el: TagElement<"div">;
    private readonly cellId: string;
    private cell: Cell;
    private path: string;
    private cellState: StateHandler<CellState>;

    constructor(
        newCellDivider: TagElement<'div'>,
        private dispatcher: NotebookMessageDispatcher,
        private notebookState: NotebookStateHandler,
        state: StateHandler<CellState>
    ) {
        super()
        const cellState = this.cellState = state.fork(this);
        const id = cellState.state.id;
        this.cellId = `Cell${id}`;
        this.cell = this.cellFor(cellState.state.language);
        this.path = notebookState.state.path;

        const dragHandle = new CellDragHandle(
            this,
            newPrev => notebookState.updateAsync(state => {
                const myIndex = state.cellOrder.indexOf(id);
                if (newPrev) {
                    const newIndex = state.cellOrder.indexOf(parseInt(newPrev, 10));
                    return {
                        cellOrder: moveArrayValue(myIndex, Math.max(newIndex < myIndex ? newIndex + 1 : newIndex, 0))
                    }
                } else {
                    return {
                        cellOrder: moveArrayValue(myIndex, 0)
                    }
                }

            }, this, 'cellOrder').then(() => {})
        );

        cellState.addObserver(state => {
            dragHandle.disabled = state.running || state.queued;
        })

        this.el = div(['cell-and-divider'], [
            div(['cell-component'], [dragHandle, this.cell.el]),
            newCellDivider
        ]).dataAttr('data-cellid', id.toString());

        this.el.click(evt => this.cell.doSelect());
        cellState.view("language").addObserver((newLang, updateResult) => {
            // Need to create a whole new cell if the language switches between code and text
            if (updateResult.oldValue && (updateResult.oldValue === "text" || newLang === "text")) {
                const newCell = this.cellFor(newLang)
                newCell.replace(this.cell).then(cell => {
                    this.cell = cell
                    this.layout()
                })
            }
        });

        ServerStateHandler.view("connectionStatus").addObserver(currentStatus => {
            if (currentStatus === "disconnected") {
                this.cell.setDisabled(true)
            } else {
                this.cell.setDisabled(false)
            }
        }).disposeWith(this)

        this.disposeWith(cellState)
    }

    private cellFor(lang: string) {
        switch (lang) {
            case "text":
                return new TextCell(this.dispatcher, this.notebookState, this.cellState);
            case "viz":
                return new VizCell(this.dispatcher, this.notebookState, this.cellState);
            default:
                return new CodeCell(this.dispatcher, this.notebookState, this.cellState);
        }
    }

    layout() {
        this.cell.layout()
    }

    delete() {
        this.cellState.dispose()
        this.cell.delete()
    }
}

export interface HotkeyInfo {
    key: string,
    description: string,
    hide?: boolean,
    vimOnly?: boolean
}

export const cellHotkeys: Record<string, HotkeyInfo> = {
    [monaco.KeyCode.UpArrow]: {key: "MoveUp", description: "Move to previous cell."},
    [monaco.KeyCode.DownArrow]: {key: "MoveDown", description: "Move to next cell. If there is no cell below, create it."},
    [monaco.KeyMod.Shift | monaco.KeyCode.Enter]: {key: "RunAndSelectNext", description: "Run cell and select the next cell. If there is no cell, create one."},
    [monaco.KeyMod.Shift | monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter]: {key: "RunAndInsertBelow", description: "Run cell and insert a new cell below it."},
    [monaco.KeyMod.CtrlCmd | monaco.KeyCode.PageUp]: {key: "SelectPrevious", description: "Move to previous."},
    [monaco.KeyMod.CtrlCmd | monaco.KeyCode.PageDown]: {key: "SelectNext", description: "Move to next cell. If there is no cell below, create it."},
    [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_A]: {key: "InsertAbove", description: "Insert a cell above this cell"},
    [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_B]: {key: "InsertBelow", description: "Insert a cell below this cell"},
    [monaco.KeyMod.WinCtrl | monaco.KeyMod.Alt | monaco.KeyCode.KEY_D]: {key: "Delete", description: "Delete this cell"},
    [monaco.KeyMod.Shift | monaco.KeyCode.F10]: {key: "RunAll", description: "Run all cells."},
    [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Alt | monaco.KeyCode.F9]: {key: "RunToCursor", description: "Run to cursor."},
    // Special hotkeys to support VIM movement across cells. They are not displayed in the hotkey list
    [monaco.KeyCode.KEY_J]: {key: "MoveDownJ", description: "", hide: true, vimOnly: true},
    [monaco.KeyCode.KEY_K]: {key: "MoveUpK", description: "", hide: true, vimOnly: true},
};

type PostKeyAction = "stopPropagation" | "preventDefault"

abstract class Cell extends Disposable {
    protected id: number;
    protected cellId: string;
    public el: TagElement<"div">;
    protected readonly cellState: StateHandler<CellState>;
    protected readonly notebookState: NotebookStateHandler;

    protected constructor(protected dispatcher: NotebookMessageDispatcher, _notebookState: NotebookStateHandler, _cellState: StateHandler<CellState>) {
        super()
        const cellState = this.cellState = _cellState.fork(this);
        const notebookState = this.notebookState = _notebookState.fork(this)

        // Attach listeners when the notebook is fully loaded (by then, `el` should be populated)
        notebookState.loaded.then(() => {
            // Handle hotkeys when cell is focused but editor is not. The editor hotkey handlers should have priority
            // if the editors are active.
            this.el.addEventListener('keydown', (evt: KeyboardEvent) => this.onKeyDown(evt))
            this.el.tabIndex = 0; // set the tabIndex so this element can be focused.
        })

        this.id = cellState.state.id;
        this.cellId = `Cell${this.id}`;

        const updateSelected = (selected: boolean | undefined) => {
            if (selected) {
                this.onSelected()
            } else {
                this.onDeselected()
            }
        }
        notebookState.observeKey("activeCellId", activeCell => updateSelected(activeCell === this.id));

        cellState.onDispose.then(() => {
            this.dispose()
        })
    }

    protected addCellClass(name: string) {
        this.el?.classList.add(name);
        this.el?.parentElement?.classList.add(name);
    }

    protected removeCellClass(name: string) {
        this.el?.classList.remove(name);
        this.el?.parentElement?.classList.remove(name);
    }

    get selected() {
        return this.notebookState.state.activeCellId === this.id
    }

    doSelect(){
        if (! this.selected) {
            this.notebookState.selectCell(this.id)
        }
    }

    doDeselect(){
        if (document.body.contains(this.el)) { // prevent a blur call when a cell gets deleted.
            if (this.selected // prevent blurring a different cell
                && ! VimStatus.currentlyActive) {  // don't blur if Vim statusbar has been selected
                this.notebookState.updateField("activeCellId", () => undefined)
            }
        }
    }

    replace(oldCell: Cell): Promise<Cell> {
        return oldCell.dispose().then(() => {
            oldCell.el.replaceWith(this.el);
            if (this.selected || oldCell.selected) {
                this.onSelected()
            }
            return Promise.resolve(this)
        })
    }

    protected onSelected() {
        this.addCellClass("active");
        if (document.activeElement instanceof HTMLElement && !this.el.contains(document.activeElement)){
            document.activeElement.blur()
            this.el?.focus()
        }
        this.scroll()
        if (!document.location.hash.includes(this.cellId)) {
            this.setUrl();
        }
    }

    protected onDeselected() {
        this.removeCellClass("active");
    }

    protected scroll() {
        const viewport = this.el.closest('.notebook-cells');
        const container = this.el.closest('.cell-component')
        if (viewport instanceof HTMLElement && container instanceof HTMLElement) {
            const viewportScrollTop = viewport.scrollTop;
            const viewportScrollBottom = viewportScrollTop + viewport.clientHeight;

            const elTop = container.offsetTop - viewport.offsetTop;
            const elBottom = elTop + this.el.offsetHeight;

            const buffer = 30 // 30 px buffer for visibility calculation
            const topVisible = elTop > viewportScrollTop && elTop < (viewportScrollBottom - 30)
            const bottomVisible = elBottom > (viewportScrollTop + 30) && elBottom < viewportScrollBottom
            if (!topVisible && !bottomVisible) {
                const needToScrollUp = elTop < viewportScrollTop;
                const needToScrollDown = elBottom > viewportScrollBottom;

                if (needToScrollUp && !needToScrollDown) {
                    this.el.scrollIntoView({behavior: "auto", block: "start", inline: "nearest"})
                } else if(!needToScrollUp && needToScrollDown) {
                    this.el.scrollIntoView({behavior: "auto", block: "start", inline: "nearest"})
                }
            }
        }
    }

    protected calculateHash(maybeSelection?: monaco.Range): URL {
        const currentURL = new URL(document.location.toString());

        currentURL.hash = `${this.cellId}`;
        return currentURL
    }
    protected setUrl(maybeSelection?: monaco.Range) {
        const currentURL = this.calculateHash(maybeSelection)

        window.history.replaceState(window.history.state, document.title, currentURL.href)
    }

    protected get state() {
        return this.cellState.state
    }

    protected onKeyDown(evt: IKeyboardEvent | KeyboardEvent) {
        let keybinding;
        if (evt instanceof StandardKeyboardEvent) {
            keybinding = (evt as typeof StandardKeyboardEvent)._asKeybinding;
        } else {
            keybinding = new StandardKeyboardEvent(evt)._asKeybinding;
        }
        const hotkey = cellHotkeys[keybinding]
        if (hotkey && (!hotkey.vimOnly || UserPreferencesHandler.state['vim'].value)) {
            const key = hotkey.key;
            const pos = this.getPosition();
            const range = this.getRange();
            const selection = this.getCurrentSelection();

            const postKeyAction = this.keyAction(key, pos, range, selection)
            if (postKeyAction?.includes("stopPropagation")) evt.stopPropagation();
            if (postKeyAction?.includes("preventDefault")) evt.preventDefault();
        }
    }

    protected selectOrInsertCell(direction: "above" | "below", doInsert = true) {
        const selected = this.notebookState.selectCell(this.id, {relative: direction, editing: true})
        // if a new cell wasn't selected, we probably need to insert a cell
        if (doInsert && (selected === undefined || selected === this.id)) {
            this.notebookState.insertCell(direction).then(id => this.notebookState.selectCell(id))
        }
    }

    protected keyAction(key: string, pos: IPosition, range: IRange, selection: string): PostKeyAction[] | undefined {
        return matchS<PostKeyAction[]>(key)
            .when("MoveUp", () => {
                this.notebookState.selectCell(this.id, {relative: "above", editing: true})
            })
            .when("MoveUpK", () => {
                this.notebookState.selectCell(this.id, {relative: "above", editing: true})
            })
            .when("MoveDown", () => {
                this.notebookState.selectCell(this.id, {relative: "below", editing: true})
            })
            .when("MoveDownJ", () => {
                this.notebookState.selectCell(this.id, {relative: "below", editing: true})
            })
            .when("RunAndSelectNext", () => {
                this.dispatcher.runActiveCell()
                this.selectOrInsertCell("below", false)
                return ["stopPropagation", "preventDefault"]
            })
            .when("RunAndInsertBelow", () => {
                this.dispatcher.runActiveCell()
                this.notebookState.insertCell("below").then(id => this.notebookState.selectCell(id))
                return ["stopPropagation", "preventDefault"]
            })
            .when("SelectPrevious", () => {
                this.notebookState.selectCell(this.id, {relative: "above", editing: true})
            })
            .when("SelectNext", () => {
                this.notebookState.selectCell(this.id, {relative: "below", editing: true})
            })
            .when("InsertAbove", () => {
                this.notebookState.insertCell("above").then(id => this.notebookState.selectCell(id))
            })
            .when("InsertBelow", () => {
                this.notebookState.insertCell("below").then(id => this.notebookState.selectCell(id))
            })
            .when("Delete", () => {
                this.notebookState.deleteCell()
            })
            .when("RunAll", () => {
                this.dispatcher.runCells([])
            })
            .when("RunToCursor", () => {
                this.dispatcher.runToActiveCell()
            })
            .otherwise(null) ?? undefined
    }

    protected abstract getPosition(): IPosition

    protected abstract getRange(): IRange

    protected abstract getCurrentSelection(): string

    abstract setDisabled(disabled: boolean): void

    delete() {
        this.dispose()
    }

    layout() {}
}

type ErrorMarker = {error: CompileErrors | RuntimeError, markers: IMarkerData[]};

export class CodeCell extends Cell {
    private readonly editor: IStandaloneCodeEditor;
    private readonly editorEl: TagElement<"div">;
    private cellInputTools: TagElement<"div">;
    private applyingServerEdits: boolean;
    private execDurationUpdater?: number;
    private highlightDecorations: string[] = [];
    private vim?: any;
    private commentHandler: CommentHandler;

    private errorMarkers: ErrorMarker[] = [];
    private overflowDomNode: TagElement<"div">;

    constructor(dispatcher: NotebookMessageDispatcher, notebookState: NotebookStateHandler, cell: StateHandler<CellState>) {
        super(dispatcher, notebookState, cell);
        const cellState = this.cellState;
        const langSelector = dropdown(['lang-selector'], ServerStateHandler.state.interpreters);
        langSelector.setSelectedValue(this.state.language);
        langSelector.addEventListener("input", evt => {
            const selectedLang = langSelector.getSelectedValue();
            if (selectedLang !== this.state.language) {
                notebookState.setCellLanguage(this.id, selectedLang)
            }
        });

        const execInfoEl = div(["exec-info"], []);

        this.editorEl = div(['cell-input-editor'], [])

        this.overflowDomNode = div(['monaco-overflow', 'monaco-editor', this.cellId], []);
        this.notebookState.loaded.then(() => {
            this.editorEl.closest('.notebook-content')!.appendChild(this.overflowDomNode)
            this.editorEl.closest('.notebook-cells')!.addEventListener('scroll', () => {
                this.layout()
            })
        })
        this.onDispose.then(() => {
            this.overflowDomNode.parentElement?.removeChild(this.overflowDomNode)
        })

        const highlightLanguage = ClientInterpreters[this.state.language]?.highlightLanguage ?? this.state.language;
        // set up editor and content
        this.editor = monaco.editor.create(this.editorEl, {
            value: this.state.content,
            language: highlightLanguage,
            automaticLayout: false,
            codeLens: false,
            dragAndDrop: true,
            minimap: { enabled: false },
            parameterHints: {enabled: true},
            scrollBeyondLastLine: false,
            fontFamily: 'Hasklig, Fira Code, Menlo, Monaco, fixed',
            fontSize: 15,
            fontLigatures: true,
            fixedOverflowWidgets: false,
            lineNumbers: 'on',
            lineNumbersMinChars: 1,
            lineDecorationsWidth: 0,
            renderLineHighlight: "none",
            scrollbar: {
                alwaysConsumeMouseWheel: false,
                vertical: "hidden",
                verticalScrollbarSize: 0,
            },
            overflowWidgetsDomNode: this.overflowDomNode
        });

        this.editorEl.setAttribute('spellcheck', 'false');  // so code won't be spellchecked

        // watch for resize events
        const resizeObs = new ResizeObserver(() => {
            this.layout()
        })
        resizeObs.observe(this.editorEl)
        this.onDispose.then(() => resizeObs.disconnect())

        this.editor.onDidFocusEditorWidget(() => {
            this.editor.updateOptions({ renderLineHighlight: "all" });
        });
        this.editor.onDidBlurEditorWidget(() => {
            this.editor.updateOptions({ renderLineHighlight: "none" });
            if (!this.commentHandler.activeComment()) {
                this.doDeselect();
            }
        });
        this.editor.onDidChangeCursorSelection(evt => {
            if (this.applyingServerEdits) return // ignore when applying server edits.

            // deep link - we only care if the user has selected more than a single character
            if ([0, 3].includes(evt.reason)) { // 0 -> NotSet, 3 -> Explicit
                this.setUrl(evt.selection);
            }

            const model = this.editor.getModel();
            if (model) {
                const range = new PosRange(model.getOffsetAt(evt.selection.getStartPosition()), model.getOffsetAt(evt.selection.getEndPosition()));
                if (evt.selection.getDirection() === SelectionDirection.RTL) {
                    this.cellState.updateField("currentSelection", () => range.reversed)
                } else {
                    this.cellState.updateField("currentSelection", () => range)
                }
            }
        });
        (this.editor.getContribution('editor.contrib.folding') as FoldingController).getFoldingModel()?.then(
            foldingModel => foldingModel?.onDidChange(() => this.layout())
        );
        this.editor.onDidChangeModelContent(event => this.onChangeModelContent(event));

        // we need to do this hack in order for completions to work :(
        (this.editor.getModel() as CodeCellModel).requestCompletion = this.requestCompletion.bind(this);
        (this.editor.getModel() as CodeCellModel).requestSignatureHelp = this.requestSignatureHelp.bind(this);

        // NOTE: this uses some private monaco APIs. If this ever ends up breaking after we update monaco it's a signal
        //       we'll need to rethink this stuff.
        this.editor.onKeyDown((evt: IKeyboardEvent | KeyboardEvent) => this.onKeyDown(evt))

        cellState.observeKey("editing", editing => {
            if (editing) {
                this.editor.focus()
            }
        })

        const compileErrorsState = cellState.view("compileErrors");
        compileErrorsState.addObserver(errors => {
            if (errors.length > 0) {
                errors.forEach(error => {
                    const model = this.editor.getModel()!;
                    const reportInfos = collect(error.reports, (report) => {
                        if (report.position) {
                            const startPos = model.getPositionAt(report.position.start ?? report.position.point);
                            const endPos = model.getPositionAt(report.position.end ?? report.position.point);
                            const severity = report.severity * 4;
                            return {
                                message: report.message,
                                startLineNumber: startPos.lineNumber,
                                startColumn: startPos.column,
                                endLineNumber: endPos.lineNumber,
                                endColumn: endPos.column,
                                severity: severity,
                                originalSeverity: report.severity
                            };
                        } else return undefined;
                    });

                    this.setErrorMarkers(error, reportInfos)
                })
            } else {
                this.clearErrorMarkers("compiler")
            }
        })

        const runtimeErrorState = cellState.view("runtimeError")
        runtimeErrorState.addObserver(runtimeError => {
            if (runtimeError) {
                const cellLine = getErrorLine(runtimeError.error, this.cellId);
                if (cellLine !== undefined && cellLine >= 0) {
                    const model = this.editor.getModel()!;
                    this.setErrorMarkers(runtimeError, [{
                        message: runtimeError.error.message, //err.summary.message,
                        startLineNumber: cellLine,
                        endLineNumber: cellLine,
                        startColumn: model.getLineMinColumn(cellLine),
                        endColumn: model.getLineMaxColumn(cellLine),
                        severity: 8
                    }])
                }
            } else {
                this.clearErrorMarkers("runtime")
            }
        })

        let copyCellOutputBtn = iconButton(['copy-output'], 'Copy Output to Clipboard', 'copy', 'Copy Output to Clipboard').click(() => this.copyOutput())
        copyCellOutputBtn.style.display = "none";
        let cellOutput = new CodeCellOutput(this.notebookState, cellState, this.cellId, compileErrorsState, runtimeErrorState, copyCellOutputBtn);

        this.el = div(['cell-container', this.state.language, 'code-cell'], [
            div(['cell-input'], [
                this.cellInputTools = div(['cell-input-tools'], [
                    iconButton(['run-cell'], 'Run this cell (only)', 'play', 'Run').click((evt) => {
                        dispatcher.runCells([this.state.id])
                    }),
                    div(['cell-label'], [this.state.id.toString()]),
                    div(['lang-selector'], [langSelector]),
                    execInfoEl,
                    div(["options"], [
                        button(['toggle-code'], {title: 'Show/Hide Code'}, ['{}']).click(evt => this.toggleCode()),
                        iconButton(['toggle-output'], 'Show/Hide Output', 'align-justify', 'Show/Hide Output').click(evt => this.toggleOutput()),
                        copyCellOutputBtn
                    ])
                ]),
                this.editorEl
            ]),
            cellOutput.el
        ]);

        cellState.observeKey("language", (newLang, update) => {
            const oldLang = update.oldValue;
            const newHighlightLang = ClientInterpreters[newLang]?.highlightLanguage ?? newLang;
            if (oldLang) {
                const oldHighlightLang = ClientInterpreters[oldLang]?.highlightLanguage ?? oldLang;
                this.el.classList.replace(oldHighlightLang, newHighlightLang);
            } else {
                this.el.classList.add(newHighlightLang)
            }
            langSelector.setSelectedValue(newHighlightLang);
            monaco.editor.setModelLanguage(this.editor.getModel()!, newHighlightLang)
        })

        const updateMetadata = (metadata: CellMetadata) => {
            if (metadata.hideSource) {
                this.el.classList.add("hide-code")
            } else {
                this.el.classList.remove("hide-code");
                this.layout();
            }

            if (metadata.hideOutput) {
                this.el.classList.add("hide-output");
            } else {
                this.el.classList.remove("hide-output");
            }

            if (metadata.executionInfo) {
                this.setExecutionInfo(execInfoEl, metadata.executionInfo)
            }
        }
        updateMetadata(this.state.metadata);
        cellState.observeKey("metadata", metadata => updateMetadata(metadata));

        cellState.observeKey("content", (content, updateResult, src) => {
            if (src === this)   // ignore edits that originated from monaco
                return;

            const edits = purematch<UpdateLike<string>, ContentEdit[]>(updateResult.update)
                .when(EditString, edits => edits)
                .whenInstance(SetValue, update => updateResult.oldValue ? diffEdits(updateResult.oldValue as string, update.value as string) : [])
                .otherwiseThrow

            if (edits && edits.length > 0) {
                // console.log("applying edits", edits)
                this.applyEdits(edits);
            }
        })

        const updateError = (error: boolean | undefined) => {
            if (error) {
                this.addCellClass("error");
            } else {
                this.removeCellClass("error");
            }
        }
        updateError(this.state.error)
        cellState.observeKey("error", error => updateError(error));

        const updateRunning = (running: boolean | undefined, previously?: boolean) => {
            if (running) {
                this.addCellClass("running");
                // clear results when a cell starts running:
                this.cellState.updateField("results", () => clearArray())

                // update Execution Status (in case this is an initial load)
                if (this.state.metadata.executionInfo) this.setExecutionInfo(execInfoEl, this.state.metadata.executionInfo)

            } else {
                this.removeCellClass("running");
                if (previously) {
                    const status = this.state.error ? "Error" : "Complete"
                    NotificationHandler.notify(this.path, `Cell ${this.id} ${status}`).then(() => {
                        this.notebookState.selectCell(this.id)
                    })
                    // clear the execution duration updater if it hasn't been cleared already.
                    if (this.execDurationUpdater) {
                        window.clearInterval(this.execDurationUpdater);
                        delete this.execDurationUpdater;
                    }
                }
            }
        }
        updateRunning(this.state.running);
        cellState.view("running").addObserver((curr, update) => updateRunning(curr, update.oldValue));

        const updateQueued = (queued: boolean | undefined, previously?: boolean) => {
            if (queued) {
                this.addCellClass("queued");
                if (!previously) {
                    FaviconHandler.inc()
                }
            } else {
                this.removeCellClass("queued");
                if (previously) {
                    FaviconHandler.dec()
                }
            }
        }
        updateQueued(this.state.queued)
        cellState.view("queued").addObserver((curr, update) => updateQueued(curr, update.oldValue));

        const updateHighlight = (h: { range: PosRange , className: string} | undefined) => {
            if (h) {
                const oldExecutionPos = this.highlightDecorations ?? [];
                const model = this.editor.getModel()!;
                const startPos = model.getPositionAt(h.range.start);
                const endPos = model.getPositionAt(h.range.end);
                this.highlightDecorations = this.editor.deltaDecorations(oldExecutionPos, [
                    {
                        range: monaco.Range.fromPositions(startPos, endPos),
                        options: { className: h.className }
                    }
                ]);
            } else if (this.highlightDecorations.length > 0) {
                this.editor.deltaDecorations(this.highlightDecorations, []);
                this.highlightDecorations = [];
            }
        }
        updateHighlight(this.state.currentHighlight)
        cellState.observeKey("currentHighlight", h => updateHighlight(h));

        const presenceMarkers: Record<number, string[]> = {};
        const updatePresence = (id: number, name: string, color: string, range: PosRange) => {
            const model = this.editor.getModel();
            if (model) {
                const old = presenceMarkers[id] ?? [];
                const startPos = model.getPositionAt(range.start);
                const endPos = model.getPositionAt(range.end);
                const newDecorations = [
                    {
                        range: monaco.Range.fromPositions(endPos, endPos),
                        options: {
                            className: `ppc ${color}`,
                            stickiness: TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
                            hoverMessage: { value: name }
                        }
                    }
                ];
                if (range.start != range.end) {
                    newDecorations.unshift({
                        range: monaco.Range.fromPositions(startPos, endPos),
                        options: {
                            className: `${color}`,
                            stickiness: TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
                            hoverMessage: { value: name }
                        }
                    });
                }
                presenceMarkers[id] = this.editor.deltaDecorations(old, newDecorations);
            }
        }
        Object.values(cellState.state.presence).forEach(p => updatePresence(p.id, p.name, p.color, p.range))
        cellState.observeKey("presence", (newPresence, updateResult) => {
            const removed = Object.values(updateResult.removedValues ?? {}) as CellPresenceState[];
            removed.forEach(p => {
                const marker = presenceMarkers[p.id]
                if (marker) {
                    this.editor.deltaDecorations(marker, [])
                }
                delete presenceMarkers[p.id]
            });

            const added = Object.values(updateResult.addedValues ?? {}) as CellPresenceState[];
            added.forEach(p => updatePresence(p.id, p.name, p.color, p.range));

            const changed = Object.values(updateResult.changedValues ?? {}) as CellPresenceState[];
            changed.forEach(p => updatePresence(p.id, p.name, p.color, p.range));
        })

        // make sure to create the comment handler.
        this.commentHandler = new CommentHandler(cellState.lens("comments"), cellState.view("currentSelection"), this.editor);

        this.onDispose.then(() => {
            this.commentHandler.dispose()
            this.getModelMarkers()?.forEach(marker => {
                this.setModelMarkers([], marker.owner)
            });
            this.editor.dispose()
        })
    }

    private onChangeModelContent(event: IModelContentChangedEvent) {
        this.layout();
        if (this.applyingServerEdits)
            return;

        // clear any error markers if present on the edited content
        const markers = this.getModelMarkers()
        if (markers) {
            const keep = markers.filter(marker => {
                const markerRange = new monaco.Range(marker.startLineNumber, marker.startColumn, marker.endLineNumber, marker.endColumn);
                return event.changes.every(change => ! markerRange.containsRange(change.range))
            })
            this.setModelMarkers(keep)
        }

        // TODO: remove cell highlights here?

        const edits = event.changes.flatMap((contentChange) => {
            if (contentChange.rangeLength > 0 && contentChange.text.length > 0) {
                return [new Delete(contentChange.rangeOffset, contentChange.rangeLength), new Insert(contentChange.rangeOffset, contentChange.text)];
            } else if (contentChange.rangeLength > 0) {
                return [new Delete(contentChange.rangeOffset, contentChange.rangeLength)];
            } else if (contentChange.text.length > 0) {
                return [new Insert(contentChange.rangeOffset, contentChange.text)];
            } else return [];
        });
        this.cellState.updateField("content", () => editString(edits), this);

        // update comments
        this.commentHandler.triggerCommentUpdate()
    }

    requestCompletion(offset: number): Promise<CompletionList> {
        return new Promise<CompletionHint>((resolve, reject) => {
            this.notebookState.state.activeCompletion?.reject() // remove previously active completion if present
            return this.notebookState.updateField("activeCompletion", () => setValue({cellId: this.id, offset, resolve, reject}))
        }).then(({cell, offset, completions}) => {
            const len = completions.length;
            const indexStrLen = ("" + len).length;
            const completionResults = completions.map((candidate, index) => {
                const isMethod = candidate.params.length > 0 || candidate.typeParams.length > 0;

                const typeParams = candidate.typeParams.length ? `[${candidate.typeParams.join(', ')}]`
                    : '';

                const params = isMethod ? candidate.params.map(pl => `(${pl.map(param => `${param.name}: ${param.type}`).join(', ')})`).join('')
                    : '';

                const label = `${candidate.name}${typeParams}${params}`;

                const insertText =
                    candidate.insertText || candidate.name; //+ (params.length ? '($2)' : '');

                // Calculating Range (TODO: Maybe we should try to standardize our range / position / offset usage across the codebase, it's a pain to keep converting back and forth).
                const model = this.editor.getModel()!;
                const p = model.getPositionAt(offset);
                const word = model.getWordUntilPosition(p);
                const range = new Range(p.lineNumber, word.startColumn, p.lineNumber, word.endColumn);
                return {
                    kind: isMethod ? 1 : 9,
                    label: label,
                    insertText: insertText,
                    filterText: insertText,
                    insertTextRules: 4,
                    sortText: ("" + index).padStart(indexStrLen, '0'),
                    detail: candidate.type,
                    range: range
                };
            });
            return {suggestions: completionResults}
        })
    }

    requestSignatureHelp(offset: number): Promise<SignatureHelpResult> {
        return new Promise<SignatureHint>((resolve, reject) => {
            this.notebookState.state.activeSignature?.reject() // remove previous active signature if present.
            return this.notebookState.updateField("activeSignature", () => setValue({cellId: this.id, offset, resolve, reject}))
        }).then(({cell, offset, signatures}) => {
            let sigHelp: SignatureHelp;
            if (signatures) {
                sigHelp = {
                    activeParameter: signatures.activeParameter,
                    activeSignature: signatures.activeSignature,
                    signatures: signatures.hints.map(sig => {
                        const params = sig.parameters.map(param => {
                            return {
                                label: param.typeName ? `${param.name}: ${param.typeName}` : param.name,
                                documentation: param.docString || undefined
                            }
                        });

                        return {
                            documentation: sig.docString || undefined,
                            label: sig.name,
                            parameters: params
                        }
                    })
                }
            } else {
                sigHelp = {activeSignature: 0, activeParameter: 0, signatures: []}
            }

            return {
                value: sigHelp,
                dispose(): void {}
            }
        })
    }

    private setErrorMarkers(error: RuntimeError | CompileErrors, markers: IMarkerData[]) {
        if (this.errorMarkers.find(e => deepEquals(e.error, error)) === undefined) {
            this.setModelMarkers(markers)
            this.errorMarkers.push({error, markers})
        }
    }

    private removeErrorMarker(marker: ErrorMarker) {
        this.errorMarkers = this.errorMarkers.filter(m => m !== marker)
        this.setModelMarkers(this.errorMarkers.flatMap(m => m.markers))
        if (marker.error instanceof RuntimeError) {
            this.cellState.updateField("runtimeError", () => setValue(undefined))
        } else {
            this.cellState.updateField("compileErrors", errs => removeFromArray(errs, marker.error as CompileErrors, deepEquals))
        }
    }

    private clearErrorMarkers(type?: "runtime" | "compiler") {
        const remove = this.errorMarkers.filter(marker => (type === "runtime" && marker.error instanceof RuntimeError) || (type === "compiler" && (marker.error as any).reports) || type === undefined )
        remove.forEach(marker => this.removeErrorMarker(marker))
    }

    private toggleCode() {
        this.cellState.updateField("metadata", prevMetadata => setValue(prevMetadata.copy({hideSource: !prevMetadata.hideSource})))
    }

    private toggleOutput() {
        this.cellState.updateField("metadata", prevMetadata => setValue(prevMetadata.copy({hideOutput: !prevMetadata.hideOutput})))
    }

    private copyOutput() {
        const maybeOutput = collect(this.cellState.state.output, output => output.contentType.startsWith('text/plain') ? output.content.join('') : undefined)
        if (maybeOutput.length > 0) {
            const content = maybeOutput.join('')
            copyToClipboard(content, this.notebookState)
        } else if (this.cellState.state.results.length > 0) {
            const maybeTextResults = collect(this.cellState.state.results, result => {
                if (result instanceof ResultValue) {
                    const maybeMIMERepr = result.reprs.find(repr => repr instanceof MIMERepr && repr.mimeType === "text/plain")
                    if (maybeMIMERepr instanceof MIMERepr) {
                        return maybeMIMERepr.content
                    } else {
                        const stringRepr = result.reprs.find(repr => repr instanceof StringRepr)
                        return stringRepr instanceof StringRepr ? stringRepr.string : undefined
                    }
                } else return undefined
            })
            const content = maybeTextResults.join('')
            copyToClipboard(content, this.notebookState)

        }
    }

    /**
     * Update the layout of the editor and cell. Height and Width are treated differently:
     *      The height of the editor and cell expands to fit the text.
     *          So, height data goes from editor -> cell.
     *          No vertical scrollbar should be visible.
     *      The width of the editor is based on the width of the cell, which is determined by the space available in the viewport.
     *          So, width data goes from cell -> editor.
     *          A horizontal scrollbar is necessary if the text content overflows.
     */
    layout() {
        const editorLayout = this.editor.getLayoutInfo();
        // set the height to the height of the text content. If there's a scrollbar, give it some room so it doesn't cover any text
        const lineHeight = this.editor.getOption(editor.EditorOption.lineHeight);
        // if the editor height is less than one line we need to initialize the height with both the content height as well as the horizontal scrollbar height.
        const shouldAddScrollbarHeight = editorLayout.height < lineHeight;
        const height = this.editor.getContentHeight() + (shouldAddScrollbarHeight ? editorLayout.horizontalScrollbarHeight : 0);
        // the editor width is determined by the container's width.
        const width = this.editorEl.clientWidth;

        // Update height and width on the editor.
        this.editor.layout({width, height});

        // update overflow widget node
        const editorNode = this.editor.getDomNode()
        if (editorNode) {
            const r = editorNode.getBoundingClientRect()
            this.overflowDomNode.style.top = r.top + "px";
            this.overflowDomNode.style.left = r.left + "px";
            this.overflowDomNode.style.height = r.height + "px"
            this.overflowDomNode.style.width = r.width + "px"
        }

    }

    private setExecutionInfo(el: TagElement<"div">, executionInfo: ExecutionInfo) {
        const start = new Date(Number(executionInfo.startTs));
        // clear display
        el.innerHTML = '';
        window.clearInterval(this.execDurationUpdater);
        delete this.execDurationUpdater;

        // populate display
        el.appendChild(span(['exec-start'], [start.toLocaleString("en-US", {timeZoneName: "short"})]));
        el.classList.add('output');

        if (this.state.running || executionInfo.endTs) {
            const endTs = executionInfo.endTs ?? Date.now();
            const duration = Number(endTs) - Number(executionInfo.startTs);
            el.appendChild(span(['exec-duration'], [prettyDuration(duration)]));

            if (executionInfo.endTs === undefined || executionInfo.endTs === null) {
                // update exec info every so often
                if (this.execDurationUpdater === undefined) {
                    this.execDurationUpdater = window.setInterval(() => this.setExecutionInfo(el, executionInfo), 333)
                }
            }
        }
    }

    private applyEdits(edits: ContentEdit[]) {
        // can't listen to these edits or they'll be sent to the server again
        // TODO: is there a better way to silently apply these edits? This seems like a hack; only works because of
        //       single-threaded JS, which I don't know whether workers impact that assumption (JS)
        this.applyingServerEdits = true;

        try {
            const monacoEdits: IIdentifiedSingleEditOperation[] = [];
            const model = this.editor.getModel()!;
            edits.forEach((edit) => {
                if (edit.isEmpty()) {
                    return;
                }

                const pos = model.getPositionAt(edit.pos);
                if (edit instanceof Delete) {
                    const endPos = model.getPositionAt(edit.pos + edit.length);
                    monacoEdits.push({
                        range: new monaco.Range(pos.lineNumber, pos.column, endPos.lineNumber, endPos.column),
                        text: null
                    });
                } else if (edit instanceof Insert) {
                    monacoEdits.push({
                        range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column),
                        text: edit.content,
                        forceMoveMarkers: true
                    });
                }
            });

            //this.editor.getModel().applyEdits(monacoEdits);
            // TODO: above API call won't put the edits on the undo stack. Should other people's edits go on my undo stack?
            //       below calls implement that. It is weird to have other peoples' edits in your undo stack, but it
            //       also gets weird when they aren't – your undos get out of sync with the document.
            //       Maybe there's something different that can be done with undo stops – I don't really know what they
            //       are because it's not well documented (JS)
            this.editor.pushUndoStop();
            this.editor.executeEdits("Anonymous", monacoEdits);
            this.editor.pushUndoStop();
            // TODO: should show some UI thing to indicate whose edits they are, rather than just having them appear
        } finally {
            this.applyingServerEdits = false;
        }
        // this.editListener = this.editor.onDidChangeModelContent(event => this.onChangeModelContent(event));
    }

    protected keyAction(key: string, pos: IPosition, range: IRange, selection: string) {
        if (this.state.metadata.hideSource) { // if the source is hidden, this acts just like any other cell.
            return super.keyAction(key, pos, range, selection)
        } else {
            const ifNoSuggestion: (fun: () => (PostKeyAction[] | undefined | void)) => () => (PostKeyAction[] | undefined) = (fun: () => (PostKeyAction[] | undefined)) => () => {
                // this is really ugly, is there a better way to tell whether the widget is visible??
                const suggestionsVisible = this.editor._contextKeyService.getContextKeyValue("suggestWidgetVisible")
                if (!suggestionsVisible) { // don't do stuff when suggestions are visible
                    return fun() ?? undefined
                } else {
                    return undefined
                }
            }
            return matchS<PostKeyAction[]>(key)
                .when("MoveUp", ifNoSuggestion(() => {
                    if (!selection && pos.lineNumber <= range.startLineNumber && pos.column <= range.startColumn) {
                        this.selectOrInsertCell("above")
                        return ["stopPropagation"]
                    }
                    return undefined
                }))
                .when("MoveUpK", ifNoSuggestion(() => {
                    if (!this.vim?.state.vim.insertMode) {
                        if (!selection && pos.lineNumber <= range.startLineNumber && pos.column <= range.startColumn) {
                            this.notebookState.selectCell(this.id, {relative: "above", editing: true})
                            return ["stopPropagation"]
                        }
                    }
                    return undefined
                }))
                .when("MoveDown", ifNoSuggestion(() => {
                    let lastColumn = range.endColumn;
                    if (this.vim && !this.vim.state.vim.insertMode) { // in normal/visual mode, the last column is never selected.
                        lastColumn -= 1
                    }
                    if (!selection && pos.lineNumber >= range.endLineNumber && pos.column >= lastColumn) {
                        this.selectOrInsertCell("below")
                        return ["stopPropagation"]
                    }
                    return undefined
                }))
                .when("MoveDownJ", ifNoSuggestion(() => {
                    if (!this.vim?.state.vim.insertMode) { // in normal/visual mode, the last column is never selected.
                        let lastColumn = range.endColumn - 1;
                        if (!selection && pos.lineNumber >= range.endLineNumber && pos.column >= lastColumn) {
                            this.notebookState.selectCell(this.id, {relative:"below", editing: true})
                            return ["stopPropagation"]
                        }
                    }
                    return undefined
                }))
                .when("SelectPrevious", ifNoSuggestion(() => {
                    this.notebookState.selectCell(this.id, {relative: "above", editing: true})
                }))
                .when("SelectNext", ifNoSuggestion(() => {
                    this.notebookState.selectCell(this.id, {relative: "below", editing: true})
                }))
                .when("InsertAbove", ifNoSuggestion(() => {
                    this.notebookState.insertCell("above").then(id => this.notebookState.selectCell(id))
                }))
                .when("InsertBelow", ifNoSuggestion(() => {
                    this.notebookState.insertCell("below").then(id => this.notebookState.selectCell(id))
                }))
                .when("Delete", ifNoSuggestion(() => {
                    this.notebookState.deleteCell()
                }))
                .when("RunAll", ifNoSuggestion(() => {
                    this.dispatcher.runCells([])
                }))
                .when("RunToCursor", ifNoSuggestion(() => {
                    this.dispatcher.runToActiveCell()
                }))
                .otherwise(() => super.keyAction(key, pos, range, selection)) ?? undefined
        }
    }

    getPosition() {
        return this.editor.getPosition()!;
    }

    getRange() {
        return this.editor.getModel()!.getFullModelRange();
    }

    getCurrentSelection() {
        return this.editor.getModel()!.getValueInRange(this.editor.getSelection()!)
    }

    get path() {
        return this.notebookState.state.path
    }

    get markerOwner() {
        return `${this.path}-${this.id}`
    }

    getModelMarkers(): editor.IMarker[] | undefined {
        const model = this.editor.getModel()
        return model ? monaco.editor.getModelMarkers({resource: model.uri}) : undefined
    }

    setModelMarkers(markers: IMarkerData[], owner: string = this.markerOwner): void {
        const model = this.editor.getModel()
        if (model) {
            monaco.editor.setModelMarkers(model, owner, markers)
        }
    }

    protected onSelected() {
        super.onSelected()
        this.vim = VimStatus.get.activate(this.editor)
    }

    protected onDeselected() {
        super.onDeselected();
        this.commentHandler.hide()
        // hide parameter hints on blur
        this.editor.trigger('keyboard', 'closeParameterHints', null);
    }

    setDisabled(disabled: boolean) {
        this.editor.updateOptions({readOnly: disabled});
        if (disabled) {
            this.cellInputTools.classList.add("disabled")
        } else {
            this.cellInputTools.classList.remove("disabled")
        }
    }

    delete() {
        super.delete()
        VimStatus.get.deactivate(this.editor.getId())
    }

    protected calculateHash(maybeSelection?: Range): URL {
        const currentURL = super.calculateHash(maybeSelection);

        const model = this.editor.getModel()
        if (model && maybeSelection && !maybeSelection.isEmpty()) {
            const pos = PosRange.fromRange(maybeSelection, model)
            currentURL.hash += `,${pos.rangeStr}`
        }
        return currentURL
    }
}

type CellErrorMarkers = editor.IMarkerData & { originalSeverity: MarkerSeverity}

class CodeCellOutput extends Disposable {
    readonly el: TagElement<"div">;
    private stdOutEl: MIMEElement | null;
    private stdOutLines: number;
    private stdOutDetails: TagElement<"details"> | null;
    private readonly cellOutputDisplay: TagElement<"div">;
    private readonly cellResultMargin: TagElement<"div">;
    private readonly cellOutputTools: TagElement<"div">;
    private readonly resultTabs: TagElement<"div">;
    private cellErrorDisplay?: TagElement<"div">;

    private displaying: Promise<void> = Promise.resolve();

    constructor(
        private notebookState: NotebookStateHandler,
        private cellState: StateView<CellState>,
        private cellId: string,
        compileErrorsHandler: StateView<CompileErrors[]>,
        runtimeErrorHandler: StateView<RuntimeError | undefined>,
        private copyOutputButton?: TagElement<"button">) {
        super()

        const outputHandler = cellState.view("output").disposeWith(this);
        const resultsHandler = cellState.view("results").disposeWith(this);

        this.el = div(['cell-output'], [
            div(['cell-output-margin'], []),
            // unfortunately we need this extra div for perf reasons (has to be a block)
            div(['cell-output-block'], [
                div(['cell-output-container'], [
                    this.cellOutputDisplay = div(['cell-output-display'], []),
                ])
            ]),
            this.cellResultMargin = div(['cell-result-margin'], []),
            this.cellOutputTools = div(['cell-output-tools'], [
                this.resultTabs = div(["result-tabs"], []),
            ]),
        ]);

        const handleResults = (results: (ClientResult | ResultValue)[], updateResult?: UpdateResult<(ClientResult | ResultValue)[]>) => {
            if (!updateResult || updateResult.update instanceof SetValue) {
                this.clearResults().then(() => this.displayResults(results));
            } else if (updateResult.addedValues) {
                this.displayResults(Object.values(updateResult.addedValues));
            }
        }
        handleResults(resultsHandler.state)
        resultsHandler.addObserver(handleResults);

        compileErrorsHandler.addObserver(errors => this.setErrors(errors));

        this.setRuntimeError(runtimeErrorHandler.state)
        runtimeErrorHandler.addObserver(error => {
            this.setRuntimeError(error)
        });

        const handleOutput = (outputs: Output[], result?: UpdateResult<Output[]>) => {
            if (!result || result.update instanceof SetValue) {
                this.clearOutput();
                outputs.forEach(o => this.addOutput(o.contentType, o.content.join('')));
            } else if (result.addedValues) {
                for (const output of Object.values(result.addedValues)) {
                    this.addOutput(output.contentType, output.content.join(''))
                }
            }
        }
        handleOutput(outputHandler.state);
        outputHandler.addObserver(handleOutput);
    }

    private displayResults(results: (ResultValue | ClientResult)[]): Promise<void> {
        const displayResult: (result: ResultValue | ClientResult) => Promise<void> = (result) => {
            if (result instanceof ResultValue) {
                // clear results
                this.resultTabs.innerHTML = '';

                if (result.name === 'Out' && result.reprs.length > 0) {
                    let inspectIcon: TagElement<"button">[] = [];
                    if (result.reprs.length > 1) {
                        inspectIcon = [
                            iconButton(['inspect'], 'Inspect', 'search', 'Inspect').click(
                                evt => {
                                    this.notebookState.insertInspectionCell(result)
                                }
                            )
                        ]
                    }

                    const outLabel = div(['out-ident', 'with-reprs'], [...inspectIcon, 'Out:']);
                    this.cellResultMargin.innerHTML = '';
                    this.cellResultMargin.appendChild(outLabel);

                    return this.displayRepr(result).then(display => {
                        const [mime, content] = display;
                        const [mimeType, args] = parseContentType(mime);
                        if (this.copyOutputButton) {
                            if (mimeType === "text/plain") {
                                this.copyOutputButton.style.display = "unset";
                            } else {
                                if (!this.hasOutput) {
                                    this.copyOutputButton.style.display = "none";
                                }
                            }
                        }
                        this.buildOutput(mime, args, content).then((el: MIMEElement) => {
                            this.resultTabs.appendChild(el);
                            el.dispatchEvent(new CustomEvent('becameVisible'));
                            this.cellOutputTools.classList.add('output');
                        })
                    }).then(() => {})
                } else {
                    return Promise.resolve();
                }
            } else {
                this.cellOutputTools.classList.add('output');
                this.resultTabs.innerHTML = '';
                this.clearOutput();
                result.display(this.cellOutputDisplay);
                return Promise.resolve();
            }
        }

        this.displaying = results.reduce((accum, next) => accum.then(() => displayResult(next)), this.displaying);
        return this.displaying;
    }

    private clearResults(): Promise<void> {
        this.displaying = this.displaying.then(() => {
            this.resultTabs.innerHTML = '';
            this.cellResultMargin.innerHTML = '';
        });
        return this.displaying;
    }

    private displayRepr(result: ResultValue): Promise<[string, string | DocumentFragment]> {
        // We're searching for the best MIME type and representation for this result by going in order of most to least
        // useful (kind of arbitrarily defined...)
        // TODO: make this smarter
        // TODO: for lazy data repr, inform that it can't be displayed immediately

        let index = -1;

        // First, check to see if there's a special DataRepr or StreamingDataRepr
        index = result.reprs.findIndex(repr => repr instanceof DataRepr);
        if (index >= 0) {
            return monaco.editor.colorize(result.typeName, "scala", {}).then(typeHTML => {
                const dataRepr = result.reprs[index] as DataRepr;
                const frag = document.createDocumentFragment();
                const resultType = span(['result-type'], []).attr("data-lang" as any, "scala");
                resultType.innerHTML = typeHTML;
                frag.appendChild(div([], [
                    h4(['result-name-and-type'], [span(['result-name'], [result.name]), ': ', resultType]),
                    displayData(dataRepr.dataType.decodeBuffer(new DataReader(dataRepr.data)), undefined, 1)
                ]));
                return ["text/html", frag];
            })
        }

        index = result.reprs.findIndex(repr => repr instanceof StreamingDataRepr);
        if (index >= 0) {
            const repr = result.reprs[index] as StreamingDataRepr;
            // surprisingly using monaco.editor.colorizeElement breaks the theme of the whole app! WAT?
            return monaco.editor.colorize(result.typeName, this.cellState.state.language, {}).then(typeHTML => {
                const frag = document.createDocumentFragment();
                const resultType = span(['result-type'], []).attr("data-lang" as any, "scala");
                resultType.innerHTML = typeHTML;
                // Why do they put a <br> in there?
                [...resultType.getElementsByTagName("br")].forEach(br => {
                    br?.parentNode?.removeChild(br)
                });

                const el = div([], [
                    h4(['result-name-and-type'], [
                        span(['result-name'], [result.name]), ': ', resultType,
                        iconButton(['view-data'], 'View data', 'table', '[View]')
                            .click(_ => this.notebookState.insertInspectionCell(result, 'table')),
                        repr.dataType instanceof StructType
                            ? iconButton(['plot-data'], 'Plot data', 'chart-bar', '[Plot]')
                                .click(_ => {
                                    this.notebookState.insertInspectionCell(result, 'plot')
                                })
                            : undefined
                    ]),
                    repr.dataType instanceof StructType ? displaySchema(repr.dataType) : undefined
                ]);
                frag.appendChild(el);
                return ["text/html", frag];
            })
        }

        const preferredMIME = result.preferredMIMERepr;
        if (preferredMIME) {
            return Promise.resolve(MIMERepr.unapply(preferredMIME));
        }

        // just give up and show some plaintext...
        return Promise.resolve(["text/plain", result.valueText]);
    }

    private setErrors(errors?: CompileErrors[]) {
        this.clearErrors()
        if (errors && errors.length > 0 && errors[0].reports.length > 0 ) {
            errors.forEach(error => {
                const reportInfos = error.reports;
                if (reportInfos.length > 0) {
                    this.cellOutputDisplay.classList.add('errors');
                    const compileError = div(
                        ['errors'],
                        reportInfos.map((report) => {
                            const severity = (['Info', 'Warning', 'Error'])[report.severity];
                            const el = blockquote(['error-report', severity], [
                                span(['severity'], [severity]),
                                span(['message'], [report.message]),
                                ' '
                            ]);

                            if (report.position) {
                                const lineNumber = linePosAt(this.cellState.state.content, report.position.point)
                                el.appendChild(span(['error-link'], [`(Line ${lineNumber})`]))
                            }

                            return el;
                        }));
                    if (this.cellErrorDisplay === undefined) {
                        this.cellErrorDisplay = compileError;
                        this.cellOutputDisplay.appendChild(this.cellErrorDisplay)
                    } else {
                        this.cellErrorDisplay.replaceWith(compileError)
                        this.cellErrorDisplay = compileError;
                    }
                }
            })
        }
    }

    setRuntimeError(error?: RuntimeError) {
        if (error) {
            const errorEl = ErrorEl.fromServerError(error.error, this.cellId)
            const runtimeError = div(['errors'], [blockquote(['error-report', 'Error'], [errorEl.el])]).click(e => e.stopPropagation());
            if (this.cellErrorDisplay === undefined) {
                this.cellErrorDisplay = runtimeError;
                this.cellOutputDisplay.appendChild(this.cellErrorDisplay)
            } else {
                this.cellErrorDisplay.replaceWith(runtimeError)
                this.cellErrorDisplay = runtimeError;
            }
        } else {
            this.clearErrors()
        }
    }

    clearOutput() {
        this.clearErrors();
        this.cellOutputDisplay.classList.remove('output');
        this.cellOutputTools.classList.remove('output');
        [...this.cellOutputDisplay.children].forEach(child => this.cellOutputDisplay.removeChild(child));
        this.cellOutputDisplay.innerHTML = "";
        this.stdOutEl = null;
        this.stdOutDetails = null;
        if (this.copyOutputButton) this.copyOutputButton.style.display = 'none';
    }

    get hasOutput() {
        return this.cellOutputDisplay.classList.contains("output")
    }

    private clearErrors() {
        this.cellOutputDisplay.classList.remove('errors');
        if (this.cellErrorDisplay) {
            this.cellOutputDisplay.removeChild(this.cellErrorDisplay)
        }
        this.cellErrorDisplay = undefined;
    }

    private buildOutput(mimeType: string, args: Record<string, string>, content: string | DocumentFragment): Promise<HTMLElement> {
        return displayContent(mimeType, content, args).then(
            (result: TagElement<any>) => mimeEl(mimeType, args, result).listener('becameVisible', () => result.dispatchEvent(new CustomEvent('becameVisible')))
        ).catch(function(err: any) {
            return div(['output'], err);
        });
    }


    private addOutput(contentType: string, content: string) {
        const [mimeType, args] = parseContentType(contentType);
        this.cellOutputDisplay.classList.add('output');

        if (this.copyOutputButton && mimeType === "text/plain") this.copyOutputButton.style.display = "unset";

        if (mimeType === 'text/plain' && args.rel === 'stdout') {
            // first, strip ANSI control codes
            // TODO: we probably want to parse & render the colors, but it would complicate things at the moment
            //       given that the folding logic relies on text nodes and that would require using elements
            content = content.replace(/\u001b\[\d+m/g, '');


            // if there are too many lines, fold some
            const lines = content.split(/\n/g);


            if (! this.stdOutEl?.parentNode) {
                this.stdOutEl = mimeEl(mimeType, args, "");
                this.stdOutLines = lines.length;
                this.cellOutputDisplay.appendChild(this.stdOutEl);
            } else {
                this.stdOutLines += lines.length - 1;
            }

            if (this.stdOutLines > 12) { // TODO: user-configurable number?

                const splitAtLine = (textNode: Text, line: number) => {
                    const lf = /\n/g;
                    const text = textNode.nodeValue || "";
                    let counted = 1;
                    let splitPos = 0;
                    while (counted < line) {
                        counted++;
                        const match = lf.exec(text);
                        if (match === null) {
                            return null; // TODO: What do we do if this happens?
                        }
                        splitPos = match.index + 1;
                    }
                    return textNode.splitText(splitPos);
                };

                // fold all but the first 5 and last 5 lines into an expandable thingy
                const numHiddenLines = this.stdOutLines - 11;
                if (! this.stdOutDetails?.parentNode) {
                    this.stdOutDetails = tag('details', [], {}, [
                        tag('summary', [], {}, [span([], '')])
                    ]);

                    // collapse into single node
                    this.stdOutEl.normalize();
                    // split the existing text node into first 5 lines and the rest
                    let textNode = this.stdOutEl.childNodes[0] as Text;
                    if (!textNode) {
                        textNode = document.createTextNode(content);
                        this.stdOutEl.appendChild(textNode);
                    } else {
                        // add the current content to the text node before folding
                        textNode.nodeValue += content;
                    }
                    const hidden = splitAtLine(textNode, 6);
                    if (hidden) {
                        const after = splitAtLine(hidden, numHiddenLines);

                        this.stdOutDetails.appendChild(hidden);
                        this.stdOutEl.insertBefore(this.stdOutDetails, after);
                    }
                } else {
                    const textNode = this.stdOutDetails.nextSibling! as Text;
                    textNode.nodeValue += content;
                    const after = splitAtLine(textNode, lines.length);
                    if (after) {
                        this.stdOutDetails.appendChild(textNode);
                        this.stdOutEl.appendChild(after);
                    }
                }
                // update summary
                this.stdOutDetails.querySelector('summary span')!.setAttribute('line-count', numHiddenLines.toString());
            } else {
                // no folding (yet) - append to the existing stdout container
                this.stdOutEl.appendChild(document.createTextNode(content));
            }

            // collapse the adjacent text nodes
            this.stdOutEl.normalize();

            // handle carriage returns in the last text node – they erase back to the start of the line
            const lastTextNode = [...this.stdOutEl.childNodes].filter(node => node.nodeType === 3).pop();
            if (lastTextNode) {
                const eat = /^(?:.|\r)+\r(.*)$/gm; // remove everything before the last CR in each line
                lastTextNode.nodeValue = lastTextNode.nodeValue!.replace(eat, '$1');
            }

        } else {
            this.buildOutput(mimeType, args, content).then((el: MIMEElement) => {
                this.cellOutputDisplay.appendChild(el);
                el.dispatchEvent(new CustomEvent('becameVisible'));
                // <script> tags won't be executed when they come in through `innerHTML`. So take them out, clone them, and
                // insert them as DOM nodes instead
                const scripts = el.querySelectorAll('script');
                scripts.forEach(script => {
                    const clone = document.createElement('script');
                    while (script.childNodes.length > 0) {
                        clone.appendChild(script.removeChild(script.childNodes[0]));
                    }
                    [...script.attributes].forEach(attr => clone.setAttribute(attr.name, attr.value));
                    script.replaceWith(clone);
                });
            })
        }
    }
}


export class TextCell extends Cell {
    private editor: RichTextEditor;
    private lastContent: string;
    private listeners: [string, (evt: Event) => void][];

    constructor(dispatcher: NotebookMessageDispatcher, notebookState: NotebookStateHandler, stateHandler: StateHandler<CellState>) {
        super(dispatcher, notebookState, stateHandler)

        const editorEl = div(['cell-input-editor', 'markdown-body'], [])

        const content = stateHandler.state.content;
        this.editor = new RichTextEditor(editorEl, content)
        this.lastContent = content;

        this.el = div(['cell-container', 'text-cell'], [
            div(['cell-input'], [editorEl])
        ])

        this.listeners = [
            ['focus', () => {
                this.doSelect();
            }],
            ['blur', () => {
                this.doDeselect();
            }],
            ['input', (evt: KeyboardEvent) => this.onInput()]
        ]
        this.listeners.forEach(([k, fn]) => {
            this.editor.element.addEventListener(k, fn);
        })

        this.onDispose.then(() => {
            this.listeners.forEach(([k, fn]) => {
                this.editor.element.removeEventListener(k, fn)
            })
        })
    }

    // not private because it is also used by latex-editor
    onInput() {
        const newContent = this.editor.markdownContent;
        const edits: ContentEdit[] = diffEdits(this.lastContent, newContent)
        this.lastContent = newContent;

        if (edits.length > 0) {
            //console.log(edits);
            this.cellState.updateField("content", () => editString(edits))
        }
    }

    // Note: lines in contenteditable are inherently weird, don't rely on this for anything aside from beginning and end
    getPosition() {
        // get selection
        const selection = document.getSelection()!;
        // ok, now we just care about the current cursor location
        let selectedNode = selection.focusNode;
        let selectionCol = selection.focusOffset;
        if (selectedNode === this.editor.element) { // this means we need to find the selected node using the offset
            selectedNode = this.editor.element.childNodes[selectionCol];
            selectionCol = 0;
        }
        // ok, now which line number?
        let selectionLineNum = -1;
        const contentNodes = this.editor.contentNodes;
        contentNodes.forEach((node, idx) => {
            if (node === selectedNode || node.contains(selectedNode)) {
                selectionLineNum = idx;
            }
        });
        return {
            lineNumber: selectionLineNum + 1, // lines start at 1 like Monaco
            column: selectionCol
        };
    }

    getRange() {
        const contentLines = this.editor.contentNodes

        const lastLine = contentLines[contentLines.length - 1]?.textContent || "";

        return {
            startLineNumber: 1, // start at 1 like Monaco
            startColumn: 0,
            endLineNumber: contentLines.length,
            endColumn: lastLine.length
        };

    }

    getCurrentSelection() {
        return document.getSelection()!.toString()
    }

    protected keyAction(key: string, pos: IPosition, range: IRange, selection: string) {
        return matchS<PostKeyAction[]>(key)
            .when("MoveUp", () => {
                if (!selection && pos.lineNumber <= range.startLineNumber && pos.column <= range.startColumn) {
                    this.notebookState.selectCell(this.id, {relative: "above", editing: true})
                }
            })
            .when("MoveUpK", () => {
                // do nothing
            })
            .when("MoveDown", () => {
                if (!selection && pos.lineNumber >= range.endLineNumber && pos.column >= range.endColumn) {
                    this.notebookState.selectCell(this.id, {relative: "below", editing: true})
                }
            })
            .when("MoveDownJ", () => {
                // do nothing
            })
            .otherwise(() => super.keyAction(key, pos, range, selection)) ?? undefined
    }

    protected onSelected() {
        super.onSelected()
        this.editor.focus()
    }

    setDisabled(disabled: boolean) {
        this.editor.disabled = disabled
    }
}


export class VizCell extends Cell {

    private editor: VizSelector;
    private editorEl: TagElement<'div'>;
    private cellInputTools: TagElement<'div'>;
    private execDurationUpdater?: number;
    private viz: Viz;
    private cellOutput: CodeCellOutput;

    private valueName: string;
    private resultValue?: ResultValue;
    private previousViews: Record<string, [Viz, Output | ClientResult]> = {};
    private copyCellOutputBtn: TagElement<"button">;
    private convertToVegaBtn: TagElement<"button">;

    constructor(dispatcher: NotebookMessageDispatcher, _notebookState: NotebookStateHandler, _cellState: StateHandler<CellState>) {
        super(dispatcher, _notebookState, _cellState);
        const cellState = this.cellState;
        const notebookState = this.notebookState;

        const initialViz = parseMaybeViz(this.cellState.state.content);
        if (isViz(initialViz)) {
            this.viz = initialViz;
            this.valueName = this.viz.value;
        } else if (initialViz && initialViz.value) {
            this.valueName = initialViz.value;
        } else {
            console.error("No value defined for viz cell!")
            this.cellState.updateField("compileErrors", () => setValue([new CompileErrors([
                new KernelReport(
                    new Position(`Cell ${this.id}`, 0, 0, 0),
                    "No value defined for viz cell! This should never happen. If you see this error, please report it to the Polynote team along with the contents of your browser console. Thank you!",
                    2)
            ])]))
        }

        this.editorEl = div(['viz-selector', 'loading'], 'Notebook is loading...');

        if (cellState.state.output.length > 0) {
            this.previousViews[this.viz.type] = [this.viz, cellState.state.output[0]];
        }

        // TODO: extract the common stuff with CodeCell

        const execInfoEl = div(["exec-info"], []);

        this.copyCellOutputBtn = iconButton(['copy-output'], 'Copy Output to Clipboard', 'copy', 'Copy Output to Clipboard').click(() => this.copyOutput())
        this.copyCellOutputBtn.style.display = "none";

        this.cellOutput = new CodeCellOutput(this.notebookState, cellState, this.cellId, cellState.view("compileErrors"), cellState.view("runtimeError"), this.copyCellOutputBtn);

        // after the cell is run, cache the viz and result and hide input
        cellState.view('results').addObserver(results => {
            const result = findInstance(results, ClientResult);
            if (result) {
                const viz = deepCopy(this.viz);
                // don't cache a "preview" plot view, only a real one.
                if (viz.type !== 'plot' || result instanceof VegaClientResult) {
                    result.toOutput().then(output => this.previousViews[viz.type] = [viz, output]);
                }
            }
        }).disposeWith(this);

        this.el = div(['cell-container', this.state.language, 'code-cell'], [
            div(['cell-input'], [
                this.cellInputTools = div(['cell-input-tools'], [
                    iconButton(['run-cell'], 'Run this cell (only)', 'play', 'Run').click((evt) => {
                        this.hideCodeAfterSuccess();
                        dispatcher.runCells([this.state.id]);
                    }),
                    div(['cell-label'], [this.state.id.toString()]),
                    div(['value-name'], ['Inspecting ', span(['name'], [this.valueName])]),
                    execInfoEl,
                    div(["options"], [
                        button(['toggle-code'], {title: 'Show/Hide Code'}, ['{}']).click(evt => this.toggleCode()),
                        this.copyCellOutputBtn,
                        this.convertToVegaBtn = button(['icon-button', 'convert-to-vega'], {title: "Convert to Vega cell"}, [
                            span(['icon'], img([], "static/style/icons/vega-logo.svg", "Vega"))
                        ]).click(() => this.convertToVega())
                    ])
                ]),
                this.editorEl
            ]),
            this.cellOutput.el
        ]);

        /**
         * Keep watching the available values, so the plot UI can be updated when the value changes.
         */
        const updateValues = (newValues: Record<string, ResultValue>) => {
            if (newValues && newValues[this.valueName] && newValues[this.valueName].live && newValues[this.valueName] !== this.resultValue) {
                this.setValue(newValues[this.valueName]);
                return true;
            }
            return false;
        }

        const watchValues = () => {
            this.notebookState.view("kernel").view("symbols").observeMapped(
                symbols => availableResultValues(symbols, this.notebookState.state.cellOrder, cellState.state.id),
                updateValues
            );

            this.notebookState.observeKey("cellOrder", (order, update) => updateValues(
                availableResultValues(this.notebookState.state.kernel.symbols, order, cellState.state.id)
            ));
        }

        if (notebookState.isLoading) {
            // wait until the notebook is done loading before populating the result, to make sure we have the result
            // and that it's the correct one.
            notebookState.loaded.then(() => {
                if (!updateValues(notebookState.availableValuesAt(cellState.state.id))) {
                    // notebook isn't live. We should wait for the value to become available.
                    // TODO: once we have metadata about which names a cell declares, we can be smarter about
                    //       exactly which cell to wait for. Right now, if the value was declared and then re-declared,
                    //       we'll get the first one (which could be bad). Alternatively, if we had stable cell IDs
                    //       (e.g. UUID) then the viz could refer to the stable source cell of the value being visualized.
                    if (this.editorEl.classList.contains('loading')) {
                        this.editorEl.innerHTML = `Waiting for value <code>${this.valueName}</code>...`;
                    }
                }
                watchValues();
            })
        } else {
            // If notebook is live & kernel is active, we should have the value right now.
            updateValues(notebookState.availableValuesAt(cellState.state.id));
            watchValues();
        }

        // watch metadata to show/hide code/output
        // TODO: this is duplicated from CodeCell; should extract a common base for code-like cells
        const updateMetadata = (metadata: CellMetadata) => {
            if (metadata.hideSource) {
                this.el.classList.add("hide-code")
            } else {
                this.el.classList.remove("hide-code");
                this.layout();
            }

            if (metadata.hideOutput) {
                this.el.classList.add("hide-output");
            } else {
                this.el.classList.remove("hide-output");
            }

            if (metadata.executionInfo) {
                this.setExecutionInfo(execInfoEl, metadata.executionInfo)
            }
        }
        updateMetadata(this.state.metadata);
        cellState.view("metadata").addObserver(metadata => updateMetadata(metadata));

        // Watch errors in order to
    }

    private hideCodeAfterSuccess(): void {
        const obs = this.cellState.observeKey("error", error => {
            obs.dispose()
            if (!error && this.cellState.state.results.length) {
                this.toggleCode(true);
            }
        });
    }

    // TODO: should be extracted from Viz and Code cells.
    private copyOutput() {
        const maybeOutput = collect(this.cellState.state.output, output => output.contentType.startsWith('text/plain') ? output.content.join('') : undefined)
        if (maybeOutput.length > 0) {
            const content = maybeOutput.join('')
            copyToClipboard(content, this.notebookState)
        }
    }

    private setValue(value: ResultValue): void {
        this.resultValue = value;
        this.viz = this.viz || parseMaybeViz(this.cellState.state.content);
        if (!isViz(this.viz)) {
            this.updateViz(this.selectDefaultViz(this.resultValue));
            this.valueName = this.viz!.value;
        }

        if (this.editor) {
            this.editor.tryDispose();
        }

        const editor = this.editor = new VizSelector(this.valueName, this.resultValue, this.dispatcher, this.notebookState, deepCopy(this.viz));

        // handle external edits
        this.cellState.view("content").addObserver((content, result, src) => {
            if (src !== this) {
                const newViz = parseMaybeViz(content);
                if (isViz(newViz)) {
                    editor.currentViz = newViz;
                }
            }
        });

        this.editor.onChange(viz => {
            // only show copy button for string results (should we show on any other results?)
            if (viz.type === "string" || viz.type === "mime" && viz.mimeType === "text/plain") {
                this.copyCellOutputBtn.style.display = "unset";
            } else {
                this.copyCellOutputBtn.style.display = "none";
            }
            this.updateViz(viz)
        });

        if (this.editorEl.parentNode) {
            this.editorEl.parentNode.replaceChild(this.editor.el, this.editorEl);
        }
        this.editorEl = this.editor.el;

        if (!this.cellState.state.output.length || this.viz.type !== 'plot') {
            const result = this.vizResult(this.viz);
            if (result) {
                this.previousViews[this.viz.type] = [this.viz, result];
                this.dispatcher.setCellOutput(this.cellState.state.id, result);
            }
        }

        this.onChangeViz();
    }

    private selectDefaultViz(resultValue: ResultValue): Viz {
        // select the default viz for the given result value
        // TODO: this should be preference-driven
        return match(resultValue.preferredRepr).typed<Viz>()
            .whenInstance(StreamingDataRepr, _ => ({ type: "schema", value: resultValue.name }))
            .whenInstance(DataRepr, _ => ({ type: "data", value: resultValue.name }))
            .whenInstance(MIMERepr, repr => ({ type: "mime", mimeType: repr.mimeType, value: resultValue.name }))
            // TODO: viz handling for lazy & updating
            .otherwise({ type: "string", value: resultValue.name })
    }

    private updateViz(viz: Viz) {
        const newCode = saveViz(viz);
        const oldViz = this.viz;
        const edits = diffEdits(this.cellState.state.content, newCode);
        if (edits.length > 0) {
            this.viz = deepCopy(viz);
            const cellId = this.cellState.state.id;
            this.cellState.updateField("content", () => editString(edits), this);
            if (this.previousViews[viz.type] && deepEquals(this.previousViews[viz.type][0], viz)) {
                const result = this.previousViews[viz.type][1];
                this.cellOutput.clearOutput();
                this.dispatcher.setCellOutput(cellId, result);
            } else if (this.resultValue) {
                const result = this.vizResult(viz);

                if (result) {
                    this.cellOutput.clearOutput();
                    this.dispatcher.setCellOutput(cellId, result);
                } else if (viz.type !== oldViz?.type) {
                    this.cellOutput.clearOutput();
                }
            }

        }
        this.onChangeViz();
    }

    private onChangeViz() {
        const viz = this.viz;
        if(viz.type === 'plot') {
            try {
                validatePlot(viz.plotDefinition);
                this.convertToVegaBtn.style.display = 'inline';
            } catch (err) {
                this.convertToVegaBtn.style.display = 'none';
            }
        } else {
            this.convertToVegaBtn.style.display = 'none';
        }
    }

    private convertToVega() {
        const viz = this.viz;
        const streaming = collectInstances(this.resultValue?.reprs ?? [], StreamingDataRepr)[0];
        if(viz.type === 'plot' && streaming && streaming.dataType instanceof StructType) {
            try {
                validatePlot(viz.plotDefinition);
                const vegaCode = plotToVegaCode(viz.plotDefinition, streaming.dataType);
                this.notebookState.insertCell("below", {id: this.id, language: "vega", metadata: new CellMetadata(), content: vegaCode})
                    .then(id => this.notebookState.selectCell(id));
            } catch (err) {
                this.convertToVegaBtn.style.display = 'none';
            }
        } else {
            this.convertToVegaBtn.style.display = 'none';
        }
    }

    private vizResult(viz: Viz): ClientResult | undefined {
        try {
            switch (viz.type) {
                case "table": return new MIMEClientResult(new MIMERepr("text/html", this.editor.tableHTML));
                case "plot": return undefined;
                default:
                    return collectInstances(
                        VizInterpreter.interpret(saveViz(viz), cellContext(this.notebookState, this.dispatcher, this.cellState.state.id)),
                        ClientResult)[0];
            }
        } catch (err) {
            console.log("error in vizResult", err)
        }
        return undefined;
    }

    protected getCurrentSelection(): string {
        return "";
    }

    protected getPosition(): IPosition {
        // plots don't really have a position.
        return {
            lineNumber: 0,
            column: 0
        };
    }

    protected getRange(): IRange {
        // plots don't really have a range
        return {
            startLineNumber: 0,
            startColumn: 0,
            endLineNumber: 0,
            endColumn: 0
        };
    }

    setDisabled(disabled: boolean): void {
        if (this.editor)
            this.editor.disabled = disabled;
    }

    private toggleCode(hide?: boolean) {
        this.cellState.updateField("metadata", meta => setValue(meta.copy({hideSource: hide ?? !meta.hideSource})));
    }

    private setExecutionInfo(el: TagElement<"div">, executionInfo: ExecutionInfo) {
        const start = new Date(Number(executionInfo.startTs));
        // clear display
        el.innerHTML = '';
        window.clearInterval(this.execDurationUpdater);
        delete this.execDurationUpdater;

        // populate display
        el.appendChild(span(['exec-start'], [start.toLocaleString("en-US", {timeZoneName: "short"})]));
        el.classList.add('output');

        if (this.state.running || executionInfo.endTs) {
            const endTs = executionInfo.endTs ?? Date.now();
            const duration = Number(endTs) - Number(executionInfo.startTs);
            el.appendChild(span(['exec-duration'], [prettyDuration(duration)]));

            if (executionInfo.endTs === undefined || executionInfo.endTs === null) {
                // update exec info every so often
                if (this.execDurationUpdater === undefined) {
                    this.execDurationUpdater = window.setInterval(() => this.setExecutionInfo(el, executionInfo), 333)
                }
            }
        }
    }

}


/**
 * Copy some text content to the clipboard.
 * @param content   The content to copy
 * @param nbState   Optionally, the NotebookStateHandler (purely to generate a Task for this process)
 */
export function copyToClipboard(content: string, notebookState?: NotebookStateHandler) {
    console.log("copying to clipboard! ", content)
    //TODO: use https://developer.mozilla.org/en-US/docs/Web/API/Clipboard/write once
    //      browser support has solidified.
    const task = new TaskInfo("Copy Task", "Copy Task", `Copying Cell ${notebookState?.state.activeCellId} Output`, TaskStatus.Queued, 0)
    notebookState?.updateField("kernel", () => ({tasks: updateProperty("Copy Task", task)}))
    navigator.clipboard.writeText(content)
        .then(() => {
            notebookState?.updateField("kernel", () => ({tasks: updateProperty("Copy Task", setValue({...task, progress: 25, status: TaskStatus.Running}))}))
            setTimeout(() => {
                notebookState?.updateField("kernel", () => ({tasks: updateProperty("Copy Task", setValue({...task, progress: 255, status: TaskStatus.Complete}))}))
            }, 333)
        })
        .catch(err => console.error("Error while writing to clipboard", err))
}
