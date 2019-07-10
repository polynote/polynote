'use strict';

import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import {} from './scala.js'
import {} from './theme.js'
import { LaTeXEditor } from './latex_editor.js'
import { UIEvent, UIEventTarget } from './ui_event.js'
import { FakeSelect } from './fake_select.js'
import { Cell, TextCell, CodeCell, BeforeCellRunEvent } from "./cell.js"
import { tag, para, span, button, iconButton, div, table, h2, h3, h4, textbox, dropdown } from './tags.js'
import { TaskStatus } from './messages.js';
import * as messages from './messages.js'
import { CompileErrors, Output, RuntimeError, ClearResults, ResultValue } from './result.js'
import { prefs } from './prefs.js'
import { ToolbarUI } from "./toolbar";
import match from "./match.js";
import {ExecutionInfo} from "./result";
import {CellMetadata} from "./messages";
import {Either} from "./codec";
import {errorDisplay} from "./cell";
import {Position} from "monaco-editor";

document.execCommand("defaultParagraphSeparator", false, "p");
document.execCommand("styleWithCSS", false, false);

export class MainToolbar extends EventTarget {
    constructor(el) {
        super();
        this.element = el;
        el.addEventListener('mousedown', (evt) => evt.preventDefault());
    }
}

export class KernelSymbolsUI {
    constructor() {
        this.symbols = {};
        this.presentedCell = 0;
        this.visibleCells = [];
        this.predefs = {};
        this.el = div(['kernel-symbols'], [
            h3([], ['Symbols']),
            this.tableEl = table(['kernel-symbols-table'], {
                header: ['Name', 'Type', 'Value'],
                classes: ['name', 'type', 'value'],
                rowHeading: true,
                addToTop: true
            })
        ]);
        this.resultSymbols = this.tableEl.tBodies[0].addClass('results');
        this.scopeSymbols = this.tableEl.addBody().addClass('scope-symbols');
    }

    updateRow(tr, name, type, value) {
        if (value === '') {
            tr.querySelector('.type').colSpan = '2';
            const v = tr.querySelector('.value');
            if (v)
                tr.removeChild(v);
            tr.updateValues({type: span([], type).attr('title', type)});
        } else {
            tr.querySelector('.type').colSpan = '1';
            const v = tr.querySelector('.value');
            if (!v) {
                tr.appendChild(tr.propCells.value);
            }
            tr.updateValues({type: span([], type).attr('title', type), value: span([], value).attr('title', value)})

        }
    }

    addRow(name, type, value, whichBody) {
        const tr = this.tableEl.addRow({name: name, type: span([], type).attr('title', type), value: span([], value).attr('title', value)}, whichBody);
        tr.data = {name, type, value};
        if (value === '') {
            tr.querySelector('.type').colSpan = '2';
            const v = tr.querySelector('.value');
            if (v)
                tr.removeChild(v)
        }
        return tr;
    }

    addScopeRow(name, type, value) {
        return this.addRow(name, type, value, this.scopeSymbols);
    }

    addResultRow(name, type, value) {
        return this.addRow(name, type, value, this.resultSymbols);
    }

    addSymbol(name, type, value, cellId, pos) {
        if (!this.symbols[cellId]) {
            this.symbols[cellId] = {};
        }
        const cellSymbols = this.symbols[cellId];
        cellSymbols[name] = {name, type, value};
        if (cellId < 0) {
            this.predefs[cellId] = cellId;
        }

        if (cellId === this.presentedCell) {
            const existing = this.tableEl.findRows({name}, this.resultSymbols)[0];
            if (existing) {
                this.updateRow(existing, name, type, value);
            } else {
                this.addResultRow(name, type, value);
            }
        } else if (this.visibleCells.indexOf(cellId) >= 0 || this.predefs[cellId]) {
            const existing = this.tableEl.findRows({name}, this.scopeSymbols)[0];
            if (existing) {
                this.updateRow(existing, name, type, value);
            } else {
                this.addScopeRow(name, type, value);
            }
        }
    }

    presentFor(id, visibleCellIds) {
        visibleCellIds = [...Object.values(this.predefs), ...visibleCellIds];
        this.presentedCell = id;
        this.visibleCells = visibleCellIds;
        const visibleSymbols = {};
        visibleCellIds.forEach(id => {
           const cellSymbols = this.symbols[id];
           for (const name in cellSymbols) {
               if (cellSymbols.hasOwnProperty(name)) {
                   visibleSymbols[name] = cellSymbols[name];
               }
           }
        });

        // update all existing symbols, remove any that aren't visible
        [...this.scopeSymbols.rows].forEach(row => {
            if (row.data) {
                const sym = visibleSymbols[row.data.name];
                if (sym === undefined) {
                    row.parentNode.removeChild(row);
                } else {
                    if (sym.value !== row.data.value || sym.type !== row.data.type) {
                        this.updateRow(row, sym.name, sym.type, sym.value);
                    }
                    delete visibleSymbols[sym.name]
                }
            }
        });

        // append all the remaining symbols
        for (const name in visibleSymbols) {
            if (visibleSymbols.hasOwnProperty(name)) {
                const sym = visibleSymbols[name];
                this.addScopeRow(sym.name, sym.type, sym.value);
            }
        }

        // clear the result rows
        this.resultSymbols.innerHTML = "";

        // add all results for the current cell
        if (this.symbols[id]) {
            const cellSymbols = this.symbols[id];
            for (const name in cellSymbols) {
                if (cellSymbols.hasOwnProperty(name)) {
                    const sym = cellSymbols[name];
                    this.addResultRow(sym.name, sym.type, sym.value);
                }
            }
        }
    }

    removeSymbol(name) {
        const existing = this.tableEl.findRowsBy(row => row.name === name);
        if (existing.length) {
            existing.forEach(tr => tr.parentNode.removeChild(tr));
        }
    }
}

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
            if (task.detailText !== detail && typeof(detail) === "string") {
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
                    setTimeout(() => { if (task.parentNode) task.parentNode.removeChild(task); delete this.tasks[id]; }, 100);
                }
            }
            task.status = status;
            KernelTasksUI.setProgress(task, progress);
        }
    }
}

// TODO: should we remember collapsed state across sessions?
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

export class SplitView {
    constructor(id, left, center, right) {
        this.left = left;
        this.center = center;
        this.right = right;
        let children = [];

        if (left) {
            const prefId = `${id}.leftSize`;
            left.el.classList.add("left");
            left.el.style.gridArea = 'left';
            left.el.style.width = prefs.get(prefId) || '300px';

            let leftDragger = div(['drag-handle', 'left'], [
                div(['inner'], []).attr('draggable', 'true')
            ]);
            leftDragger.style.gridArea = 'leftdrag';

            children.push(left.el);
            children.push(leftDragger);

            leftDragger.addEventListener('dragstart', (evt) => {
                leftDragger.initialX = evt.clientX;
                leftDragger.initialWidth = left.el.offsetWidth;
            });

            leftDragger.addEventListener('drag', (evt) => {
                evt.preventDefault();
                if (evt.clientX) {
                    left.el.style.width = (leftDragger.initialWidth + (evt.clientX - leftDragger.initialX )) + "px";
                }
            });

            leftDragger.addEventListener('dragend', (evt) => {
                prefs.set(prefId, left.el.style.width);
                window.dispatchEvent(new CustomEvent('resize', {}));
            });
        }

        if (center) {
            children.push(center.el);
        }

        if (right) {
            const prefId = `${id}.rightSize`;
            right.el.classList.add("right");
            right.el.style.gridArea = 'right';
            right.el.style.width = prefs.get(prefId) || '300px';

            let rightDragger = div(['drag-handle', 'right'], [
                div(['inner'], []).attr('draggable', 'true')
            ]);

            rightDragger.style.gridArea = 'rightdrag';

            children.push(rightDragger);
            children.push(right.el);

            rightDragger.addEventListener('dragstart', (evt) => {
                rightDragger.initialX = evt.clientX;
                rightDragger.initialWidth = right.el.offsetWidth;
            });

            rightDragger.addEventListener('drag', (evt) => {
                evt.preventDefault();
                if (evt.clientX) {
                    right.el.style.width = (rightDragger.initialWidth - (evt.clientX - rightDragger.initialX)) + "px";
                }
            });

            rightDragger.addEventListener('dragend', evt => {
                prefs.set(prefId, right.el.style.width);
                window.dispatchEvent(new CustomEvent('resize', {}));
            });
        }

        this.el = div(['split-view', id], children);
    }

    collapse(side, force) {
        if (side === 'left') {
            this.el.classList.toggle('left-collapsed', force || undefined) // undefined because we want it to toggle normally if we aren't forcing it.
            window.dispatchEvent(new CustomEvent('resize', {}));
        } else if (side === 'right') {
            this.el.classList.toggle('right-collapsed', force || undefined)
            window.dispatchEvent(new CustomEvent('resize', {}));
        } else {
            throw `Supported values are 'right' and 'left', got ${side}`
        }
    }
}

export class KernelUI extends UIEventTarget {
    constructor(socket) {
        super();
        this.info = new KernelInfoUI();
        this.symbols = new KernelSymbolsUI();
        this.tasks = new KernelTasksUI();
        this.socket = socket;
        this.el = div(['kernel-ui', 'ui-panel'], [
            h2([], [
                this.status = span(['status'], ['●']),
                'Kernel',
                span(['buttons'], [
                  iconButton(['connect'], 'Connect to server', '', 'Connect').click(evt => this.connect(evt)),
                  iconButton(['start'], 'Start kernel', '', 'Start').click(evt => this.startKernel(evt)),
                  iconButton(['kill'], 'Kill kernel', '', 'Kill').click(evt => this.killKernel(evt))
                ])
            ]).click(evt => this.collapse()),
            div(['ui-panel-content'], [
                this.info.el,
                this.symbols.el,
                this.tasks.el
            ])
        ]);
    }

    // Check prefs to see whether this should be collapsed. Sends events, so must be called AFTER the element is created.
    init() {
        const prefs = this.getPrefs();
        if (prefs && prefs.collapsed) {
            this.collapse(true);
        }
    }

    getPrefs() {
        return prefs.get("KernelUI")
    }

    setPrefs(obj) {
        prefs.set("KernelUI", {...this.getPrefs(), ...obj})
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
        this.el.classList.remove('busy', 'idle', 'dead', 'disconnected');
        if (state === 'busy' || state === 'idle' || state === 'dead' || state === 'disconnected') {
            this.el.classList.add(state);
            this.status.title = state;
            if (state === 'dead') {
                this.info.clearInfo();
            }
        } else {
            throw "State must be one of [busy, idle, dead, disconnected]";
        }
    }

    collapse(force) {
        const prefs = this.getPrefs();
        if (force) {
            this.dispatchEvent(new UIEvent('ToggleKernelUI', {force: true}))
        } else if (prefs && prefs.collapsed) {
            this.setPrefs({collapsed: false});
            this.dispatchEvent(new UIEvent('ToggleKernelUI'))
        } else {
            this.setPrefs({collapsed: true});
            this.dispatchEvent(new UIEvent('ToggleKernelUI'))
        }
    }
}

export class NotebookConfigUI extends UIEventTarget {
    constructor() {
        super();
        this.el = div(['notebook-config'], [
            h2(['config'], ['Configuration & dependencies']).click(evt => this.el.classList.toggle('open')),
            div(['content'], [
                div(['notebook-dependencies', 'notebook-config-section'], [
                    h3([], ['Dependencies']),
                    para([], ['Specify Maven coordinates for your dependencies, e.g. ', span(['pre'], ['org.myorg:package-name_2.11:1.0.1']), ', or URLs like ', span(['pre'], ['s3://path/to/my.jar'])]),
                    this.dependencyContainer = div(['dependency-list'], [
                        this.dependencyRowTemplate = div(['dependency-row', 'notebook-config-row'], [
                            textbox(['dependency'], 'Dependency coordinate or URL'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                this.addDependency(evt.currentTarget.parentNode.querySelector('.dependency').value);
                                this.dependencyRowTemplate.querySelector('.dependency').value = '';
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['notebook-resolvers', 'notebook-config-section'], [
                    h3([], ['Resolvers']),
                    para([], ['Specify any custom Ivy or Maven repositories here.']),
                    this.resolverContainer = div(['resolver-list'], [
                        this.resolverRowTemplate = div(['resolver-row', 'notebook-config-row', 'ivy'], [
                            dropdown(['resolver-type'], {ivy: 'Ivy', maven: 'Maven'}).change(evt => {
                                const self = evt.currentTarget;
                                const row = self.parentNode;
                                const value = self.options[self.selectedIndex].value;
                                row.className = 'resolver-row';
                                row.classList.add('notebook-config-row');
                                row.classList.add(value);
                            }),
                            textbox(['resolver-url'], 'Resolver URL or pattern'),
                            textbox(['resolver-artifact-pattern', 'ivy'], 'Artifact pattern (blank for default)'),
                            textbox(['resolver-metadata-pattern', 'ivy'], 'Metadata pattern (blank for default)'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                const row = evt.currentTarget.parentNode;
                                this.addResolver(this.mkResolver(row));
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['notebook-exclusions', 'notebook-config-section'], [
                    h3([], ['Exclusions']),
                    para([], ['Specify organization:module coordinates for your exclusions, i.e. ', span(['pre'], ['org.myorg:package-name_2.11'])]),
                    this.exclusionContainer = div(['exclusion-list'], [
                        this.exclusionRowTemplate = div(['exclusion-row', 'notebook-config-row'], [
                            textbox(['exclusion'], 'Exclusion organization:name'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                this.addExclusion(evt.currentTarget.parentNode.querySelector('.exclusion').value);
                                this.exclusionRowTemplate.querySelector('.exclusion').value = '';
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['notebook-spark-config', 'notebook-config-section'], [
                    h3([], ['Spark Config']),
                    para([], ['Set Spark configuration for this notebook here. Please note that it is possible that your environment may override some of these settings at runtime :(']),
                    this.sparkConfigContainer = div(['spark-config-list'], [
                        this.sparkConfigRowTemplate = div(['spark-config-row', 'notebook-config-row'], [
                            textbox(['spark-config-key'], 'key'),
                            textbox(['spark-config-val'], 'value'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                this.addSparkConfig([
                                    evt.currentTarget.parentNode.querySelector('.spark-config-key').value.trim(),
                                    evt.currentTarget.parentNode.querySelector('.spark-config-val').value.trim()
                                ]);
                                this.sparkConfigRowTemplate.querySelector('.spark-config-key').value = '';
                                this.sparkConfigRowTemplate.querySelector('.spark-config-val').value = '';
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['controls'], [
                    button(['save'], {}, ['Save & Restart']).click(evt => {
                        this.lastConfig = this.config;
                        this.dispatchEvent(new UIEvent('UpdatedConfig', { config: this.config }));
                    }),
                    button(['cancel'], {}, ['Cancel']).click(evt => {
                        if (this.lastConfig) {
                            this.setConfig(this.lastConfig);
                        }
                        this.el.classList.remove("open");
                    })
                ])
            ])
        ]);
    }

    mkResolver(row) {
        const typeSelect = row.querySelector('.resolver-type');
        const type = typeSelect.options[typeSelect.selectedIndex].value;
        if (type === 'ivy') {
            return new messages.IvyRepository(
                row.querySelector('.resolver-url').value,
                row.querySelector('.resolver-artifact-pattern').value || null,
                row.querySelector('.resolver-metadata-pattern').value || null,
                null
            );
        } else if (type === 'maven') {
            return new messages.MavenRepository(
                row.querySelector('.resolver-url').value,
                null
            );
        }
    }

    addDependency(value) {
        const row = this.dependencyRowTemplate.cloneNode(true);
        row.querySelector('.dependency').value = value;
        row.querySelector('.remove').addEventListener('click', evt => {
            row.innerHTML = '';
            row.parentNode.removeChild(row);
        });
        this.dependencyContainer.insertBefore(row, this.dependencyRowTemplate);
    }

    addExclusion(value) {
        const row = this.exclusionRowTemplate.cloneNode(true);
        row.querySelector('.exclusion').value = value;
        row.querySelector('.remove').addEventListener('click', evt => {
            row.innerHTML = '';
            row.parentNode.removeChild(row);
        });
        this.exclusionContainer.insertBefore(row, this.exclusionRowTemplate);
    }

    addResolver(value) {
        const row = this.resolverRowTemplate.cloneNode(true);
        row.querySelector('.resolver-url').value = value.base;

        const type = value.constructor.msgTypeId;

        if (value instanceof messages.IvyRepository) {
            row.querySelector('.resolver-artifact-pattern').value = value.artifactPattern || '';
            row.querySelector('.resolver-metadata-pattern').value = value.metadataPattern || '';
        }

        const typeSelect = row.querySelector('.resolver-type');
        typeSelect.selectedIndex = type;

        row.querySelector('.remove').addEventListener('click', evt => {
            row.innerHTML = '';
            row.parentNode.removeChild(row);
        });

        this.resolverContainer.insertBefore(row, this.resolverRowTemplate);
    }

    addSparkConfig(value) {
        const row = this.sparkConfigRowTemplate.cloneNode(true);
        row.querySelector('.spark-config-key').value = value[0] || '';
        row.querySelector('.spark-config-val').value = value[1] || '';
        row.querySelector('.remove').addEventListener('click', evt => {
            row.innerHTML = '';
            row.parentNode.removeChild(row);
        });
        this.sparkConfigContainer.insertBefore(row, this.sparkConfigRowTemplate);
    }

    clearConfig() {
        const containers = new Map([
            [this.dependencyContainer, this.dependencyRowTemplate],
            [this.exclusionContainer, this.exclusionRowTemplate],
            [this.resolverContainer, this.resolverRowTemplate],
            [this.sparkConfigContainer, this.sparkConfigRowTemplate]
        ]);

        for (const [container, template] of containers) {
            while (container.childNodes.length > 0) {
                container.removeChild(container.childNodes[0]);
            }
            container.appendChild(template);
            [...container.querySelectorAll('input')].forEach(input => input.value = '');
        }
    }

    setConfig(config) {
        this.lastConfig = config;
        this.clearConfig();

        if (config.dependencies && config.dependencies.scala) {
            for (const dep of config.dependencies.scala) {
                this.addDependency(dep);
            }
        }

        if (config.exclusions) {
            for (const excl of config.exclusions) {
                this.addExclusion(excl);
            }
        }

        if(config.repositories) {
            for (const repository of config.repositories) {
                this.addResolver(repository);
            }
        }

        if(config.sparkConfig) {
            for (const entry of Object.entries(config.sparkConfig)) {
                this.addSparkConfig(entry);
            }
        }
    }

    get config() {
        const deps = [];
        const depInputs = this.dependencyContainer.querySelectorAll('.dependency-row input');
        depInputs.forEach(input => {
            if (input.value) deps.push(input.value);
        });

        const exclusions = [];
        const exclusionInputs = this.exclusionContainer.querySelectorAll('.exclusion-row input');
        exclusionInputs.forEach(input => {
            if (input.value) exclusions.push(input.value);
        });

        const repos = [];
        const repoRows = this.resolverContainer.querySelectorAll('.resolver-row');
        repoRows.forEach(row => {
            const repository = this.mkResolver(row);
            if (repository.base) {
                repos.push(repository);
            }
        });

        const sparkConfig = {};
        const sparkConfigRows = this.sparkConfigContainer.querySelectorAll('.spark-config-row');
        sparkConfigRows.forEach(row => {
            const k = row.querySelector('.spark-config-key').value.trim();
            const v = row.querySelector('.spark-config-val').value.trim();
            if (k) sparkConfig[k] = v;
        });

        return new messages.NotebookConfig(
            {scala: deps},
            exclusions,
            repos,
            sparkConfig
        );
    }

}

export class NotebookCellsUI extends UIEventTarget {
    constructor(path) {
        super();
        this.configUI = new NotebookConfigUI().setEventParent(this);
        this.path = path;
        this.el = div(['notebook-cells'], [this.configUI.el, this.newCellDivider()]);
        this.el.cellsUI = this;  // TODO: this is hacky and bad (using for getting to this instance via the element, from the tab content area of MainUI#currentNotebook)
        this.cells = {};
        this.cellCount = 0;
        window.addEventListener('resize', this.forceLayout.bind(this));
    }

    newCellDivider() {
        const self = this;
        return div(['new-cell-divider'], []).click(function() {
            const nextCell = self.getCellAfterEl(this);
            if (nextCell) {
                self.dispatchEvent(new UIEvent('InsertCellBefore', {cellId: nextCell.id}));
            } else { // last cell
                self.dispatchEvent(new UIEvent('InsertCellAfter', {cellId: self.getCellBeforeEl(this).id}));
            }
        });
    }

    setStatus(id, status) {
        const cell = this.getCell(id);
        if (!cell) return;

        switch (status.status) {
            case TaskStatus.Complete:
                cell.container.classList.remove('running', 'queued', 'error');
                break;

            case TaskStatus.Error:
                cell.container.classList.remove('queued', 'running');
                cell.container.classList.add('error');
                break;

            case TaskStatus.Queued:
                cell.container.classList.remove('running', 'error');
                cell.container.classList.add('queued');
                break;

            case TaskStatus.Running:
                cell.container.classList.remove('queued', 'error');
                cell.container.classList.add('running');
                const progressBar = cell.container.querySelector('.progress-bar');
                if (progressBar && status.progress) {
                    progressBar.style.width = (status.progress * 100 / 255).toFixed(0) + "%";
                }


        }
    }

    setExecutionHighlight(id, pos) {
        const cell = this.getCell(id);
        if (cell instanceof CodeCell) {
            cell.setHighlight(pos, "currently-executing");
        }
    }

    firstCell() {
        return this.getCells()[0];
    }

    getCell(cellId) {
        return this.cells[cellId];
    }

    getCellBeforeEl(el) {
        let before = this.el.firstElementChild;
        let cell = undefined;
        while(before !== el) {
            if (before && before.cell) {
                cell = before.cell;
            }
            before = before.nextElementSibling;
        }
        return cell
    }

    getCellAfterEl(el) {
        let after = this.el.lastElementChild;
        let cell = undefined;
        while(after !== el) {
            if (after && after.cell) {
                cell = after.cell;
            }
            after = after.previousElementSibling;
        }
        return cell
    }

    getCells() {
        return Array.from(this.el.children)
            .map(container => container.cell)
            .filter(cell => cell);
    }

    getCodeCellIds() {
        return this.getCells().filter(cell => cell instanceof CodeCell).map(cell => cell.id);
    }

    getCodeCellIdsBefore(id) {
        const result = [];
        let child = this.el.firstElementChild;
        while (child && (!child.cell || child.cell.id !== id)) {
            if (child.cell) {
                result.push(child.cell.id);
            }
            child = child.nextElementSibling;
        }
        return result;
    }

    forceLayout(evt) {
        if (this.resizeTimeout) {
            clearTimeout(this.resizeTimeout);
        }
        this.resizeTimeout = setTimeout(() => {
            this.getCells().forEach((cell) => {
                if (cell instanceof CodeCell) {
                    cell.editor.layout();
                }
            });
            // scroll to previous position, if any
            const scrollPosition = prefs.get('notebookLocations')[this.path];
            if (scrollPosition || scrollPosition === 0) {
                this.el.parentElement.scrollTop = scrollPosition;
            }
        }, 333);
    }
    
    addCell(cell) {
        this.cellCount++;
        this.el.appendChild(cell.container);
        this.el.appendChild(this.newCellDivider());
        this.setupCell(cell);
    }

    insertCell(cell, after) {
        let prevCell = after;
        if (after && after instanceof Cell) {
            prevCell = after.container;
        } else if ((after || after === 0) && this.getCell(after)) {
            prevCell = this.getCell(after).container;
        } else if (!after) {
            prevCell = this.configUI.el;
        }

        this.cellCount++;

        const prevCellDivider = prevCell.nextElementSibling;

        const newDivider = this.newCellDivider();
        this.el.insertBefore(cell.container, prevCellDivider);
        this.el.insertBefore(newDivider, cell.container);

        this.setupCell(cell);
    }

    removeCell(cellId) {
        const cell = this.getCell(cellId);
        if (cell) {
            const divider = cell.container.nextElementSibling;
            this.el.removeChild(cell.container);
            if (divider) {
                this.el.removeChild(divider);
            } else {
                throw ["couldn't find divider after", cell.container] // why wasn't the divider there??
            }
            delete this.cells[cellId];
            cell.dispose();
            cell.container.innerHTML = '';
        }
    }

    setupCell(cell) {
        this.cells[cell.id] = cell;
        if (cell.editor && cell.editor.layout) {
            cell.editor.layout();
        }
        cell.setEventParent(this);

        cell.nextCell = () => {
            return this.getCellAfterEl(cell.container);
        };

        cell.prevCell = () => {
            return this.getCellBeforeEl(cell.container);
        }
    }

    setCellLanguage(cell, language) {
        const currentCell = this.getCell(cell.id);
        if (cell !== currentCell){
            throw { message: "Cell with that ID is not the same cell as the target cell" };
        }


        if (currentCell.language === language)
            return;

        if (currentCell instanceof TextCell && language !== 'text') {
            // replace text cell with a code cell
            const newCell = new CodeCell(currentCell.id, currentCell.content, language, this.path);
            this.el.replaceChild(newCell.container, currentCell.container);
            currentCell.dispose();
            this.setupCell(newCell);
            newCell.focus();
        } else if (currentCell instanceof CodeCell && language === 'text') {
            // replace code cell with a text cell
            const newCell = new TextCell(currentCell.id, currentCell.content, this.path);
            this.el.replaceChild(newCell.container, currentCell.container);
            currentCell.dispose();
            this.setupCell(newCell);
            newCell.focus();
        } else {
            // already a code cell, change the language
            monaco.editor.setModelLanguage(currentCell.editor.getModel(), language);
            currentCell.setLanguage(language);
        }
    }
}

class EditBuffer {

    constructor() {
        this.versions = [];
    }

    /**
     * Add edits corresponding to a version. The version should always increase.
     * @param version The version corresponding to the edits
     * @param edits   The edits
     */
    push(version, edits) {
        this.versions.push({version, edits});
    }

    /**
     * Discard edits with versions before the given version
     * @param until The earliest version to keep
     */
    discard(until) {
        while (this.versions.length > 0 && this.versions[0].version < until) {
            this.versions.shift();
        }
    }

    /**
     * Retrieve edits corresponding to a range of versions from the buffer
     *
     * @param from The start version, exclusive
     * @param to   The end version, inclusive
     * @returns {Array}
     */
    range(from, to) {
        let i = 0;
        while (i < this.versions.length && this.versions[i].version <= from) {
            i++;
        }
        const edits = [];
        while (i < this.versions.length && this.versions[i].version <= to) {
            edits.push(...this.versions[i].edits);
            i++;
        }
        return edits;
    }
}

function maxId(cells) {
    let max = -1;
    cells.forEach(cell => {
        if (cell.id > max) {
            max = cell.id;
        }
    });
    return max;
}

export class NotebookUI extends UIEventTarget {
    constructor(path, socket, mainUI) {  // TODO: Maybe UI shouldn't talk directly to session? I dunno...
        super();
        let cellUI = new NotebookCellsUI(path).setEventParent(this);
        cellUI.notebookUI = this;
        let kernelUI = new KernelUI().setEventParent(this);
        //super(null, cellUI, kernelUI);
        //this.el.classList.add('notebook-ui');
        this.path = path;
        this.socket = socket;
        this.cellUI = cellUI;
        this.kernelUI = kernelUI;

        this.globalVersion = 0;
        this.localVersion = 0;

        this.editBuffer = new EditBuffer();

        this.cellUI.addEventListener('UpdatedConfig', evt => {
            const update = new messages.UpdateConfig(path, this.globalVersion, ++this.localVersion, evt.detail.config);
            this.editBuffer.push(this.localVersion, update);
            this.kernelUI.tasks.clear(); // old tasks no longer relevant with new config.
            this.socket.send(update);
        });

        this.cellUI.addEventListener('SelectCell', evt => {
            const cellTypeSelector = mainUI.toolbarUI.cellToolbar.cellTypeSelector;
            const id = evt.detail.cellId;
            let i = 0;

            // update cell type selector
            for (const opt of cellTypeSelector.options) {
                if (opt.value === evt.detail.cell.language) {
                    cellTypeSelector.selectedIndex = i;
                    break;
                }
                i++;
            }

            // notify toolbar of context change
            mainUI.toolbarUI.onContextChanged();

            // check if element is in viewport
            const viewport = mainUI.notebookContent;
            const viewportScrollTop = viewport.scrollTop;
            const viewportScrollBottom = viewportScrollTop + viewport.clientHeight;

            const container = evt.detail.cell.container;
            const elTop = container.offsetTop;
            const elBottom = elTop + container.offsetHeight;

            if (elBottom < viewportScrollTop) { // need to scroll up
                evt.detail.cell.container.scrollIntoView({behavior: "auto", block: "start", inline: "nearest"})
            } else if (elTop > viewportScrollBottom) { // need to scroll down
                evt.detail.cell.container.scrollIntoView({behavior: "auto", block: "end", inline: "nearest"})
            }

            // update the symbol table to reflect what's visible from this cell
            const ids = this.cellUI.getCodeCellIdsBefore(id);
            this.kernelUI.symbols.presentFor(id, ids);
        });

        this.cellUI.addEventListener('AdvanceCell', evt => {
            if (Cell.currentFocus) {
                if (evt.backward) {
                    const prev = Cell.currentFocus.prevCell();
                    if (prev) {
                        prev.focus();
                    }
                } else {
                    const next = Cell.currentFocus.nextCell();
                    if (next) {
                        next.focus();
                    } else {
                        this.cellUI.dispatchEvent(new UIEvent('InsertCellAfter', {cellId: Cell.currentFocus.id}));
                    }
                }
            }
        });

        this.cellUI.addEventListener('InsertCellAfter', evt => {
           const current = this.cellUI.getCell(evt.detail.cellId) || this.cellUI.getCell(this.cellUI.firstCell().id);
           const nextId = maxId(this.cellUI.getCells()) + 1;
           const newCell = current.language === 'text' ? new TextCell(nextId, '', this.path) : new CodeCell(nextId, '', current.language, this.path);
           const update = new messages.InsertCell(path, this.globalVersion, ++this.localVersion, new messages.NotebookCell(newCell.id, newCell.language, ''), current.id);
           this.socket.send(update);
           this.editBuffer.push(this.localVersion, update);
           this.cellUI.insertCell(newCell, current);
           newCell.focus();
        });

        this.cellUI.addEventListener('InsertCellBefore', evt => {
            const current = this.cellUI.getCell(evt.detail.cellId) || this.cellUI.firstCell();
            const nextId = maxId(this.cellUI.getCells()) + 1;
            const newCell = current.language === 'text' ? new TextCell(nextId, '', this.path) : new CodeCell(nextId, '', current.language, this.path);
            if (current === this.cellUI.firstCell()) {
                const update = new messages.InsertCell(path, this.globalVersion, this.localVersion++, new messages.NotebookCell(newCell.id, newCell.language, ''), -1);
                this.socket.send(update);
                this.cellUI.insertCell(newCell, null);
            } else {
                const prev = current.prevCell();
                const update = new messages.InsertCell(path, this.globalVersion, ++this.localVersion, new messages.NotebookCell(newCell.id, newCell.language, ''), prev.id);
                this.socket.send(update);
                this.cellUI.insertCell(newCell, prev);

            }
            newCell.focus();
        });

        this.cellUI.addEventListener('DeleteCell', evt => {
            const current = Cell.currentFocus;
            if (current) {
                const allCellIds = this.cellUI.getCells().map(cell => cell.id);
                const currentIndex = allCellIds.indexOf(current.id);
                if (currentIndex < 0) {
                    throw "Active cell is not part of current notebook?"
                }
                const prevCells = allCellIds.slice(0, currentIndex);

                const update = new messages.DeleteCell(path, this.globalVersion, ++this.localVersion, current.id);
                this.socket.send(update);
                this.editBuffer.push(this.localVersion, update);
                const nextCell = current.nextCell();

                const cell = new messages.NotebookCell(current.id, current.language, current.content);

                const undoEl = div(['undo-delete'], [
                    span(['close-button', 'fa'], ['']).click(evt => {
                        undoEl.parentNode.removeChild(undoEl);
                    }),
                    span(['undo-message'], [
                        'Cell deleted. ',
                        span(['undo-link'], ['Undo']).click(evt => {
                            let prevCell = prevCells.pop();
                            while (prevCells.length && !this.cellUI.getCell(prevCell)) {
                                prevCell = prevCells.pop();
                            }

                            const update = new messages.InsertCell(path, this.globalVersion, ++this.localVersion, cell, this.cellUI.getCell(prevCell) ? prevCell : null);
                            this.socket.send(update);
                            const newCell = cell.language === 'text' ? new TextCell(cell.id, cell.content, this.path) : new CodeCell(cell.id, cell.content, cell.language, this.path);
                            this.cellUI.insertCell(newCell, prevCell);
                            undoEl.parentNode.removeChild(undoEl);
                        })
                    ])
                ]);

                if (nextCell) {
                    nextCell.focus();
                    nextCell.container.parentNode.insertBefore(undoEl, nextCell.container);
                } else {
                    current.container.parentNode.insertBefore(undoEl, current.container);
                }
                this.cellUI.removeCell(current.id);
            }
        });

        this.cellUI.addEventListener('RunCell', (evt) => {
            this.runCells(evt.detail.cellId);
        });

        this.cellUI.addEventListener('RunCurrentCell', () => {
            this.runCells(Cell.currentFocus.id);
        });

        this.cellUI.addEventListener('RunAll', () => {
            const cellIds = this.cellUI.getCodeCellIds();
            this.runCells(cellIds);
        });

        this.cellUI.addEventListener('RunToCursor', () => {
            const allCellIds = this.cellUI.getCodeCellIds();
            const activeCellIdx = allCellIds.indexOf(Cell.currentFocus.id);
            if (activeCellIdx < 0) {
                console.log("Active cell is not part of current notebook?")
            } else {
                const cellIds = this.cellUI.getCodeCellIds();
                this.runCells(cellIds.slice(0, activeCellIdx + 1));
            }
        });

        this.cellUI.addEventListener('ContentChange', (evt) => {
            const update = new messages.UpdateCell(path, this.globalVersion, ++this.localVersion, evt.detail.cellId, evt.detail.edits);
            this.socket.send(update);
            this.editBuffer.push(this.localVersion, update);
        });

        this.cellUI.addEventListener('CompletionRequest', (evt) => {
            const id = evt.detail.id;
            const pos = evt.detail.pos;
            const resolve = evt.detail.resolve;
            const reject = evt.detail.reject;

            const receiveCompletions = (notebook, cell, receivedPos, completions) => {
                if (notebook === path && cell === id && pos === receivedPos) {
                    this.socket.removeMessageListener(messages.CompletionsAt, receiveCompletions);
                    const completionResults = completions.map(candidate => {
                        const isMethod = candidate.params.length > 0 || candidate.typeParams.length > 0;

                        const typeParams = candidate.typeParams.length ? `[${candidate.typeParams.join(', ')}]`
                            : '';

                        const params = isMethod ? candidate.params.map(pl => `(${pl.map(param => `${param.name}: ${param.type}`).join(', ')})`).join('')
                            : '';

                        const label = `${candidate.name}${typeParams}${params}`;

                        const insertText =
                            candidate.name + (typeParams.length ? '[$1]' : '') + (params.length ? '($2)' : '');

                        return {
                            kind: isMethod ? 1 : 9,
                            label: label,
                            insertText: insertText,
                            insertTextRules: 4,
                            detail: candidate.type
                        }
                    });
                    //console.log(completionResults);
                    resolve({suggestions: completionResults});
                }
            };

            this.socket.addMessageListener(messages.CompletionsAt, receiveCompletions);
            this.socket.send(new messages.CompletionsAt(path, id, pos, []));
        });

        this.cellUI.addEventListener('ParamHintRequest', (evt) => {
            const id = evt.detail.id;
            const pos = evt.detail.pos;
            const resolve = evt.detail.resolve;
            const reject = evt.detail.reject;

            const receiveHints = (notebook, cell, receivedPos, signatures) => {
                if (notebook === path && cell === id && pos === receivedPos) {
                    this.socket.removeMessageListener(messages.ParametersAt, receiveHints);
                    if (signatures != null) {
                        resolve({
                            activeParameter: signatures.activeParameter,
                            activeSignature: signatures.activeSignature,
                            signatures: signatures.hints.map(sig => {
                                const params = sig.parameters.map(param => {
                                    return {
                                        label: `${param.name}: ${param.typeName}`,
                                        documentation: param.docString
                                    };
                                });

                                return {
                                    documentation: sig.docString,
                                    label: sig.name,
                                    parameters: params
                                }
                            })
                        });
                    } else resolve(null);
                }
            };

            this.socket.addMessageListener(messages.ParametersAt, receiveHints);
            this.socket.send(new messages.ParametersAt(path, id, pos, null))
        });

        this.cellUI.addEventListener("ReprDataRequest", evt => {
            const req = evt.detail;
            this.socket.listenOnceFor(messages.HandleData, (path, handleType, handleId, count, data) => {
                if (path === this.path && handleType === req.handleType && handleId === req.handleId) {
                    req.onComplete(data);
                    return false;
                } else return true;
            });
            this.socket.send(new messages.HandleData(path, req.handleType, req.handleId, req.count, []));
        });

        this.kernelUI.addEventListener('Connect', evt => {
            if (!this.socket.isOpen) {
                this.socket.reconnect();
            }
        });

        this.kernelUI.addEventListener('StartKernel', evt => {
           this.socket.send(new messages.StartKernel(path, messages.StartKernel.NoRestart));
        });

        this.kernelUI.addEventListener('KillKernel', evt => {
           this.socket.send(new messages.StartKernel(path, messages.StartKernel.Kill));
        });

        socket.addMessageListener(messages.NotebookCells, this.onCellsLoaded.bind(this));

        socket.addMessageListener(messages.KernelStatus, (path, update) => {
            if (path === this.path) {
                switch (update.constructor) {
                    case messages.UpdatedTasks:
                        update.tasks.forEach(taskInfo => {
                            this.kernelUI.tasks.updateTask(taskInfo.id, taskInfo.label, taskInfo.detail, taskInfo.status, taskInfo.progress);

                            // TODO: this is a quick-and-dirty running cell indicator. Should do this in a way that doesn't use the task updates
                            //       and instead have an EOF message to tell us when a cell is done
                            const cellMatch = taskInfo.id.match(/^Cell (\d+)$/);
                            if (cellMatch && cellMatch[1]) {
                                this.cellUI.setStatus(+(cellMatch[1]), taskInfo);
                            }
                        });
                        break;

                    case messages.KernelBusyState:
                        const state = (update.busy && 'busy') || (!update.alive && 'dead') || 'idle';
                        this.kernelUI.setKernelState(state);
                        break;

                    case messages.KernelInfo:
                        this.kernelUI.info.updateInfo(update.content);
                        break;

                    case messages.ExecutionStatus:
                        this.cellUI.setExecutionHighlight(update.cellId, update.pos);
                        break;
                }
            }
        });

        socket.addMessageListener(messages.NotebookUpdate, update => {
            if (update.path === this.path) {
                if (update.globalVersion >= this.globalVersion) {
                    this.globalVersion = update.globalVersion;

                    if (update.localVersion < this.localVersion) {
                        const prevUpdates = this.editBuffer.range(update.localVersion, this.localVersion);
                        update = messages.NotebookUpdate.rebase(update, prevUpdates);
                    }


                    this.localVersion++;

                    match(update)
                        .when(messages.UpdateCell, (p, g, l, id, edits) => {
                            const cell = this.cellUI.getCell(id);
                            if (cell) {
                                cell.applyEdits(edits);
                                this.editBuffer.push(this.localVersion, messages);
                            }
                        })
                        .when(messages.InsertCell, (p, g, l, cell, after) => {
                            const prev = this.cellUI.getCell(after);
                            const newCell = (prev && prev.language && prev.language !== "text")
                                            ? new CodeCell(cell.id, cell.content, cell.language, this.path)
                                            : new TextCell(cell.id, cell.content, this.path);
                            this.cellUI.insertCell(newCell, after)
                        })
                        .when(messages.DeleteCell, (p, g, l, id) => this.cellUI.removeCell(id))
                        .when(messages.UpdateConfig, (p, g, l, config) => this.cellUI.configUI.setConfig(config))
                        .when(messages.SetCellLanguage, (p, g, l, id, language) => this.cellUI.setCellLanguage(this.cellUI.getCell(id), language))
                        .otherwise();


                    // discard edits before the local version from server – it will handle rebasing at least until that point
                    this.editBuffer.discard(update.localVersion);

                }
            }
        });

        socket.addEventListener('close', evt => {
            this.kernelUI.setKernelState('disconnected');
            socket.addEventListener('open', evt => this.socket.send(new messages.KernelStatus(path, new messages.KernelBusyState(false, false))));
        });

        socket.addMessageListener(messages.Error, (code, err) => {
            console.log("Kernel error:", err);

            const {el, messageStr, cellLine} = errorDisplay(err);

            const id = err.id;
            const message = div(["message"], [
                para([], `${err.className}: ${err.message}`),
                para([], el)
            ]);
            this.kernelUI.tasks.updateTask(id, id, message, TaskStatus.Error, 0);

            // clean up (assuming that running another cell means users are done with this error)
            socket.addMessageListener(messages.CellResult, () => {
                this.kernelUI.tasks.updateTask(id, id, message, TaskStatus.Complete, 100);
                return false // make sure to remove the listener
            }, true);
        });

        socket.addMessageListener(messages.CellResult, (path, id, result) => {
            if (path === this.path) {
                const cell = this.cellUI.getCell(id);
                if (cell instanceof CodeCell) {
                    if (result instanceof CompileErrors) {
                        cell.setErrors(result.reports);
                    } else if (result instanceof RuntimeError) {
                        console.log(result.error);
                        cell.setRuntimeError(result.error);
                    } else if (result instanceof Output) {
                        cell.addOutput(result.contentType, result.content);
                    } else if (result instanceof ClearResults) {
                        cell.clearResult();
                    } else if (result instanceof ExecutionInfo) {
                        cell.setExecutionInfo(result);
                    } else if (result instanceof ResultValue) {
                        cell.addResult(result);
                    }
                }

                if (result instanceof ResultValue) {
                    this.kernelUI.symbols.addSymbol(
                        result.name,
                        result.typeName,
                        result.valueText,
                        result.sourceCell,
                        result.pos);
                }
            }
        });
    }

    runCells(cellIds) {
        if (!(cellIds instanceof Array)) {
            cellIds = [cellIds];
        }
        cellIds.forEach(id => {
            const cell = this.cellUI.getCell(id);
            if (cell) {
                cell.dispatchEvent(new BeforeCellRunEvent(id));
            }
        });
        this.socket.send(new messages.RunCell(this.path, cellIds));
    }

    onCellLanguageSelected(setLanguage, path) {
        if (Cell.currentFocus && this.cellUI.getCell(Cell.currentFocus.id) && this.cellUI.getCell(Cell.currentFocus.id).language !== setLanguage) {
            const id = Cell.currentFocus.id;
            this.cellUI.setCellLanguage(Cell.currentFocus, setLanguage);
            this.socket.send(new messages.SetCellLanguage(path, this.globalVersion, this.localVersion++, id, setLanguage));
        }
    }
    
    onCellsLoaded(path, cells, config) {
        console.log(`Loaded ${path}`);
        if (path === this.path) {
            if (config) {
                this.cellUI.configUI.setConfig(config);
            } else {
                this.cellUI.configUI.setConfig(messages.NotebookConfig.default);
            }
            // TODO: move all of this logic out.
            this.socket.removeMessageListener(messages.NotebookCells, this.onCellsLoaded);
            for (const cellInfo of cells) {
                let cell = null;
                switch (cellInfo.language) {
                    case 'text':
                    case 'markdown':
                        cell = new TextCell(cellInfo.id, cellInfo.content, path, cellInfo.metadata);
                        break;
                    default:
                        cell = new CodeCell(cellInfo.id, cellInfo.content, cellInfo.language, path, cellInfo.metadata);
                }

                this.cellUI.addCell(cell);
                cellInfo.results.forEach(
                    result => {
                        if (result instanceof CompileErrors) {
                            cell.setErrors(result.reports)
                        } else if (result instanceof RuntimeError) {
                            cell.setRuntimeError(result.error)
                        } else if (result instanceof Output) {
                            cell.addOutput(result.contentType, result.content)
                        } else if (result instanceof ResultValue) {
                            cell.addResult(result);
                        }
                    }
                )
            }
        }
    }
}

export class TabUI extends EventTarget {

    constructor(contentAreas) {
        super();
        this.el = div(['tabbed-pane'], [
            this.tabContainer = div(['tab-container'], [])
        ]);

        this.contentAreas = contentAreas;

        this.tabs = {};
        this.tabEls = {};
    }

    addTab(name, title, content, type) {
        const tab = {
            name: name,
            title: title,
            content: content,
            type: type
        };

        this.tabs[name] = tab;
        const tabEl = div(['tab'], [
            title,
            span(['close-button', 'fa'], ['']).click(evt => this.removeTab(tab))
        ]).attr('title', name);
        tabEl.tab = tab;

        this.tabEls[name] = tabEl;

        tabEl.addEventListener('mousedown', evt => {
            if (evt.button === 0) { // left click
                this.activateTab(tab);
            } else if (evt.button === 1) { // middle click
                this.removeTab(tab)
            } // nothing on right click...
        });

        this.tabContainer.appendChild(tabEl);

        if (this.currentTab !== tab) {
            this.activateTab(tab);
        }
        return tab;
    }

    removeTab(tab) {
        const tabEl = this.tabEls[tab.name];

        if (this.currentTab === tab) {
            if (tabEl) {
                const nextTab = tabEl.previousSibling || tabEl.nextSibling;
                if (nextTab && nextTab.tab) {
                    this.activateTab(nextTab.tab);
                } else {
                    setTimeout(() => this.dispatchEvent(new UIEvent('NoActiveTab')), 0);
                }
            }
        }

        if (tabEl) {
            this.tabContainer.removeChild(tabEl);
        }

        delete this.tabEls[tab.name];
        delete this.tabs[tab.name];

        this.dispatchEvent(new UIEvent('TabRemoved', { name: tab.name }));

        // if (tab.content && tab.content.parentNode) {
        //     tab.content.parentNode.removeChild(tab.content);
        // }
    }

    activateTabName(name) {
        if (this.tabs[name]) {
            this.activateTab(this.tabs[name]);
            return true;
        } else {
            return false;
        }
    }

    activateTab(tab) {
        if (this.currentTab && this.currentTab === tab) {
            return;
        } else if (this.currentTab) {
            // remember previous location
            prefs.update('notebookLocations', locations => {
                if (!locations) {
                    locations = {};
                }

                locations[this.currentTab.name] = this.currentTab.content.notebook.parentElement.scrollTop;
                return locations;
            });

            for (const area in this.contentAreas) {
                if (this.contentAreas.hasOwnProperty(area)) {
                    if (this.currentTab.content[area] && this.currentTab.content[area].parentNode) {
                        this.currentTab.content[area].parentNode.removeChild(this.currentTab.content[area]);
                    }
                }
            }
            if (this.tabEls[this.currentTab.name] && this.tabEls[this.currentTab.name].classList) {
                this.tabEls[this.currentTab.name].classList.remove('active');
            }
        }

        for (const area in this.contentAreas) {
            if (this.contentAreas.hasOwnProperty(area)) {
                if (tab.content[area]) {
                    this.contentAreas[area].appendChild(tab.content[area]);
                }
            }
        }
        this.tabEls[tab.name].classList.add('active');
        this.currentTab = tab;
        this.dispatchEvent(new UIEvent('TabActivated', { tab: tab }));
    }

    getTab(name) {
        return this.tabs[name];
    }
}

export class NotebookListUI extends UIEventTarget {
    constructor() {
        super();
        this.el = div(
            ['notebooks-list', 'ui-panel'], [
                h2([], [
                    'Notebooks',
                    span(['buttons'], [
                        iconButton(['import-notebook'], 'Import a notebook', '', 'Import').click(evt => {
                            evt.stopPropagation();
                            this.dispatchEvent(new UIEvent('ImportNotebook'));
                        }),
                        iconButton(['create-notebook'], 'Create new notebook', '', 'New').click(evt => {
                            evt.stopPropagation();
                            this.dispatchEvent(new UIEvent('NewNotebook'));
                        })
                    ])
                ]).click(evt => this.collapse()),
                div(['ui-panel-content'], [
                    this.treeView = div(['tree-view'], [])
                ])
            ]
        );

        // Drag n' drop!
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(evt => {
            this.el.addEventListener(evt, this.fileHandler.bind(this), false)
        });
    }

    // Check prefs to see whether this should be collapsed. Sends events, so must be called AFTER the element is created.
    init() {
        const prefs = this.getPrefs();
        if (prefs && prefs.collapsed) {
            this.collapse(true);
        }
    }

    getPrefs() {
        return prefs.get("NotebookListUI")
    }

    setPrefs(obj) {
        prefs.set("NotebookListUI", {...this.getPrefs(), ...obj})
    }

    setItems(items) {
        if (this.tree) {
            // remove current items
            this.treeView.innerHTML = '';
        }

        const tree = NotebookListUI.parseItems(items);

        const [itemTree, treeEl] = this.buildTree(tree, [], tag('ul', [], {}, []));
        this.tree = itemTree;
        this.treeEl = treeEl;
        this.treeView.appendChild(treeEl);
    }

    static parseItems(items) {
        const tree = {};

        for (const item of items) {
            const itemPath = item.split(/\//g);
            let currentTree = tree;

            while (itemPath.length > 1) {
                const pathSegment = itemPath.shift();
                if (!currentTree[pathSegment]) {
                    currentTree[pathSegment] = {};
                }
                currentTree = currentTree[pathSegment];
            }

            currentTree[itemPath[0]] = item;
        }
        return tree;
    }

    buildTree(treeObj, path, listEl) {

        const resultTree = {};

        for (const itemName in treeObj) {
            if (treeObj.hasOwnProperty(itemName)) {
                const item = treeObj[itemName];
                let itemEl = null;
                if (typeof item === "string") {
                    // leaf - item is the complete path
                    itemEl = tag('li', ['leaf'], {}, [
                        span(['name'], [itemName]).click(evt => {
                            console.log(`Load ${item}`);
                            this.dispatchEvent(new UIEvent('TriggerItem', {item: item}));
                        })
                    ]);
                    itemEl.item = item;
                    resultTree[itemName] = itemEl;
                    listEl.appendChild(itemEl);
                } else {
                    const itemPath = [...path, itemName];
                    const pathStr = itemPath.join('/');
                    let subListEl = null;
                    for (const child of listEl.children) {
                        if (child.pathStr && child.pathStr === pathStr) {
                            subListEl = child.listEl;
                            itemEl = child;
                            break;
                        }
                    }

                    if (subListEl === null) {
                        subListEl = tag('ul', [], {}, []);
                        itemEl = tag('li', ['branch'], {}, [
                            span(['branch-outer'], [
                                span(['expander'], []).click(evt => this.toggle(itemEl)),
                                span(['icon'], []),
                                span(['name'], [itemName])
                            ]),
                            subListEl
                        ]);
                        itemEl.path = itemPath;

                        listEl.appendChild(itemEl);

                        itemEl.appendChild(subListEl);
                        itemEl.listEl = subListEl;
                        itemEl.pathStr = pathStr;
                        listEl.appendChild(itemEl);
                    }

                    const [itemTree, itemList] = this.buildTree(item, itemPath, subListEl);
                    resultTree[itemName] = itemTree;
                }
            }
        }
        return [resultTree, listEl];
    }

    addItem(path) {
        this.buildTree(NotebookListUI.parseItems([path]), [], this.treeEl);
    }

    toggle(el) {
        if (!el) return;
        el.classList.toggle('expanded');
    }

    collapse(force) {
        const prefs = this.getPrefs();
        if (force) {
            this.dispatchEvent(new UIEvent('ToggleNotebookListUI', {force: true}))
        } else if (prefs && prefs.collapsed) {
            this.setPrefs({collapsed: false});
            this.dispatchEvent(new UIEvent('ToggleNotebookListUI'))
        } else {
            this.setPrefs({collapsed: true});
            this.dispatchEvent(new UIEvent('ToggleNotebookListUI'))
        }
    }

    fileHandler(evt) {
        // prevent browser from displaying the ipynb file.
        evt.stopPropagation();
        evt.preventDefault();

        // handle highlighting
        if (evt.type === "dragenter" || evt.type === "dragover") {
            this.dragEnter = evt.target;
            this.el.classList.add('highlight');
        } else if (evt.type === "drop" || (evt.type === "dragleave" && evt.target === this.dragEnter)) {
            this.el.classList.remove('highlight');
        }

        // actually handle the file
        if (evt.type === "drop") {
            const xfer = evt.dataTransfer;
            const files = xfer.files;
            [...files].forEach((file) => {
                const reader = new FileReader();
                reader.readAsText(file);
                reader.onloadend = () => {
                    this.dispatchEvent(new UIEvent('ImportNotebook', {name: file.name, content: reader.result}))
                }
            })
        }
    }
}

export class WelcomeUI extends UIEventTarget {
    constructor() {
        super();
        this.el = div(['welcome-page'], []);
        this.el.innerHTML = `
          <img src="/style/polynote.svg" alt="Polynote" />
          <h2>Home</h2>
          
          <p>
            To get started, open a notebook by clicking on it in the Notebooks panel, or create a new notebook by
             clicking the Create Notebook (<span class="create-notebook icon fas"></span>) button.
          </p>
          
          <h3>Recent notebooks</h3>
          <ul class="recent-notebooks"></ul>  
        `;

        const recent = this.el.querySelector('.recent-notebooks');
        (prefs.get('recentNotebooks') || []).forEach(nb => {
           recent.appendChild(
               tag('li', ['notebook-link'], {}, [
                   span([], nb.name).click(
                        evt => this.dispatchEvent(new UIEvent('TriggerItem', {item: nb.path})))
               ])
           );
        });
    }
}

export class MainUI extends EventTarget {
    constructor(socket) {
        super();
        let left = { el: div(['grid-shell'], []) };
        let center = { el: div(['tab-view'], []) };
        let right = { el: div(['grid-shell'], []) };

        this.mainView = new SplitView('split-view', left, center, right);
        this.toolbarUI = new ToolbarUI();

        this.el = div(['main-ui'], [this.toolbarUI.el, this.mainView.el]);

        this.notebookContent = div(['notebook-content'], []);

        this.tabUI = new TabUI({notebook: this.notebookContent, kernel: right.el});
        this.mainView.center.el.appendChild(this.tabUI.el);
        this.mainView.center.el.appendChild(this.notebookContent);

        this.browseUI = new NotebookListUI().setEventParent(this);
        this.mainView.left.el.appendChild(this.browseUI.el);
        this.addEventListener('TriggerItem', evt => this.loadNotebook(evt.detail.item));
        this.browseUI.addEventListener('NewNotebook', () => this.createNotebook());
        this.browseUI.addEventListener('ImportNotebook', evt => this.importNotebook(evt));
        this.browseUI.addEventListener('ToggleNotebookListUI', (evt) => this.mainView.collapse('left', evt.detail && evt.detail.force));
        this.browseUI.init();

        this.socket = socket;

        socket.listenOnceFor(messages.ListNotebooks, (items) => this.browseUI.setItems(items));
        socket.send(new messages.ListNotebooks([]));

        socket.listenOnceFor(messages.ServerHandshake, (interpreters) => {
            this.toolbarUI.cellToolbar.setInterpreters(interpreters);
        });

        window.addEventListener('popstate', evt => {
           if (evt.state && evt.state.notebook) {
               this.loadNotebook(evt.state.notebook);
           }
        });

        this.tabUI.addEventListener('TabActivated', evt => {
            const tab = evt.detail.tab;
            if (tab.type === 'notebook') {
                const tabPath = `/notebook/${tab.name}`;

                const href = window.location.href;
                const hash = window.location.hash;
                const title = `${tab.name.split(/\//g).pop()} | Polynote`;
                document.title = title; // looks like chrome ignores history title so we need to be explicit here.

                 // handle hashes and ensure scrolling works
                if (hash && window.location.pathname === tabPath) {
                    window.history.pushState({notebook: tab.name}, title, href);
                    this.handleHashChange()
                } else {
                    window.history.pushState({notebook: tab.name}, title, tabPath);
                }

                this.currentNotebookPath = tab.name;
                this.currentNotebook = this.tabUI.getTab(tab.name).content.notebook.cellsUI;
                this.currentNotebook.notebookUI.cellUI.forceLayout(evt)
            } else if (tab.type === 'home') {
                const title = 'Polynote'
                window.history.pushState({notebook: tab.name}, title, '/');
                document.title = title
            }
        });

        window.addEventListener('hashchange', evt => {
            this.handleHashChange(evt)
        });

        this.tabUI.addEventListener('NoActiveTab', () => {
            this.showWelcome();
        });

        this.toolbarUI.addEventListener('RunAll', (evt) => {
            if (this.currentNotebook) {
                evt.forward(this.currentNotebook);
            }
        });

        this.toolbarUI.addEventListener('RunToCursor', (evt) => {
            if (this.currentNotebook) {
                evt.forward(this.currentNotebook);
            }
        });

        this.toolbarUI.addEventListener('RunCurrentCell', (evt) => {
            if (this.currentNotebook) {
                evt.forward(this.currentNotebook);
            }
        });

        this.toolbarUI.addEventListener('CancelTasks', () => {
           this.socket.send(new messages.CancelTasks(this.currentNotebookPath));
        });

        this.toolbarUI.addEventListener('Undo', () => {
           const notebookUI = this.currentNotebook.cellsUI.notebookUI;
           if (notebookUI instanceof NotebookUI) {
               notebookUI // TODO: implement undoing after deciding on behavior
           }
        });


        this.toolbarUI.addEventListener('InsertAbove', () => {
            const cellsUI = this.currentNotebook;
            let activeCell = Cell.currentFocus;
            if (!activeCell) {
                activeCell = cellsUI.firstCell();
            }
            const activeCellId = activeCell.id;
            if (!cellsUI.getCell(activeCellId) || cellsUI.getCell(activeCellId) !== activeCell) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            cellsUI.dispatchEvent(new UIEvent('InsertCellBefore', { cellId: activeCellId }));
        });

        this.toolbarUI.addEventListener('InsertBelow', () => {
            const cellsUI = this.currentNotebook;
            let activeCell = Cell.currentFocus;
            if (!activeCell) {
                activeCell = cellsUI.firstCell();
            }
            const activeCellId = activeCell.id;
            if (!cellsUI.getCell(activeCellId) || cellsUI.getCell(activeCellId) !== activeCell) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            cellsUI.dispatchEvent(new UIEvent('InsertCellAfter', { cellId: activeCellId }));
        });

        this.toolbarUI.addEventListener('DeleteCell', () => {
            const cellsUI = this.currentNotebook;
            const activeCellId = Cell.currentFocus.id;
            if (!cellsUI.getCell(activeCellId) || cellsUI.getCell(activeCellId) !== Cell.currentFocus) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            cellsUI.dispatchEvent(new UIEvent('DeleteCell', {cellId: activeCellId }));
        });

        // TODO: maybe we can break out this menu stuff once we need more menus.
        this.toolbarUI.addEventListener('ViewPrefs', (evt) => {
            const anchorElem = document.getElementsByClassName(evt.detail.anchor.className)[0];
            const anchorPos = anchorElem.getBoundingClientRect();

            const menu = evt.detail.elem;
            const content = JSON.stringify(prefs.show(), null, 2);

            monaco.editor.colorize(content, "json", {}).then(function(result) {
                menu.innerHTML = result;
            });

            menu.style.display = 'block';

            const bodySize = document.body.getBoundingClientRect();

            menu.style.right = (bodySize.width - anchorPos.left) - anchorPos.width + "px";

            // hide it when you click away...
            document.addEventListener('mousedown', () => {
                menu.style.display = 'none';
            }, {once: true});

            //... but not if you click inside it:
            menu.addEventListener('mousedown', (evt) => evt.stopPropagation());
        });

        this.toolbarUI.addEventListener('ResetPrefs', () => {
            prefs.clear();
            location.reload(); //TODO: can we avoid reloading?
        });

        this.toolbarUI.addEventListener('ToggleVIM', () => {
            const currentVim = prefs.get('VIM');
            if (currentVim) {
                prefs.set('VIM', false);
            } else {
                prefs.set('VIM', true);
            }

            this.toolbarUI.settingsToolbar.colorVim();
        });

        this.toolbarUI.addEventListener('DownloadNotebook', () => {
            MainUI.browserDownload(window.location.pathname + "?download=true", this.currentNotebook.path);
        });

        this.toolbarUI.addEventListener('ClearOutput', () => {
            this.socket.send(new messages.ClearOutput(this.currentNotebookPath))
        });

    }

    showWelcome() {
        if (!this.welcomeUI) {
            this.welcomeUI = new WelcomeUI().setEventParent(this);
        }
        this.tabUI.addTab('home', span([], 'Home'), { notebook: this.welcomeUI.el, path: '/' }, 'home');
    }

    loadNotebook(path) {
        const notebookTab = this.tabUI.getTab(path);

        if (!notebookTab) {
            const notebookUI = new NotebookUI(path, this.socket, this);
            this.socket.send(new messages.LoadNotebook(path));
            const tab = this.tabUI.addTab(path, span(['notebook-tab-title'], [path.split(/\//g).pop()]), {
                notebook: notebookUI.cellUI.el,
                kernel: notebookUI.kernelUI.el
            }, 'notebook');
            this.tabUI.activateTab(tab);

            this.toolbarUI.cellToolbar.cellTypeSelector.addEventListener('change', evt => {
                // hacky way to tell whether this is the current notebook ...
                if (this.currentNotebook.notebookUI === notebookUI) {
                    notebookUI.onCellLanguageSelected(evt.newValue, path);
                }
            });

            notebookUI.kernelUI.addEventListener('ToggleKernelUI', (evt) => {
                this.mainView.collapse('right', evt.detail && evt.detail.force)
            });
            notebookUI.kernelUI.init();

        } else {
            this.tabUI.activateTab(notebookTab);
        }

        const notebookName = path.split(/\//g).pop();

        prefs.update('recentNotebooks', recentNotebooks => {
            const currentIndex = recentNotebooks.findIndex(nb => nb.path === path);
            if (currentIndex !== -1) {
                recentNotebooks.splice(currentIndex, 1);
            }
            recentNotebooks.unshift({name: notebookName, path: path});
            return recentNotebooks;
        })
    }

    createNotebook() {
        const handler = this.socket.addMessageListener(messages.CreateNotebook, (actualPath) => {
            this.socket.removeMessageListener(handler);
            this.browseUI.addItem(actualPath);
            this.loadNotebook(actualPath);
        });

        const notebookPath = prompt("Enter the name of the new notebook (no need for an extension)");
        if (notebookPath) {
            this.socket.send(new messages.CreateNotebook(notebookPath))
        }
    }

    importNotebook(evt) {
        const handler = this.socket.addMessageListener(messages.CreateNotebook, (actualPath) => {
            this.socket.removeMessageListener(handler);
            this.browseUI.addItem(actualPath);
            this.loadNotebook(actualPath);
        });

        if (evt.detail && evt.detail.name) { // the evt has all we need
            this.socket.send(new messages.CreateNotebook(evt.detail.name, Either.right(evt.detail.content)));
        } else {
            const notebookPath = prompt("Enter the full URL of another Polynote instance.");

            if (notebookPath && notebookPath.startsWith("http")) {
                const nbFile = decodeURI(notebookPath.split("/").pop());
                const targetPath = notebookPath + "?download=true";
                this.socket.send(new messages.CreateNotebook(nbFile, Either.left(targetPath)));
            }
        }
    }

    handleHashChange(evt) {
        // TODO: we need a better way to tell whether the notebook has been rendered rather than just using setTimeout and hoping the timing will work.
        setTimeout(() => {
            const hash = document.location.hash;
            // the hash can (potentially) have two parts: the selected cell and selected lines.
            // for example: #Cell2,6-12 would mean Cell2 lines 6-12
            const [hashId, lines] = hash.slice(1).split(",");

            const selected = document.getElementById(hashId);
            if (selected && selected.cell && selected.cell !== Cell.currentFocus) {

                // highlight lines
                if (lines) {
                    let [startLine, endLine] = lines.split("-").map(s => parseInt(s));
                    const startPos = Position.lift({lineNumber: startLine, column: 0});

                    let endPos;
                    if (endLine) {
                        endPos = Position.lift({lineNumber: endLine, column: 0});
                    } else {
                        endPos = Position.lift({lineNumber: startLine + 1, column: 0});
                    }

                    selected.cell.setHighlight({
                        startPos: startPos,
                        endPos: endPos
                    }, "link-highlight")
                }
                // select cell and scroll to it.
                selected.cell.focus();
            }

        }, 500);
    }

    static browserDownload(path, filename) {
        const link = document.createElement('a');
        link.setAttribute("href", path);
        link.setAttribute("download", filename);
        link.click()
    }
}

// TODO: move all these to happen when server handshake gives list of languages
monaco.languages.registerCompletionItemProvider('scala', {
  triggerCharacters: ['.'],
  provideCompletionItems: (doc, pos, context, cancelToken) => {
      return doc.cellInstance.requestCompletion(doc.getOffsetAt(pos));
  }
});

monaco.languages.registerCompletionItemProvider('python', {
  triggerCharacters: ['.'],
  provideCompletionItems: (doc, pos, cancelToken, context) => {
      return doc.cellInstance.requestCompletion(doc.getOffsetAt(pos));
  }
});

monaco.languages.registerSignatureHelpProvider('scala', {
  signatureHelpTriggerCharacters: ['(', ','],
  provideSignatureHelp: (doc, pos, cancelToken, context) => {
      return doc.cellInstance.requestSignatureHelp(doc.getOffsetAt(pos));
  }
});

monaco.languages.registerCompletionItemProvider('sql', {
    triggerCharacters: ['.'],
    provideCompletionItems: (doc, pos, context, cancelToken) => {
        return doc.cellInstance.requestCompletion(doc.getOffsetAt(pos));
    }
});