"use strict";

import {TriggerItem, UIMessage, UIMessageTarget} from "../util/ui_event";
import {div, span, tag, TagElement} from "../util/tags";
import {storage} from "../util/storage";

export class HomeUI extends UIMessageTarget {
    readonly el: TagElement<"div">;
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
        (storage.get('recentNotebooks') || []).forEach((nb: {name: string, path: string}) => {
            recent!.appendChild(
                tag('li', ['notebook-link'], {}, [
                    span([], nb.name).click(() => this.publish(new TriggerItem(nb.path)))
                ])
            );
        });
    }
}