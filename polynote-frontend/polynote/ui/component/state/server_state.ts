import {StateHandler} from "./state_handler";
import {ServerErrorWithCause} from "../../../data/result";
import {NotebookState, NotebookStateHandler} from "./notebook_state";
import {NotebookConfig, SparkPropertySet} from "../../../data/data";
import {Identity} from "../../../data/messages";
import {EditBuffer} from "../../../data/edit_buffer";
import {SocketSession} from "../../../comms";
import {NotebookMessageReceiver} from "../messaging/receiver";
import {NotebookMessageDispatcher} from "../messaging/dispatcher";

export type NotebookInfo = {
    state: NotebookState,
    info?: {
        handler: NotebookStateHandler,
        receiver: NotebookMessageReceiver,
        dispatcher: NotebookMessageDispatcher
    }
};

export interface ServerState {
    errors: { code: number, err: ServerErrorWithCause }[],
    notebooks: Record<string, NotebookInfo>,
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
                sparkTemplates: []
            })
        }
        return ServerStateHandler.inst;
    }

    /**
     * Initialize a new NotebookState and create a NotebookMessageReceiver for that notebook.
     *
     * @param path
     * @param doLoad            Whether to actually open a socket and load the notebook. False by default.
     * @return NotebookState
     */
    static newNotebookState(path: string, doLoad: boolean = false): NotebookInfo {
        const state: NotebookState = {
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
            activePresence: {}
        };

        // TODO: how will anything get the nbHandler? where will new views, etc. be created?

        // Note: the server will start sending notebook data on this socket automatically after it connects
        let info: NotebookInfo["info"] = undefined;
        if (doLoad) {
            const nbSocket = SocketSession.fromRelativeURL(`ws/${encodeURIComponent(path)}`);
            const handler = new NotebookStateHandler(state);
            const receiver =  new NotebookMessageReceiver(nbSocket, handler);
            const dispatcher =  new NotebookMessageDispatcher(nbSocket, handler);
            info = {handler, receiver, dispatcher};
        }

        return {state, info}
    }
}
