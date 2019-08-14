// REMOVE SOCKET
import {UIEvent, UIEventTarget} from "../util/ui_event";
import {KernelInfoUI} from "./kernel_info";
import {KernelSymbolsUI} from "./symbol_table";
import {KernelTasksUI} from "./tasks";
import {div, h2, iconButton, para, span} from "../util/tags";
import * as messages from "../../data/messages";
import {TaskStatus} from "../../data/messages";
import {errorDisplay} from "./cell";
import {storage} from "../util/storage";

export class KernelUI extends UIEventTarget {
    constructor(socket, path, showInfo = true, showSymbols = true, showTasks = true, showStatus = true) {
        super();
        this.info = new KernelInfoUI();
        this.symbols = new KernelSymbolsUI(path).setEventParent(this);
        this.tasks = new KernelTasksUI();
        this.socket = socket;
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

        this.addEventListener('Connect', evt => {
            if (!socket.isOpen) {
                socket.reconnect();
            }
        });

        this.addEventListener('StartKernel', evt => {
            socket.send(new messages.StartKernel(path, messages.StartKernel.NoRestart));
        });

        this.addEventListener('KillKernel', evt => {
            socket.send(new messages.StartKernel(path, messages.StartKernel.Kill));
        });

        // TODO: shouldn't listen to socket directly
        socket.addMessageListener(messages.KernelStatus, (path, update) => {
            if (path === this.path) {
                switch (update.constructor) {
                    case messages.UpdatedTasks:
                        update.tasks.forEach(taskInfo => {
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

        socket.addEventListener('close', _ => {
            this.setKernelState('disconnected');
        });

        socket.addMessageListener(messages.Error, (code, err) => {
            console.log("Kernel error:", err);

            const {el, messageStr, cellLine} = errorDisplay(err);

            const id = err.id;
            const message = div(["message"], [
                para([], `${err.className}: ${err.message}`),
                para([], el)
            ]);
            this.tasks.updateTask(id, id, message, TaskStatus.Error, 0);

            // clean up (assuming that running another cell means users are done with this error)
            socket.addMessageListener(messages.CellResult, () => {
                this.tasks.updateTask(id, id, message, TaskStatus.Complete, 100);
                return false // make sure to remove the listener
            }, true);
        });

    }

    // Check storage to see whether this should be collapsed. Sends events, so must be called AFTER the element is created.
    init() {
        const prefs = this.getStorage();
        if (prefs && prefs.collapsed) {
            this.collapse(true);
        }
    }

    getStorage() {
        return storage.get("KernelUI")
    }

    setStorage(obj) {
        storage.set("KernelUI", {...this.getStorage(), ...obj})
    }

    connect(evt) {
        evt.stopPropagation();
        this.dispatchEvent(new UIEvent('Connect'))
    }

    startKernel(evt) {
        evt.stopPropagation();
        this.dispatchEvent(new UIEvent('StartKernel'))
    }

    killKernel(evt) {
        evt.stopPropagation();
        if (confirm("Kill running kernel? State will be lost.")) {
            this.dispatchEvent(new UIEvent('KillKernel'))
        }
    }

    setKernelState(state) {
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

    collapse(force) {
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