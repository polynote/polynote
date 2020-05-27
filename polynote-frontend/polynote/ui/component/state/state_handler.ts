import * as deepEquals from 'fast-deep-equal/es6';
import * as clone from "clone";
import {deepFreeze, isObject} from "../../../util/functions";

// The StateHandler mediates interactions between Components and States
export class StateHandler<S> {
    public dispose: () => void = () => {};

    protected state: S

    // the current value of S
    getState(): S {
        return this.state
    }

    /**
     * Handle with which to set the state and notify observers of the new state.
     */
    protected setState(newState: S) {
        if (newState !== this.state) {
            const oldState = this.state;
            this.state = newState;
            this.observers.forEach(obs => {
                obs(newState, oldState)
            });
        }
    }

    // handle with which to modify the state, given the old state. All observers get notified of the new state.
    updateState(f: (s: S) => S | undefined) {
        const currentState = this.getState()
        const newState = f(currentState);
        if (newState) {
            const frozenState = Object.isFrozen(newState) ? newState : deepFreeze(newState); // Note: this won't deepfreeze a shallow-frozen object. Don't pass one in.
            this.setState(frozenState)
        }
    }

    // Create a child 'view' of this state. Changes to this state will propagate to the view, and changes to the view
    // will be reflected in this state.
    // Optionally, caller can provide the constructor to use to instantiate the view StateHandler.
    // TODO: maybe views should be read-only? How often are views even changed?
    view<K extends keyof S, C extends StateHandler<S[K]>>(key: K, constructor?: { new(s: S[K]): C}): C {
        const view: StateHandler<S[K]> = constructor ? new constructor(this.state[key]) : new StateHandler(this.state[key]);
        const obs = this.addObserver(s => {
            const observedVal = s[key];
            if (observedVal !== undefined) {
                // if (!deepEquals(observedVal, view.getState())) {
                if (observedVal !== view.getState()) {
                    view.setState(observedVal)
                }
                // }
            } else {
                // the key being viewed no longer exists, clean up the view.
                view.dispose();
                view.clearObservers();
            }
        });
        view.addObserver(s => {
            // if (! deepEquals(s, this.getState()[key])) {
            if (s !== this.getState()[key]) {
                this.updateState(st => {
                    return {
                        ...st,
                        key: s
                    }
                })
            }
        });
        view.dispose = () => {
            this.removeObserver(obs);
        };
        return view as C
    }

    // // Create a child 'lens' of this state. Changes to this state will propagate to the view, and changes to the lens
    // // will be reflected in this state.
    // // Note:
    // //       You probably don't need this, because you're only supposed to update the state in the receiver/dispatcher.
    // //       This is less performant than a view, so don't use this if you don't need to update the state.
    // lens<K extends keyof S, C extends StateHandler<S[K]>>(key: K, constructor?: { new(s: S[K]): C}): C {
    //     const lens: StateHandler<S[K]> = constructor ? new constructor(this.state[key]) : new StateHandler(this.state[key]);
    //     const obs = this.addObserver(s => {
    //         if (!deepEquals(lens.getState(), s[key])) {
    //             lens.setState(s[key])
    //         } else {
    //             // the key being viewed no longer exists, clean up the view.
    //             lens.dispose();
    //             lens.clearObservers();
    //         }
    //     });
    //     lens.addObserver(s => {
    //         // is this even necessary?
    //         this.updateState(st => {
    //             st[key] = s;
    //             return st
    //         })
    //     });
    //     lens.dispose = () => {
    //         this.removeObserver(obs);
    //     };
    //     return lens as C
    // }

    // A child 'view' + a transformation.
    xmapView<K extends keyof S, T>(key: K, toT: (s: S[K]) => T | undefined, fromT: (s: S[K], t: T) => S[K]) {
        const view = this.view(key);
        const initialT = toT(view.getState());
        if (initialT === undefined) {
            throw new Error("Initial view state is undefined, unable to xmap an undefined view.")
        }
        const xmapView = new StateHandler<T>(initialT);
        xmapView.addObserver(t => {
            const newT = fromT(view.getState(), t)
            if (! deepEquals(t, newT)) {
                view.updateState(() => newT)
            }
        });
        view.addObserver(newState => {
            const t = toT(newState);
            if (t) {
                if (! deepEquals(t, xmapView.getState())) {
                    xmapView.updateState(() => t);
                }
            } else {
                // hopefully this is all the cleanup we need to do?
                view.dispose();
                view.clearObservers();
                xmapView.clearObservers();
            }
        });
        const oldDispose = view.dispose
        view.dispose = () => {
            oldDispose()
            view.clearObservers()
            xmapView.dispose()
            xmapView.clearObservers()
        }
        return xmapView;
    }

    constructor(state: S) {
        this.setState(deepFreeze(state))
    }

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
