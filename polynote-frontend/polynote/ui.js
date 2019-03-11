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
        this.el = div(['kernel-symbols'], [
            h3([], ['Symbols']),
            this.tableEl = table(['kernel-symbols-table'], {
                header: ['Name', 'Type', 'Value'],
                classes: ['name', 'type', 'value'],
                rowHeading: true,
                addToTop: true
            })
        ]);
    }

    setSymbolInfo(name, type, value) {
        const existing = this.tableEl.findRowsBy(row => row.name === name);
        if (existing.length) {
            existing.forEach(tr => {
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

            });
        } else {
            const tr = this.tableEl.addRow({name: name, type: span([], type).attr('title', type), value: span([], value).attr('title', value)});
            if (value === '') {
                tr.querySelector('.type').colSpan = '2';
                const v = tr.querySelector('.value');
                if (v)
                    tr.removeChild(v)
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
            this.infoEl = div(['info-container'], []),
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

    toggleVisibility() {
        if (this.info.size === 0) {
            this.el.style.display = "none";
        } else {
            this.renderInfo();
            this.el.style.display = "block";
        }
    }

    renderInfo() {
        this.infoEl.innerHTML = "";
        for (const [k, v] of this.info) {
            const val = div(['info-value'], []);
            val.innerHTML = v;
            const el = div(['info-item'], [
                div(['info-key'], [k]),
                val
            ]);
            this.infoEl.appendChild(el);
        }
    }
}

export class SplitView {
    constructor(id, left, center, right) {
        this.left = left;
        this.center = center;
        this.right = right;
        let classes = [id];
        let children = [];
        this.templates = {};
        this.areas = [];


        if (left) {
            const prefId = `${id}.leftSize`;
            classes.push('l');
            children.push(left.el);
            left.el.style.gridArea = 'left';
            let leftDragger = div(['drag-handle'], [div(['inner'], [])])
                .attr('draggable', 'true');
            children.push(leftDragger);

            leftDragger.addEventListener('dragstart', (evt) => {
                leftDragger.initialX = evt.clientX;
                leftDragger.initialWidth = left.el.offsetWidth;
            });

            leftDragger.addEventListener('drag', (evt) => {
                evt.preventDefault();
                if (evt.clientX) {
                    this.templates.left = (leftDragger.initialWidth + (evt.clientX - leftDragger.initialX )) + "px";
                    this.layout();
                }
            });

            leftDragger.addEventListener('dragend', (evt) => {
                prefs.set(prefId, this.templates.left);
                window.dispatchEvent(new CustomEvent('resize', {}));
            });

            leftDragger.style.gridArea = 'leftdrag';

            this.templates.left = prefs.get(prefId) || '300px';
            this.templates.leftdrag = '1px';
            this.areas.push('left');
            this.areas.push('leftdrag')
        }
        if (center) {
            classes.push('c');
            children.push(center.el);
            this.templates.center = 'auto';
            this.areas.push('center');
        }
        if (right) {
            const prefId = `${id}.rightSize`;
            classes.push('r');

            let rightDragger = div(['drag-handle'], [div(['inner'], [])])
                .attr('draggable', 'true');
            rightDragger.style.gridArea = 'rightdrag';

            rightDragger.addEventListener('dragstart', (evt) => {
                rightDragger.initialX = evt.clientX;
                rightDragger.initialWidth = right.el.offsetWidth;
            });

            rightDragger.addEventListener('drag', (evt) => {
                evt.preventDefault();
                if (evt.clientX) {
                    this.templates.right = (rightDragger.initialWidth - (evt.clientX - rightDragger.initialX)) + "px";
                    this.layout();
                }
            });

            rightDragger.addEventListener('dragend', evt => {
                prefs.set(prefId, this.templates.right);
                window.dispatchEvent(new CustomEvent('resize', {}));
            });

            children.push(rightDragger);

            children.push(right.el);
            right.el.style.gridArea = 'right';
            this.templates.rightdrag = '1px';
            this.templates.right = prefs.get(prefId) || '300px';
            this.areas.push('rightdrag');
            this.areas.push('right');
        }

        this.el = div(['split-view', ...classes], children);
        this.el.style.display = 'grid';
        this.el.style.gridTemplateAreas = `"${this.areas.join(' ')}"`;
        this.layout();
    }

    layout() {
        let templateValues = [];
        for (const area of this.areas) {
            templateValues.push(this.templates[area]);
        }
        this.el.style.gridTemplateColumns = templateValues.join(' ');
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
                span(['status'], ['●']),
                'Kernel',
                span(['buttons'], [
                  iconButton(['connect'], 'Connect to server', '', 'Connect').click(evt => this.connect()),
                  iconButton(['start'], 'Start kernel', '', 'Start').click(evt => this.startKernel()),
                  iconButton(['kill'], 'Kill kernel', '', 'Kill').click(evt => this.killKernel())
                ])
            ]),
            div(['ui-panel-content'], [
                this.info.el,
                this.symbols.el,
                this.tasks.el
            ])
        ]);
    }

    connect() {
        this.dispatchEvent(new UIEvent('Connect'))
    }

    startKernel() {
        this.dispatchEvent(new UIEvent('StartKernel'))
    }

    killKernel() {
        if (confirm("Kill running kernel? State will be lost.")) {
            this.dispatchEvent(new UIEvent('KillKernel'))
        }
    }

    setKernelState(state) {
        this.el.classList.remove('busy', 'idle', 'dead', 'disconnected');
        if (state === 'busy' || state === 'idle' || state === 'dead' || state === 'disconnected') {
            this.el.classList.add(state);
        } else {
            throw "State must be one of [busy, idle, dead, disconnected]";
        }
    }
}

export class NotebookConfigUI extends UIEventTarget {
    constructor() {
        super();
        this.el = div(['notebook-config'], [
            h2(['config'], ['Configuration & dependencies']).click(evt => this.el.classList.toggle('open')),
            div(['content'], [
                div(['notebook-dependencies'], [
                    h3([], ['Dependencies']),
                    para([], ['Specify Maven coordinates for your dependencies, i.e. ', span(['pre'], ['org.myorg:package-name_2.11:1.0.1'])]),
                    this.dependencyContainer = div(['dependency-list'], [
                        this.dependencyRowTemplate = div(['dependency-row'], [
                            textbox(['dependency'], 'Dependency coordinate'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                this.addDependency(evt.currentTarget.parentNode.querySelector('.dependency').value);
                                this.dependencyRowTemplate.querySelector('.dependency').value = '';
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['notebook-resolvers'], [
                    h3([], ['Resolvers']),
                    para([], ['Specify any custom Ivy or Maven repositories here.']),
                    this.resolverContainer = div(['resolver-list'], [
                        this.resolverRowTemplate = div(['resolver-row', 'ivy'], [
                            dropdown(['resolver-type'], {ivy: 'Ivy', maven: 'Maven'}).change(evt => {
                                const self = evt.currentTarget;
                                const row = self.parentNode;
                                const value = self.options[self.selectedIndex].value;
                                row.className = 'resolver-row';
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

    clearConfig() {
        while (this.dependencyContainer.childNodes.length > 0) {
            this.dependencyContainer.removeChild(this.dependencyContainer.childNodes[0]);
        }
        this.dependencyContainer.appendChild(this.dependencyRowTemplate);
        [...this.dependencyContainer.querySelectorAll('input')].forEach(input => input.value = '');

        while (this.resolverContainer.childNodes.length > 0) {
            this.resolverContainer.removeChild(this.resolverContainer.childNodes[0]);
        }
        this.resolverContainer.appendChild(this.resolverRowTemplate);
        [...this.resolverContainer.querySelectorAll('input')].forEach(input => input.value = '');
    }

    setConfig(config) {
        this.lastConfig = config;
        this.clearConfig();

        if (config.dependencies && config.dependencies.scala) {
            for (const dep of config.dependencies.scala) {
                this.addDependency(dep);
            }
        }

        if(config.repositories) {
            for (const repository of config.repositories) {
                this.addResolver(repository);
            }
        }
    }

    get config() {
        const deps = [];
        const depInputs = this.dependencyContainer.querySelectorAll('.dependency-row input');
        depInputs.forEach(input => {
            if (input.value) deps.push(input.value);
        });

        const repos = [];
        const repoRows = this.resolverContainer.querySelectorAll('.resolver-row');
        repoRows.forEach(row => {
            const repository = this.mkResolver(row);
            if (repository.base) {
                repos.push(repository);
            }
        });

        return new messages.NotebookConfig(
            {scala: deps},
            repos
        );
    }

}

export class NotebookCellsUI extends UIEventTarget {
    constructor(path) {
        super();
        this.configUI = new NotebookConfigUI().setEventParent(this);
        this.path = path;
        this.el = div(['notebook-cells'], [this.configUI.el]);
        this.el.cellsUI = this;  // TODO: this is hacky and bad (using for getting to this instance via the element, from the tab content area of MainUI#currentNotebook)
        this.cells = {};
        this.cellCount = 0;
        window.addEventListener('resize', this.onWindowResize.bind(this));
    }

    extractId(cellRef) {
        if (typeof cellRef === 'string') {
            return cellRef.match(/^Cell(\d+)$/)[1];
        } else if (typeof cellRef === 'number') {
            return cellRef;
        } else {
            throw { message: `Unable to parse cell reference ${cellRef}` };
        }
    }

    getCell(cellRef) {
        return this.cells[this.extractId(cellRef)];
    }

    onWindowResize(evt) {
        if (this.resizeTimeout) {
            clearTimeout(this.resizeTimeout);
        }
        this.resizeTimeout = setTimeout(() => {
            this.el.querySelectorAll('.code-cell').forEach((el) => {
                if (el.cell instanceof CodeCell) {
                    el.cell.editor.layout();
                }
            })
        }, 333);
    }
    
    addCell(cell) {
        this.cellCount++;
        this.el.appendChild(cell.container);
        this.setupCell(cell);
    }

    insertCell(cell, after) {
        let prevCell = after;
        if (after && after instanceof Cell) {
            prevCell = after.container;
        } else if (after && this.getCell(after)) {
            prevCell = this.getCell(after).container;
        } else if (!after) {
            prevCell = this.configUI.el;
        }

        this.cellCount++;
        this.el.insertBefore(cell.container, prevCell.nextSibling);
        this.setupCell(cell);
    }

    removeCell(cellId) {
        const cell = this.getCell(cellId);
        if (cell) {
            this.el.removeChild(cell.container);
            cell.dispose();
            cell.container.innerHTML = '';
        }
    }

    setupCell(cell) {
        this.cells[this.extractId(cell.id)] = cell;
        if (cell.editor && cell.editor.layout) {
            cell.editor.layout();
        }
        cell.setEventParent(this);
    }

    setCellLanguage(cell, language) {
        const currentCell = this.getCell(cell.id);
        if (cell !== currentCell)
            throw { message:"Cell with that ID is not the same cell as the target cell" };


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
            this.socket.send(this.localVersion, update);
        });

        this.cellUI.addEventListener('SelectCell', evt => {
            const cellTypeSelector = mainUI.toolbarUI.cellToolbar.cellTypeSelector;
            let i = 0;

            // update cell type selector
            for (const opt of cellTypeSelector.options) {
                if (opt.value === evt.detail.cell.lang) {
                    cellTypeSelector.selectedIndex = i;
                    break;
                }
                i++;
            }

            // notify toolbar of context change
            mainUI.toolbarUI.onContextChanged();

            // ensure cell is visible in the viewport
            const container = evt.detail.cell.container;
            const cellY = container.offsetTop;
            const cellHeight = container.offsetHeight;
            const viewport = mainUI.notebookContent;
            const viewportScrollTop = viewport.scrollTop;
            const viewportHeight = viewport.clientHeight;
            if (cellY + cellHeight > viewportScrollTop + viewportHeight || cellY < viewportScrollTop) {
                setTimeout(() => viewport.scrollTop = cellY, 0);
            }
        });

        this.cellUI.addEventListener('AdvanceCell', evt => {
            if (Cell.currentFocus) {
                if (evt.backward) {
                    const prev = Cell.currentFocus.container.previousSibling && cellUI.getCell(Cell.currentFocus.container.previousSibling.id);
                    if (prev) {
                        prev.focus();
                    }
                } else {
                    const next = Cell.currentFocus.container.nextSibling && cellUI.getCell(Cell.currentFocus.container.nextSibling.id);
                    if (next) {
                        next.focus();
                    } else {
                        this.cellUI.dispatchEvent(new UIEvent('InsertCellAfter', {cellId: Cell.currentFocus.id}));
                    }
                }
            }
        });

        this.cellUI.addEventListener('InsertCellAfter', evt => {
           const current = this.cellUI.getCell(evt.detail.cellId) || this.cellUI.getCell(this.cellUI.el.querySelector('.cell-container').id);
           const nextId = this.cellUI.cellCount;
           const newCell = current.language === 'text' ? new TextCell(nextId, '', this.path) : new CodeCell(nextId, '', current.language, this.path);
           const update = new messages.InsertCell(path, this.globalVersion, ++this.localVersion, new messages.NotebookCell(newCell.id, newCell.language, ''), current.id)
           this.socket.send(update);
           this.editBuffer.push(this.localVersion, update);
           this.cellUI.insertCell(newCell, current);
           newCell.focus();
        });

        this.cellUI.addEventListener('DeleteCell', evt => {
            const current = this.cellUI.getCell(evt.detail.cellId);
            if (current) {
                const update = new messages.DeleteCell(path, this.globalVersion, ++this.localVersion, current.id);
                this.socket.send(update);
                this.editBuffer.push(this.localVersion, update);
                this.cellUI.removeCell(current.id);
            }
        });

        this.cellUI.addEventListener('RunCell', (evt) => {
            let cellId = evt.detail.cellId;
            if (!(cellId instanceof Array)) {
                cellId = [cellId];
            }
            cellId.forEach(id => {
               const cell = this.cellUI.getCell(id);
               if (cell) {
                   cell.dispatchEvent(new BeforeCellRunEvent(id));
               }
            });
            this.socket.send(new messages.RunCell(path, cellId));
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
                            //console.log(taskInfo);
                            this.kernelUI.tasks.updateTask(taskInfo.id, taskInfo.label, taskInfo.detail, taskInfo.status, taskInfo.progress);
                        });
                        break;

                    case messages.KernelBusyState:
                        const state = (update.busy && 'busy') || (!update.alive && 'dead') || 'idle';
                        this.kernelUI.setKernelState(state);
                        break;

                    case messages.KernelInfo:
                        this.kernelUI.info.updateInfo(update.content);
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
            // TODO: show this better in the UI
            console.log("Kernel error:", err);
            const id = "KernelError";
            const message = div(["message"], [
                para([], `${err.className}: ${err.message}`),
                para([], "Please see the console for more details.")
            ]);
            this.kernelUI.tasks.updateTask(id, "Kernel Error", message, TaskStatus.Error, 0);

            // clean up (assuming that running another cell means users are done with this error)
            socket.addMessageListener(messages.CellResult, () => {
                this.kernelUI.tasks.updateTask(id, "Kernel Error", message, TaskStatus.Complete, 100);
                return false // make sure to remove the listener
            }, true)
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
                    } else if (result instanceof ResultValue) {
                        cell.addResult(result);
                    }
                }

                if (result instanceof ResultValue) {
                    this.kernelUI.symbols.setSymbolInfo(
                        result.name,
                        result.typeName,
                        result.valueText);
                }
            }
        });
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
                        cell = new TextCell(cellInfo.id, cellInfo.content, path);
                        break;
                    default:
                        cell = new CodeCell(cellInfo.id, cellInfo.content, cellInfo.language, path);
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
           this.activateTab(tab);
        });

        this.tabContainer.appendChild(tabEl);


        if (!this.currentTab) {
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
                }
            }
        }

        if (tabEl) {
            tabEl.parentNode.removeChild(tabEl);
        }

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
            for (const area in this.contentAreas) {
                if (this.contentAreas.hasOwnProperty(area)) {
                    if (this.currentTab.content[area] && this.currentTab.content[area].parentNode) {
                        this.currentTab.content[area].parentNode.removeChild(this.currentTab.content[area]);
                    }
                }
            }
            this.tabEls[this.currentTab.name].classList.remove('active');
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
                        iconButton(['create-notebook'], 'Create new notebook', '', 'New').click(evt => this.dispatchEvent(new UIEvent('NewNotebook')))
                    ])
                ]),
                div(['ui-panel-content'], [
                    this.treeView = div(['tree-view'], [])
                ])
            ]
        );
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
        this.browseUI.addEventListener('NewNotebook', evt => this.createNotebook(evt));

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
                window.history.pushState({notebook: tab.name}, `${tab.name.split(/\//g).pop()} | Polynote`, `/notebook/${tab.name}`);
                this.currentNotebookPath = tab.name;
                this.currentNotebook = this.tabUI.getTab(tab.name).content.notebook;
            }
        });

        this.toolbarUI.addEventListener('RunAll', () =>
            this.runCells(this.currentNotebook.querySelectorAll('.cell-container.code-cell'))
        );

        this.toolbarUI.addEventListener('RunToCursor', () => {
            const cells = [...this.currentNotebook.querySelectorAll('.cell-container.code-cell')];
            const activeCell = Cell.currentFocus.container;

            const activeCellIndex = cells.indexOf(activeCell);
            if (activeCellIndex < 0) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            this.runCells(cells.slice(0, activeCellIndex + 1));
        });

        this.toolbarUI.addEventListener('RunCurrentCell', () => {
            const cells = [...this.currentNotebook.querySelectorAll('.cell-container.code-cell')];
            const activeCell = Cell.currentFocus.container;

            const activeCellIndex = cells.indexOf(activeCell);

            if (activeCellIndex < 0) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            this.runCells([cells[activeCellIndex]]);
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
            const cellsUI = this.currentNotebook.cellsUI;
            const activeCell = Cell.currentFocus.id;
            if (!cellsUI.getCell(activeCell) || cellsUI.getCell(activeCell) !== Cell.currentFocus) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            const prevCell = Cell.currentFocus.container.previousSibling;

            cellsUI.dispatchEvent(new UIEvent('InsertCellAfter', { cellId: (prevCell && prevCell.id) || null }));
        });

        this.toolbarUI.addEventListener('InsertBelow', () => {
            const cellsUI = this.currentNotebook.cellsUI;
            const activeCell = Cell.currentFocus.id;
            if (!cellsUI.getCell(activeCell) || cellsUI.getCell(activeCell) !== Cell.currentFocus) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            cellsUI.dispatchEvent(new UIEvent('InsertCellAfter', { cellId: activeCell }));
        });

        this.toolbarUI.addEventListener('DeleteCell', () => {
            const cellsUI = this.currentNotebook.cellsUI;
            const activeCell = Cell.currentFocus.id;
            if (!cellsUI.getCell(activeCell) || cellsUI.getCell(activeCell) !== Cell.currentFocus) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            cellsUI.dispatchEvent(new UIEvent('DeleteCell', {cellId: activeCell }));
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
    }

    runCells(cellContainers) {
        const ids = [];
        [...cellContainers].forEach(container => {
            if (container.cell && container.id) {
                ids.push(container.cell.id);
                container.cell.dispatchEvent(new BeforeCellRunEvent(container.cell.id));
            }
        });

        if (ids.length) {
            this.socket.send(new messages.RunCell(this.currentNotebookPath, ids));
        }
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
                notebookUI.onCellLanguageSelected(evt.newValue, path);
            })
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

    createNotebook(evt) {
        const notebookPath = prompt("Enter the name of the new notebook (no need for an extension)");
        if (notebookPath) {
            const handler = this.socket.addMessageListener(messages.CreateNotebook, (actualPath) => {
                this.socket.removeMessageListener(handler);
                this.browseUI.addItem(actualPath);
                this.loadNotebook(actualPath);
            });

            this.socket.send(new messages.CreateNotebook(notebookPath))
        }
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