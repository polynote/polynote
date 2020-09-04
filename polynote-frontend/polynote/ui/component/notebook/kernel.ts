import {KernelInfo, KernelState, KernelStateView, KernelSymbols, KernelTasks} from "../../../state/kernel_state";
import {
    Content,
    div,
    h2,
    h3,
    h4,
    icon,
    iconButton, para,
    span,
    table,
    TableElement,
    TableRowElement,
    TagElement
} from "../../tags";
import {
    KernelCommand,
    NotebookMessageDispatcher,
    Reconnect, RemoveError, RemoveTask,
    ServerMessageDispatcher,
    ShowValueInspector
} from "../../../messaging/dispatcher";
import {StateView} from "../../../state/state_handler";
import {ViewPreferences, ViewPrefsHandler} from "../../../state/preferences";
import {TaskStatus} from "../../../data/messages";
import {ResultValue, ServerErrorWithCause} from "../../../data/result";
import {CellState, NotebookStateHandler} from "../../../state/notebook_state";
import {ServerError, ServerStateHandler} from "../../../state/server_state";
import {diffArray, removeKey} from "../../../util/helpers";
import {ErrorEl} from "../../display/error";

// TODO: this should probably handle collapse and expand of the pane, rather than the Kernel itself.
export class KernelPane {
    el: TagElement<"div">;
    header: TagElement<"div">;
    private kernels: Record<string, Kernel> = {};

    constructor(serverMessageDispatcher: ServerMessageDispatcher) {
        const placeholderEl = div(['kernel-ui-placeholder'], []);
        const placeholderHeader = div(["kernel-header-placeholder"], []);
        this.el = placeholderEl;
        this.header = placeholderHeader;

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

                    this.header.replaceWith(kernel.statusEl);
                    this.header = kernel.statusEl;
                } else {
                    console.warn("Requested notebook at path", path, "but it wasn't loaded. This is unexpected...")
                }
            } else {
                // no notebook selected
                // TODO: keep task component around for errors?
                this.el.replaceWith(placeholderEl);
                this.el = placeholderEl

                this.header.replaceWith(placeholderHeader);
                this.header = placeholderHeader;
            }
        }
        handleCurrentNotebook(ServerStateHandler.state.currentNotebook)
        ServerStateHandler.get.view("currentNotebook").addObserver(path => handleCurrentNotebook(path))
    }

}

export class Kernel {
    readonly el: TagElement<"div">;
    readonly statusEl: TagElement<"h2">;
    private status: TagElement<"span">;
    private kernelState: KernelStateView;

    // TODO: this implementation will no longer appear on the welcome screen, which means that errors won't show.
    //       another solution for showing errors on the welcome screen needs to be implemented.
    constructor(private serverMessageDispatcher: ServerMessageDispatcher,
                private dispatcher: NotebookMessageDispatcher,
                private notebookState: NotebookStateHandler,
                private whichPane: keyof ViewPreferences) {

        this.kernelState = notebookState.view("kernel", KernelStateView);

        const info = new KernelInfoEl(this.kernelState);
        const symbols = new KernelSymbolsEl(dispatcher, notebookState);
        const tasks = new KernelTasksEl(dispatcher, serverMessageDispatcher, this.kernelState.kernelTasks, notebookState.view("errors"));

        this.statusEl = h2(['kernel-status'], [
            this.status = span(['status'], ['●']),
            'Kernel',
            span(['buttons'], [
                iconButton(['connect'], 'Connect to server', 'plug', 'Connect').click(evt => this.connect(evt)),
                iconButton(['start'], 'Start kernel', 'power-off', 'Start').click(evt => this.startKernel(evt)),
                iconButton(['kill'], 'Kill kernel', 'skull', 'Kill').click(evt => this.killKernel(evt))
            ])
        ]).click(evt => this.collapse())

        this.el = div(['kernel-ui'], [
            info.el,
            symbols?.el,
            tasks.el
        ]);

        this.setKernelStatus(this.kernelState.kernelStatus.state)
        this.kernelState.kernelStatus.addObserver(status => {
            this.setKernelStatus(status)
        })

    }

    private connect(evt: Event) {
        evt.stopPropagation();
        this.serverMessageDispatcher.dispatch(new Reconnect(true))
    }

    private startKernel(evt: Event) {
        evt.stopPropagation();
        this.dispatcher.dispatch(new KernelCommand('start'))
    }

    private killKernel(evt: Event) {
        evt.stopPropagation();
        this.dispatcher.dispatch(new KernelCommand('kill'))
    }

    private collapse() {
        ViewPrefsHandler.updateState(state => {
            return {
                ...state,
                [this.whichPane]: {
                    ...state[this.whichPane],
                    collapsed: !state[this.whichPane].collapsed
                }
            }
        })
    }

    private setKernelStatus(state: 'busy' | 'idle' | 'dead' | 'disconnected') {
        this.statusEl.classList.remove('busy', 'idle', 'dead', 'disconnected');
        if (state === 'busy' || state === 'idle' || state === 'dead' || state === 'disconnected') {
            this.statusEl.classList.add(state);
            this.status.title = state;
        } else {
            throw "State must be one of [busy, idle, dead, disconnected]";
        }
    }
}

class KernelInfoEl {
    readonly el: TagElement<"div">;
    private toggleEl: TagElement<"h3">;
    private infoEl: TableElement;

    constructor(kernelStateHandler: KernelStateView) {
        this.el = div(['kernel-info'], [
            this.toggleEl = h3(['toggle'], ['...']).click(() => this.toggleCollapse()),
            h3(['title'], ['Info']),
            this.infoEl = table(['info-container'], {
                classes: ['key', 'val'],
                rowHeading: false,
                addToTop: false
            }),
        ]);

        this.renderInfo(kernelStateHandler.kernelInfo.state);
        kernelStateHandler.kernelInfo.addObserver(info => this.renderInfo(info));

        kernelStateHandler.kernelStatus.addObserver(status => {
            if (status === "dead") {
                this.el.style.display = "none";
            } else {
                this.el.style.display = "block";
            }
        })
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

class KernelTasksEl {
    readonly el: TagElement<"div">;
    private taskContainer: TagElement<"div">;
    private tasks: Record<string, KernelTask> = {};
    private serverErrorIds: Record<string, ServerError>;
    private kernelErrorIds: Record<string, ServerErrorWithCause>;

    constructor(private dispatcher: NotebookMessageDispatcher,
                private serverMessageDispatcher: ServerMessageDispatcher,
                private kernelTasksHandler: StateView<KernelTasks>,
                private kernelErrors: StateView<ServerErrorWithCause[]>) {
        this.el = div(['kernel-tasks'], [
            h3([], ['Tasks']),
            this.taskContainer = div(['task-container'], [])
        ]);

        Object.values(kernelTasksHandler.state).forEach(task => this.addTask(task.id, task.label, task.detail, task.status, task.progress, task.parent));
        kernelTasksHandler.addObserver((currentTasks, oldTasks) => {
            const [added, removed] = diffArray(Object.keys(currentTasks), Object.keys(oldTasks))

            added.forEach(taskId => {
                const task = currentTasks[taskId];
                this.addTask(task.id, task.label, task.detail, task.status, task.progress, task.parent)
            })

            removed.forEach(taskId => {
                this.removeTask(taskId)
            })

            Object.values(currentTasks).forEach(task => {
                this.updateTask(task.id, task.label, task.detail, task.status, task.progress, task.parent)
            })
        })

        this.kernelErrorIds = {};
        const handleKernelErrors = (errs: ServerErrorWithCause[]) => {
            if (errs.length > 0) {
                errs.forEach(e => {
                    const id = `KernelError ${e.className}`;
                    if (this.kernelErrorIds[id] === undefined) {
                        this.addError(id, e)
                        this.kernelErrorIds[id] = e;
                    }
                })

                // clear any old errors
                Object.entries(this.kernelErrorIds).forEach(([id, err]) => {
                    if (! errs.includes(err)) {
                        this.removeTask(id)
                        delete this.kernelErrorIds[id]
                    }
                })
            } else {
                Object.keys(this.kernelErrorIds).forEach(id => this.removeTask(id))
                this.kernelErrorIds = {};
            }
        }
        handleKernelErrors(kernelErrors.state)
        kernelErrors.addObserver(e => handleKernelErrors(e))

        const serverErrors = ServerStateHandler.view("errors", kernelTasksHandler)
        this.serverErrorIds = {};
        const handleServerErrors = (errs: ServerError[]) => {
            if (errs.length > 0) {
                console.error("Got server errors", errs)
                errs.forEach(e => {
                    const id = `ServerError: ${e.err.className}`;
                    if (this.serverErrorIds[id] === undefined) {
                        this.addError(id, e.err)
                        this.serverErrorIds[id] = e
                    }
                })

                // clear any old errors
                Object.entries(this.serverErrorIds).forEach(([id, err]) => {
                    if (! errs.includes(err)) {
                        this.removeTask(id)
                        delete this.serverErrorIds[id]
                    }
                })
            } else {
                Object.keys(this.serverErrorIds).forEach(id => {
                    this.removeTask(id)
                })
                this.serverErrorIds = {};
            }
        }
        handleServerErrors(serverErrors.state)
        serverErrors.addObserver(e => handleServerErrors(e))
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
        const maybeKernelError = this.kernelErrorIds[id]
        if (maybeKernelError) {
            this.dispatcher.dispatch(new RemoveError(maybeKernelError))
        } else {
            const maybeServerError = this.serverErrorIds[id]?.err;
            if (maybeServerError) {
                this.serverMessageDispatcher.dispatch(new RemoveError(maybeServerError))
            }
        }
    }

    private addTask(id: string, label: string, detail: Content, status: number, progress: number, parent: string | undefined = undefined, remove: () => void = () => this.dispatcher.dispatch(new RemoveTask(id))) {
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
                if (statusClass === "complete") {
                    window.setTimeout(() => {
                        this.dispatcher.dispatch(new RemoveTask(id))
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
    }
}

interface ResultRow extends TableRowElement {
    resultValue: ResultValue
    data: {
        name: string,
        type: string
    }
}

class KernelSymbolsEl {
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

        const handleSymbols = (symbols: KernelSymbols) => {
            if (symbols.length > 0) {
                symbols.forEach(s => this.addSymbol(s))
            } else if (Object.values(this.symbols).length > 0) {
                Object.entries(this.symbols).forEach(([cellId, syms]) => {
                    Object.keys(syms).forEach(s => this.removeSymbol(parseInt(cellId), s))
                })
            }
        }
        const symbolHandler = notebookState.view("kernel", KernelStateView).kernelSymbols;
        handleSymbols(symbolHandler.state)
        symbolHandler.addObserver(symbols => handleSymbols(symbols))

        const handleActiveCell = (cell?: CellState) => {
            if (cell === undefined) {
                // only show predef
                this.presentFor(-1, [])
            } else {
                const cells = notebookState.state.cells;
                const idx = cells.findIndex(c => c.id === cell.id);
                const cellsBefore = cells.slice(0, idx).map(cell => cell.id)
                this.presentFor(cell.id, cellsBefore)
            }
        }
        const activeCellHandler = notebookState.view("activeCell");
        handleActiveCell(activeCellHandler.state)
        activeCellHandler.addObserver(cell => handleActiveCell(cell))
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
            this.dispatcher.dispatch(new ShowValueInspector(tr.resultValue))
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
