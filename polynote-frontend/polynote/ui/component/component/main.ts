import {div, h2, span, TagElement} from "../../util/tags";
import {MarkdownIt} from "../../../util/markdown-it";
import {scala, vega} from "../../monaco/languages";
import * as monaco from "monaco-editor";
import {theme} from "../../monaco/theme";
import * as Tinycon from "tinycon";
import {SocketSession} from "../../../comms";
import {ServerMessageReceiver} from "../messaging/receiver";
import {LoadNotebook, ServerMessageDispatcher} from "../messaging/dispatcher";
import {ToolbarComponent} from "./toolbar";
import {SplitViewComponent} from "./splitview";
import {ServerStateHandler} from "../state/server_state";
import {TabComponent} from "./tab";
import {Notebook} from "./notebook";
import {Kernel} from "./kernel";
import {KernelStateHandler} from "../state/kernel_state";
import {NotebookList} from "./notebooklist";

/**
 * Main is the entry point to the entire UI. It initializes the state, starts the websocket connection, and contains the
 * other components.
 */
class Main {
    public el: TagElement<"div">;
    private receiver: ServerMessageReceiver;

    private constructor() {

        this.receiver = new ServerMessageReceiver();
        const dispatcher = new ServerMessageDispatcher();

        // serverDispatcher.dispatch(new RequestNotebooksList()) // TODO: notebook list component should do this in its constructor!

        const nbList = new NotebookList(dispatcher, ServerStateHandler.get)
        const leftPane = { header: nbList.header, el: nbList.el };
        const tabs = new TabComponent(dispatcher, ServerStateHandler.get);
        const center = tabs.el;
        const rightPane = { header: h2([], []), el: div([], [])}; // TODO: need a better placeholder here...

        this.el = div(['main-ui'], [
            div(['header'], [new ToolbarComponent(dispatcher).el]),
            div(['body'], [new SplitViewComponent(leftPane, center, rightPane).el]),
            div(['footer'], []) // no footer yet!
        ]);

        const path = unescape(window.location.pathname.replace(new URL(document.baseURI).pathname, ''));
        const notebookBase = 'notebook/';
        if (path.startsWith(notebookBase)) {
            dispatcher.dispatch(new LoadNotebook(path.substring(notebookBase.length)))
        }

        ServerStateHandler.get.view("currentNotebook").addObserver((_, path) => {
            if (path) {

                // TODO: set the page title here

                if(tabs.getTab(path) === undefined) {
                    const nbInfo = ServerStateHandler.get.getState().notebooks[path].info;
                    if (nbInfo) {
                        tabs.add(path, span(['notebook-tab-title'], [path.split(/\//g).pop()!]), new Notebook(nbInfo.dispatcher, nbInfo.handler).el);
                        const kernel = new Kernel(nbInfo.dispatcher, nbInfo.handler.view("kernel", KernelStateHandler) as KernelStateHandler, 'rightPane')
                        this.el.replaceChild(kernel.statusEl, rightPane.header);
                        this.el.replaceChild(kernel.el, rightPane.el);
                        rightPane.header = kernel.statusEl;
                        rightPane.el = kernel.el;
                    }
                } else {
                    tabs.activate(path);
                }
            }
        })
    }

    private static inst: Main;
    static get get() {
        if (!Main.inst) {
            Main.inst = new Main()
        }
        return Main.inst;
    }
}

window.MarkdownIt = MarkdownIt;

// set up custom highlighters
monaco.languages.register({ id: 'scala' });
monaco.languages.setMonarchTokensProvider('scala', scala.definition);
monaco.languages.setLanguageConfiguration('scala', scala.config);

monaco.languages.register({id: 'vega'});
monaco.languages.setMonarchTokensProvider('vega', vega.definition);
monaco.languages.setLanguageConfiguration('vega', vega.config);

// use our theme
monaco.editor.defineTheme('polynote', theme);

// open the global socket for control messages
SocketSession.global;

const mainEl = document.getElementById('Main');
mainEl?.appendChild(Main.get.el);

Tinycon.setOptions({
    background: '#308b24'
});

