import {KernelInfo, KernelState, KernelStateHandler, KernelSymbols, KernelTasks} from "../state/kernel_state";
import {
    Content,
    div,
    h2,
    h3,
    h4,
    icon,
    iconButton,
    span,
    table,
    TableElement,
    TableRowElement,
    TagElement
} from "../../util/tags";
import {KernelCommand, NotebookMessageDispatcher, Reconnect, ShowValueInspector} from "../messaging/dispatcher";
import {StateHandler} from "../state/state_handler";
import {ViewPreferences, ViewPrefsHandler} from "../state/storage";
import {TaskInfo, TaskStatus} from "../../../data/messages";
import {ResultValue} from "../../../data/result";
import {CellState, NotebookStateHandler} from "../state/notebook_state";

export class Kernel {
    readonly el: TagElement<"div">;
    readonly statusEl: TagElement<"h2">;
    private status: TagElement<"span">;
    private kernelState: KernelStateHandler;

    // TODO: this implementation will no longer appear on the welcome screen, which means that errors won't show.
    //       another solution for showing errors on the welcome screen needs to be implemented.
    constructor(private dispatcher: NotebookMessageDispatcher, private notebookState: NotebookStateHandler, private whichPane: keyof ViewPreferences) {

        this.kernelState = notebookState.view("kernel", KernelStateHandler);

        const info = new KernelInfoComponent(this.kernelState.kernelInfoHandler);
        const symbols = new KernelSymbolsComponent(dispatcher, notebookState);
        const tasks = new KernelTasksComponent(this.kernelState.kernelTasksHandler);

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

        this.kernelState.kernelStatusHandler.addObserver(status => {
            this.setKernelStatus(status)
        })

    }

    private connect(evt: Event) {
        evt.stopPropagation();
        this.dispatcher.dispatch(new Reconnect(true))
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
            if (state === 'dead') {
                this.kernelState.kernelInfoHandler.updateState(() => ({}))
            }
        } else {
            throw "State must be one of [busy, idle, dead, disconnected]";
        }
    }
}

// TODO: remove 'Component' suffix
class KernelInfoComponent {
    readonly el: TagElement<"div">;
    private toggleEl: TagElement<"h3">;
    private infoEl: TableElement;

    constructor(kernelInfoHandler: StateHandler<KernelInfo>) {
        this.el = div(['kernel-info'], [
            this.toggleEl = h3(['toggle'], ['...']).click(() => this.toggleCollapse()),
            h3(['title'], ['Info']),
            this.infoEl = table(['info-container'], {
                classes: ['key', 'val'],
                rowHeading: false,
                addToTop: false
            }),
        ]);

        this.renderInfo(kernelInfoHandler.getState());
        kernelInfoHandler.addObserver(info => this.renderInfo(info));
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

class KernelTasksComponent {
    readonly el: TagElement<"div">;
    private taskContainer: TagElement<"div">;
    private tasks: Record<string, KernelTask> = {};

    constructor(private kernelTasksHandler: StateHandler<KernelTasks>) {
        this.el = div(['kernel-tasks'], [
            h3([], ['Tasks']),
            this.taskContainer = div(['task-container'], [])
        ]);

        Object.values(kernelTasksHandler.getState()).forEach(task => this.addTask(task.id, task.label, task.detail, task.status, task.progress, task.parent));
        kernelTasksHandler.addObserver(tasks => {
            Object.values(tasks).forEach(task => {
                this.updateTask(task.id, task.label, task.detail, task.status, task.progress, task.parent)
            })
        })
        // TODO: get errors too
    }

    private addTask(id: string, label: string, detail: Content, status: number, progress: number, parent?: string) {
        const taskEl: KernelTask = Object.assign(div(['task', (Object.keys(TaskStatus)[status] || 'unknown').toLowerCase()], [
            icon(['close-button'], 'times', 'close icon').click(_ => this.removeTask(id)),
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

        const container = (typeof parent !== "undefined" && (this.tasks[parent]?.querySelector('.child-tasks'))) ?? this.taskContainer;

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
                    setTimeout(() => {
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
        this.kernelTasksHandler.updateState(tasks => {
            tasks = {...tasks}
            delete tasks[task.id];
            return tasks
        });
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

class KernelSymbolsComponent {
    readonly el: TagElement<"div">;
    private tableEl: TableElement;
    private resultSymbols: TagElement<"tbody">;
    private scopeSymbols: TagElement<"tbody">;
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

        notebookState.view("kernel", KernelStateHandler).kernelSymbolsHandler.addObserver(symbols => {
            symbols.forEach(s => this.addSymbol(s))
        })

        notebookState.view("activeCell").addObserver(cell => {
            if (cell === undefined) {
                // only show predef
                this.presentFor(-1, [])
            } else {
                const cells = notebookState.getState().cells;
                const idx = cells.findIndex(c => c.id === cell.id);
                const cellsBefore = cells.slice(0, idx).map(cell => cell.id)
                this.presentFor(cell.id, cellsBefore)
            }
        })
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

    private removeSymbol(name: string) {
        const existing = this.tableEl.findRowsBy(row => row.name === name);
        if (existing.length > 0) {
            existing.forEach(tr => tr.parentNode!.removeChild(tr));
        }
    }
}
