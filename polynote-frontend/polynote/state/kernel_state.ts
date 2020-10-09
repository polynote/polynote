import {ResultValue} from "../data/result";
import {StateHandler, StateView} from "./state_handler";
import {KernelStatusString, TaskInfo} from "../data/messages";
import {NotebookStateHandler} from "./notebook_state";

export type KernelSymbols = (ResultValue)[];
export type KernelInfo = Record<string, string>;
export type KernelTasks = Record<string, TaskInfo>; // taskId -> TaskInfo

export interface KernelState {
    symbols: KernelSymbols,
    status: KernelStatusString,
    info: KernelInfo,
    tasks: KernelTasks
}

export class KernelStateHandler extends StateHandler<KernelState> {
    readonly kernelInfo: StateHandler<KernelInfo>;
    readonly kernelStatus: StateHandler<KernelStatusString>;
    readonly kernelSymbols: StateHandler<KernelSymbols>;
    readonly kernelTasks: StateHandler<KernelTasks>;

    constructor(state: KernelState) {
        super(state);
        this.kernelInfo = this.lens("info");
        this.kernelStatus = this.lens("status");
        this.kernelSymbols = this.lens("symbols");
        this.kernelTasks = this.lens("tasks");
    }
}
