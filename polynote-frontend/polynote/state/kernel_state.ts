import {ResultValue} from "../data/result";
import {KernelStatusString, TaskInfo} from "../data/messages";

// id -> (name -> ResultValue)
export type KernelSymbols = Record<string, Record<string, ResultValue>>;
export type KernelInfo = Record<string, string>;
export type KernelTasks = Record<string, TaskInfo>; // taskId -> TaskInfo

export interface KernelState {
    symbols: KernelSymbols,
    status: KernelStatusString,
    info: KernelInfo,
    tasks: KernelTasks
}
