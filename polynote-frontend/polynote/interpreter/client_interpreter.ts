"use strict";

import {VegaInterpreter, VizInterpreter} from "./vega_interpreter";
import {ClientResult, CompileErrors, ExecutionInfo, ResultValue, RuntimeError} from "../data/result";
import {availableResultValues, NotebookStateHandler} from "../state/notebook_state";
import {NotebookMessageDispatcher} from "../messaging/dispatcher";
import {NotebookMessageReceiver} from "../messaging/receiver";
import {CellResult, CellStatusUpdate, KernelStatus, TaskInfo, TaskStatus, UpdatedTasks} from "../data/messages";
import {DataRepr, StreamingDataRepr} from "../data/value_repr";
import {DataStream} from "../messaging/datastream";
import {ServerStateHandler} from "../state/server_state";

export interface CellContext {
    id: number,
    availableValues: Record<string, any>
    resultValues: Record<string, ResultValue>
}

export interface IClientInterpreter {
    languageTitle: string;
    highlightLanguage: string;
    interpret(code: string, cellContext: CellContext): (ClientResult | CompileErrors | RuntimeError)[];
    hidden?: boolean
}

export const ClientInterpreters: Record<string, IClientInterpreter> = {
    "vega": VegaInterpreter,
    "viz": VizInterpreter
};

/**
 *  The ClientInterpreter interprets client-side cells (currently, Vega cells). It acts like the backend, receiving
 *  messages from the dispatcher and sending messages to the receiver.
 *
 *  This will hopefully make it easier to fold vega / js interpretation into the server if we decide to do that.
 *
 */
export class ClientInterpreter {

    private static instances: Record<string, ClientInterpreter> = {};
    private constructor(private notebookState: NotebookStateHandler, private receiver: NotebookMessageReceiver) {}

    static forPath(path: string): ClientInterpreter | undefined {
        let inst = ClientInterpreter.instances[path];
        if (inst === undefined) {
            const nbInfo = ServerStateHandler.getNotebook(path);
            if (nbInfo && nbInfo.info) {
                inst = new ClientInterpreter(nbInfo.handler, nbInfo.info.receiver);
                ClientInterpreter.instances[path] = inst;
            }
        }
        return inst
    }

    runCell(id: number, dispatcher: NotebookMessageDispatcher, queueAfter?: number) {
        // we want to run the cell in order, so we need to find any cells above this one that are currently running/queued
        // and wait for them to complete
        const nbState = this.notebookState.state
        const cellIdx = this.notebookState.getCellIndex(id)!
        const cell = nbState.cells[id]!;
        const taskId = `Cell ${id}`;

        // first, queue up the cell, waiting for another cell to queue if necessary
        Promise.resolve().then(() => {
            if (queueAfter !== undefined) {
                return this.notebookState.waitForCellChange(queueAfter, "queued")
            } else return Promise.resolve()
        }).then(() => {
            const promise = this.notebookState.waitForCellChange(id, "queued");
            this.receiver.inject(new KernelStatus(new CellStatusUpdate(id, TaskStatus.Queued)))
            return promise
        }).then(() => { // next, wait for any cells queued up earlier.
            let waitIdx = cellIdx;
            let waitCellId: number | undefined = undefined;
            while (waitIdx >= 0 && waitCellId === undefined) {
                waitIdx -= 1;
                const maybeWaitCell = nbState.cells[nbState.cellOrder[waitIdx]];
                if (maybeWaitCell && (maybeWaitCell.queued || maybeWaitCell.running)) {
                    waitCellId = maybeWaitCell.id;
                }
            }

            if (waitCellId !== undefined) {
                let wasRunning = false;
                return new Promise<boolean>(resolve => {
                    const disposable = this.notebookState.view("cells").view(waitCellId!).addObserver(state => {
                        if (state?.running) {
                            wasRunning = true;
                        } else if (!state?.queued) {
                            disposable.dispose();
                            if (!state?.error && wasRunning) {
                                resolve(false);
                            } else {
                                resolve(true);
                            }
                        }
                    })
                })
            } else return Promise.resolve(false)
        }).then(cancelled => { // finally, interpret the cell
            if (!cancelled) {
                const start = Date.now()
                const updateStatus = (progress: number) => {
                    if (progress < 256) {
                        this.receiver.inject(new KernelStatus(new CellStatusUpdate(id, TaskStatus.Running)))
                        this.receiver.inject(new KernelStatus(new UpdatedTasks([new TaskInfo(taskId, taskId, '', TaskStatus.Running, progress)])))
                        this.receiver.inject(new CellResult(id, new ExecutionInfo(start)))
                    } else {
                        this.receiver.inject(new KernelStatus(new CellStatusUpdate(id, TaskStatus.Complete)))
                        this.receiver.inject(new KernelStatus(new UpdatedTasks([new TaskInfo(taskId, taskId, '', TaskStatus.Complete, progress)])))
                        this.receiver.inject(new CellResult(id, new ExecutionInfo(start, Date.now())))
                    }
                }
                updateStatus(1)

                const results = ClientInterpreters[cell.language].interpret(cell.content, cellContext(this.notebookState, dispatcher, id));
                updateStatus(256)
                results.forEach(res => {
                    if (res instanceof ClientResult) {
                        dispatcher.setCellOutput(id, res);
                    } else {
                        this.receiver.inject(new CellResult(id, res))
                    }
                })
            }
        }).finally(() => {
            this.receiver.inject(new KernelStatus(new CellStatusUpdate(id, TaskStatus.Complete)))
            this.receiver.inject(new KernelStatus(new UpdatedTasks([new TaskInfo(taskId, taskId, '', TaskStatus.Complete, 255)])))
        })
    }

}

export function cellContext(notebookState: NotebookStateHandler, dispatcher: NotebookMessageDispatcher, cellId: number): CellContext {
    const resultValues = availableResultValues(notebookState.state.kernel.symbols, notebookState.state.cellOrder, cellId);
    const availableValues = availableClientValues(resultValues, notebookState, dispatcher);
    return {id: cellId, availableValues, resultValues};
}

function availableClientValues(resultValues: Record<string, ResultValue>, notebookState: NotebookStateHandler, dispatcher: NotebookMessageDispatcher): Record<string, any> {
    return Object.fromEntries(
        Object.entries(resultValues).map(
            entry => {
                const [name, result] = entry;
                let bestValue: any = result.valueText;
                const dataRepr = result.reprs.find(repr => repr instanceof DataRepr);
                if (dataRepr) {
                    bestValue = (dataRepr as DataRepr).decode();
                } else {
                    const streamingRepr = result.reprs.find(repr => repr instanceof StreamingDataRepr);
                    if (streamingRepr instanceof StreamingDataRepr) {
                        bestValue = new DataStream(dispatcher, notebookState, streamingRepr);
                    }
                }
                return [name, bestValue];
            }
        )
    );
}

