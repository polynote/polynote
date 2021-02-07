import {
    append,
    NotebookStateHandler,
    NoUpdate,
    ObjectStateHandler,
    OpenNotebooksHandler,
    RecentNotebooksHandler,
    removeFromArray,
    removeKey,
    renameKey,
    setValue,
    SocketStateHandler,
    StateView,
    UpdateOf
} from ".";
import {Identity} from "../data/messages";
import {SocketSession} from "../messaging/comms";
import {NotebookMessageReceiver} from "../messaging/receiver";
import {NotebookMessageDispatcher,} from "../messaging/dispatcher";
import {SparkPropertySet} from "../data/data";
import {nameFromPath} from "../util/helpers";

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

export class ServerStateHandler extends ObjectStateHandler<ServerState> {
    private static notebooks: Record<string, NotebookInfo> = {};

    private constructor(state: ServerState) {
        super(state)
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

    static updateState(update: UpdateOf<ServerState>, updateSource?: any) {
        return ServerStateHandler.get.update(update, updateSource)
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

        const openNotebooks = ServerStateHandler.state.openNotebooks;
        ServerStateHandler.updateState({
            notebooks: { [path]: nbInfo.loaded },
            openNotebooks: open && !openNotebooks.includes(path) ? append(path) : NoUpdate
        })

        return new Promise(resolve => {
            const checkIfLoaded = () => {
                const maybeLoaded = ServerStateHandler.getOrCreateNotebook(path)
                if (maybeLoaded.loaded && maybeLoaded.info) {
                    loading.dispose();
                    resolve(maybeLoaded)
                }
            }
            const loading = nbInfo.handler.addObserver(checkIfLoaded).disposeWith(nbInfo.handler)
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
            nbInfo.handler.updateField("path", newPath);
            // update our notebooks dictionary
            ServerStateHandler.notebooks[newPath] = nbInfo
            delete ServerStateHandler.notebooks[oldPath]

            // update the server state's notebook dictionary
            ServerStateHandler.get.update({
                notebooks: renameKey(oldPath, newPath)
            })

            // update recent notebooks
            let prevIdx = RecentNotebooksHandler.state.findIndex(nb => nb.path === oldPath);
            if (prevIdx >= 0) {
                RecentNotebooksHandler.updateField(prevIdx, {name: nameFromPath(newPath), path: newPath})
            }

            // update open notebooks
            prevIdx = OpenNotebooksHandler.state.findIndex(nb => nb === oldPath);
            if (prevIdx >= 0) {
                OpenNotebooksHandler.updateField(prevIdx, setValue(newPath));
            }
        }
    }

    static deleteNotebook(path: string) {
        ServerStateHandler.closeNotebook(path)

        // update our notebooks dictionary
        delete ServerStateHandler.notebooks[path]

        // update recent notebooks
        RecentNotebooksHandler.update(removeFromArray(RecentNotebooksHandler.state, {name: nameFromPath(path), path: path}, (a, b) => a.path === b.path));

        // update the server state's notebook dictionary
        ServerStateHandler.get.update({
            notebooks: removeKey(path)
        })
    }

    static closeNotebook(path: string) {
        const maybeNb = ServerStateHandler.notebooks[path];
        if (maybeNb) {
            maybeNb.handler.dispose()

            // reset the entry for this notebook.
            delete ServerStateHandler.notebooks[path]
            ServerStateHandler.getOrCreateNotebook(path)

            ServerStateHandler.updateState({
                notebooks: removeKey(path),
                openNotebooks: removeFromArray(ServerStateHandler.state.openNotebooks, path)
            })
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
        ServerStateHandler.updateState({currentNotebook: path})
    }

    update(updates: UpdateOf<ServerState>, updateSource?: any, updatePath?: string) {
        super.update(updates, updateSource, updatePath);
    }
}

