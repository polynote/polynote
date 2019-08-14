// TODO: should we remember collapsed state across sessions?
import {div, h3, table} from "../util/tags";

export class KernelInfoUI {
    constructor() {
        this.el = div(['kernel-info'], [
            this.toggleEl = h3(['toggle'], ['...']).click(evt => this.toggleCollapse()),
            h3(['title'], ['Info']),
            this.infoEl = table(['info-container'], {
                header: false,
                classes: ['key', 'val'],
                rowHeading: false,
                addToTop: false
            }),
        ]);
        this.info = new Map();

        this.toggleVisibility();
    }

    toggleCollapse() {
        if (this.toggleEl.classList.contains('collapsed')) {
            this.toggleEl.classList.remove('collapsed')
            this.infoEl.style.display = null;
            this.el.querySelector(".title").style.display = null;
        } else {
            this.toggleEl.classList.add('collapsed')
            this.infoEl.style.display = "none";
            this.el.querySelector(".title").style.display = "none";
        }
    }

    updateInfo(content) {
        for (const [key, val] of Object.entries(content)) {
            if (val.size === 0) { // empty val is a proxy for removing key
                this.removeInfo(key);
            } else {
                this.addInfo(key, val);
            }
        }
    }

    addInfo(key, value) {
        this.info.set(key, value);
        this.toggleVisibility()
    }

    removeInfo(key) {
        this.info.delete(key);
        this.toggleVisibility()
    }

    clearInfo() {
        this.info.clear();
        this.toggleVisibility()
    }

    toggleVisibility() {
        if (this.info.size === 0) {
            this.el.style.display = "none";
        } else {
            this.renderInfo();
            this.el.style.display = "block";
        }
    }

    renderInfo() {
        for (const [k, v] of this.info) {
            const el = div([], []);
            el.innerHTML = v;
            if (this.infoEl.findRowsBy(row => row.key === k).length === 0) {
                this.infoEl.addRow({key: k, val: el.firstChild});
            }
        }
    }
}