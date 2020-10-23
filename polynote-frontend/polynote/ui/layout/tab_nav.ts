"use strict";

import {div, span, TagElement} from "../tags";

interface ItemLabel extends TagElement<"div"> {
    name: string
}

export class TabNav {
    readonly el: TagElement<"div">;
    private itemLabels: ItemLabel[];
    private content: TagElement<"div">;
    private _disabled: boolean = false;
    private selectedItem?: string;

    constructor(readonly items: Record<string, (() => TagElement<"div">) | TagElement<"div">>, orientation: "vertical" | "horizontal" = "vertical") {
        const itemNames = Object.keys(items);
        if (itemNames.length === 0) {
            throw new Error("Hey! You gotta initialize the TabNav with something!")
        }
        const firstItemName = itemNames.shift()!;
        this.el = div(['tab-nav', orientation], [
            div(['tab-nav-items'], this.itemLabels = [
                div(['tab-nav-item', 'active'], [span([], firstItemName)]).withKey('name', firstItemName).click(evt => this.clickItem(firstItemName)) as ItemLabel,
                ...itemNames.map(name => div(['tab-nav-item'], [span([], name)]).withKey('name', name).click(evt => {this.clickItem(name)}) as ItemLabel)
            ]),
            this.content = div(['tab-nav-content'], [])
        ]);

        this.showItem(firstItemName);
    }

    private clickItem(name: string) {
        if (this._disabled)
            return;
        this.showItem(name);
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

        while (this.content.childNodes.length > 0) {
            this.content.removeChild(this.content.childNodes[0]);
        }

        let tabContent = this.items[name];
        if (typeof tabContent === "function") {
            tabContent = tabContent();
        }

        this.content.appendChild(tabContent);
        if (this.content.offsetWidth) {
            tabContent.dispatchEvent(new CustomEvent("TabDisplayed"));
            tabContent.dispatchEvent(new CustomEvent("becameVisible"));
        }

        this.selectedItem = name;
    }

    set disabled(disabled: boolean) {
        this._disabled = disabled;
        if (disabled)
            this.el.classList.add('disabled');
        else
            this.el.classList.remove('disabled');
    }

    get disabled(): boolean {
        return this._disabled;
    }

}