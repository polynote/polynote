import {div, TagElement} from "./ui/tags";
import {MarkdownIt} from "./ui/input/markdown-it";
import {scala, vega} from "./ui/input/monaco/languages";
import * as monaco from "monaco-editor";
import {themes} from "./ui/input/monaco/themes";
import {SocketSession} from "./messaging/comms";
import {ServerMessageReceiver} from "./messaging/receiver";
import {LoadNotebook, Reconnect, ServerMessageDispatcher, SetSelectedNotebook} from "./messaging/dispatcher";
import {Toolbar} from "./ui/component/toolbar";
import {SplitView} from "./ui/layout/splitview";
import {ServerStateHandler} from "./state/server_state";
import {Tabs} from "./ui/component/tabs";
import {KernelPane} from "./ui/component/notebook/kernel";
import {NotebookList} from "./ui/component/notebooklist";
import {SocketStateHandler} from "./state/socket_state";
import {Home} from "./ui/component/home";
import {OpenNotebooksHandler, RecentNotebooksHandler} from "./state/preferences";
import {ThemeHandler} from "./state/theme";
import {CodeCellModel} from "./ui/component/notebook/cell";

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

        // handle reconnecting
        const reconnectOnWindowFocus = () => {
            console.warn("Window was focused! Attempting to reconnect.")
            dispatcher.dispatch(new Reconnect(true))
        }
        ServerStateHandler.get.view("connectionStatus").addObserver(status => {
            if (status === "disconnected") {
                window.addEventListener("focus", reconnectOnWindowFocus)
            } else {
                window.removeEventListener("focus", reconnectOnWindowFocus)
            }
        })

        const nbList = new NotebookList(dispatcher)
        const leftPane = { header: nbList.header, el: nbList.el };
        const home = new Home(dispatcher)
        const tabs = new Tabs(dispatcher, home.el);
        const center = tabs.el;
        const kernelPane = new KernelPane(dispatcher)
        const rightPane = { header: kernelPane.header, el: kernelPane.el};

        this.el = div(['main-ui'], [
            div(['header'], [new Toolbar(dispatcher).el]),
            div(['body'], [new SplitView(leftPane, center, rightPane).el]),
            div(['footer'], []) // no footer yet!
        ]);

        ServerStateHandler.get.view("currentNotebook").addObserver(path => {
            Main.handlePath(path)
        })

        const path = decodeURIComponent(window.location.pathname.replace(new URL(document.baseURI).pathname, ''));
        Promise.allSettled(OpenNotebooksHandler.state.map(path => {
            return dispatcher.loadNotebook(path, true)
        })).then(() => {
            const notebookBase = 'notebook/';
            if (path.startsWith(notebookBase)) {
                const nbPath = path.substring(notebookBase.length)
                dispatcher.loadNotebook(nbPath).then(() => {
                    dispatcher.dispatch(new SetSelectedNotebook(nbPath))
                })
            }
        })

    }

    private static handlePath(path?: string) {
        if (path && path !== "home") {
            const tabUrl = new URL(`notebook/${encodeURIComponent(path)}`, document.baseURI);

            const href = window.location.href;
            const hash = window.location.hash;
            const title = `${path.split(/\//g).pop()} | Polynote`;
            document.title = title; // looks like chrome ignores history title so we need to be explicit here.

            if (hash && window.location.href === (tabUrl.href + hash)) {
                window.history.pushState({notebook: path}, title, href);
            } else {
                window.history.pushState({notebook: path}, title, tabUrl.href);
            }

            RecentNotebooksHandler.updateState(recents => {
                const maybeExists = recents.find(r => r.path === path);
                if (maybeExists) {
                    return [maybeExists, ...recents.filter(r => r.path !== path)];
                } else {
                    const name = path.split(/\//g).pop()!;
                    return [{path, name}, ...recents];
                }
            })
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

// use our themes
monaco.editor.defineTheme('polynote-light', themes.light);
monaco.editor.defineTheme('polynote-dark', themes.dark);

// start the theme handler
const themeHandler = new ThemeHandler()

// open the global socket for control messages
SocketSession.global;

const mainEl = document.getElementById('Main');
mainEl?.appendChild(Main.get.el);

// Register all the completion providers.
// TODO: is there a more elegant way to do this?
monaco.languages.registerCompletionItemProvider('scala', {
    triggerCharacters: ['.'],
    provideCompletionItems: (doc, pos, context, cancelToken) => {
        return (doc as CodeCellModel).requestCompletion(doc.getOffsetAt(pos));
    }
});

monaco.languages.registerCompletionItemProvider('python', {
    triggerCharacters: ['.', "["],
    provideCompletionItems: (doc, pos, cancelToken, context) => {
        return (doc as CodeCellModel).requestCompletion(doc.getOffsetAt(pos));
    }
});

monaco.languages.registerSignatureHelpProvider('scala', {
    signatureHelpTriggerCharacters: ['(', ','],
    provideSignatureHelp: (doc, pos, cancelToken, context) => {
        return (doc as CodeCellModel).requestSignatureHelp(doc.getOffsetAt(pos));
    }
});

monaco.languages.registerSignatureHelpProvider('python', {
    signatureHelpTriggerCharacters: ['(', ','],
    provideSignatureHelp: (doc, pos, cancelToken, context) => {
        return (doc as CodeCellModel).requestSignatureHelp(doc.getOffsetAt(pos));
    }
});

monaco.languages.registerCompletionItemProvider('sql', {
    triggerCharacters: ['.'],
    provideCompletionItems: (doc, pos, context, cancelToken) => {
        return (doc as CodeCellModel).requestCompletion(doc.getOffsetAt(pos));
    }
});