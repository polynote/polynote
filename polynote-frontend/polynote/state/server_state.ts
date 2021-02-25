import {
    append, BaseHandler, IDisposable,
    NoUpdate,
    ObjectStateHandler,
    removeFromArray, removeIndex,
    removeKey,
    renameKey, setProperty,
    setValue, StateHandler,
    StateView,
    UpdateOf, updateProperty
} from ".";
import {Identity} from "../data/messages";
import {SocketSession} from "../messaging/comms";
import {NotebookMessageReceiver} from "../messaging/receiver";
import {NotebookMessageDispatcher,} from "../messaging/dispatcher";
import {SparkPropertySet} from "../data/data";
import {nameFromPath} from "../util/helpers";
import {NotebookStateHandler} from "./notebook_state";
import {SocketStateHandler} from "./socket_state";
import {OpenNotebooksHandler, RecentNotebooksHandler} from "./preferences";
import {Updater} from "./state_handler";

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

export class ServerStateHandler extends BaseHandler<ServerState> {
    private static notebooks: Record<string, NotebookInfo> = {};

    private constructor(parent: StateHandler<ServerState>) {
        super(parent)
    }

    private static inst: ServerStateHandler;
    static get get() {
        if (!ServerStateHandler.inst) {
            ServerStateHandler.inst = new ServerStateHandler(new ObjectStateHandler<ServerState>({
                notebooks: {},
                connectionStatus: "disconnected",
                interpreters: {},
                serverVersion: "unknown",
                serverCommit: "unknown",
                identity: new Identity("Unknown User", null),
                sparkTemplates: [],
                currentNotebook: undefined,
                openNotebooks: []
            }))
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

    static updateState(update: Updater<ServerState>, updateSource?: any) {
        return ServerStateHandler.get.update(update, updateSource)
    }

    // only for testing
    static clear() {
        if (ServerStateHandler.inst) {
            ServerStateHandler.inst.dispose()

            ServerStateHandler.inst = new ServerStateHandler(new ObjectStateHandler<ServerState>({
                notebooks: {},
                connectionStatus: "disconnected",
                interpreters: {},
                serverVersion: "unknown",
                serverCommit: "unknown",
                identity: new Identity("Unknown User", null),
                sparkTemplates: [],
                currentNotebook: undefined,
                openNotebooks: []
            }))
        }
    }

    static loadNotebook(path: string, open?: boolean): Promise<NotebookInfo> {
        let nbInfo = ServerStateHandler.getOrCreateNotebook(path)
        const loaded =  nbInfo?.info;
        if (! loaded) {
            // Note: the server will start sending notebook data on this socket automatically after it connects
            const nbSocket = SocketStateHandler.create(SocketSession.fromRelativeURL(`ws/${encodeURIComponent(path)}`)).disposeWith(nbInfo.handler);
            const receiver = new NotebookMessageReceiver(nbSocket, nbInfo.handler).disposeWith(nbInfo.handler);
            const dispatcher = new NotebookMessageDispatcher(nbSocket, nbInfo.handler).disposeWith(nbInfo.handler);
            nbInfo.info = {receiver, dispatcher};
            nbInfo.loaded = true;
            ServerStateHandler.notebooks[path] = nbInfo;
        }

        ServerStateHandler.updateState(state => ({
            notebooks: { [path]: nbInfo.loaded },
            openNotebooks: open && !state.openNotebooks.includes(path) ? append(path) : NoUpdate
        }))

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
            nbInfo.handler.updateField("path", () => newPath);
            // update our notebooks dictionary
            ServerStateHandler.notebooks[newPath] = nbInfo
            delete ServerStateHandler.notebooks[oldPath]

            // update the server state's notebook dictionary
            ServerStateHandler.get.updateField("notebooks", () => renameKey(oldPath, newPath))

            // update recent notebooks
            RecentNotebooksHandler.update(recents => {
                const prevIdx = recents.findIndex(nb => nb.path === oldPath);
                return prevIdx >= 0 ? updateProperty(prevIdx, {name: nameFromPath(newPath), path: newPath}) : NoUpdate
            })

            // update open notebooks
            OpenNotebooksHandler.update(nbs => {
                const prevIdx = nbs.indexOf(oldPath);
                return prevIdx >= 0 ? setProperty(prevIdx, newPath) : NoUpdate
            })
        }
    }

    static deleteNotebook(path: string) {
        ServerStateHandler.closeNotebook(path)

        // update our notebooks dictionary
        delete ServerStateHandler.notebooks[path]

        // update recent notebooks
        RecentNotebooksHandler.update(state => {
            const idx = state.findIndex(nb => nb.path === path);
            if (idx >= 0)
                return removeIndex(state, idx);
            return NoUpdate;
        });

        // update the server state's notebook dictionary
        ServerStateHandler.get.updateField("notebooks", notebooks => notebooks[path] !== undefined ? removeKey(path) : NoUpdate);
    }

    static closeNotebook(path: string) {
        const maybeNb = ServerStateHandler.notebooks[path];
        if (maybeNb) {

            // reset the entry for this notebook.
            delete ServerStateHandler.notebooks[path];

            maybeNb.handler.dispose().then(() => {
                ServerStateHandler.updateState(state => ({
                    notebooks: updateProperty(path, false),
                    openNotebooks: removeFromArray(state.openNotebooks, path)
                }))
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
        ServerStateHandler.updateState(() => ({currentNotebook: path}))
    }

    fork(disposeContext?: IDisposable): ServerStateHandler {
        const fork = new ServerStateHandler(this.parent.fork(disposeContext).disposeWith(this)).disposeWith(this);
        return disposeContext ? fork.disposeWith(disposeContext) : fork;
    }
}

