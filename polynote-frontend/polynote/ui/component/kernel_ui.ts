import {KernelCommand, UIMessage, UIMessageTarget, UIToggle} from "../util/ui_event";
import {KernelInfoUI} from "./kernel_info";
import {KernelSymbolsUI} from "./symbol_table";
import {KernelTasksUI} from "./tasks";
import {div, h2, iconButton, para, span, TagElement} from "../util/tags";
import * as messages from "../../data/messages";
import {TaskInfo, TaskStatus} from "../../data/messages";
import {errorDisplay} from "./cell";
import {storage} from "../util/storage";
import {SocketSession} from "../../comms";

export class KernelUI extends UIMessageTarget {
    private info: KernelInfoUI;
    readonly tasks: KernelTasksUI;
    readonly symbols: KernelSymbolsUI;
    readonly el: TagElement<"div">;
    private statusEl: TagElement<"h2">;
    private status: TagElement<"span">;

    // TODO: instead of passing path in, can it be enriched by a parent?
    constructor(eventParent: UIMessageTarget, readonly path: string, showInfo = true, showSymbols = true, showTasks = true, showStatus = true) {
        super(eventParent);
        this.info = new KernelInfoUI();
        this.symbols = new KernelSymbolsUI(path).setParent(this);
        this.tasks = new KernelTasksUI();
        this.path = path;
        this.el = div(['kernel-ui', 'ui-panel'], [
            this.statusEl = h2(['kernel-status'], [
                this.status = span(['status'], ['●']),
                'Kernel',
                showStatus ? span(['buttons'], [
                    iconButton(['connect'], 'Connect to server', '', 'Connect').click(evt => this.connect(evt)),
                    iconButton(['start'], 'Start kernel', '', 'Start').click(evt => this.startKernel(evt)),
                    iconButton(['kill'], 'Kill kernel', '', 'Kill').click(evt => this.killKernel(evt))
                ]) : undefined
            ]).click(evt => this.collapse()),
            div(['ui-panel-content'], [
                showInfo ? this.info.el : undefined,
                showSymbols ? this.symbols.el : undefined,
                showTasks ? this.tasks.el : undefined
            ])
        ]);

        SocketSession.get.addEventListener('close', () => this.setKernelState('disconnected'));

        SocketSession.get.addMessageListener(messages.Error, (code, err) => {
            console.log("Kernel error:", err);

            const {el, messageStr, cellLine} = errorDisplay(err, this.path);

            const id = err.id;
            const message = div(["message"], [
                para([], `${err.className}: ${err.message}`),
                para([], el)
            ]);
            this.tasks.updateTask(id, id, message, TaskStatus.Error, 0);

            // clean up (assuming that running another cell means users are done with this error)
            SocketSession.get.listenOnceFor(messages.CellResult, () => this.tasks.updateTask(id, id, message, TaskStatus.Complete, 100));
        });

        // Check storage to see whether this should be collapsed
        const prefs = this.getStorage();
        if (prefs && prefs.collapsed) {
            this.collapse(true);
        }
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
        if (SocketSession.get.isClosed) {
            SocketSession.get.reconnect(true);
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
        } else if (prefs && prefs.collapsed) {
            this.setStorage({collapsed: false});
            this.publish(new UIToggle('KernelUI'));
        } else {
            this.setStorage({collapsed: true});
            this.publish(new UIToggle('KernelUI'));
        }
    }
}