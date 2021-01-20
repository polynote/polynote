import {NoUpdate, StateHandler, StateView} from "./state_handler";
import {Identity} from "../data/messages";
import {NotebookStateHandler} from "./notebook_state";
import {SocketSession} from "../messaging/comms";
import {NotebookMessageReceiver} from "../messaging/receiver";
import {
    NotebookMessageDispatcher,
} from "../messaging/dispatcher";
import {SocketStateHandler} from "./socket_state";
import {NotebookConfig, SparkPropertySet} from "../data/data";
import {arrDeleteFirstItem, arrReplace, nameFromPath, removeKeys} from "../util/helpers";
import {OpenNotebooksHandler, RecentNotebooksHandler} from "./preferences";

export type NotebookInfo = {
    handler: NotebookStateHandler,
    loaded: boolean,
    info?: {
        receiver: NotebookMessageReceiver,
        dispatcher: NotebookMessageDispatcher
    }
};

export interface ServerState {
    // Keys are notebook path. Values denote whether the notebook has ever been loaded in this session.
    notebooks: Record<string, NotebookInfo["loaded"]>,
    connectionStatus: "connected" | "disconnected",
    interpreters: Record<string, string>,
    serverVersion: string,
    serverCommit: string,
    identity: Identity,
    sparkTemplates: SparkPropertySet[]
    // ephemeral states
    currentNotebook?: string,
    openNotebooks: string[]
}

export class ServerStateHandler extends StateHandler<ServerState> {
    private static notebooks: Record<string, NotebookInfo> = {};

    private constructor(state: ServerState) {
        const view = new StateView(state)
        super(view);
    }

    private static inst: ServerStateHandler;
    static get get() {
        if (!ServerStateHandler.inst) {
            ServerStateHandler.inst = new ServerStateHandler({
                notebooks: {},
                connectionStatus: "disconnected",
                interpreters: {},
                serverVersion: "unknown",
                serverCommit: "unknown",
                identity: new Identity("Unknown User", null),
                sparkTemplates: [],
                currentNotebook: undefined,
                openNotebooks: []
            })
        }
        return ServerStateHandler.inst;
    }

    /**
     * Create a temporary view into the ServerState.
     *
     * @param key
     * @param disposeWhen
     */
    static view<T extends keyof ServerState>(key: T): StateView<ServerState[T]> {
        return ServerStateHandler.get.view(key)
    }

    /**
     * Convenience method to get the state.
     */
    static get state(): ServerState {
        return ServerStateHandler.get.state;
    }

    static updateState(f: (s: ServerState) => (typeof NoUpdate | ServerState), updateSource?: any) {
        return ServerStateHandler.get.update(f, updateSource)
    }

    // only for testing
    static clear() {
        if (ServerStateHandler.inst) {
            ServerStateHandler.inst.dispose()

            ServerStateHandler.inst = new ServerStateHandler({
                notebooks: {},
                connectionStatus: "disconnected",
                interpreters: {},
                serverVersion: "unknown",
                serverCommit: "unknown",
                identity: new Identity("Unknown User", null),
                sparkTemplates: [],
                currentNotebook: undefined,
                openNotebooks: []
            })
        }
    }

    static loadNotebook(path: string, open?: boolean): Promise<NotebookInfo> {
        let nbInfo = ServerStateHandler.getOrCreateNotebook(path)
        const loaded =  nbInfo?.info;
        if (! loaded) {
            // Note: the server will start sending notebook data on this socket automatically after it connects
            const nbSocket = new SocketStateHandler(SocketSession.fromRelativeURL(`ws/${encodeURIComponent(path)}`));
            const receiver = new NotebookMessageReceiver(nbSocket, nbInfo.handler);
            const dispatcher = new NotebookMessageDispatcher(nbSocket, nbInfo.handler)
            nbInfo.info = {receiver, dispatcher};
            nbInfo.loaded = true;
            ServerStateHandler.notebooks[path] = nbInfo;
        }

        ServerStateHandler.updateState(s => ({
            ...s,
            notebooks: {...s.notebooks, [path]: nbInfo.loaded},
            openNotebooks: open && !s.openNotebooks.includes(path) ? [...s.openNotebooks, path] : s.openNotebooks
        }))

        return new Promise(resolve => {
            const checkIfLoaded = () => {
                const maybeLoaded = ServerStateHandler.getOrCreateNotebook(path)
                if (maybeLoaded.loaded && maybeLoaded.info) {
                    nbInfo.handler.removeObserver(loading);
                    resolve(maybeLoaded)
                }
            }
            const loading = nbInfo.handler.addObserver(checkIfLoaded, nbInfo.handler)
            checkIfLoaded()
        })
    }

    static getNotebook(path: string): NotebookInfo | undefined {
        return ServerStateHandler.notebooks[path]
    }

    /**
     * Initialize a new NotebookState and create a NotebookMessageReceiver for that notebook.
     */
    static getOrCreateNotebook(path: string): NotebookInfo {
        const maybeExists = ServerStateHandler.notebooks[path]
        if (maybeExists) {
            return maybeExists
        } else {
            const nbInfo = {
                handler: NotebookStateHandler.forPath(path),
                loaded: false,
                info: undefined,
            }

            ServerStateHandler.notebooks[path] = nbInfo;
            return nbInfo
        }
    }

    // TODO: should all this rename stuff be handled by state changes somewhere?
    static renameNotebook(oldPath: string, newPath: string) {
        const nbInfo = ServerStateHandler.notebooks[oldPath]
        if (nbInfo) {
            // update the path in the notebook's handler
            nbInfo.handler.update(nbState => {
                return {
                    ...nbState,
                    path: newPath
                }
            })
            // update our notebooks dictionary
            ServerStateHandler.notebooks[newPath] = nbInfo
            delete ServerStateHandler.notebooks[oldPath]

            // update the server state's notebook dictionary
            ServerStateHandler.get.update(s => {
                const prev = s.notebooks[oldPath]
                return {
                    ...s,
                    notebooks: {
                        ...removeKeys(s.notebooks, oldPath),
                        [newPath]: prev
                    }
                }
            })

            // update recent notebooks
            RecentNotebooksHandler.update(nbs => {
                const prevIdx = nbs.findIndex(nb => nb.path === oldPath)
                if (prevIdx > -1) {
                    return arrReplace(nbs, prevIdx, {name: nameFromPath(newPath), path: newPath})
                } else return nbs // not a recent notebook
            })

            // update open notebooks
            OpenNotebooksHandler.update(nbs => {
                const prevIdx = nbs.findIndex(nb => nb === oldPath)
                if (prevIdx > -1) {
                    return arrReplace(nbs, prevIdx, newPath)
                } else return nbs
            })
        }
    }

    static deleteNotebook(path: string) {
        ServerStateHandler.closeNotebook(path)

        // update our notebooks dictionary
        delete ServerStateHandler.notebooks[path]

        // update recent notebooks
        RecentNotebooksHandler.update(nbs => arrDeleteFirstItem(nbs, {name: nameFromPath(path), path: path}))

        // update the server state's notebook dictionary
        ServerStateHandler.get.update(s => {
            return {
                ...s,
                notebooks: {
                    ...removeKeys(s.notebooks, path),
                }
            }
        })
    }

    static closeNotebook(path: string) {
        const maybeNb = ServerStateHandler.notebooks[path];
        if (maybeNb) {
            maybeNb.handler.dispose()

            // reset the entry for this notebook.
            delete ServerStateHandler.notebooks[path]
            ServerStateHandler.getOrCreateNotebook(path)

            ServerStateHandler.updateState(s => ({
                ...s,
                notebooks: {
                    ...s.notebooks,
                    [path]: false
                },
                openNotebooks: arrDeleteFirstItem(s.openNotebooks, path)
            }))
        }
    }

    static reconnectNotebooks(onlyIfClosed: boolean) {
        Object.entries(ServerStateHandler.notebooks).forEach(([path, notebook]) => {
            if (notebook.loaded && notebook.info) {
                notebook.info.dispatcher.reconnect(onlyIfClosed)
            }
        })
    }

    static get openNotebooks(): [string, NotebookInfo][] {
        return Object.entries(ServerStateHandler.notebooks).reduce<[string, NotebookInfo][]>((acc, [path, info]) => {
            if (info.loaded) {
                return [...acc, [path, info]]
            } else if (info.handler.state.kernel.status !== "disconnected") {
                return [...acc, [path, info]]
            } else return acc
        }, [])
    }

    static selectNotebook(path: string) {
        ServerStateHandler.updateState(s => ({...s, currentNotebook: path}))
    }
}

