import {ResultValue} from "../../../data/result";
import {StateHandler} from "./state_handler";
import {TaskInfo} from "../../../data/messages";

export type KernelSymbols = ResultValue[];
export type KernelStatus = 'busy' | 'idle' | 'dead' | 'disconnected';
export type KernelInfo = Record<string, string>;
export type KernelTasks = Record<string, TaskInfo>;

export interface KernelState {
    symbols: KernelSymbols,
    status: KernelStatus,
    info: KernelInfo,
    tasks: KernelTasks
}

export class KernelStateHandler extends StateHandler<KernelState> {
    readonly kernelInfoHandler: StateHandler<KernelInfo>;
    readonly kernelStatusHandler: StateHandler<KernelStatus>;
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
