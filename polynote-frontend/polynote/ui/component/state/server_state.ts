import {StateHandler} from "./state_handler";
import {ServerErrorWithCause} from "../../../data/result";
import {Identity, KernelBusyState} from "../../../data/messages";
import {NotebookState, NotebookStateHandler} from "./notebook_state";
import {SocketSession} from "../messaging/comms";
import {NotebookMessageReceiver} from "../messaging/receiver";
import {NotebookMessageDispatcher} from "../messaging/dispatcher";
import {SocketStateHandler} from "./socket_state";
import {NotebookConfig, SparkPropertySet} from "../../../data/data";
import {removeKey} from "../../../util/functions";
import {EditBuffer} from "./edit_buffer";
import {ClientInterpreterComponent} from "../component/interpreter/client_interpreter";

export type NotebookInfo = {
    handler: NotebookStateHandler,
    loaded: boolean,
    info?: {
        receiver: NotebookMessageReceiver,
        dispatcher: NotebookMessageDispatcher
    }
};

export interface ServerState {
    errors: { code: number, err: ServerErrorWithCause }[],
    // Keys are notebook path. Values denote whether the notebook has ever been loaded in this session.
    notebooks: Record<string, NotebookInfo["loaded"]>,
    connectionStatus: "connected" | "disconnected",
    interpreters: Record<string, string>,
    serverVersion: string,
    serverCommit: string,
    identity?: Identity,
    sparkTemplates: SparkPropertySet[]
    // ephemeral states
    currentNotebook?: string
}

export class ServerStateHandler extends StateHandler<ServerState> {
    private static notebooks: Record<string, NotebookInfo> = {};

    private constructor(state: ServerState) {
        super(state);
    }

    private static inst: ServerStateHandler;
    static get get() {
        if (!ServerStateHandler.inst) {
            ServerStateHandler.inst = new ServerStateHandler({
                errors: [],
                notebooks: {},
                connectionStatus: "disconnected",
                interpreters: {},
                serverVersion: "unknown",
                serverCommit: "unknown",
                sparkTemplates: [],
                currentNotebook: undefined
            })
            console.log("created ServerStateHandler")
        }
        return ServerStateHandler.inst;
    }

    // only for testing
    static clear() {
        if (ServerStateHandler.inst) {
            ServerStateHandler.inst.clearObservers();
            ServerStateHandler.inst.dispose()

            ServerStateHandler.inst = new ServerStateHandler({
                errors: [],
                notebooks: {},
                connectionStatus: "disconnected",
                interpreters: {},
                serverVersion: "unknown",
                serverCommit: "unknown",
                sparkTemplates: [],
                currentNotebook: undefined
            })
        }
    }

    static loadNotebook(path: string): NotebookInfo {
        const nbInfo = ServerStateHandler.getOrCreateNotebook(path)
        const loaded =  nbInfo?.info;
        if (loaded) {
            return nbInfo
        } else {
            // Note: the server will start sending notebook data on this socket automatically after it connects
            const nbSocket = new SocketStateHandler(SocketSession.fromRelativeURL(`ws/${encodeURIComponent(path)}`));
            const receiver = new NotebookMessageReceiver(nbSocket, nbInfo.handler);
            const dispatcher = new NotebookMessageDispatcher(nbSocket, nbInfo.handler, new ClientInterpreterComponent(nbInfo.handler, receiver));
            nbInfo.info = {receiver, dispatcher};
            nbInfo.loaded = true;
            ServerStateHandler.notebooks[path] = nbInfo;
            return nbInfo
        }
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
                handler: new NotebookStateHandler({
                    path,
                    cells: [],
                    config: NotebookConfig.default,
                    errors: [],
                    kernel: {
                        symbols: [],
                        status: 'disconnected',
                        info: {},
                        tasks: {},
                    },
                    globalVersion: -1,
                    localVersion: -1,
                    editBuffer: new EditBuffer(),
                    activePresence: {},
                    activeStreams: {}
                }),
                loaded: false,
                info: undefined,
            }

            ServerStateHandler.notebooks[path] = nbInfo;
            return nbInfo
        }
    }

    static renameNotebook(oldPath: string, newPath: string) {
        const nbInfo = ServerStateHandler.notebooks[oldPath]
        if (nbInfo) {
            // update the path in the notebook's handler
            nbInfo.handler.updateState(nbState => {
                return {
                    ...nbState,
                    path: newPath
                }
            })
            // update our notebooks dictionary
            ServerStateHandler.notebooks[newPath] = nbInfo
            delete ServerStateHandler.notebooks[oldPath]

            // update the server state's notebook dictionary
            ServerStateHandler.get.updateState(s => {
                const prev = s.notebooks[oldPath]
                return {
                    ...s,
                    notebooks: {
                        ...removeKey(s.notebooks, oldPath),
                        [newPath]: prev
                    }
                }
            })
        }
    }

    static deleteNotebook(path: string) {
        // update our notebooks dictionary
        delete ServerStateHandler.notebooks[path]

        // update the server state's notebook dictionary
        ServerStateHandler.get.updateState(s => {
            return {
                ...s,
                notebooks: {
                    ...removeKey(s.notebooks, path),
                }
            }
        })
    }

    static get runningNotebooks(): [string, NotebookInfo][] {
        return Object.entries(ServerStateHandler.notebooks).reduce<[string, NotebookInfo][]>((acc, [path, info]) => {
            if (info.loaded) {
                return [...acc, [path, info]]
            } else return acc
        }, [])
    }
}

