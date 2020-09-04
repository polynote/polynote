import {div, icon, span, TagElement} from "../tags";
import {CloseNotebook, ServerMessageDispatcher, SetSelectedNotebook} from "../../messaging/dispatcher";
import {ServerStateHandler} from "../../state/server_state";
import {Observer} from "../../state/state_handler";
import {NotebookStateHandler} from "../../state/notebook_state";
import {Notebook} from "./notebook/notebook";
import {VimStatus} from "./notebook/vim_status";

export class Tabs {
    readonly el: TagElement<"div">;
    private readonly tabs: Record<string, { tab: TagElement<"div">, content: TagElement<"div">, handler: NotebookStateHandler, obs: Observer<any>}> = {};
    private tabContainer: TagElement<"div">;
    private currentTab?: { path: string, tab: TagElement<"div">, content: TagElement<"div">};

    constructor(private readonly dispatcher: ServerMessageDispatcher, private homeTab: TagElement<"div">) {
        this.el = div(['tab-view'], [
            this.tabContainer = div(['tabbed-pane', 'tab-container'], []),
            VimStatus.get.el
        ]);

        this.addHome()

        ServerStateHandler.get.view("openNotebooks").addObserver(nbs => {
            if (nbs) {
                nbs.forEach(path => {
                    if (this.getTab(path) === undefined && path !== "home") {
                        const nbInfo = ServerStateHandler.getOrCreateNotebook(path);
                        if (nbInfo?.info) {
                            this.add(path, span(['notebook-tab-title'], [path.split(/\//g).pop()!]), new Notebook(nbInfo.info.dispatcher, nbInfo.handler).el);
                        }
                    }
                })
            } else {
                Object.keys(this.tabs).forEach(tab => this.remove(tab))
            }
        })

        ServerStateHandler.get.view("currentNotebook").addObserver(path => {
            if (path) {
                if (this.getTab(path) === undefined && path !== "home") {
                    const nbInfo = ServerStateHandler.getOrCreateNotebook(path);
                    if (nbInfo?.info) {
                        this.add(path, span(['notebook-tab-title'], [path.split(/\//g).pop()!]), new Notebook(nbInfo.info.dispatcher, nbInfo.handler).el);
                        this.activate(path)
                    }
                } else {
                    this.activate(path)
                }
            }
        })
    }

    getTab(path: string) {
        return this.tabs[path]
    }

    add(path: string, title: TagElement<"span">, content: TagElement<"div">) {
        if (this.tabs[path] === undefined) {

            // define callbacks here so we can change the path if the tab is renamed.
            let activate = () => this.activate(path);
            let remove = () => this.remove(path);

            const tab: TagElement<"div"> = div(['tab'], [
                title,
                icon(['close-button'], 'times', 'close icon').mousedown(evt => {
                    evt.stopPropagation();
                    remove()
                })
            ])
                .attr('title', path)
                .mousedown((evt: MouseEvent) => {
                    if (evt.button === 0) { // left click
                        activate()
                    } else if (evt.button === 1) { // middle click
                        remove()
                    } // nothing on right click...
                });

            this.tabContainer.appendChild(tab);

            // watch for renames of this notebook.
            const handler = ServerStateHandler.getOrCreateNotebook(path).handler;
            const obs = handler.view("path").addObserver((newPath, oldPath) => {
                const tab = this.tabs[oldPath];
                delete this.tabs[oldPath];
                this.tabs[newPath] = tab;
                activate = () => this.activate(newPath);
                remove = () => this.remove(newPath);
            });

            this.tabs[path] = {tab, content, handler, obs};
        }
    }

    activate(path: string) {
        if (this.currentTab === undefined || this.currentTab.tab.classList.contains("active")) {
            const tab = this.tabs[path];
            const current = this.currentTab;
            if (current) {
                current.content.replaceWith(tab.content);
                current.tab.classList.remove("active");
            } else {
                this.el.appendChild(tab.content)
            }
            tab.tab.classList.add("active");
            this.currentTab = {path, tab: tab.tab, content: tab.content};
            this.dispatcher.dispatch(new SetSelectedNotebook(path))
        }
    }

    private remove(path: string) {
        const tab = this.tabs[path];
        if (tab) {
            tab.handler.removeObserver(tab.obs);

            const nextTabEl = tab.tab.previousElementSibling || tab.tab.nextElementSibling;
            const nextTabPath = Object.entries(this.tabs).find(([p, t]) => t.tab === nextTabEl)?.[0];

            this.tabContainer.removeChild(tab.tab);
            delete this.tabs[path];

            if (path !== "home") {
                this.dispatcher.dispatch(new CloseNotebook(path))
            }

            if (this.currentTab?.path === path) {
                if (nextTabPath) {
                    this.activate(nextTabPath);
                }
            }

            if (Object.keys(this.tabs).length === 0) {
                this.addHome()
            }
        }
    }

    private addHome() {
        this.add("home", span([], "Home"), this.homeTab);
    }
}