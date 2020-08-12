import * as deepEquals from 'fast-deep-equal/es6';
import {deepFreeze, Deferred, isObject} from "../../../util/helpers";

// TODO: should probably validate that an object isn't used once disposed...
/**
 * An implementer of Disposable must have a dispose function which disposes the implementer, and a didDispose Promise
 * that resolves when the implementer has been disposed.
 *
 * TODO: what's the point of this interface?
 */
export interface Disposable {
    didDispose: Promise<void>,
    dispose(): void
}

/**
 * Helper implementation of Disposable. Extenders can override `onDispose` to add any special cleanup logic.
 */
export abstract class BaseDisposable implements Disposable {
    private deferred: Deferred<void> = new Deferred()
    didDispose: Promise<void> = this.deferred

    // TODO: maybe onDispose isn't necessary, as implementers could instead use `this.didDispose.then()` to achieve the same thing...
    protected onDispose(): void {}

    // this readonly field trick is a hacky way to make a `final` member in Typescript...
    readonly dispose = () => {
        this.onDispose()
        this.deferred.resolve()
    };
}


export const NoUpdate: unique symbol = Symbol()

// The StateHandler mediates interactions between Components and States
export class StateHandler<S> extends BaseDisposable {

    protected state: S

    // the current value of S
    getState(): S {
        return this.state
    }

    /**
     * Handle with which to set the state and notify observers of the new state.
     */
    protected setState(newState: S) {
        if (! this.compare(newState, this.state)) {
            const oldState = this.state;
            this.state = newState;
            this.observers.forEach(obs => {
                obs(newState, oldState)
            });
        }
    }

    // handle with which to modify the state, given the old state. All observers get notified of the new state.
    updateState(f: (s: S) => S | typeof NoUpdate) {
        const currentState = this.getState()
        const newState = f(currentState);
        if (! this.compare(newState, NoUpdate)) {
            const frozenState = Object.isFrozen(newState) ? newState : deepFreeze(newState); // Note: this won't deepfreeze a shallow-frozen object. Don't pass one in.
            this.setState(frozenState as S)
        }
    }

    // Create a child 'view' of this state. Changes to this state will propagate to the view, and changes to the view
    // will be reflected in this state.
    // Optionally, caller can provide the constructor to use to instantiate the view StateHandler.
    // TODO: maybe views should be read-only? How often are views even changed?
    // TODO: should views remove themselves if they have no more observers?
    // TODO: how often do we really use the constructor parameter? Feels like it kind of complicates things and isn't that useful.
    // TODO: cache views for the same key, to avoid recreating them? This may conflict with disposal though.
    view<K extends keyof S, C extends StateHandler<S[K]>>(key: K, constructor?: { new(s: S[K]): C}, disposeWhen?: Disposable): C {
        const view: StateHandler<S[K]> = constructor ? new constructor(this.state[key]) : new StateHandler(this.state[key]);
        const obs = this.addObserver(s => {
            const observedVal = s[key];
            if (! this.compare(observedVal, view.getState())) {
                view.setState(observedVal)
            }
        });
        view.addObserver(s => {
            if (! this.compare(s, this.getState()[key])) {
                this.updateState(st => {
                    return {
                        ...st,
                        [key]: s
                    }
                })
            }
        });
        view.didDispose.then(() => {
            this.removeObserver(obs);
        })
        Promise.race([this.didDispose, ...(disposeWhen === undefined ? [] : [disposeWhen.didDispose])]).then(() => view.dispose())
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
    // TODO: revisit this to use NoUpdate. Also, mapView might supersede it
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
            // TODO: is it necessary to check `if (t)`? this is problematic if `undefined` is a legitimate value.
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
        const oldDispose = view.onDispose
        view.onDispose = () => {
            oldDispose()
            view.clearObservers()
            xmapView.dispose()
            xmapView.clearObservers()
        }
        return xmapView;
    }

    // a child view and a one-way transformation.
    mapView<K extends keyof S, T>(key: K, toT: (s: S[K]) => T | typeof NoUpdate) {
        const view = this.view(key);
        const initialT = toT(view.getState());
        const mapView = new StateHandler<T | undefined>(initialT === NoUpdate ? undefined : initialT);
        view.addObserver(newState => {
            const t = toT(newState);
            if (t !== NoUpdate) {
                if (! deepEquals(t, mapView.getState())) {
                    mapView.updateState(() => t);
                }
            }
        });
        const oldDispose = view.dispose
        view.onDispose = () => {
            oldDispose()
            view.clearObservers()
            mapView.dispose()
            mapView.clearObservers()
        }
        return mapView;
    }

    constructor(state: S) {
        super()
        this.setState(deepFreeze(state))
    }

    // Comparison function to use. Subclasses can provide a different comparison function (e.g., deepEquals) but should
    // be aware of the performance implications.
    protected compare(s1: any, s2: any) {
        return s1 === s2
    }

    protected observers: Observer<S>[] = [];

    /**
     * Add an Observer to this State Handler.
     * If a Disposable is passed to `disposeWhen`, the Observer will be removed following `disposeWhen`'s disposal.
     *
     * @param disposeWhen
     * @param f
     */
    addObserver(f: Observer<S>, disposeWhen?: Disposable): Observer<S> {
        this.observers.push(f);
        disposeWhen?.didDispose.then(() => {
            this.removeObserver(f)
        })
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

    protected onDispose() {
        this.clearObservers()
    }
}

export type Observer<S> = (currentS: S, previousS: S) => void;
