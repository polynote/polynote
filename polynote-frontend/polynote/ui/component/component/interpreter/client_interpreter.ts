"use strict";

import { VegaInterpreter } from "./vega_interpreter";
import {ClientResult, ExecutionInfo, Result, ResultValue, RuntimeError} from "../../../../data/result";
import {NotebookStateHandler} from "../../state/notebook_state";
import {NotebookMessageDispatcher, SetCellOutput} from "../../messaging/dispatcher";
import {NotebookMessageReceiver} from "../../messaging/receiver";
import {CellResult, KernelStatus, TaskInfo, TaskStatus, UpdatedTasks} from "../../../../data/messages";
import {DataRepr, StreamingDataRepr} from "../../../../data/value_repr";
import {DataStream} from "../../messaging/datastream";

export interface CellContext {
    id: number,
    availableValues: Record<string, any>
}

export interface ClientInterpreter {
    languageTitle: string;
    highlightLanguage: string;
    interpret(code: string, cellContext: CellContext): (ClientResult | RuntimeError)[]
}

export const ClientInterpreters: Record<string, ClientInterpreter> = {
    "vega": VegaInterpreter
};

/**
 *  The ClientInterpreter interprets client-side cells (currently, Vega cells). It acts like the backend, receiving
 *  messages from the dispatcher and sending messages to the receiver.
 *
 *  This will hopefully make it easier to fold vega / js interpretation into the server if we decide to do that.
 *
 */
export class ClientInterpreterComponent {

    constructor(private notebookState: NotebookStateHandler, private receiver: NotebookMessageReceiver) {}

    runCell(id: number, dispatcher: NotebookMessageDispatcher) {
        // we want to run the cell in order, so we need to find any cells above this one that are currently running/queued
        // and wait for them to complete
        const nbState = this.notebookState.getState()
        const cellIdx = nbState.cells.findIndex(cell => cell.id === id)
        const cell = nbState.cells[cellIdx]!;

        // first, wait for this cell to be queued
        new Promise(resolve => {
            const obs = this.notebookState.addObserver(state => {
                const maybeQueued = state.cells[cellIdx]
                if (maybeQueued && maybeQueued.queued) {
                    this.notebookState.removeObserver(obs)
                    resolve()
                }
            })
        }).then(() => { // next, wait for any cells queued up earlier.
            let waitIdx = cellIdx;
            let waitCellId: number | undefined = undefined;
            while (waitIdx >= 0 && waitCellId === undefined) {
                waitIdx -= 1;
                const maybeWaitCell = this.notebookState.getState().cells[waitIdx]
                if (maybeWaitCell && (maybeWaitCell.queued || maybeWaitCell.running)) {
                    waitCellId = maybeWaitCell.id;
                }
            }

            if (waitCellId) {
                return new Promise(resolve => {
                    const obs = this.notebookState.addObserver(state => {
                        const maybeCellReady = state.cells.find(c => c.id === waitCellId)
                        if (maybeCellReady && !maybeCellReady.running && !maybeCellReady.queued) {
                            this.notebookState.removeObserver(obs)
                            resolve()
                        }
                    })
                })
            } else return Promise.resolve()
        }).then(() => { // finally, interpret the cell
            const taskId = `Cell ${id}`
            const start = Date.now()
            const updateStatus = (progress: number) => {
                if (progress < 256) {
                    this.receiver.inject(new KernelStatus(new UpdatedTasks([new TaskInfo(taskId, taskId, '', TaskStatus.Running, progress)])))
                    this.receiver.inject(new CellResult(id, new ExecutionInfo(start)))
                } else {
                    this.receiver.inject(new KernelStatus(new UpdatedTasks([new TaskInfo(taskId, taskId, '', TaskStatus.Complete, progress)])))
                    this.receiver.inject(new CellResult(id, new ExecutionInfo(start, Date.now())))
                }
            }
            updateStatus(1)

            const currentState = this.notebookState.getState();
            const availableValues = currentState.cells.slice(0, cellIdx).reduce<Record<string, any>>((acc, next) => {
                next.results
                    .filter(res => res instanceof ResultValue) // for now, ClientResults can't be used in other cells
                    .forEach((result: ResultValue) => {
                        let bestValue: any = result.valueText;
                        const dataRepr = result.reprs.find(repr => repr instanceof DataRepr);
                        if (dataRepr) {
                            bestValue = (dataRepr as DataRepr).decode();
                        } else {
                            const streamingRepr = result.reprs.find(repr => repr instanceof StreamingDataRepr);
                            if (streamingRepr instanceof StreamingDataRepr) {
                                bestValue = new DataStream(dispatcher, this.notebookState, streamingRepr);
                            }
                        }
                        acc[result.name] = bestValue;
                    })
                return acc
            }, {})
            const results = ClientInterpreters[cell.language].interpret(cell.content, {id, availableValues})
            updateStatus(256)
            results.forEach(res => {
                if (res instanceof RuntimeError) {
                    this.receiver.inject(new CellResult(id, res))
                } else {
                    dispatcher.dispatch(new SetCellOutput(id, res))
                }
            })
        })
    }

}