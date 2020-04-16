import {StateHandler} from "./state_handler";
import {ServerErrorWithCause} from "../../../data/result";
import {NotebookState} from "./notebook_state";
import {SparkPropertySet} from "../../../data/data";
import {Identity} from "../../../data/messages";
import {diffArray} from "../../../util/functions";

export interface ServerState {
    errors: { code: number, err: ServerErrorWithCause }[],
    notebooks: Record<string, NotebookState>,
    connectionStatus: "connected" | "disconnected",
    interpreters: Record<string, string>,
    serverVersion: string,
    serverCommit: string,
    identity?: Identity,
    sparkTemplates: SparkPropertySet[]
}

class NotebookStatesHandler extends StateHandler<Record<string, NotebookState>> {
    constructor(state: Record<string, NotebookState>) {
        super(state);
    }
}

export class ServerStateHandler extends StateHandler<ServerState> {
    readonly currentNotebooks: StateHandler<Record<string, NotebookState>>;
    constructor(state: ServerState) {
        super(state);

        // TODO: what to do when new notebooks are loaded...
        this.currentNotebooks = this.view("notebooks", NotebookStatesHandler);
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
}
