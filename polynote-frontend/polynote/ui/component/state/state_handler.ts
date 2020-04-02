import * as deepEquals from 'fast-deep-equal/es6';
import {Message} from "../../../data/messages";

// The StateHandler mediates interactions between Components and States
export class StateHandler<S> {
    // direct retrieval of the current value of S
    getState(): S {
        return this.state;
    }

    // handle with which to set the state. When the state is changed, all observers get notified.
    /**
     * Handle with which to set the state. If the new state is different from the previous state, all observers are notified.
     * If the provided state is the same as the existing state, nothing happens.
     */
    setState(s: S) {
        if (!deepEquals(s, this.state)) {
            this.observers.forEach(obs => {
                obs(s)
            });
            this.state = s
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

    constructor(protected state: S) {}

    // methods to add and remove observers.
    protected readonly observers: ((s: S) => void)[];
    addObserver(f: (s: S) => void): (s: S) => void {
        this.observers.push(f);
        return f;
    }
    removeObserver(f: (s: S) => void): void {
        const idx = this.observers.indexOf(f);
        if (idx >= 0) {
            this.observers.splice(idx, 1)
        }
    }
}
