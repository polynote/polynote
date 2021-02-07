import {div, icon, span, TagElement} from "../tags";
import {ServerMessageDispatcher} from "../../messaging/dispatcher";
import {Disposable, IDisposable, NotebookStateHandler, ServerStateHandler} from "../../state";
import {Notebook} from "./notebook/notebook";
import {VimStatus} from "./notebook/vim_status";
import {nameFromPath} from "../../util/helpers";

export class Tabs extends Disposable {
    readonly el: TagElement<"div">;
    private readonly tabs: Record<string, { tab: TagElement<"div">, content: TagElement<"div">, handler: NotebookStateHandler, obs: IDisposable}> = {};
    private tabContainer: TagElement<"div">;
    private currentTab?: { path: string, tab: TagElement<"div">, content: TagElement<"div">};

    constructor(private readonly dispatcher: ServerMessageDispatcher, private homeTab: TagElement<"div">) {
        super()
        this.el = div(['tab-view'], [
            this.tabContainer = div(['tabbed-pane', 'tab-container'], []),
            VimStatus.get.el
        ]);

        this.addHome()

        ServerStateHandler.get.observeKey("openNotebooks", nbs => {
            if (nbs.length > 0) {
                nbs.forEach(path => {
                    if (this.getTab(path) === undefined && path !== "home") {
                        const nbInfo = ServerStateHandler.getOrCreateNotebook(path);
                        if (nbInfo?.info) {
                            this.add(path, this.mkTitle(path), new Notebook(nbInfo.info.dispatcher, nbInfo.handler).el);
                        }
                    }
                })
            } else {
                Object.keys(this.tabs).forEach(tab => this.remove(tab))
            }
        }).disposeWith(this)

        const handleCurrentNotebook = (path?: string) => {
            if (path) {
                if (path && this.getTab(path) === undefined && path !== "home") {
                    const nbInfo = ServerStateHandler.getOrCreateNotebook(path);
                    if (nbInfo?.info) {
                        this.add(path, this.mkTitle(path), new Notebook(nbInfo.info.dispatcher, nbInfo.handler).el);
                        this.activate(path)
                    }
                } else {
                    this.activate(path)
                }
            } else {
                this.activate("home")
            }
        }
        ServerStateHandler.get.observeKey("currentNotebook", n => handleCurrentNotebook(n)).disposeWith(this)
        handleCurrentNotebook(ServerStateHandler.get.state.currentNotebook)
    }

    getTab(path: string) {
        return this.tabs[path]
    }

    add(path: string, title: TagElement<"span">, content: TagElement<"div">) {
        if (this.tabs[path] === undefined) {

            const mkTab = (tabPath: string, tabTitle: TagElement<"span">): TagElement<"div"> => {
                return div(['tab'], [
                    tabTitle,
                    icon(['close-button'], 'times', 'close icon').mousedown(evt => {
                        evt.stopPropagation();
                        this.remove(tabPath)
                    })
                ])
                    .attr('title', tabPath)
                    .mousedown((evt: MouseEvent) => {
                        if (evt.button === 0) { // left click
                            this.activate(tabPath)
                        } else if (evt.button === 1) { // middle click
                            this.remove(tabPath)
                        } // nothing on right click...
                    });
            }

            const tab: TagElement<"div"> = mkTab(path, title)

            this.tabContainer.appendChild(tab);

            // watch for renames of this notebook.
            const handler = ServerStateHandler.getOrCreateNotebook(path).handler;
            const obs = handler.preObserveKey("path", oldPath => newPath => {
                    const tab = this.tabs[oldPath];
                    delete this.tabs[oldPath];
                    const newTab = mkTab(newPath, this.mkTitle(newPath))
                    this.tabContainer.replaceChild(newTab, tab.tab)
                    this.tabs[newPath] = {...tab, tab: newTab};
                    if (this.currentTab?.path === oldPath) {
                        this.activate(newPath)
                    }
                }
            ).disposeWith(this);

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
            ServerStateHandler.selectNotebook(path)
        }
    }

    private remove(path: string) {
        const tab = this.tabs[path];
        if (tab) {
            tab.obs.tryDispose();

            const nextTabEl = tab.tab.previousElementSibling || tab.tab.nextElementSibling;
            const nextTabPath = Object.entries(this.tabs).find(([p, t]) => t.tab === nextTabEl)?.[0];

            this.tabContainer.removeChild(tab.tab);
            delete this.tabs[path];

            if (path !== "home") {
                ServerStateHandler.closeNotebook(path)
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

    private mkTitle(path: string): TagElement<"span"> {
       return span(['notebook-tab-title'], [nameFromPath(path)]);
    }
}