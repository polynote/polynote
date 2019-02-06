'use strict';

import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import {} from './scala.js'
import {} from './theme.js'
import { LaTeXEditor } from './latex_editor.js'
import { Toolbar } from './toolbar.js'
import { UIEvent, UIEventTarget } from './ui_event.js'
import { FakeSelect } from './fake_select.js'
import { TextToolbar } from './text_editor.js'
import { Cell, TextCell, CodeCell, BeforeCellRunEvent } from "./cell.js"
import { tag, para, span, button, iconButton, div, table, h2, h3, h4, textbox, dropdown } from './tags.js'
import { TaskStatus } from './messages.js';
import * as messages from './messages.js'
import { CompileErrors, Output, RuntimeError, ClearResults } from './result.js'
import { Prefs, prefs } from './prefs.js'


document.execCommand("defaultParagraphSeparator", false, "p");
document.execCommand("styleWithCSS", false, false);

export class MainToolbar extends EventTarget {
    constructor(el) {
        super();
        this.element = el;
        el.addEventListener('mousedown', (evt) => evt.preventDefault());
        // TODO: clean up toolbar code
        MainToolbar.cellTypeSelector = new FakeSelect(document.getElementById('Toolbar-Cell-Language'));

        this.addEventListener("ContextChanged", evt => this.onContextChanged(evt));
    }

    onContextChanged(evt) {
        let newContext = '';
        if (Cell.currentFocus instanceof TextCell) {
            newContext = 'editing-text';
        } else if (Cell.currentFocus instanceof CodeCell) {
            newContext = 'editing-code';
        }
        this.element.className = newContext;
    }

    static setCurrentCellType(type) {
        const cellTypeSelector = MainToolbar.cellTypeSelector;
        let i = 0;
        for (const opt of cellTypeSelector.options) {
            if (opt.value === type) {
                cellTypeSelector.selectedIndex = i;
                break;
            }
            i++;
        }
    }
}

// TODO: remove during toolbar cleanup
export const mainToolbar = new MainToolbar(document.getElementById('Toolbar'));

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
            if (task.detailText !== detail) {
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

export class SplitView {
    constructor(id, left, center, right) {
        this.left = left;
        this.center = center;
        this.right = right;
        let classes = [];
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
                this.templates.left = (leftDragger.initialWidth + (evt.clientX - leftDragger.initialX )) + "px";
                this.layout();
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
                if (evt.screenY) {
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
        if (id) {
            this.el.id = id;
        }
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

export class KernelUI extends EventTarget {
    constructor(socket) {
        super();
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
                this.symbols.el,
                this.tasks.el
            ])
        ]);
    }

    connect() {
        this.dispatchEvent(new CustomEvent('Connect'))
    }

    startKernel() {
        this.dispatchEvent(new CustomEvent('StartKernel'))
    }

    killKernel() {
        if (confirm("Kill running kernel? State will be lost.")) {
            this.dispatchEvent(new CustomEvent('KillKernel'))
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

export class NotebookConfigUI extends EventTarget {
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
                        this.dispatchEvent(new CustomEvent('UpdatedConfig', {detail: { config: this.config }}));
                    }),
                    button(['cancel'], {}, ['Cancel']).click(evt => {
                        if (this.lastConfig) {
                            this.setConfig(this.lastConfig);
                        }
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

    setConfig(config) {
        this.lastConfig = config;
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
    constructor() {
        super();
        this.configUI = new NotebookConfigUI();
        this.el = div(['notebook-cells'], [this.configUI.el]);
        this.el.cellsUI = this;  // TODO: this is hacky and bad (using for getting to this instance via the element, from the tab content area of MainUI#currentNotebook)
        this.cells = {};
        this.cellCount = 0;
        window.addEventListener('resize', this.onWindowResize.bind(this));
        this.configUI.addEventListener('UpdatedConfig', evt => this.dispatchEvent(new CustomEvent('UpdatedConfig', { detail: evt.detail })));
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
        } else if (after && typeof(after) === 'string') {
            prevCell = this.cells[after].container;
        } else if (!after) {
            prevCell = this.configUI.el;
        }

        this.cellCount++;
        this.el.insertBefore(cell.container, prevCell.nextSibling);
        this.setupCell(cell);
    }

    removeCell(cellId) {
        const cell = this.cells[cellId];
        if (cell) {
            this.el.removeChild(cell.container);
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
    }

    setCellLanguage(cell, language) {
        const currentCell = this.cells[cell.id];
        if (cell !== currentCell)
            throw { message:"Cell with that ID is not the same cell as the target cell" };


        if (currentCell.language === language)
            return;

        if (currentCell instanceof TextCell && language !== 'text') {
            // replace text cell with a code cell
            const newCell = new CodeCell(currentCell.id, currentCell.content, language);
            this.el.replaceChild(newCell.container, currentCell.container);
            currentCell.dispose();
            this.setupCell(newCell);
            newCell.focus();
        } else if (currentCell instanceof CodeCell && language === 'text') {
            // replace code cell with a text cell
            const newCell = new TextCell(currentCell.id, currentCell.content, language);
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

export class NotebookUI {
    constructor(path, socket) {  // TODO: Maybe UI shouldn't talk directly to session? I dunno...
        let cellUI = new NotebookCellsUI();
        let kernelUI = new KernelUI();
        //super(null, cellUI, kernelUI);
        //this.el.classList.add('notebook-ui');
        this.path = path;
        this.socket = socket;
        this.cellUI = cellUI;
        this.kernelUI = kernelUI;

        this.globalVersion = 0;
        this.localVersion = 0;

        this.cellUI.addEventListener('UpdatedConfig', evt => {
            this.socket.send(new messages.UpdateConfig(path, this.globalVersion, this.localVersion++, evt.detail.config));
        });

        this.cellUI.addEventListener('AdvanceCell', evt => {
            if (Cell.currentFocus) {
                if (evt.backward) {
                    const prev = Cell.currentFocus.container.previousSibling && cellUI.cells[Cell.currentFocus.container.previousSibling.id];
                    if (prev) {
                        prev.focus();
                    }
                } else {
                    const next = Cell.currentFocus.container.nextSibling && cellUI.cells[Cell.currentFocus.container.nextSibling.id];
                    if (next) {
                        next.focus();
                    } else {
                        this.cellUI.dispatchEvent(new CustomEvent('InsertCellAfter', {detail: {cellId: Cell.currentFocus.id}}));
                    }
                }
            }
        });

        this.cellUI.addEventListener('InsertCellAfter', evt => {
           const current = this.cellUI.cells[evt.detail.cellId] || this.cellUI.cells[this.cellUI.el.querySelector('.cell-container').id];
           const nextId = "Cell" + this.cellUI.cellCount;
           const newCell = current.language === 'text' ? new TextCell(nextId, '', 'text') : new CodeCell(nextId, '', current.language);
           this.socket.send(new messages.InsertCell(path, this.globalVersion, this.localVersion++, new messages.NotebookCell(newCell.id, newCell.language, ''), current.id));
           this.cellUI.insertCell(newCell, current);
           newCell.focus();
        });

        this.cellUI.addEventListener('DeleteCell', evt => {
            const current = this.cellUI.cells[evt.detail.cellId];
            if (current) {
                this.socket.send(new messages.DeleteCell(path, this.globalVersion, this.localVersion++, current.id));
                this.cellUI.removeCell(current.id);
            }
        });

        this.cellUI.addEventListener('RunCell', (evt) => {
            let cellId = evt.detail.cellId;
            if (!(cellId instanceof Array)) {
                cellId = [cellId];
            }
            cellId.forEach(id => {
               const cell = this.cellUI.cells[id];
               if (cell) {
                   cell.dispatchEvent(new BeforeCellRunEvent(id));
               }
            });
            this.socket.send(new messages.RunCell(path, cellId));
        });

        this.cellUI.addEventListener('ContentChange', (evt) => {
            this.socket.send(new messages.UpdateCell(path, this.globalVersion, this.localVersion++, evt.detail.cellId, evt.detail.edits));
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
                    case messages.UpdatedSymbols:
                        update.newOrUpdated.forEach(symbolInfo => {
                            this.kernelUI.symbols.setSymbolInfo(symbolInfo.name, symbolInfo.typeName, symbolInfo.valueText);
                        });
                        update.removed.forEach(name => ui.kernelUI.symbols.removeSymbol(name));
                        break;

                    case messages.UpdatedTasks:
                        update.tasks.forEach(taskInfo => {
                            //console.log(taskInfo);
                            this.kernelUI.tasks.updateTask(taskInfo.id, taskInfo.label, taskInfo.detail, taskInfo.status, taskInfo.progress);
                        });
                        break;

                    case messages.KernelBusyState:
                        const state = (update.busy && 'busy') || (!update.alive && 'dead') || 'idle';
                        this.kernelUI.setKernelState(state);
                }
            }
        });

        socket.addMessageListener(messages.UpdateCell, (path, globalVersion, localVersion, id, edits) => {
            this.globalVersion = globalVersion;
            // TODO: rebase the edits from localVersion to this.localVersion, and update the cell
            console.log(globalVersion, localVersion, this.localVersion, edits);
        });

        socket.addEventListener('close', evt => {
            this.kernelUI.setKernelState('disconnected');
            socket.addEventListener('open', evt => this.socket.send(new messages.KernelStatus(path, new messages.KernelBusyState(false, false))));
        });

        socket.addMessageListener(messages.Error, (code, err) => {
            // TODO: show this in the UI
            console.log("Kernel error:", err);
        });

        socket.addMessageListener(messages.CellResult, (path, id, result) => {
            //console.log(result);
            if (path === this.path) {
                const cell = this.cellUI.cells[id];
                if (cell instanceof CodeCell) {
                    if (result instanceof CompileErrors) {
                        cell.setErrors(result.reports);
                    } else if (result instanceof RuntimeError) {
                        console.log(result.error);
                        cell.setRuntimeError(result.error);
                    } else if (result instanceof Output) {
                        cell.addResult(result.contentType, result.content);
                    } else if (result instanceof ClearResults) {
                        cell.clearResult();
                    }
                }
                //console.log("Cell result:", path, id, result);
            }
        });

        // TODO: Toolbar cleanup: this should be an event emitted by the toolbar itself
        MainToolbar.cellTypeSelector.addEventListener('change', evt => {
            const setLanguage = evt.newValue;
            if (Cell.currentFocus && cellUI.cells[Cell.currentFocus.id] && cellUI.cells[Cell.currentFocus.id].language !== setLanguage) {
                const id = Cell.currentFocus.id;
                cellUI.setCellLanguage(Cell.currentFocus, setLanguage);
                socket.send(new messages.SetCellLanguage(path, this.globalVersion, this.localVersion++, id, setLanguage));
            }
        })
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
                        cell = new TextCell(cellInfo.id, cellInfo.content, 'text');
                        break;
                    default:
                        cell = new CodeCell(cellInfo.id, cellInfo.content, cellInfo.language);
                }

                this.cellUI.addCell(cell);
                cellInfo.results.forEach(
                    result => {
                        if (result instanceof CompileErrors) {
                            cell.setErrors(result.reports)
                        } else if (result instanceof RuntimeError) {
                            cell.setRuntimeError(result.error)
                        } else if (result instanceof Output) {
                            cell.addResult(result.contentType, result.content)
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

    addTab(name, title, content) {
        const tab = {
            name: name,
            title: title,
            content: content
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

        this.dispatchEvent(new CustomEvent('TabRemoved', { detail: tab.name }));

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
        this.dispatchEvent(new CustomEvent('TabActivated', { detail: tab.name }));
    }

    getTab(name) {
        return this.tabs[name];
    }
}

export class NotebookListUI extends EventTarget {
    constructor() {
        super();
        this.el = div(
            ['notebooks-list', 'ui-panel'], [
                h2([], [
                    'Notebooks',
                    span(['buttons'], [
                        iconButton(['create-notebook'], 'Create new notebook', '', 'New').click(evt => this.dispatchEvent(new CustomEvent('NewNotebook')))
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
                            this.dispatchEvent(new CustomEvent('TriggerItem', {detail: item}));
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

export class MainUI extends SplitView {
    constructor(socket) {
        let left = new NotebookListUI();
        let center = { el: div(['tab-view'], []) };
        let right = { el: div(['grid-shell'], []) };
        super('MainUI', left, center, right);

        this.el.classList.add('main-ui');
        this.notebookContent = div(['notebook-content'], []);

        this.browseUI = left;
        this.tabUI = new TabUI({notebook: this.notebookContent, kernel: right.el});
        this.center.el.appendChild(this.tabUI.el);
        this.center.el.appendChild(this.notebookContent);

        this.browseUI.addEventListener('TriggerItem', evt => this.loadNotebook(evt.detail));
        this.browseUI.addEventListener('NewNotebook', evt => this.createNotebook(evt));

        this.socket = socket;

        const listingListener = (items) => {
            socket.removeMessageListener(messages.ListNotebooks, listingListener);
            this.left.setItems(items);
        };

        socket.addMessageListener(messages.ListNotebooks, listingListener);
        socket.send(new messages.ListNotebooks([]));

        window.addEventListener('popstate', evt => {
           if (evt.state && evt.state.notebook) {
               this.loadNotebook(evt.state.notebook);
           }
        });

        this.tabUI.addEventListener('TabActivated', evt => {
            window.history.pushState({notebook: evt.detail}, `${evt.detail.split(/\//g).pop()} | Polynote`, `/notebook/${evt.detail}`);
            this.currentNotebookPath = evt.detail;
            this.currentNotebook = this.tabUI.getTab(evt.detail).content.notebook;
        });

        // TODO: part of toolbar cleanup
            document.querySelector('.run-all').addEventListener('click', evt => {
                this.runCells(this.currentNotebook.querySelectorAll('.cell-container.code-cell'))
            });

            document.querySelector('.run-cell.to-cursor').addEventListener('click', evt => {
                const cells = [...this.currentNotebook.querySelectorAll('.cell-container.code-cell')];
                const notebookUI = this.currentNotebook.cellsUI;
                const activeCell = Cell.currentFocus;

                const activeCellIndex = cells.indexOf(activeCell);
                if (activeCellIndex < 0) {
                    console.log("Active cell is not part of current notebook?");
                    return;
                }

                this.runCells(cells.slice(0, activeCellIndex + 1));
            });

            document.querySelector('.insert-cell-below').addEventListener('click', evt => {
                const notebookUI = this.currentNotebook.cellsUI;
                const activeCell = Cell.currentFocus.id;
                if (!notebookUI.cells[activeCell] || notebookUI.cells[activeCell] !== Cell.currentFocus) {
                    console.log("Active cell is not part of current notebook?");
                    return;
                }

                notebookUI.dispatchEvent(new CustomEvent('InsertCellAfter', { detail: { cellId: activeCell }}));
            });

        document.querySelector('.insert-cell-above').addEventListener('click', evt => {
            const notebookUI = this.currentNotebook.cellsUI;
            const activeCell = Cell.currentFocus.id;
            if (!notebookUI.cells[activeCell] || notebookUI.cells[activeCell] !== Cell.currentFocus) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            const prevCell = Cell.currentFocus.container.previousSibling;

            notebookUI.dispatchEvent(new CustomEvent('InsertCellAfter', { detail: { cellId: (prevCell && prevCell.id) || null }}));
        });

        document.querySelector('.delete-cell').addEventListener('click', evt => {
            const notebookUI = this.currentNotebook.cellsUI;
            const activeCell = Cell.currentFocus.id;
            if (!notebookUI.cells[activeCell] || notebookUI.cells[activeCell] !== Cell.currentFocus) {
                console.log("Active cell is not part of current notebook?");
                return;
            }

            notebookUI.dispatchEvent(new CustomEvent('DeleteCell', { detail: {cellId: activeCell }}));
        });
    }

    runCells(cellContainers) {
        const ids = [];
        [...cellContainers].forEach(container => {
            if (container.cell && container.id) {
                ids.push(container.id);
                container.cell.dispatchEvent(new BeforeCellRunEvent(container.id));
            }
        });

        if (ids.length) {
            this.socket.send(new messages.RunCell(this.currentNotebookPath, ids));
        }
    }

    loadNotebook(path) {
        const notebookTab = this.tabUI.getTab(path);

        if (!notebookTab) {
            const notebookUI = new NotebookUI(path, this.socket);
            this.socket.send(new messages.LoadNotebook(path));
            const tab = this.tabUI.addTab(path, span(['notebook-tab-title'], [path.split(/\//g).pop()]), {
                notebook: notebookUI.cellUI.el,
                kernel: notebookUI.kernelUI.el
            });
            this.tabUI.activateTab(tab);
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
        const notebookPath = prompt("Enter the path to the new notebook");
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



const textToolbar = new TextToolbar('Toolbar-Text', {
    '.PN-Text-blockType': {
        change: (evt) => {
            document.execCommand('formatBlock', false, `<${evt.target.value}>`);
        }
    },
    '.PN-Text-bold': 'command',
    '.PN-Text-italic': 'command',
    '.PN-Text-underline': 'command',
    '.PN-Text-strike': 'command',
    '.PN-Text-code': {
        click: (evt) => {
            const selection = document.getSelection();
            if (selection.baseNode && selection.baseNode.tagName && selection.baseNode.tagName.toLowerCase() === "code") {
                document.execCommand('insertHTML', false, selection.toString());
            } else {
                document.execCommand('insertHTML', false, '<code>' + selection.toString() + '</code>');
            }
        },
        getState: (selection) => (selection.baseNode && selection.baseNode.tagName && selection.baseNode.tagName.toLowerCase() === "code")
    },
    '.PN-Text-Equation': {
        click: (evt) => LaTeXEditor.forSelection().show(),
        getState: (evt) => {
            const selection = document.getSelection();
            if (selection.focusNode && selection.focusNode.childNodes) {
                for (let i = 0; i < selection.focusNode.childNodes.length; i++) {
                    const node = selection.focusNode.childNodes[i];
                    if (node.nodeType === 1 && selection.containsNode(node, false) && (node.classList.contains('katex') || node.classList.contains('katex-block'))) {
                        return true;
                    }
                }
            }
            return false;
        }
    },
    '.PN-Text-ul': 'command',
    '.PN-Text-ol': 'command',
    '.PN-Text-indent': 'command',
    '.PN-Text-outdent': 'command'
});

document.addEventListener('selectionchange', (evt) => textToolbar.action(evt));


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