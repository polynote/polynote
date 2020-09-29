import {ResultValue} from "../data/result";
import {StateView} from "./state_handler";
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

export class KernelStateView extends StateView<KernelState> {
    readonly kernelInfo: StateView<KernelInfo>;
    readonly kernelStatus: StateView<KernelStatusString>;
    readonly kernelSymbols: StateView<KernelSymbols>;
    readonly kernelTasks: StateView<KernelTasks>;

    constructor(state: KernelState) {
        super(state);
        this.kernelInfo = this.view("info");
        this.kernelStatus = this.view("status");
        this.kernelSymbols = this.view("symbols");
        this.kernelTasks = this.view("tasks");
    }
}
