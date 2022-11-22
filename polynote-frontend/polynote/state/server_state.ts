import {
    append,
    BaseHandler,
    IDisposable,
    NoUpdate,
    ObjectStateHandler,
    removeFromArray,
    removeKey,
    renameKey,
    replaceArrayValue,
    StateHandler,
    StateView,
    updateProperty
} from ".";
import {Identity, NotebookSearchResult} from "../data/messages";
import {SocketSession} from "../messaging/comms";
import {NotebookMessageReceiver} from "../messaging/receiver";
import {NotebookMessageDispatcher,} from "../messaging/dispatcher";
import {SparkPropertySet} from "../data/data";
import {NotebookStateHandler} from "./notebook_state";
import {SocketStateHandler} from "./socket_state";
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
    notebookTimestamps: Record<string, number>,
    connectionStatus: "connected" | "disconnected",
    interpreters: Record<string, string>,
    serverVersion: string,
    serverCommit: string,
    identity: Identity,
    sparkTemplates: SparkPropertySet[],
    notebookTemplates: string[],
    notifications: boolean,
    // ephemeral states
    currentNotebook?: string,
    openNotebooks: string[],
    serverOpenNotebooks: string[],
    searchResults: NotebookSearchResult[] // TODO: This should be an array of type SearchResult (which must be created)
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
                notebookTimestamps: {},
                connectionStatus: "disconnected",
                interpreters: {},
                serverVersion: "unknown",
                serverCommit: "unknown",
                identity: new Identity("Unknown User", null),
                sparkTemplates: [],
                notebookTemplates: [],
                notifications: false,
                currentNotebook: undefined,
                openNotebooks: [],
                serverOpenNotebooks: [],
                searchResults: []
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
                notebookTimestamps: {},
                connectionStatus: "disconnected",
                interpreters: {},
                serverVersion: "unknown",
                serverCommit: "unknown",
                identity: new Identity("Unknown User", null),
                sparkTemplates: [],
                notebookTemplates: [],
                notifications: false,
                currentNotebook: undefined,
                openNotebooks: [],
                serverOpenNotebooks: [],
                searchResults: []
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
     * Initialize a new NotebookState for a notebook.
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

            ServerStateHandler.updateState(state =>  {
                const pathIdx = state.openNotebooks.indexOf(oldPath)
                return {
                    notebooks: renameKey(oldPath, newPath),
                    notebookTimestamps: renameKey(oldPath, newPath),
                    openNotebooks: pathIdx >= 0 ? replaceArrayValue(newPath, pathIdx) : NoUpdate
                }
            })
        }
    }

    static deleteNotebook(path: string) {
        ServerStateHandler.closeNotebook(path, /*reinitialize*/ false).then(() => {
            // update the server state's notebook dictionaries
            ServerStateHandler.get.updateField("notebooks", notebooks => notebooks[path] !== undefined ? removeKey(path) : NoUpdate);
            ServerStateHandler.get.updateField("notebookTimestamps", notebooks => notebooks[path] !== undefined ? removeKey(path) : NoUpdate);
        })
    }

    static closeNotebook(path: string, reinitialize: boolean = true): Promise<void> {
        const maybeNb = ServerStateHandler.notebooks[path];
        if (maybeNb) {
            delete ServerStateHandler.notebooks[path];

            return maybeNb.handler.dispose().then(() => {
                ServerStateHandler.updateState(state => ({
                    notebooks: updateProperty(path, false),
                    openNotebooks: removeFromArray(state.openNotebooks, path)
                }))

                // reinitialize notebook
                if (reinitialize) {
                    this.getOrCreateNotebook(path)
                }
            })
        } else return Promise.resolve()
    }

    static reconnectNotebooks(onlyIfClosed: boolean) {
        Object.entries(ServerStateHandler.notebooks).forEach(([path, notebook]) => {
            if (notebook.loaded && notebook.info) {
                notebook.info.dispatcher.reconnect(onlyIfClosed)
            }
        })
    }

    static get serverOpenNotebooks(): [string, number, NotebookInfo][] {
        return ServerStateHandler.state.serverOpenNotebooks.reduce<[string, number, NotebookInfo][]>((acc, path) => {
            const info = this.notebooks[path]
            const lastSaved = ServerStateHandler.state.notebookTimestamps[path];
            if (info?.loaded) {
                return [...acc, [path, lastSaved, info]]
            } else if (info?.handler.state.kernel.status !== "disconnected") {
                return [...acc, [path, lastSaved, info]]
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

