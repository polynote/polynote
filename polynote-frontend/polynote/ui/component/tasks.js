// GENERIFY (remove 'kernel' references?)
import {div, h3, h4} from "../util/tags";
import {TaskStatus} from "../../data/messages";

export class KernelTasksUI {
    constructor() {
        this.el = div(['kernel-tasks'], [
            h3([], ['Tasks']),
            this.taskContainer = div(['task-container'], [])
        ]);
        this.tasks = {};
    }

    clear() {
        while (this.taskContainer.firstChild) {
            this.taskContainer.removeChild(this.taskContainer.firstChild);
        }
        this.tasks = {};
    }

    addTask(id, label, detail, status, progress) {
        const taskEl = div(['task', (Object.keys(TaskStatus)[status] || 'unknown').toLowerCase()], [
            h4([], [label]),
            div(['detail'], [detail]),
            div(['progress'], [div(['progress-bar'], [])])
        ]);

        taskEl.labelText = label;
        taskEl.detailText = detail;
        taskEl.status = status;

        KernelTasksUI.setProgress(taskEl, progress);

        let before = this.taskContainer.firstChild;
        while (before && before.status <= status) {
            before = before.nextSibling;
        }

        this.taskContainer.insertBefore(taskEl, before);

        this.tasks[id] = taskEl;
    }

    static setProgress(el, progress) {
        const progressBar = el.querySelector('.progress-bar');
        progressBar.style.width = (progress * 100 / 255).toFixed(0) + "%";
    }

    taskStatus(id) {
        const task = this.tasks[id];
        return task && task.status;
    }

    updateTask(id, label, detail, status, progress) {
        if (!this.tasks[id]) {
            if (status > TaskStatus.Complete) {
                this.addTask(id, label, detail, status, progress);
            }
        } else {
            const task = this.tasks[id];
            if (task.labelText !== label) {
                const heading = task.querySelector('h4');
                heading.innerHTML = '';
                heading.appendChild(document.createTextNode(label));
                task.labelText = label;
            }
            if (task.detailText !== detail && typeof (detail) === "string") {
                const detailEl = task.querySelector('.detail');
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