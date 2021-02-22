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
import {changedKeys} from "../../../util/helpers";

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
            if (path && path !== "home") {
                const nbInfo = ServerStateHandler.getOrCreateNotebook(path);
                // the notebook should already be loaded
                if (nbInfo?.info) {
                    if (this.kernels[path] === undefined) {
                        this.kernels[path] = new Kernel(
                            serverMessageDispatcher,
                            nbInfo.info.dispatcher,
                            nbInfo.handler,
                            'rightPane');
                    }
                    const kernel = this.kernels[path];
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
                this.el.replaceWith(placeholderEl);
                this.el = placeholderEl

                this.statusEl.replaceWith(placeholderStatus);
                this.statusEl = placeholderStatus;
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

        this.kernelState = notebookState.lens("kernel").disposeWith(this);

        const info = new KernelInfoEl(this.kernelState).disposeWith(this);
        const symbols = new KernelSymbolsEl(dispatcher, notebookState).disposeWith(this);
        const tasks = new KernelTasksEl(dispatcher, notebookState.view("path"), this.kernelState.lens("tasks")).disposeWith(this);

        this.statusEl = h2(['kernel-status'], [
            this.status = span(['status'], ['●']),
            'Kernel',
            span(['buttons'], [
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

    constructor(dispatcher: NotebookMessageDispatcher,
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
        const el = ErrorEl.fromServerError(err, undefined).el;

        const message = div(["message"], [
            para([], `${err.className}: ${err.message}`),
            para([], el)
        ]);

        this.addTask(id, id, message, TaskStatus.Error, 0, undefined, () => {
            this.removeError(id)
        })
    }

    private removeError(id: string) {
        const maybeError = this.errors[id];
        if (maybeError) {
            ErrorStateHandler.removeError(maybeError)
            delete this.errors[id];
        }
    }

    private addTask(id: string, label: string, detail: Content, status: number, progress: number, parent: string | undefined = undefined, remove: () => void = () => this.removeTask(id)) {
        // short-circuit if the task coming in is already completed.
        if (status === TaskStatus.Complete) {
            remove()
        } else {
            const taskEl: KernelTask = Object.assign(div(['task', (Object.keys(TaskStatus)[status] || 'unknown').toLowerCase()], [
                icon(['close-button'], 'times', 'close icon').click(() => remove()),
                h4([], [label]),
                div(['detail'], detail),
                div(['progress'], [div(['progress-bar'], [])]),
                div(['child-tasks'], [])
            ]), {
                labelText: label,
                detailText: detail,
                status: status,
                childTasks: {}
            });

            if (detail && typeof detail === "string") {
                taskEl.attr('title', detail);
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
}

interface ResultRow extends TableRowElement {
    resultValue: ResultValue
    data: {
        name: string,
        type: string
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
            this.tableEl = table(['kernel-symbols-table'], {
                header: ['Name', 'Type'],
                classes: ['name', 'type'],
                rowHeading: true,
                addToTop: true
            })
        ]);
        this.resultSymbols = (this.tableEl.tBodies[0] as TagElement<"tbody">).addClass('results');
        this.scopeSymbols = this.tableEl.addBody().addClass('scope-symbols');

        const handleSymbols = (symbols: KernelSymbols, updateResult?: UpdateResult<KernelSymbols>) => {
            const cells = Object.keys(symbols)
            if (cells.length > 0) {
                const changedCells = updateResult ? UpdateResult.addedOrChangedKeys(updateResult) : Object.keys(symbols);
                changedCells.forEach(cell => {
                    Object.values(symbols[cell]).forEach(s => this.addSymbol(s));
                })
            } else if (Object.values(this.symbols).length > 0) {
                Object.entries(this.symbols).forEach(([cellId, syms]) => {
                    Object.keys(syms).forEach(s => this.removeSymbol(parseInt(cellId), s))
                })
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
            evt.preventDefault();
            evt.stopPropagation();
            this.dispatcher.showValueInspector(tr.resultValue)
        };
        tr.data = {name: resultValue.name, type: resultValue.typeName};
        tr.resultValue = resultValue;
        return tr;
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
}
