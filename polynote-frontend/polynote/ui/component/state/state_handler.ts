import * as deepEquals from 'fast-deep-equal/es6';
import * as clone from "clone";

// The StateHandler mediates interactions between Components and States
export class StateHandler<S> {
    public dispose: () => void = () => {};
    // the current value of S, cloned to ensure the state can't be modified
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
                obs(newState, oldState)
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
    view<K extends keyof S, C extends StateHandler<S[K]>>(key: K, constructor?: { new(s: S[K]): C}): C {
        const view: StateHandler<S[K]> = constructor ? new constructor(this.state[key]) : new StateHandler(this.state[key]);
        const obs = this.addObserver(s => view.setState(s[key]));
        view.addObserver(s =>
            this.updateState(st => {
                st[key] = s;
                return st
            })
        );
        view.dispose = () => {
            this.removeObserver(obs);
        };
        return view as C
    }

    // A child 'view' + a transformation.
    xmapView<K extends keyof S, T>(key: K, toT: (s: S[K]) => T | undefined, fromT: (s: S[K], t: T) => S[K]) {
        const view = this.view(key);
        const initialT = toT(view.getState());
        if (initialT === undefined) {
            throw new Error("Initial view state is undefined, unable to xmap an undefined view.")
        }
        const xmapView = new StateHandler<T>(initialT);
        xmapView.addObserver(t => {
            view.setState(fromT(view.getState(), t))
        });
        view.addObserver(newState => {
            const t = toT(newState);
            if (t) {
                xmapView.setState(t);
            } else {
                // hopefully this is all the cleanup we need to do?
                view.dispose();
                view.clearObservers();
                xmapView.clearObservers();
            }
        });
        return xmapView;
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

    clearObservers(): void {
        this.observers = [];
    }
}

export type Observer<S> = (currentS: S, previousS: S) => void;

export class SimpleStateHandler<S> extends StateHandler<S> {}