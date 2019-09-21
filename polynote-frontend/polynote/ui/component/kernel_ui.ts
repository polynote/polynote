// REMOVE SOCKET
import {CallbackEvent, UIEvent, UIEventTarget} from "../util/ui_event";
import {KernelInfoUI} from "./kernel_info";
import {KernelSymbolsUI} from "./symbol_table";
import {KernelTasksUI} from "./tasks";
import {div, h2, iconButton, para, span, TagElement} from "../util/tags";
import * as messages from "../../data/messages";
import {TaskInfo, TaskStatus} from "../../data/messages";
import {errorDisplay} from "./cell";
import {storage} from "../util/storage";

export class KernelUI extends UIEventTarget {
    private info: KernelInfoUI;
    readonly tasks: KernelTasksUI;
    readonly symbols: KernelSymbolsUI;
    readonly el: TagElement<"div">;
    private statusEl: TagElement<"h2">;
    private status: TagElement<"span">;

    // TODO: instead of passing path in, can it be enriched by a parent?
    constructor(eventParent: UIEventTarget, readonly path: string, showInfo = true, showSymbols = true, showTasks = true, showStatus = true) {
        super(eventParent);
        this.info = new KernelInfoUI();
        this.symbols = new KernelSymbolsUI(path).setEventParent(this);
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

        this.registerEventListener('KernelStatus', (path, update) => {
            if (path === this.path) {
                switch (update.constructor) {
                    case messages.UpdatedTasks:
                        update.tasks.forEach((taskInfo: TaskInfo) => {
                            this.tasks.updateTask(taskInfo.id, taskInfo.label, taskInfo.detail, taskInfo.status, taskInfo.progress);
                            this.dispatchEvent(new UIEvent('UpdatedTask', {taskInfo: taskInfo}));
                        });
                        break;

                    case messages.KernelBusyState:
                        const state = (update.busy && 'busy') || (!update.alive && 'dead') || 'idle';
                        this.setKernelState(state);
                        break;

                    case messages.KernelInfo:
                        this.info.updateInfo(update.content);
                        break;

                    case messages.ExecutionStatus:
                        this.dispatchEvent(new UIEvent('UpdatedExecutionStatus', {update: update}));
                        break;
                }
            }
        });

        this.registerEventListener('SocketClosed', () => this.setKernelState('disconnected'));

        this.registerEventListener('KernelError', (code, err) => {
            console.log("Kernel error:", err);

            const {el, messageStr, cellLine} = errorDisplay(err, this.path);

            const id = err.id;
            const message = div(["message"], [
                para([], `${err.className}: ${err.message}`),
                para([], el)
            ]);
            this.tasks.updateTask(id, id, message, TaskStatus.Error, 0);

            // clean up (assuming that running another cell means users are done with this error)
            this.registerEventListener('CellResult', () => this.tasks.updateTask(id, id, message, TaskStatus.Complete, 100), {once: true})
        });

        // Check storage to see whether this should be collapsed
        const prefs = this.getStorage();
        if (prefs && prefs.collapsed) {
            this.collapse(true);
        }
    }

    getStorage() {
        return storage.get("KernelUI")
    }

    setStorage(obj: any) {
        storage.set("KernelUI", {...this.getStorage(), ...obj})
    }

    connect(evt: Event) {
        evt.stopPropagation();
        this.dispatchEvent(new UIEvent('Connect'))
    }

    startKernel(evt: Event) {
        evt.stopPropagation();
        this.dispatchEvent(new UIEvent('StartKernel', {path: this.path}))
    }

    killKernel(evt: Event) {
        evt.stopPropagation();
        this.dispatchEvent(new UIEvent('KillKernel', {path: this.path}))
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
            this.dispatchEvent(new UIEvent('ToggleKernelUI', {force: true}))
        } else if (prefs && prefs.collapsed) {
            this.setStorage({collapsed: false});
            this.dispatchEvent(new UIEvent('ToggleKernelUI'))
        } else {
            this.setStorage({collapsed: true});
            this.dispatchEvent(new UIEvent('ToggleKernelUI'))
        }
    }
}