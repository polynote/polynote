import {div, icon, TagElement} from "../../util/tags";
import {ServerMessageDispatcher, SetSelectedNotebook} from "../messaging/dispatcher";
import {ServerStateHandler} from "../state/server_state";
import {Observer} from "../state/state_handler";
import {NotebookStateHandler} from "../state/notebook_state";

export class TabComponent {
    readonly el: TagElement<"div">;
    private readonly tabs: Record<string, { tab: TagElement<"div">, content: TagElement<"div">, handler: NotebookStateHandler, obs: Observer<any>}> = {};
    private tabContainer: TagElement<"div">;
    private currentTab?: string;

    constructor(private readonly dispatcher: ServerMessageDispatcher, private readonly serverStateHandler: ServerStateHandler) {
        this.el = div(['tab-view'], [
            this.tabContainer = div(['tabbed-pane', 'tab-container'], [])]
        );
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
                icon(['close-button'], 'times', 'close icon').click(evt => remove())
            ]).attr('title', path);

            tab.addEventListener('mousedown', evt => {
                if (evt.button === 0) { // left click
                    activate()
                } else if (evt.button === 1) { // middle click
                    remove()
                } // nothing on right click...
            });

            this.tabContainer.appendChild(tab);

            // watch for renames of this notebook.
            const handler = this.serverStateHandler.getState().notebooks[path].info!.handler;
            const obs = handler.view("path").addObserver((newPath, oldPath) => {
                const tab = this.tabs[oldPath];
                delete this.tabs[oldPath];
                this.tabs[newPath] = tab;
                activate = () => this.activate(newPath);
                remove = () => this.remove(newPath);
            });

            this.tabs[path] = {tab, content, handler, obs};
        }

        this.activate(path);
    }

    activate(path: string) {
        if (this.currentTab !== path) {
            const tab = this.tabs[path];
            const current = this.currentTab && this.tabs[this.currentTab];
            if (current) {
                this.el.replaceChild(tab.content, current.content);
                current.tab.classList.remove("active");
            } else {
                this.el.appendChild(tab.content)
            }
            tab.tab.classList.add("active");
            this.currentTab = path;
            this.dispatcher.dispatch(new SetSelectedNotebook(path))
        }
    }

    private remove(path: string) {
        const tab = this.tabs[path];
        if (tab) {
            tab.handler.removeObserver(tab.obs);
            if (this.currentTab === path) {
                const nextTabEl = tab.tab.previousElementSibling || tab.tab.nextElementSibling;
                const nextTabPath = Object.entries(this.tabs).find(([p, t]) => t.tab === nextTabEl)?.[0];
                if (nextTabPath) {
                    this.activate(nextTabPath);
                }
                this.dispatcher.dispatch(new SetSelectedNotebook(nextTabPath));
            }

            this.tabContainer.removeChild(tab.tab);
            delete this.tabs[path];
        }
    }
}