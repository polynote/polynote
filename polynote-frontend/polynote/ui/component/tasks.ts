// GENERIFY (remove 'kernel' references?)
import {Content, div, h3, h4, TagElement} from "../util/tags";
import {TaskStatus} from "../../data/messages";

type KernelTask = TagElement<"div"> & {
    labelText: string,
    detailText: Content,
    status: number
}

export class KernelTasksUI {
    readonly el: TagElement<"div">;
    private taskContainer: TagElement<"div">;
    private tasks: Record<string, KernelTask> = {};

    constructor() {
        this.el = div(['kernel-tasks'], [
            h3([], ['Tasks']),
            this.taskContainer = div(['task-container'], [])
        ]);
    }

    clear() {
        while (this.taskContainer.firstChild) {
            this.taskContainer.removeChild(this.taskContainer.firstChild);
        }
        this.tasks = {};
    }

    addTask(id: string, label: string, detail: Content, status: number, progress: number) {
        const taskEl: KernelTask = Object.assign(div(['task', (Object.keys(TaskStatus)[status] || 'unknown').toLowerCase()], [
            h4([], [label]),
            div(['detail'], detail),
            div(['progress'], [div(['progress-bar'], [])])
        ]), {
            labelText: label,
            detailText: detail,
            status: status,
        });


        KernelTasksUI.setProgress(taskEl, progress);

        let before = this.taskContainer.firstChild as KernelTask;
        while (before && before.status <= status) {
            before = before.nextSibling as KernelTask;
        }

        this.taskContainer.insertBefore(taskEl, before);

        this.tasks[id] = taskEl;
    }

    static setProgress(el: KernelTask, progress: number) {
        const progressBar = el.querySelector('.progress-bar') as HTMLElement;
        progressBar.style.width = (progress * 100 / 255).toFixed(0) + "%";
    }

    taskStatus(id: string) {
        const task = this.tasks[id];
        return task && task.status;
    }

    updateTask(id: string, label: string, detail: Content, status: number, progress: number) {
        if (!this.tasks[id]) {
            if (status > TaskStatus.Complete) {
                this.addTask(id, label, detail, status, progress);
            }
        } else {
            const task = this.tasks[id];
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
            }

            const statusClass = (Object.keys(TaskStatus)[status] || 'unknown').toLowerCase();
            if (!task.classList.contains(statusClass)) {
                task.className = 'task';
                task.classList.add(statusClass);
                if (statusClass === "complete") {
                    setTimeout(() => {
                        if (task.parentNode) task.parentNode.removeChild(task);
                        delete this.tasks[id];
                    }, 100);
                }
            }
            task.status = status;
            KernelTasksUI.setProgress(task, progress);
        }
    }
}