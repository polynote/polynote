import {Disposable, NoUpdate, StateHandler, StateView} from "./state_handler";
import {ServerErrorWithCause} from "../data/result";
import {arrDelete, arrDeleteFirstItem, deepEquals} from "../util/helpers";

export type DisplayError = {err: ServerErrorWithCause, id: string}

export interface ErrorState {
    serverErrors: DisplayError[],
    [path: string]: DisplayError[]
}
export class ErrorStateHandler extends StateHandler<ErrorState> {
    private static inst: ErrorStateHandler;
    static get get() {
        if (!ErrorStateHandler.inst) {
            ErrorStateHandler.inst = new ErrorStateHandler({
                serverErrors: []
            })
        }
        return ErrorStateHandler.inst
    }

    static addServerError(err: ServerErrorWithCause) {
        ErrorStateHandler.get.update(s => {
            if (s.serverErrors.find(e => deepEquals(e.err, err)) === undefined) {
                return {
                    ...s,
                    serverErrors: [...s.serverErrors, {id: `ServerError: ${err.className}`, err}]
                }
            } else return NoUpdate
        })
    }

    static addKernelError(path: string, err: ServerErrorWithCause) {
        ErrorStateHandler.get.update(s => {
            if (s[path]?.find(e => deepEquals(e.err, err)) === undefined) {
                return {
                ...s,
                    [path]: [...s[path] ?? [], {id: `KernelError: ${err.className}`, err}]
                }
            } else return NoUpdate
        })
    }

    static removeError(err: DisplayError) {
        ErrorStateHandler.get.update(errorState => {
            let state = { ...errorState };
            Object.keys(errorState).forEach(key => {
                state = {...state, [key]: arrDeleteFirstItem(state[key], err)}
            })
            return state
        })
    }

    static notebookRenamed(oldPath: string, newPath: string) {
        ErrorStateHandler.get.update(s => {
            const newState = {...s}
            newState[newPath] = newState[oldPath]
            delete newState[oldPath]
            return newState
        })
    }
}