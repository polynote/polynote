import {KernelCommand, UIMessage, UIMessageTarget, UIToggle} from "../util/ui_event";
import {KernelInfoUI} from "./kernel_info";
import {KernelSymbolsUI} from "./symbol_table";
import {KernelTasksUI} from "./tasks";
import {div, h2, iconButton, para, span, TagElement} from "../util/tags";
import * as messages from "../../data/messages";
import {TaskInfo, TaskStatus} from "../../data/messages";
import {storage} from "../util/storage";
import {SocketSession} from "../../comms";
import {NotebookUI} from "./notebook";
import {ServerErrorWithCause, ResultValue} from "../../data/result";
import * as cell from "./cell";

export class KernelUI extends UIMessageTarget {
    private info: KernelInfoUI;
    readonly tasks: KernelTasksUI;
    readonly symbols?: KernelSymbolsUI;
    readonly el: TagElement<"div">;
    private statusEl: TagElement<"h2">;
    private status: TagElement<"span">;
    private socket: SocketSession;
    private path: string;

    constructor(private notebook?: NotebookUI) {
        super(notebook);
        this.info = new KernelInfoUI();
        if (notebook) {
            this.symbols = new KernelSymbolsUI(notebook).setParent(this);
            this.path = notebook.path;
            this.socket = notebook.socket;
        } else {
            this.socket = SocketSession.global; // TODO: the welcome screen uses a KernelUI to be able to show errors, make a dedicated thing for that and remove this (make notebook non-optional)
            this.path = "/";
        }
        this.tasks = new KernelTasksUI();

        this.el = div(['kernel-ui', 'ui-panel'], [
            this.statusEl = h2(['kernel-status'], [
                this.status = span(['status'], ['●']),
                'Kernel',
                notebook && span(['buttons'], [
                    iconButton(['connect'], 'Connect to server', 'plug', 'Connect').click(evt => this.connect(evt)),
                    iconButton(['start'], 'Start kernel', 'power-off', 'Start').click(evt => this.startKernel(evt)),
                    iconButton(['kill'], 'Kill kernel', 'skull', 'Kill').click(evt => this.killKernel(evt))
                ])
            ]).click(evt => this.collapse()),
            div(['ui-panel-content'], [
                notebook && this.info.el,
                notebook && this.symbols?.el,
                notebook && this.tasks.el
            ])
        ]);

        this.socket.addEventListener('close', () => this.setKernelState('disconnected'));

        this.socket.addMessageListener(messages.Error, (code, err) => this.errorDisplay(code, err));
        SocketSession.global.addMessageListener(messages.Error, (code, err) => this.errorDisplay(code, err));

        // Check storage to see whether this should be collapsed
        const prefs = this.getStorage();
        if (prefs?.collapsed) {
            this.collapse(true);
        }
    }

    errorDisplay(code: number, err: ServerErrorWithCause) {
        console.log("Kernel error:", err);

        const {el, messageStr, cellLine} = cell.errorDisplay(err, this.path);

        const id = "KernelError";
        const message = div(["message"], [
            para([], `${err.className}: ${err.message}`),
            para([], el)
        ]);
        this.tasks.updateTask(id, id, message, TaskStatus.Error, 0);

        // clean up (assuming that running another cell means users are done with this error)
        this.socket.listenOnceFor(messages.CellResult, () => this.tasks.updateTask(id, id, message, TaskStatus.Complete, 100));
    }

    presentSymbols(id: number, ids: number[]) {
        if (this.symbols)
            this.symbols.presentFor(id, ids);
    }

    addSymbol(resultValue: ResultValue) {
        if (this.symbols)
            this.symbols.addSymbol(resultValue)
    }

    updateInfo(info: Record<string, string>) {
        this.info.updateInfo(info)
    }

    updateTask(task: TaskInfo) {
        this.tasks.updateTask(task.id, task.label, task.detail, task.status, task.progress, task.parent);
    }

    getStorage() {
        return storage.get("KernelUI")
    }

    setStorage(obj: any) {
        storage.set("KernelUI", {...this.getStorage(), ...obj})
    }

    connect(evt: Event) {
        evt.stopPropagation();
        if (this.socket.isClosed) {
            this.socket.reconnect(true);
        }
    }

    startKernel(evt: Event) {
        evt.stopPropagation();
        this.publish(new KernelCommand(this.path, 'start'));
    }

    killKernel(evt: Event) {
        evt.stopPropagation();
        this.publish(new KernelCommand(this.path, 'kill'));
    }

    setKernelState(state: 'busy' | 'idle' | 'dead' | 'disconnected') {
        this.statusEl.classList.remove('busy', 'idle', 'dead', 'disconnected');
        if (state === 'busy' || state === 'idle' || state === 'dead' || state === 'disconnected') {
            this.statusEl.classList.add(state);
            this.status.title = state;
            if (state === 'dead') {
                this.info.clearInfo();
            }
        } else {
            throw "State must be one of [busy, idle, dead, disconnected]";
        }
    }

    collapse(force = false) {
        const prefs = this.getStorage();
        if (force) {
            this.publish(new UIToggle('KernelUI', /* force */ true));
        } else if (prefs?.collapsed) {
            this.setStorage({collapsed: false});
            this.publish(new UIToggle('KernelUI'));
        } else {
            this.setStorage({collapsed: true});
            this.publish(new UIToggle('KernelUI'));
        }
    }
}