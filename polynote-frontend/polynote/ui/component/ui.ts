'use strict';

import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import {
    CancelTasks, CreateNotebook, UIMessageTarget, ImportNotebook, UIToggle, TabActivated, NoActiveTab, ViewAbout,
    DownloadNotebook, ClearOutput, UIMessageRequest, ServerVersion, RunningKernels, KernelCommand, LoadNotebook,
    CellsLoaded, RenameNotebook, DeleteNotebook, TabRemoved, TabRenamed, FocusCell, CopyNotebook, CurrentIdentity
} from '../util/ui_event'
import {Cell, CellContainer, CodeCell, CodeCellModel} from "./cell"
import {div, span, TagElement} from '../util/tags'
import * as messages from '../../data/messages';
import {storage} from '../util/storage'
import {ToolbarUI} from "./toolbar";
import {clientInterpreters} from "../../interpreter/client_interpreter";
import {Position} from "monaco-editor";
import {About} from "./about";
import {SplitView} from "./split_view";
import {KernelUI} from "./kernel_ui";
import {NotebookUI} from "./notebook";
import {TabUI} from "./tab";
import {CreateNotebookDialog, NotebookNameChangeDialog, NotebookListUI} from "./notebook_list";
import {HomeUI} from "./home";
import {SocketSession} from "../../comms";
import {CurrentNotebook} from "./current_notebook";
import {NotebookCellsUI} from "./nb_cells";
import {Identity, KernelBusyState} from "../../data/messages";
import {SparkPropertySet} from "../../data/data";

// what is this?
document.execCommand("defaultParagraphSeparator", false, "p");
document.execCommand("styleWithCSS", false);

export const Interpreters: Record<string, string> = {};
export const SparkTemplates: Record<string, SparkPropertySet> = {};

export class MainUI extends UIMessageTarget {
    private mainView: SplitView;
    readonly toolbarUI: ToolbarUI;
    readonly el: TagElement<"div">;
    readonly notebookContent: TagElement<"div">;
    readonly tabUI: TabUI;
    private browseUI: NotebookListUI;
    private disabled: boolean;
    private currentServerCommit?: string;
    private currentServerVersion: string;
    private about?: About;
    private welcomeUI?: HomeUI;
    private identity?: Identity;

    constructor() {
        super();
        this.makeRoot(); // MainUI is always a root message target.

        let left = { el: div(['grid-shell'], []) };
        let center = { el: div(['tab-view'], []) };
        let right = { el: div(['grid-shell'], []) };

        this.mainView = new SplitView('split-view', left, center, right);
        this.toolbarUI = new ToolbarUI().setParent(this);

        this.el = div(['main-ui'], [this.toolbarUI.el, this.mainView.el]);

        this.notebookContent = div(['notebook-content'], []);

        this.tabUI = new TabUI({notebook: this.notebookContent, kernel: right.el}).setParent(this);
        this.mainView.center.el.appendChild(this.tabUI.el);
        this.mainView.center.el.appendChild(this.notebookContent);

        this.browseUI = new NotebookListUI().setParent(this);
        this.mainView.left.el.appendChild(this.browseUI.el);
        // TODO: remove listeners on children.
        this.subscribe(CreateNotebook, (path) => this.createNotebook(path));
        this.subscribe(RenameNotebook, path => this.renameNotebook(path));
        this.subscribe(CopyNotebook, path => this.copyNotebook(path));
        this.subscribe(DeleteNotebook, path => this.deleteNotebook(path));
        this.subscribe(ImportNotebook, (name, content) => this.importNotebook(name, content));
        this.subscribe(UIToggle, (which, force) => {
            if (which === "NotebookList") {
                this.mainView.collapse('left', force)
            } else if (which === "KernelUI") {
                this.mainView.collapse('right', force)
            }
        });
        this.browseUI.init();

        SocketSession.global.listenOnceFor(messages.ListNotebooks, (items) => this.browseUI.setItems(items));
        SocketSession.global.send(new messages.ListNotebooks([]));

        SocketSession.global.listenOnceFor(messages.ServerHandshake, (interpreters, serverVersion, serverCommit, identity, sparkTemplates) => {
            for (let interp of Object.keys(interpreters)) {
                Interpreters[interp] = interpreters[interp];
            }
            for (let interp of Object.keys(clientInterpreters)) {
                Interpreters[interp] = clientInterpreters[interp].languageTitle;
            }

            for (let template of sparkTemplates) {
                SparkTemplates[template.name] = template;
            }

            this.toolbarUI.cellToolbar.setInterpreters(Interpreters);

            // just got a handshake for a server running on a different commit! We better reload since who knows what could've changed!
            if (this.currentServerCommit && this.currentServerCommit !== serverCommit) {
                document.location.reload();
            }
            this.currentServerVersion = serverVersion;
            this.currentServerCommit = serverCommit;
            this.identity = identity || undefined;
        });

        SocketSession.global.addMessageListener(
            messages.RenameNotebook,
            (oldPath, newPath) => this.onNotebookRenamed(oldPath, newPath));

        SocketSession.global.addMessageListener(
            messages.DeleteNotebook,
            path => this.onNotebookDeleted(path));

        SocketSession.global.addMessageListener(
            messages.CreateNotebook,
            actualPath => this.browseUI.addItem(actualPath));

        SocketSession.global.addEventListener('close', evt => {
           this.browseUI.setDisabled(true);
           this.toolbarUI.setDisabled(true);
           this.disabled = true;
        });

        SocketSession.global.addEventListener('open', evt => {
           this.browseUI.setDisabled(false);
           if (this.tabUI.getCurrentTab().name !== 'home') {
               this.toolbarUI.setDisabled(false);
           }
           this.disabled = false;
        });

        window.addEventListener('popstate', evt => {
           if (evt.state?.notebook) {
               this.loadNotebook(evt.state.notebook);
           }
        });

        this.subscribe(TabActivated, (name, type) => {
            if (type === 'notebook') {
                const tabUrl = new URL(`notebook/${encodeURIComponent(name)}`, document.baseURI);

                const href = window.location.href;
                const hash = window.location.hash;
                const title = `${name.split(/\//g).pop()} | Polynote`;
                document.title = title; // looks like chrome ignores history title so we need to be explicit here.

                 // handle hashes and ensure scrolling works
                if (hash && window.location.href === (tabUrl.href + hash)) {
                    window.history.pushState({notebook: name}, title, href);
                    this.handleHashChange()
                } else {
                    window.history.pushState({notebook: name}, title, tabUrl.href);
                }

                const currentNotebook = this.tabUI.getTab(name).content.notebook.cellsUI as NotebookCellsUI;
                CurrentNotebook.set(currentNotebook.notebook);
                currentNotebook.notebook.cellUI.forceLayout();
                if (SocketSession.global.isOpen) {
                    this.toolbarUI.setDisabled(false);
                }

                currentNotebook.notebook.setIconBubble();
            } else if (type === 'home') {
                const title = 'Polynote';
                window.history.pushState({notebook: name}, title, document.baseURI);
                document.title = title;
                this.toolbarUI.setDisabled(true);
            }
        });

        this.subscribe(TabRenamed, (oldName, newName, type, isCurrent) => {
            if (isCurrent) {
                const tabUrl = new URL(`notebook/${newName}`, document.baseURI);
                const href = window.location.hash ? `${tabUrl.href}#${window.location.hash.replace(/^#/, '')}` : tabUrl.href;
                window.history.replaceState({notebook: newName}, `${newName.split(/\//g).pop()} | Polynote`, href);
            }
        });

        this.subscribe(TabRemoved, path => {
            const nb = NotebookUI.getInstance(path);
            if (nb) {
                nb.close();
            }
        });

        window.addEventListener('hashchange', evt => {
            this.handleHashChange()
        });

        this.subscribe(NoActiveTab, () => {
            this.showWelcome();
        });

        this.subscribe(CancelTasks, path => {
           const current = CurrentNotebook.get;
           if (current) {
               current.socket.send(new messages.CancelTasks(path))
           }
        });

        this.subscribe(ViewAbout, section => {
            if (!this.about) {
                this.about = new About().setParent(this);
            }
            this.about.show(section);
        });

        this.subscribe(DownloadNotebook, path => {
            MainUI.browserDownload(window.location.pathname + "?download=true", path);
        });

        this.subscribe(ClearOutput, path => {
            CurrentNotebook.get.socket.send(new messages.ClearOutput())
        });

        this.subscribe(UIMessageRequest, (msg, cb) => {
            if (msg.prototype === ServerVersion.prototype)  {
                cb(this.currentServerVersion, this.currentServerCommit)
            } else if (msg.prototype === RunningKernels.prototype) {
                SocketSession.global.request(new messages.RunningKernels([])).then((msg) => {
                    const statuses: Record<string, KernelBusyState> = {};
                    for (const kv of msg.kernelStatuses) {
                        statuses[kv.first] = kv.second;
                    }
                    cb(statuses);
                })
            } else if (msg.prototype === CurrentIdentity.prototype) {
                const name = this.identity?.name;
                const avatar = this.identity?.avatar ?? undefined;
                cb(name, avatar);
            }
        });

        this.subscribe(KernelCommand, (path, command) => {
            if (command === "start") {
                CurrentNotebook.get.socket.send(new messages.StartKernel(messages.StartKernel.NoRestart));
            } else if (command === "kill") {
                if (confirm("Kill running kernel? State will be lost.")) {
                    CurrentNotebook.get.socket.send(new messages.StartKernel(messages.StartKernel.Kill));
                }
            }
        });

        this.subscribe(LoadNotebook, path => this.loadNotebook(path));

        this.subscribe(FocusCell, (path, cellId) => {
            this.publish(new LoadNotebook(path));
            CurrentNotebook.get.selectCell(cellId)
        })
    }

    showWelcome() {
        if (!this.welcomeUI) {
            this.welcomeUI = new HomeUI().setParent(this);
        }
        const welcomeKernelUI = new KernelUI();
        this.tabUI.addTab('home', span([], 'Home'), {
            notebook: this.welcomeUI.el,
            kernel: welcomeKernelUI.el
        }, 'home');
        this.tabUI.activateTab(this.tabUI.getTab('home'));
    }

    loadNotebook(path: string) {
        const notebookTab = this.tabUI.getTab(path);

        if (!notebookTab) {
            const notebookUI = NotebookUI.getOrCreate(this, path, this); // TODO: remove these `this`
            const tab = this.tabUI.addTab(path, span(['notebook-tab-title'], [path.split(/\//g).pop()!]), {
                notebook: notebookUI.cellUI.el,
                kernel: notebookUI.kernelUI.el
            }, 'notebook');
            this.tabUI.activateTab(tab);
        } else {
            this.tabUI.activateTab(notebookTab);
        }

        const notebookName = path.split(/\//g).pop()!;

        storage.update<{name: string, path: string}[]>('recentNotebooks', recentNotebooks => {
            const currentIndex = recentNotebooks.findIndex(nb => nb.path === path);
            if (currentIndex !== -1) {
                recentNotebooks.splice(currentIndex, 1);
            }
            recentNotebooks.unshift({name: notebookName, path: path});
            return recentNotebooks;
        })
    }

    createNotebook(path?: string) {
        CreateNotebookDialog.prompt(path).then(
            notebookPath => {
                if (notebookPath) {
                    SocketSession.global.listenOnceFor(messages.CreateNotebook, actualPath => {
                        if (actualPath.substring(0, notebookPath.length) === notebookPath) {
                            this.loadNotebook(actualPath);
                        }
                    });
                    SocketSession.global.send(new messages.CreateNotebook(notebookPath));
                }
            }
        ).catch(() => null)
    }

    renameNotebook(path: string) {
        // Existing listener will hear broadcast and update UI
        NotebookNameChangeDialog.prompt(path, "Rename")
            .then(newPath => SocketSession.global.send(new messages.RenameNotebook(path, newPath)))
    }

    copyNotebook(path: string) {
        // Existing listener will hear broadcast and update UI
        NotebookNameChangeDialog.prompt(path, "Copy")
            .then(newPath => SocketSession.global.send(new messages.CopyNotebook(path, newPath)))
    }

    onNotebookRenamed(oldPath: string, newPath: string) {
        this.browseUI.renameItem(oldPath, newPath);
        const newName =  newPath.split(/\//g).pop();
        this.tabUI.renameTab(oldPath, newPath, newName);
        NotebookUI.renameInstance(oldPath, newPath);

        storage.update<{name: string, path: string}[]>('recentNotebooks', recentNotebooks => {
            return recentNotebooks.map(nb => {
                if (nb.path === oldPath) {
                    nb.name = newName || newPath;
                    nb.path = newPath;
                    return nb
                } else return nb
            });
        })
    }

    deleteNotebook(path: string) {
        // Existing listener will hear broadcast and update UI
        // TODO: this should probably get its own dialog too, for consistency
        if (confirm(`Permanently delete ${path}?`)) {
            SocketSession.global.send(new messages.DeleteNotebook(path))
        }
    }

    onNotebookDeleted(path: string) {
        this.browseUI.removeItem(path);

        // remove from recent notebooks
        storage.update<{name: string, path: string}[]>('recentNotebooks', recentNotebooks => {
            return recentNotebooks.filter(nb => nb.path !== path)
        })
    }

    importNotebook(name: string, content: string) {
        SocketSession.global.listenOnceFor(messages.CreateNotebook, (actualPath) => {
            this.loadNotebook(actualPath);
        });
        SocketSession.global.send(new messages.CreateNotebook(name, content));
    }

    handleHashChange() {
        this.subscribe(CellsLoaded, () => {
            const hash = document.location.hash;
            // the hash can (potentially) have two parts: the selected cell and selected lines.
            // for example: #Cell2,6-12 would mean Cell2 lines 6-12
            const [hashId, lines] = hash.slice(1).split(",");

            const selected = document.getElementById(hashId) as CellContainer;
            if (selected && selected.cell !== Cell.currentFocus) {

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

                    if (selected.cell instanceof CodeCell) {
                        selected.cell.setHighlight({
                            startPos: startPos,
                            endPos: endPos
                        }, "link-highlight")
                    }
                }
                // select cell and scroll to it.
                selected.cell.focus();
            }
        });
    }

    static browserDownload(path: string, filename: string) {
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
      return (doc as CodeCellModel).cellInstance.requestCompletion(doc.getOffsetAt(pos));
  }
});

monaco.languages.registerCompletionItemProvider('python', {
  triggerCharacters: ['.', "["],
  provideCompletionItems: (doc, pos, cancelToken, context) => {
      return (doc as CodeCellModel).cellInstance.requestCompletion(doc.getOffsetAt(pos));
  }
});

monaco.languages.registerSignatureHelpProvider('scala', {
  signatureHelpTriggerCharacters: ['(', ','],
  provideSignatureHelp: (doc, pos, cancelToken, context) => {
      return (doc as CodeCellModel).cellInstance.requestSignatureHelp(doc.getOffsetAt(pos));
  }
});

monaco.languages.registerSignatureHelpProvider('python', {
    signatureHelpTriggerCharacters: ['(', ','],
    provideSignatureHelp: (doc, pos, cancelToken, context) => {
        return (doc as CodeCellModel).cellInstance.requestSignatureHelp(doc.getOffsetAt(pos));
    }
});

monaco.languages.registerCompletionItemProvider('sql', {
    triggerCharacters: ['.'],
    provideCompletionItems: (doc, pos, context, cancelToken) => {
        return (doc as CodeCellModel).cellInstance.requestCompletion(doc.getOffsetAt(pos));
    }
});