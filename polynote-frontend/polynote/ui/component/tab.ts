// TODO: Shouldn't it extend UIEventTarget?
import {div, span, TagElement} from "../util/tags";
import {NoActiveTab, TabActivated, TabRemoved, UIMessage, UIMessageTarget} from "../util/ui_event";
import {storage} from "../util/storage";

interface Tab {
    name: string,
    title: TagElement<"span">,
    content: TagElement<any>,
    type: string
}

type TabEl = TagElement<"div"> & { tab: Tab }
function isTabEl(tabEl: any): tabEl is TabEl {
    return (tabEl as TabEl).tab !== undefined;
}

export class TabUI extends UIMessageTarget {
    readonly el: TagElement<"div">;
    private tabContainer: TagElement<"div">;
    private readonly tabs: Record<string, Tab> = {};
    private readonly tabEls: Record<string, TabEl> = {};
    private currentTab: Tab;

    constructor(private contentAreas: Record<string, TagElement<any>>) {
        super();
        this.el = div(['tabbed-pane'], [
            this.tabContainer = div(['tab-container'], [])
        ]);
    }

    addTab(name: string, title: TagElement<"span">, content: TagElement<any>, type: string) {
        const tab = {
            name: name,
            title: title,
            content: content,
            type: type
        };

        this.tabs[name] = tab;
        const tabEl = Object.assign(
            div(['tab'], [
                title,
                span(['close-button', 'fa'], ['']).click(evt => this.removeTab(tab))
            ]).attr('title', name),
            { tab: tab });

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

    removeTab(tab: Tab) {
        const tabEl = this.tabEls[tab.name];

        if (this.currentTab === tab) {
            if (tabEl) {
                const nextTab = tabEl.previousSibling || tabEl.nextSibling;
                if (nextTab && isTabEl(nextTab) && nextTab.tab) {
                    this.activateTab(nextTab.tab);
                } else {
                    setTimeout(() => this.publish(new NoActiveTab()), 0);
                }
            }
        }

        if (tabEl) {
            this.tabContainer.removeChild(tabEl);
        }

        delete this.tabEls[tab.name];
        delete this.tabs[tab.name];

        this.publish(new TabRemoved(tab.name));

        // if (tab.content && tab.content.parentNode) {
        //     tab.content.parentNode.removeChild(tab.content);
        // }
    }

    activateTabName(name: string) {
        if (this.tabs[name]) {
            this.activateTab(this.tabs[name]);
            return true;
        } else {
            return false;
        }
    }

    setCurrentScrollLocation(scrollTop: number) {
        storage.update('notebookLocations', (locations: Record<string, number>) => {
            if (!locations) {
                locations = {};
            }

            locations[this.currentTab.name] = scrollTop;
            return locations;
        });
    }

    activateTab(tab: Tab) {
        if (this.currentTab && this.currentTab === tab) {
            return;
        } else if (this.currentTab) {
            // remember previous location
            this.setCurrentScrollLocation(this.currentTab.content.notebook.parentElement.scrollTop);

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
        this.publish(new TabActivated(tab.name, tab.type));
    }

    getTab(name: string) {
        return this.tabs[name];
    }
}