// TODO: Shouldn't it extend UIEventTarget?
import {div, span} from "../util/tags";
import {UIEvent} from "../util/ui_event";
import {storage} from "../util/storage";

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

        this.dispatchEvent(new UIEvent('TabRemoved', {name: tab.name}));

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

    setCurrentScrollLocation(scrollTop) {
        storage.update('notebookLocations', locations => {
            if (!locations) {
                locations = {};
            }

            locations[this.currentTab.name] = scrollTop;
            return locations;
        });
    }

    activateTab(tab) {
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
        this.dispatchEvent(new UIEvent('TabActivated', {tab: tab}));
    }

    getTab(name) {
        return this.tabs[name];
    }
}