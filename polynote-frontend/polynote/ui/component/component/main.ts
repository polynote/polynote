import {div, h2, span, TagElement} from "../../util/tags";
import {MarkdownIt} from "../../../util/markdown-it";
import {scala, vega} from "../../monaco/languages";
import * as monaco from "monaco-editor";
import {theme} from "../../monaco/theme";
import * as Tinycon from "tinycon";
import {SocketSession} from "../messaging/comms";
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
import {SocketStateHandler} from "../state/socket_state";
import {NotebookCellsUI} from "../nb_cells";
import {CurrentNotebook} from "../current_notebook";
import {About} from "./about";

/**
 * Main is the entry point to the entire UI. It initializes the state, starts the websocket connection, and contains the
 * other components.
 */
class Main {
    public el: TagElement<"div">;
    private receiver: ServerMessageReceiver;

    private constructor(socket: SocketStateHandler) {

        this.receiver = new ServerMessageReceiver();
        const dispatcher = new ServerMessageDispatcher(socket);

        // serverDispatcher.dispatch(new RequestNotebooksList()) // TODO: notebook list component should do this in its constructor!

        const nbList = new NotebookList(dispatcher)
        const leftPane = { header: nbList.header, el: nbList.el };
        const tabs = new TabComponent(dispatcher);
        const center = tabs.el;
        const rightPane = { header: h2(['right-header'], []), el: div(['right-el'], [])}; // TODO: need a better placeholder here...

        this.el = div(['main-ui'], [
            div(['header'], [new ToolbarComponent(dispatcher).el]),
            div(['body'], [new SplitViewComponent(leftPane, center, rightPane).el]),
            div(['footer'], []) // no footer yet!
        ]);

        ServerStateHandler.get.view("currentNotebook").addObserver(path => {
            if (path) {

                Main.setTitle(path)

                if(tabs.getTab(path) === undefined) {
                    const nbInfo = ServerStateHandler.getOrCreateNotebook(path);
                    if (nbInfo?.info) {
                        tabs.add(path, span(['notebook-tab-title'], [path.split(/\//g).pop()!]), new Notebook(nbInfo.info.dispatcher, nbInfo.handler).el);
                        const kernel = new Kernel(nbInfo.info.dispatcher, nbInfo.handler.view("kernel", KernelStateHandler), 'rightPane');

                        rightPane.header.replaceWith(kernel.statusEl);
                        rightPane.header = kernel.statusEl;

                        rightPane.el.replaceWith(kernel.el);
                        rightPane.el = kernel.el;
                    }
                } else {
                    tabs.activate(path);
                }
            }
        })

        const path = unescape(window.location.pathname.replace(new URL(document.baseURI).pathname, ''));
        const notebookBase = 'notebook/';
        if (path.startsWith(notebookBase)) {
            dispatcher.dispatch(new LoadNotebook(path.substring(notebookBase.length)))
        }

        // make sure to start About
        const about = new About(dispatcher)
    }

    private static setTitle(path?: string) {
        if (path) {
            const tabUrl = new URL(`notebook/${encodeURIComponent(path)}`, document.baseURI);

            const href = window.location.href;
            const hash = window.location.hash;
            const title = `${path.split(/\//g).pop()} | Polynote`;
            document.title = title; // looks like chrome ignores history title so we need to be explicit here.

            // handle hashes and ensure scrolling works
            if (hash && window.location.href === (tabUrl.href + hash)) {
                window.history.pushState({notebook: path}, title, href);
            } else {
                window.history.pushState({notebook: path}, title, tabUrl.href);
            }
        } else {
            const title = 'Polynote';
            window.history.pushState({notebook: name}, title, document.baseURI);
            document.title = title;
        }
    }

    private static inst: Main;
    static get get() {
        if (!Main.inst) {
            Main.inst = new Main(SocketStateHandler.global)
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

