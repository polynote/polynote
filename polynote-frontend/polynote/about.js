import {div, span, tag} from "./tags";
import {FullScreenModal} from "./modal";
import {TabNav} from "./tab_nav";

export class About extends FullScreenModal {
    constructor(mainUI) {
        super();
        this.mainUI = mainUI; // unfortunately we need to be able to pull info from the main ui...
    }

    aboutContent() {
        const version = this.mainUI.currentServerVersion;
        const commit = this.mainUI.currentServerCommit;
        const info = [
            ["Server Version", version],
            ["Server Commit", commit]
        ];
        const el = tag('ul', ['server-info'], {}, []);
        for (const [k, v] of info) {
            el.appendChild(tag('li', [], {}, [
                span(['label'], [k]),
                span(['data'], [v])
            ]))
        }
        return el;
    }

    show() {
        const tabs = {
            'About': div([], [span([], [this.aboutContent()])]),
            'Hotkeys': div([], [span([], ["Press these buttons to do things"])]),
            'Preferences': div([], [span([], ["Here are some preferences"])]),
            'Running Kernels': div([], [span([], ["Here are some running kernels"])])
        };
        this.content.replaceChild(new TabNav(tabs).container, this.content.firstChild);

        super.show();
    }
}
