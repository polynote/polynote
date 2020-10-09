import {ResultValue} from "../data/result";
import {KernelStatusString, TaskInfo} from "../data/messages";

export type KernelSymbols = (ResultValue)[];
export type KernelInfo = Record<string, string>;
export type KernelTasks = Record<string, TaskInfo>; // taskId -> TaskInfo

export interface KernelState {
    symbols: KernelSymbols,
    status: KernelStatusString,
    info: KernelInfo,
    tasks: KernelTasks
}
