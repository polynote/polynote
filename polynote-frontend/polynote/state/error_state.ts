import {append, clearArray, destroy, ObjectStateHandler, removeIndex, renameKey, UpdateOf} from ".";
import {ServerErrorWithCause} from "../data/result";
import {deepEquals} from "../util/helpers";

export type DisplayError = {err: ServerErrorWithCause, id: string}

export interface ErrorState {
    serverErrors: DisplayError[],
    [path: string]: DisplayError[]
}

/**
 * Unified error display handler. Allows for a single observer to get all errors, no matter where they come from, which
 * makes it easier to display them all in one place.
 *
 * A singleton, which makes it easy to add errors from anywhere in the UI.
 *
 * TODO: make this singleton a const object. No need for it to be a subclass of StateHandler.
 */
export class ErrorStateHandler extends ObjectStateHandler<ErrorState> {
    private static inst: ErrorStateHandler;
    static get get() {
        if (!ErrorStateHandler.inst) {
            ErrorStateHandler.inst = new ErrorStateHandler({
                serverErrors: []
            })
        }
        return ErrorStateHandler.inst
    }

    static get state() {
        return ErrorStateHandler.get.state;
    }

    static addServerError(err: ServerErrorWithCause) {
        const state = ErrorStateHandler.state
        if (!state.serverErrors.find(e => deepEquals(e.err, err))) {
            ErrorStateHandler.get.updateField("serverErrors", () => append({id: `ServerError: ${err.className}`, err}))
        }
    }

    static addKernelError(path: string, err: ServerErrorWithCause) {
        const state = ErrorStateHandler.state[path];
        if (!state || !state.find(e => deepEquals(e.err, err))) {
            ErrorStateHandler.get.updateField(path, () => append({id: `KernelError: ${err.className}`, err}))
        }
    }

    static removeError(err: DisplayError) {
        ErrorStateHandler.get.update(errorState => {
            const updates: UpdateOf<ErrorState> = {};
            Object.keys(errorState).forEach(key => {
                const idx = errorState[key].findIndex(cellErr => deepEquals(err, cellErr))
                if (idx >= 0) {
                    if (errorState[key].length <= 1 && key !== 'serverErrors') {
                        updates[key] = destroy();
                    } else {
                        updates[key] = removeIndex(errorState[key], idx);
                    }
                }
            });
            return updates;
        });
    }

    static clear() {
        ErrorStateHandler.get.update(errorState => {
            const updates: UpdateOf<ErrorState> = {serverErrors: clearArray()}
            Object.keys(errorState).forEach(key => {
                if (key !== 'serverErrors') {
                    updates[key] = destroy();
                }
            });
            return updates;
        });
    }

    static notebookRenamed(oldPath: string, newPath: string) {
        ErrorStateHandler.get.update(() => renameKey(oldPath, newPath));
    }
}