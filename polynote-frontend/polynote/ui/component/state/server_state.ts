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
        // FIXME: this doesn't seem to do anything..
        // this.addObserver(notebooks => {
        //     const currentStates = this.getState();
        //     const currentKeys = Object.keys(currentStates);
        //     const newKeys = Object.keys(notebooks);
        //
        //     const [deletedKeys, addedKeys] = diffArray(currentKeys, newKeys);
        //     deletedKeys.forEach(key => {
        //         // TODO: do we need to do any cleanup?
        //         delete currentStates[key];
        //     });
        //     addedKeys.forEach(key => {
        //         this.updateState(s => {
        //             s[key] = notebooks[key];
        //             return s
        //         });
        //     })
        // })
    }
}

export class ServerStateHandler extends StateHandler<ServerState> {
    readonly currentNotebooks: StateHandler<Record<string, NotebookState>>;
    constructor(state: ServerState) {
        super(state);

        // TODO: what to do when new notebooks are loaded...
        this.currentNotebooks = this.view("notebooks", NotebookStatesHandler);
    }
}
