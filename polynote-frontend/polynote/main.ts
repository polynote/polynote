import {div, TagElement} from "./ui/tags";
import {MarkdownIt} from "./ui/input/markdown-it";
import {scala, vega} from "./ui/input/monaco/languages";
import * as monaco from "monaco-editor";
import {themes} from "./ui/input/monaco/themes";
import {SocketSession} from "./messaging/comms";
import {ServerMessageReceiver} from "./messaging/receiver";
import {ServerMessageDispatcher} from "./messaging/dispatcher";
import {Toolbar} from "./ui/component/toolbar";
import {SplitView} from "./ui/layout/splitview";
import {InsertValue, moveArrayValue, NoUpdate, removeIndex, RemoveValue, RenameKey, setValue,} from "./state";
import {Tabs} from "./ui/component/tabs";
import {KernelPane} from "./ui/component/notebook/kernel";
import {NotebookList} from "./ui/component/notebooklist";
import {Home} from "./ui/component/home";
import {CodeCellModel} from "./ui/component/notebook/cell";
import {collect, nameFromPath} from "./util/helpers";
import {SocketStateHandler} from "./state/socket_state";
import {ServerStateHandler} from "./state/server_state";
import {OpenNotebooksHandler, RecentNotebooks, RecentNotebooksHandler} from "./state/preferences";
import {ThemeHandler} from "./state/theme";

/**
 * Main is the entry point to the entire UI. It initializes the state, starts the websocket connection, and contains the
 * other components.
 */
export class Main {
    public el: TagElement<"div">;
    private receiver: ServerMessageReceiver;

    readonly splitView: SplitView;

    private constructor(socket: SocketStateHandler) {

        this.receiver = new ServerMessageReceiver();
        const dispatcher = new ServerMessageDispatcher(socket);

        // handle reconnecting
        const reconnectOnWindowFocus = () => {
            console.warn("Window was focused! Attempting to reconnect.")
            dispatcher.reconnect(true)
        }
        ServerStateHandler.get.view("connectionStatus").addObserver(status => {
            if (status === "disconnected") {
                window.addEventListener("focus", reconnectOnWindowFocus)
            } else {
                window.removeEventListener("focus", reconnectOnWindowFocus)
            }
        }).disposeWith(this.receiver)

        const nbList = new NotebookList(dispatcher)
        const leftPane = { header: nbList.header, el: nbList.el };
        const home = new Home()
        const tabs = new Tabs(dispatcher, home.el);
        const center = tabs.el;
        const kernelPane = new KernelPane(dispatcher)
        const rightPane = { header: kernelPane.header, el: kernelPane.el};

        this.el = div(['main-ui'], [
            div(['header'], [new Toolbar(dispatcher).el]),
            div(['body'], [this.splitView = new SplitView(leftPane, center, rightPane)]),
            div(['footer'], []) // no footer yet!
        ]);

        ServerStateHandler.get.view("currentNotebook").addObserver(path => {
            Main.handlePath(path)
        }).disposeWith(this.receiver)

        const path = decodeURIComponent(window.location.pathname.replace(new URL(document.baseURI).pathname, ''));
        Promise.allSettled(OpenNotebooksHandler.state.map(path => {
            return ServerStateHandler.loadNotebook(path, true)
        })).then(() => {
            const notebookBase = 'notebook/';
            if (path.startsWith(notebookBase)) {
                const nbPath = path.substring(notebookBase.length)
                ServerStateHandler.loadNotebook(nbPath, true).then(() => {
                    ServerStateHandler.selectNotebook(nbPath)
                })
            }
        })

        ServerStateHandler.get.observeKey("openNotebooks", (nbs, upd) => {
            // update open notebooks preference
            OpenNotebooksHandler.update(() => setValue([...nbs]))

            // add newly opened notebooks to recent notebooks
            if (upd.addedValues && upd.update instanceof InsertValue) {
                const addedValues = Object.values(upd.addedValues);
                RecentNotebooksHandler.update(recents => {
                    const newNotebooks = collect(addedValues, path => {
                        if (recents.find(nb => nb.path === path) === undefined) {
                            return {path, name: nameFromPath(path)}
                        } else return undefined
                    })

                    if (newNotebooks.length > 0) {
                        return recents.concat(newNotebooks)
                    } else return NoUpdate
                })
            }
        })

        ServerStateHandler.get.observeKey("notebooks", (nbs, upd) => {
            if (upd.addedValues && upd.removedValues && upd.update instanceof RenameKey) { // check for a rename
                const oldPath = Object.keys(upd.removedValues)[0];
                const newPath = Object.keys(upd.addedValues)[0];
                RecentNotebooksHandler.update(recents => {
                    return collect(recents as RecentNotebooks, nb => nb.path === oldPath ? {path: newPath, name: nameFromPath(newPath)} : nb)
                })
            } else if (upd.removedValues && upd.update instanceof RemoveValue) { // check for removed notebook
                const removed = Object.keys(upd.removedValues)[0];
                RecentNotebooksHandler.update(recents => {
                    const idx = recents.findIndex(nb => nb.path === removed);
                        if (idx >= 0) {
                            return removeIndex(recents, idx);
                        } else return NoUpdate;
                })
            }
        })

    }

    private static handlePath(path?: string) {
        if (path && path !== "home") {
            const tabUrl = new URL(`notebook/${encodeURIComponent(path)}`, document.baseURI);

            const href = window.location.href;
            const hash = window.location.hash;
            const title = `${nameFromPath(path)} | Polynote`;
            document.title = title; // looks like chrome ignores history title so we need to be explicit here.

            if (hash && window.location.href === (tabUrl.href + hash)) {
                window.history.pushState({notebook: path}, title, href);
            } else {
                window.history.pushState({notebook: path}, title, tabUrl.href);
            }

            RecentNotebooksHandler.update(recents => {
                // update recent notebooks order
                const currentIndex = recents.findIndex(r => r && r.path === path);
                if (currentIndex >= 0) {
                    return moveArrayValue(currentIndex, 0);
                } else return NoUpdate
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
    provideCompletionItems: (doc, pos, context, cancelToken) => {
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

// remeasure custom fonts after loading to avoid changing whitespace size when selected, see also microsoft/monaco-editor#648
document.fonts.ready.then(() => monaco.editor.remeasureFonts())
