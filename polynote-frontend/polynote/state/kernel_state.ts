import {ResultValue} from "../data/result";
import {StateHandler} from "./state_handler";
import {KernelStatusString, TaskInfo} from "../data/messages";

// TODO: KernelSymbols needs to keep track of the cell source of each symbol doesn't it?
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
    readonly kernelInfoHandler: StateHandler<KernelInfo>;
    readonly kernelStatusHandler: StateHandler<KernelStatusString>;
    readonly kernelSymbolsHandler: StateHandler<KernelSymbols>;
    readonly kernelTasksHandler: StateHandler<KernelTasks>;

    constructor(state: KernelState) {
        super(state);
        this.kernelInfoHandler = this.view("info");
        this.kernelStatusHandler = this.view("status");
        this.kernelSymbolsHandler = this.view("symbols");
        this.kernelTasksHandler = this.view("tasks");
    }
}
