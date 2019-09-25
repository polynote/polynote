"use strict";

import {UIEventTarget} from "../util/ui_event";
import {div, TagElement} from "../util/tags";

interface ItemLabel extends TagElement<"div"> {
    name: string
}

export class TabNav extends UIEventTarget {
    readonly el: TagElement<"div">;
    private itemLabels: ItemLabel[];
    private content: TagElement<"div">;
    private selectedItem?: string;

    constructor(readonly items: Record<string, (() => TagElement<"div">) | TagElement<"div">>) {
        super();
        const itemNames = Object.keys(items);
        if (itemNames.length === 0) {
            throw new Error("Hey! You gotta initialize the TabNav with something!")
        }
        const firstItemName = itemNames.shift()!;
        this.el = div(['tab-nav'], [
            div(['tab-nav-items'], this.itemLabels = [
                div(['tab-nav-item', 'active'], [firstItemName]).withKey('name', firstItemName).click(evt => this.showItem(firstItemName)) as ItemLabel,
                ...itemNames.map(name => div(['tab-nav-item'], [name]).withKey('name', name).click(evt => this.showItem(name)) as ItemLabel)
            ]),
            this.content = div(['tab-nav-content'], [])
        ]);

        this.showItem(firstItemName);
    }

    showItem(name: string) {
        if (this.selectedItem === name) {
            return;
        }
        const newLabel = this.itemLabels.find(label=> label.name === name);
        if (!newLabel) {
            return;
        }

        if (this.selectedItem) this.itemLabels.find(label => label.name === this.selectedItem)!.classList.remove('active');

        newLabel.classList.add('active');

        while (this.content.childNodes.length) {
            this.content.removeChild(this.content.childNodes[0]);
        }

        let tabContent = this.items[name];
        if (typeof tabContent === "function") {
            tabContent = tabContent();
        }

        this.content.appendChild(tabContent);

        this.selectedItem = name;
    }

}