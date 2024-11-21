import {
    Disposable,
    removeKey,
    StateHandler,
    StateView, UpdateResult,
} from "../../../state";
import {
    Content,
    div,
    h2,
    h3,
    h4,
    helpIconButton,
    icon,
    iconButton,
    para,
    span,
    table,
    TableElement,
    TableRowElement,
    TagElement
} from "../../tags";
import {NotebookMessageDispatcher, ServerMessageDispatcher,} from "../../../messaging/dispatcher";
import {KernelStatusString, TaskStatus} from "../../../data/messages";
import {ResultValue, ServerErrorWithCause} from "../../../data/result";
import {ErrorEl} from "../../display/error";
import {ServerStateHandler} from "../../../state/server_state";
import {KernelInfo, KernelState, KernelSymbols, KernelTasks, NotebookStateHandler} from "../../../state/notebook_state";
import {ViewPreferences} from "../../../state/preferences";
import {DisplayError, ErrorStateHandler} from "../../../state/error_state";
import {changedKeys, findInstance} from "../../../util/helpers";
import {remove} from "vega-lite/build/src/compositemark";
import * as monaco from "monaco-editor";
import {displayData, prettyDisplayData, prettyDisplayString} from "../../display/display_content";
import {DataReader} from "../../../data/codec";
import {DataRepr, StreamingDataRepr} from "../../../data/value_repr";
import {ValueInspector} from "../value_inspector";
import {copyToClipboard} from "./cell";

// TODO: this should probably handle collapse and expand of the pane, rather than the Kernel itself.
export class KernelPane extends Disposable {
    el: TagElement<"div">;
    header: TagElement<"div">;
    statusEl: TagElement<"div">;
    private kernels: Record<string, Kernel> = {};

    constructor(serverMessageDispatcher: ServerMessageDispatcher) {
        super()
        const placeholderEl = div(['kernel-ui-placeholder'], []);
        const placeholderStatus = div(['kernel-header-placeholder'], []);
        this.el = placeholderEl;
        this.statusEl = placeholderStatus;
        this.header = div(['ui-panel-header'], [this.statusEl]);

        const handleCurrentNotebook = (path?: string) => {
            if (path) {
                const of = ServerStateHandler.state.openFiles.find(of => of.path === path);
                if (of && of.type === 'notebook') {
                    const nbInfo = ServerStateHandler.getOrCreateNotebook(path);
                    // the notebook should already be loaded
                    if (nbInfo?.info) {
                        if (this.kernels[path] === undefined) {
                            const kernel = new Kernel(
                                serverMessageDispatcher,
                                nbInfo.info.dispatcher,
                                nbInfo.handler,
                                'rightPane');
                            kernel.onDispose.then(() => delete this.kernels[path])
                            this.kernels[path] = kernel;
                        }
                        const kernel = this.kernels[path];
                        document.getElementsByClassName('split-view')[0]?.classList?.remove('no-kernel');
                        this.el.replaceWith(kernel.el);
                        this.el = kernel.el

                        this.statusEl.replaceWith(kernel.statusEl);
                        this.statusEl = kernel.statusEl;
                    } else {
                        console.warn("Requested notebook at path", path, "but it wasn't loaded. This is unexpected...")
                    }
                } else {
                    // no notebook selected
                    // TODO: keep task component around for errors?
                    document.getElementsByClassName('split-view')[0]?.classList?.add('no-kernel');
                    this.el.replaceWith(placeholderEl);
                    this.el = placeholderEl

                    this.statusEl.replaceWith(placeholderStatus);
                    this.statusEl = placeholderStatus;
                }
            }
        }
        handleCurrentNotebook(ServerStateHandler.state.currentNotebook)
        ServerStateHandler.get.view("currentNotebook").addObserver(path => handleCurrentNotebook(path)).disposeWith(this)
    }

}

export class Kernel extends Disposable {
    readonly el: TagElement<"div">;
    readonly statusEl: TagElement<"h2">;
    private status: TagElement<"span">;
    private kernelState: StateHandler<KernelState>;

    // TODO: this implementation will no longer appear on the welcome screen, which means that errors won't show.
    //       another solution for showing errors on the welcome screen needs to be implemented.
    constructor(private serverMessageDispatcher: ServerMessageDispatcher,
                private dispatcher: NotebookMessageDispatcher,
                private notebookState: NotebookStateHandler,
                private whichPane: keyof ViewPreferences) {
        super()
        this.disposeWith(notebookState);

        this.kernelState = notebookState.lens("kernel").disposeWith(this);

        const info = new KernelInfoEl(this.kernelState).disposeWith(this);
        const symbols = new KernelSymbolsEl(dispatcher, notebookState).disposeWith(this);
        const tasks = new KernelTasksEl(dispatcher, notebookState.view("path"), this.kernelState.lens("tasks")).disposeWith(this);

        this.statusEl = h2(['kernel-status'], [
            this.status = span(['status'], ['●']),
            'Kernel',
            span(['left-buttons'], [
                helpIconButton([], "https://polynote.org/latest/docs/kernel-pane/"),
            ]),
            span(['middle-area'], ""),
            span(['right-buttons'], [
                iconButton(['connect'], 'Connect to server', 'plug', 'Connect').click(evt => this.connect(evt)),
                iconButton(['start'], 'Start kernel', 'power-off', 'Start').click(evt => this.startKernel(evt)),
                iconButton(['kill'], 'Kill kernel', 'skull', 'Kill').click(evt => this.killKernel(evt))
            ])
        ]);

        this.el = div(['kernel-ui'], [
            info.el,
            symbols?.el,
            tasks.el
        ]);

        this.setKernelStatus(this.kernelState.state.status)
        this.kernelState.observeKey("status", status => {
            this.setKernelStatus(status)
        })

    }

    private connect(evt: Event) {
        evt.stopPropagation();
        this.serverMessageDispatcher.reconnect(true)
    }

    private startKernel(evt: Event) {
        evt.stopPropagation();
        this.dispatcher.kernelCommand('start')
    }

    private killKernel(evt: Event) {
        evt.stopPropagation();
        this.dispatcher.kernelCommand('kill')
    }

    private setKernelStatus(state: KernelStatusString) {
        this.statusEl.classList.remove('busy', 'idle', 'dead', 'disconnected');
        if (state === 'busy' || state === 'idle' || state === 'dead' || state === 'disconnected') {
            this.statusEl.classList.add(state);
            this.status.title = state;
        } else {
            throw "State must be one of [busy, idle, dead, disconnected]";
        }
    }
}

class KernelInfoEl extends Disposable {
    readonly el: TagElement<"div">;
    private toggleEl: TagElement<"h3">;
    private infoEl: TableElement;

    constructor(kernelStateHandler: StateHandler<KernelState>) {
        super()
        this.el = div(['kernel-info'], [
            this.toggleEl = h3(['toggle'], ['...']).click(() => this.toggleCollapse()),
            h3(['title'], ['Info']),
            this.infoEl = table(['info-container'], {
                classes: ['key', 'val'],
                rowHeading: false,
                addToTop: false
            }),
        ]);

        this.renderInfo(kernelStateHandler.state.info);
        kernelStateHandler.observeKey("info", info => this.renderInfo(info)).disposeWith(this);

        kernelStateHandler.observeKey("status", status => {
            if (status === "dead") {
                this.el.style.display = "none";
            } else {
                this.el.style.display = "block";
            }
        }).disposeWith(this)
    }

    private toggleCollapse() {
        const collapsed = 'collapsed';
        if (this.toggleEl.classList.contains(collapsed)) {
            this.toggleEl.classList.remove(collapsed);
            this.infoEl.style.display = '';
        } else {
            this.toggleEl.classList.add(collapsed);
            this.infoEl.style.display = 'none';
        }
    }

    renderInfo(info?: KernelInfo) {
        if (info && Object.keys(info).length > 0) {
            this.el.style.display = "block";
            Object.entries(info).forEach(([k, v]) => {
                const el = div([], []);
                el.innerHTML = v;
                if (this.infoEl.findRowsBy(row => row.key === k).length === 0) {
                    this.infoEl.addRow({key: k, val: el.firstChild as HTMLElement});
                }
            });
        } else {
            this.el.style.display = "none";
        }
    }
}

type KernelTask = TagElement<"div"> & {
    labelText: string,
    detailText: Content,
    status: number,
    childTasks: Record<string, KernelTask>
}

class KernelTasksEl extends Disposable {
    readonly el: TagElement<"div">;
    private notebookPath: string;
    private taskContainer: TagElement<"div">;
    private cancelButton: TagElement<"button">;
    private tasks: Record<string, KernelTask> = {};
    private errors: Record<string, DisplayError>;
    private errorTimeouts: Record<string, number> = {};
    private notebookPathHandler: StateView<string>
    private kernelTasksHandler: StateHandler<KernelTasks>

    constructor(private dispatcher: NotebookMessageDispatcher,
                notebookPathHandler: StateView<string>,
                kernelTasksHandler: StateHandler<KernelTasks>) {
        super()
        this.notebookPathHandler = notebookPathHandler = notebookPathHandler.fork(this);
        this.kernelTasksHandler = kernelTasksHandler = kernelTasksHandler.fork(this);
        this.el = div(['kernel-tasks'], [
            h3([], [
                'Tasks',
                this.cancelButton = iconButton(["stop-cell"], "Cancel all tasks", "stop", "Cancel All")
                    .click(_ => dispatcher.cancelTasks())
            ]),
            this.taskContainer = div(['task-container'], [])
        ]);

        this.el.addEventListener('mousedown', evt => evt.preventDefault())

        this.notebookPath = notebookPathHandler.state
        notebookPathHandler.addObserver(path => this.notebookPath = path)

        Object.values(kernelTasksHandler.state).forEach(task => this.addTask(task.id, task.label, task.detail, task.status, task.progress, task.parent));
        kernelTasksHandler.addObserver((currentTasks, updateResult) => {
            UpdateResult.addedOrChangedKeys(updateResult).forEach(taskId => {
                const task = currentTasks[taskId];
                if (taskId in this.tasks) {
                    this.updateTask(task.id, task.label, task.detail, task.status, task.progress, task.parent)
                } else {
                    this.addTask(task.id, task.label, task.detail, task.status, task.progress, task.parent)
                }
            })

            Object.keys(updateResult.removedValues ?? {}).forEach(taskId => {
                this.removeTask(taskId)
            })
        })

        this.errors = {}
        const handleErrors = (errs: DisplayError[]) => {
            if (errs.length > 0) {
                errs.forEach(e => {
                    if (this.errors[e.id] === undefined) {
                        this.addError(e.id, e.err)
                        this.errors[e.id] = e
                    }
                })

                // clear any old errors
                Object.entries(this.errors).forEach(([id, err]) => {
                    if (! errs.includes(err)) {
                        this.removeTask(id)
                        delete this.errors[id]
                    }
                })
            } else {
                Object.keys(this.errors).forEach(id => {
                    this.removeTask(id)
                })
                this.errors = {}
            }
        }
        ErrorStateHandler.get.addObserver(
            errors => handleErrors([...errors.serverErrors, ...errors[this.notebookPath] ?? []])
        ).disposeWith(this)
    }

    private addError(id: string, err: ServerErrorWithCause) {
        const errorEl = ErrorEl.fromServerError(err, undefined);

        const message = div(["message"], [
            para([], `${err.className}: ${err.message}`),
            para([], errorEl.el)
        ]);

        this.addTask(id, id, message, TaskStatus.Error, 0, undefined, () => {this.removeError(id)}, () => errorEl.copyFromServerError());
    }

    private removeError(id: string) {
        const maybeError = this.errors[id];
        if (maybeError) {
            ErrorStateHandler.removeError(maybeError)
            delete this.errors[id];
        }
    }

    private addTask(id: string, label: string, detail: Content, status: number, progress: number, parent: string | undefined = undefined, remove: () => void = () => this.cancelTaskWrapper(id), copy?: () => string) {
        // short-circuit if the task coming in is already completed.
        if (status === TaskStatus.Complete) {
            remove()
        } else {
            const taskEl: KernelTask = Object.assign(div(['task', (Object.keys(TaskStatus)[status] || 'unknown').toLowerCase()], [
                icon(['close-button'], 'times', 'close icon').click(evt => {
                    evt.stopPropagation();
                    remove();
                }),
                h4([], [label]),
                div(['detail'], detail),
                div(['progress'], [div(['progress-bar'], [])]),
                div(['child-tasks'], [])
            ]).click(() => this.jumpToCell(id)), {
                labelText: label,
                detailText: detail,
                status: status,
                childTasks: {}
            });

            if (detail && typeof detail === "string") {
                taskEl.attr('title', detail);
            }

            if (Object.keys(TaskStatus)[status] === "Error") {
                taskEl.prepend(icon(['copy-button'], 'copy', 'copy icon').click(() => {
                    const errCopy = copy?.();
                    if (errCopy != undefined)
                        copyToClipboard(errCopy);
                }));
            }

            const container = (typeof parent !== "undefined" && (this.tasks[parent]?.querySelector('.child-tasks'))) || this.taskContainer;

            if (container) {
                this.setProgress(taskEl, progress);

                let before = container.firstChild as KernelTask;
                while (before?.status <= status) {
                    before = before.nextSibling as KernelTask;
                }

                container.insertBefore(taskEl, before);

                this.tasks[id] = taskEl;
                this.el.classList.add("nonempty");
                this.cancelButton.disabled = false;
            }
        }
    }

    private jumpToCell(id: string) {
        const nbInfo = ServerStateHandler.getOrCreateNotebook(this.notebookPathHandler.state);
        const idAsNum = id.split(" ").pop(); // extract the actual id number
        if (idAsNum != undefined && !isNaN(parseInt(idAsNum))) { // Check there was a second word, and verify it is a number
            nbInfo.handler.selectCell(parseInt(idAsNum));
        }
    }

    private setProgress(el: KernelTask, progress: number) {
        const progressBar = el.querySelector('.progress-bar') as HTMLElement;
        progressBar.style.width = (progress * 100 / 255).toFixed(0) + "%";
    }

    private updateTask(id: string, label: string, detail: Content, status: number, progress: number, parent?: string) {
        let task = this.tasks[id];

        if (task === undefined) {
            if (status > TaskStatus.Complete) {
                this.addTask(id, label, detail, status, progress, parent);
            }
        } else {
            if (task.labelText !== label) {
                const heading = task.querySelector('h4') as HTMLElement;
                heading.innerHTML = '';
                heading.appendChild(document.createTextNode(label));
                task.labelText = label;
            }
            if (task.detailText !== detail && typeof (detail) === "string") {
                const detailEl = task.querySelector('.detail') as HTMLElement;
                detailEl.innerHTML = '';
                detailEl.appendChild(document.createTextNode(detail));
                task.detailText = detail;
                task.attr("title", detail);
            }

            const statusClass = (Object.keys(TaskStatus)[status] || 'unknown').toLowerCase();
            if (!task.classList.contains(statusClass)) {
                task.className = 'task';
                task.classList.add(statusClass);
                const maybeTimeout = this.errorTimeouts[id]
                if (maybeTimeout) window.clearTimeout(maybeTimeout)
                if (statusClass === "complete") {
                    this.errorTimeouts[id] = window.setTimeout(() => {
                        this.removeTask(id)
                    }, 100);
                }
            }
            task.status = status;
            this.setProgress(task, progress);
        }
    }

    private removeTask(id: string) {
        const task = this.tasks[id];
        if (task?.parentNode) task.parentNode.removeChild(task);
        delete this.tasks[id];
        this.kernelTasksHandler.update(() => removeKey(id))
        if (Object.keys(this.tasks).length === 0) {
            this.el.classList.remove('nonempty');
            this.cancelButton.disabled = true;
        }
    }

    // Serves as a wrapper for canceling a task - if the cancel button is hit on a task that was running but crashed,
    // its status will be checked so it can be safely removed from the UI.
    private cancelTaskWrapper(id: string) {
        if (this.tasks[id] !== undefined && this.tasks[id].status === TaskStatus.Error) {
            this.removeTask(id);
        } else {
            this.dispatcher.cancelTask(id);
        }
    }
}

interface ResultRow extends TableRowElement {
    resultValue: ResultValue
    data: {
        name: string,
        type: string
    }
}

class KernelSymbolViewWidget {
    static instance: KernelSymbolViewWidget = new KernelSymbolViewWidget();

    readonly el: TagElement<'div'>;
    private content: TagElement<'div'>;

    private pointer: TagElement<'div'>;
    private buttons: TagElement<'div'>;
    private viewDataButton: TagElement<'button'>;
    private plotButton: TagElement<'button'>;
    private visualizeButton: TagElement<'button'>;
    private inspectButton: TagElement<'button'>;

    private currentElement?: HTMLElement;
    private currentValue?: ResultValue;
    private currentHandler?: NotebookStateHandler;

    private currentLayout?: DOMRect;

    private hideTimeout: number = 0;
    private readonly cancelHide: () => void = () => {
        if (this.hideTimeout) {
            window.clearTimeout(this.hideTimeout);
            this.hideTimeout = 0;
            this.el.style.opacity = '1.0';
        }
    }

    private handleHide: () => void = () => {
        this.cancelHide();
        this.hideTimeout = window.setTimeout(() => {
            this.el.style.opacity = '0';
            this.hideTimeout = window.setTimeout(() => this.hide(), 250);
        }, 180);
    }

    private relayout: () => void = () => {
        const newLayout = this.el.getBoundingClientRect();
        if (!this.currentLayout || this.currentLayout.height !== newLayout.height) {
            this.layout(false);
        }
    }

    constructor() {
        this.el = div(['kernel-symbol-widget'], [
            this.pointer = div(['pointer'], [div(['arrow'], [])]),
            div(['inner'], [
                this.content = div(['content'], []),
                this.buttons = div(['buttons'], [
                    this.viewDataButton = iconButton(['view-data'], 'View data', 'table', '[View]').click(() => this.browse()),
                    this.plotButton = iconButton(['plot-data'], 'Plot data', 'chart-bar', '[Plot]').click(() => this.plot()),
                    this.visualizeButton = iconButton(['create-cell'], 'Visualize', 'plus-circle', '[Visualize]').click(() => this.visualize()),
                    this.inspectButton = iconButton(['inspect-value'], 'Quick look', 'search', '[Quick Look]').click(() => this.inspect())
                ])
            ])
        ]);
        this.el.addEventListener('mouseleave', this.handleHide);
        this.el.addEventListener('mouseenter', this.cancelHide);
        this.content.addEventListener('toggle', this.relayout);
        window.addEventListener('resize', this.relayout);
        this.el.style.opacity = '0';
    }

    private static contentFor(value: ResultValue) {
        const dataRepr = findInstance(value.reprs, DataRepr);
        if (dataRepr) {
            return prettyDisplayData(value.name, value.typeName, dataRepr)
                .then(([_, el]) => el)
        } else {
            return prettyDisplayString(value.name, value.typeName, value.valueText)
                .then(([_, el]) => el)
        }
    }

    showFor(row: HTMLElement, value: ResultValue, handler: NotebookStateHandler) {
        const rowDims = row.getBoundingClientRect();
        this.cancelHide();

        this.setFocus([row, value, handler]);

        KernelSymbolViewWidget.contentFor(value).then(content => {
            this.cancelHide();
            this.content.innerHTML = '';
            this.content.appendChild(content);

            if (value.reprs.findIndex(repr => repr instanceof StreamingDataRepr) >= 0) {
                this.plotButton.style.display = '';
                this.viewDataButton.style.display = '';
            } else {
                this.plotButton.style.display = 'none';
                this.viewDataButton.style.display = 'none';
            }

            this.layout();
        }).then(() => window.requestAnimationFrame(() => this.el.style.opacity = '1.0'))

    }

    /**
     * Layout the widget.
     * @param canMove If true, the widget can be re-positioned to be centered when possible. If false, the widget won't
     *                be repositioned (for example, if it is already visible but we are redoing its layout in response
     *                to a toggle event in the content, then we don't want it to jump around)
     * @private
     */
    private layout(canMove: boolean = true) {
        if (this.currentElement) {
            const targetEl = this.currentElement;
            this.buttons.style.marginTop = '0';
            const rowDims = targetEl.getBoundingClientRect();
            const right = (document.body.clientWidth - rowDims.left);
            this.el.style.right = right + 'px';
            this.el.style.maxWidth =
                ((document.querySelector('.tab-view')?.clientWidth ?? document.body.clientWidth - right - 184) - 16) + 'px';

            document.body.appendChild(this.el);

            const widgetDims = this.el.getBoundingClientRect();
            // center the widget vertically as much as possible
            let top = (rowDims.y + (rowDims.height / 2) - (widgetDims.height / 2));
            if (top >= 20 && (top + widgetDims.height + 20 < document.body.clientHeight) && canMove) {
                this.el.classList.remove('floating');
                this.el.style.top = top + 'px';
                this.pointer.style.top = '';
            } else {
                // the widget is too large to properly center it vertically (or we can't move it vertically) – instead,
                // we'll center it as closely as possible (if we can vertically move it) and move the arrow and buttons
                // to be in line with the element we're pointing to.
                this.el.classList.add('floating');
                if (canMove) {
                    // position the thing in the center of the whole screen
                    top = (document.body.clientHeight / 2) - (widgetDims.height / 2);
                    this.el.style.top = top + 'px';
                    this.el.style.maxHeight = '90vh';
                } else {
                    top = widgetDims.y;
                    this.el.style.maxHeight = (document.body.clientHeight - 20 - top) + 'px';
                }
                const arrowY = (rowDims.y + (rowDims.height / 2) - top);
                this.pointer.style.top = arrowY + 'px';
                this.buttons.style.marginTop = (arrowY - (this.buttons.clientHeight / 2)) + 'px';
            }
            this.currentLayout = this.el.getBoundingClientRect();
        }
    }

    private plot() {
        if (this.currentValue && this.currentHandler) {
            this.currentHandler.insertInspectionCell(this.currentValue, 'plot');
            this.hide();
        }
    }

    private browse() {
        if (this.currentValue && this.currentHandler) {
            this.currentHandler.insertInspectionCell(this.currentValue, 'table');
            this.hide();
        }
    }

    private visualize() {
        if (this.currentValue && this.currentHandler) {
            this.currentHandler.insertInspectionCell(this.currentValue);
            this.hide();
        }
    }

    private inspect() {
        if (this.currentValue && this.currentHandler) {
            ValueInspector.get.inspect(this.currentHandler, this.currentValue);
            this.hide();
        }
    }

    private setFocus(focus?: [HTMLElement, ResultValue, NotebookStateHandler]) {
        if (this.currentElement) {
            this.currentElement?.removeEventListener('mouseleave', this.handleHide);
            this.currentElement?.removeEventListener('mouseenter', this.cancelHide);
        }
        if (focus) {
            const [targetEl, targetValue, targetHandler] = focus;
            targetEl.addEventListener('mouseleave', this.handleHide);
            targetEl.addEventListener('mouseenter', this.cancelHide);
            this.currentElement = targetEl;
            this.currentValue = targetValue;
            this.currentHandler = targetHandler;
        }
    }

    hide() {
        if (this.hideTimeout) {
            clearTimeout(this.hideTimeout);
            this.hideTimeout = 0;
        }
        this.setFocus(undefined);
        this.el.style.maxHeight = '90vh';
        this.el.style.maxWidth = 'none';
        this.buttons.style.marginTop = '0';
        this.el.classList.remove('floating');
        this.el.parentNode?.removeChild(this.el);
        this.el.style.opacity = '0';
        this.buttons.style.marginTop = '0';
        this.content.innerHTML = '';
        this.currentElement = this.currentValue = this.currentHandler = undefined;
    }
}

class KernelSymbolsEl extends Disposable {
    readonly el: TagElement<"div">;
    private tableEl: TableElement;
    private resultSymbols: TagElement<"tbody">;
    private scopeSymbols: TagElement<"tbody">;
    //                      cellId -> {    name -> symbol }
    private symbols: Record<number, Record<string, ResultValue>> = {};
    private predefs: Record<number, number> = {};
    private presentedCell: number = 0;
    private visibleCells: number[] = [];

    constructor(private dispatcher: NotebookMessageDispatcher, private notebookState: NotebookStateHandler) {
        super()
        this.el = div(['kernel-symbols'], [
            h3([], ['Symbols']),
            div(['table-scroller'], [
                this.tableEl = table(['kernel-symbols-table'], {
                    header: ['Name', 'Type'],
                    classes: ['name', 'type'],
                    rowHeading: true,
                    addToTop: true
                })
            ])
        ]);
        this.resultSymbols = (this.tableEl.tBodies[0] as TagElement<"tbody">).addClass('results');
        this.scopeSymbols = this.tableEl.addBody().addClass('scope-symbols');

        const handleSymbols = (symbols: KernelSymbols, updateResult?: UpdateResult<KernelSymbols>) => {
            const cells = Object.keys(symbols)
            if (cells.length > 0 || updateResult) {
                const addedCellIds = updateResult ? Object.keys(updateResult.addedValues ?? {}) : Object.keys(symbols);
                addedCellIds.forEach(cellId => Object.values(symbols[cellId]).forEach(s => this.addSymbol(s)))

                const changedCellIds = Object.keys(updateResult?.changedValues ?? {});
                changedCellIds.forEach(cellIdStr => {
                    const cellId: number = parseInt(cellIdStr, 10);
                    const fieldUpdates: UpdateResult<Record<string, ResultValue>> | undefined = updateResult?.fieldUpdates?.[cellIdStr]
                    const addedSymbols = fieldUpdates?.addedValues ?? {};
                    const removedSymbols = fieldUpdates?.removedValues ?? {};
                    const changedSymbols = fieldUpdates?.changedValues ?? {};

                    Object.values(addedSymbols).forEach(value => value ? this.addSymbol(value) : {});
                    Object.values(changedSymbols).forEach(value => value ? this.addSymbol(value) : {});
                    Object.keys(removedSymbols).forEach(name => this.removeSymbol(cellId, name));
                })

                const removedCellIds = Object.keys(updateResult?.removedValues ?? {});
                removedCellIds.forEach(cellId => this.removeAllSymbols(parseInt(cellId, 10)));
            } else if (Object.values(this.symbols).length > 0) {
                Object.keys(this.symbols).forEach(cellId => this.removeAllSymbols(parseInt(cellId, 10)));
            }
        }
        handleSymbols(notebookState.state.kernel.symbols)
        notebookState.view("kernel").observeKey("symbols", (symbols, updateResult) => handleSymbols(symbols, updateResult)).disposeWith(this);

        const handleActiveCell = (cellId?: number) => {
            if (cellId === undefined) {
                // only show predef:
                this.presentFor(-1, [])
            } else {
                const idx = notebookState.getCellIndex(cellId)
                const cellsBefore = notebookState.state.cellOrder.slice(0, idx)
                this.presentFor(cellId, cellsBefore)
            }
        }
        handleActiveCell(notebookState.state.activeCellId)
        notebookState.observeKey("activeCellId", cell => handleActiveCell(cell)).disposeWith(this);
    }

    private updateRow(tr: ResultRow, resultValue: ResultValue) {
        tr.resultValue = resultValue;
        tr.updateValues({type: span([], resultValue.typeName).attr('title', resultValue.typeName)})
    }

    private addRow(resultValue: ResultValue, whichBody: TagElement<"tbody">) {
        const tr = this.tableEl.addRow({
            name: resultValue.name,
            type: span([], [resultValue.typeName]).attr('title', resultValue.typeName)
        }, whichBody) as ResultRow;
        tr.onmousedown = (evt) => {
            if (evt.button !== 0)
                return; // only for primary mouse button
            evt.stopPropagation();
        };
        tr.onmouseover = () => this.showPopupFor(tr, resultValue);
        tr.data = {name: resultValue.name, type: resultValue.typeName};
        tr.resultValue = resultValue;
        return tr;
    }

    private showPopupFor(row: HTMLElement, resultValue: ResultValue) {
        KernelSymbolViewWidget.instance.showFor(row, resultValue, this.notebookState);
    }

    private addScopeRow(resultValue: ResultValue) {
        return this.addRow(resultValue, this.scopeSymbols);
    }

    private addResultRow(resultValue: ResultValue) {
        return this.addRow(resultValue, this.resultSymbols);
    }

    // TODO: future optimization - check if value has changed before updating it.
    private addSymbol(resultValue: ResultValue) {
        const cellId = resultValue.sourceCell;
        const name = resultValue.name;

        if (this.symbols[cellId] === undefined) {
            this.symbols[cellId] = {};
        }
        const cellSymbols = this.symbols[cellId];
        cellSymbols[name] = resultValue;
        if (cellId < 0) {
            this.predefs[cellId] = cellId;
        }

        if (cellId === this.presentedCell) {
            const existing = this.tableEl.findRows({name}, this.resultSymbols)[0] as ResultRow;
            if (existing) {
                this.updateRow(existing, resultValue);
            } else {
                this.addResultRow(resultValue);
            }
        } else if (this.visibleCells.indexOf(cellId) >= 0 || this.predefs[cellId]) {
            const existing = this.tableEl.findRows({name}, this.scopeSymbols)[0] as ResultRow;
            if (existing) {
                this.updateRow(existing, resultValue);
            } else {
                this.addScopeRow(resultValue);
            }
        }
    }

    private presentFor(id: number, visibleCellIds: number[]) {
        visibleCellIds = [...Object.values(this.predefs), ...visibleCellIds];
        this.presentedCell = id;
        this.visibleCells = visibleCellIds;
        const visibleSymbols: Record<string, ResultValue> = {};
        visibleCellIds.forEach(id => {
            const cellSymbols = this.symbols[id];
            for (const name in cellSymbols) {
                if (cellSymbols.hasOwnProperty(name)) {
                    visibleSymbols[name] = cellSymbols[name];
                }
            }
        });

        // update all existing symbols, remove any that aren't visible
        [...this.scopeSymbols.rows].forEach((row: ResultRow) => {
            if (row.data) {
                const sym = visibleSymbols[row.data.name];
                if (sym === undefined) {
                    row.parentNode!.removeChild(row);
                } else {
                    if (sym.typeName !== row.data.type) {
                        this.updateRow(row, sym);
                    }
                    delete visibleSymbols[sym.name]
                }
            }
        });

        // append all the remaining symbols
        for (const name in visibleSymbols) {
            if (visibleSymbols.hasOwnProperty(name)) {
                this.addScopeRow(visibleSymbols[name]);
            }
        }

        // clear the result rows
        this.resultSymbols.innerHTML = "";

        // add all results for the current cell
        if (this.symbols[id] !== undefined) {
            const cellSymbols = this.symbols[id];
            for (const name in cellSymbols) {
                if (cellSymbols.hasOwnProperty(name)) {
                    this.addResultRow(cellSymbols[name]);
                }
            }
        }
    }

    private removeSymbol(cellId: number, name: string) {
        delete this.symbols[cellId][name];
        const existing = this.tableEl.findRowsBy(row => row.name === name);
        if (existing.length > 0) {
            existing.forEach(tr => tr.parentNode!.removeChild(tr));
        }
    }

    private removeAllSymbols(cellId: number) {
        const names = Object.keys(this.symbols[cellId] ?? {});
        names.forEach(name => this.removeSymbol(cellId, name));
    }
}
