import * as deepEquals from 'fast-deep-equal/es6';
import * as clone from "clone";

// The StateHandler mediates interactions between Components and States
export class StateHandler<S> {
    // direct retrieval of the current value of S
    getState(): S {
        return clone(this.state);
    }

    /**
     * Handle with which to set the state. If the new state is different from the previous state, all observers are notified.
     * If the provided state is the same as the existing state, nothing happens.
     */
    setState(newState: S) {
        if (!deepEquals(newState, this.state)) {
            const oldState = this.state;
            this.state = newState;
            this.observers.forEach(obs => {
                obs(oldState, newState)
            });
        }
    }

    // handle with which to modify the state, given the old state. All observers get notified of the new state.
    updateState(f: (s: S) => S) {
        const newState = f(this.getState());
        this.setState(newState)
    }

    // Create a child 'view' of this state. Changes to this state will propagate to the view, and changes to the view
    // will be reflected in this state.
    // Optionally, caller can provide the constructor to use to instantiate the view StateHandler.
    view<K extends keyof S, C extends (new (s: S[K]) => StateHandler<S[K]>)>(key: K, constructor?: C): StateHandler<S[K]> {
        const view: StateHandler<S[K]> = constructor ? new constructor(this.state[key]) : new StateHandler(this.state[key]);
        this.addObserver(s => view.setState(s[key]));
        view.addObserver(s =>
            this.updateState(st => {
                st[key] = s;
                return st
            })
        );
        return view
    }

    // A child 'view' + a transformation.
    xmapView<K extends keyof S, T>(key: K, toT: (s: S[K]) => T | undefined, fromT: (s: S[K], t: T) => S[K]) {
        const view = this.view(key);
        const initialT = toT(view.getState());
        if (initialT === undefined) {
            throw new Error("how did this happen...")
        }
        const state = new StateHandler<T>(initialT);
        state.addObserver(t => {
            view.setState(fromT(view.getState(), t))
        });
        const viewObs = view.addObserver((_, newState) => {
            const t = toT(newState);
            if (t) {
                state.setState(t);
            } else {
                // hopefully this is all the cleanup we need to do?
                view.removeObserver(viewObs);
                state.clearObservers();
            }
        });
        return state;
    }

    constructor(protected state: S) {}

    // methods to add and remove observers.
    protected observers: Observer<S>[] = [];
    addObserver(f: Observer<S>): Observer<S> {
        this.observers.push(f);
        return f;
    }

    removeObserver(f: Observer<S>): void {
        const idx = this.observers.indexOf(f);
        if (idx >= 0) {
            this.observers.splice(idx, 1)
        }
    }

    // TODO: would views need to be cleared as well? could they leak?
    clearObservers(): void {
        this.observers = [];
    }
}

export type Observer<S> = (oldS: S, newS: S) => void;

export class SimpleStateHandler<S> extends StateHandler<S> {}