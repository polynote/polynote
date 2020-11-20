import {deepEquals, deepFreeze, Deferred} from "../util/helpers";

/**
 * An implementer of Disposable must have a dispose function which disposes the implementer, and a didDispose Promise
 * that resolves when the implementer has been disposed.
 */
export class Disposable {
    private deferred: Deferred<void> = new Deferred()
    onDispose: Promise<void> = this.deferred

    // this readonly field trick is a hacky way to make it `final`...
    readonly dispose = () => {
        if (this.isDisposed) {
            throw new Error("Already disposed! Can't dispose again.")
        }
        this.deferred.resolve()
    };

    readonly tryDispose = () => {
        if (!this.isDisposed) {
            this.deferred.resolve();
        }
    }

    get isDisposed() {
        return this.deferred.isSettled
    }
}

export const NoUpdate: unique symbol = Symbol()

export class StateView<S> extends Disposable {

    protected _state: S

    // the current value of S
    get state(): S {
        if (this.isDisposed) {
            throw new Error("Operations on disposed states are not supported")
        }
        return this._state
    }

    /**
     * Handle with which to set the state and notify observers of the new state.
     *
     * Note: this should be a setter but typescript won't allow a protected setter with a public getter, sigh.
     */
    protected setState(newState: S, quiet?: boolean) {
        if (this.isDisposed) {
            throw new Error("Operations on disposed states are not supported")
        }
        if (!this.compare(newState, this._state)) {
            const oldState = this._state;
            this._state = newState;
            if (!quiet) {
                this.observers.forEach(obs => {
                    obs(newState, oldState)
                });
            }
        }
    }

    // chainUpdates(): ChainableUpdate<S> {
    //     return new ChainableUpdate(this, [])
    // }

    // Create a child 'view' of this state. Changes to this state will propagate to the view.
    // Optionally, caller can provide the constructor to use to instantiate the view StateHandler.
    // TODO: should views remove themselves if they have no more observers?
    view<K extends keyof S, C extends StateView<S[K]>>(key: K, constructor?: { new(s: S[K]): C}, disposeWhen?: Disposable): C {
        const view: StateView<S[K]> = constructor ? new constructor(this.state[key]) : new StateView(this.state[key]);
        const obs = this.addObserver(s => {
            const observedVal = (s !== null && s !== undefined) ? s[key] : (s as unknown as S[K]);
            if (! this.compare(observedVal, view.state)) {
                view.setState(observedVal)
            }
        });
        view.onDispose.then(() => {
            this.removeObserver(obs);
        })
        Promise.race([this.onDispose, ...(disposeWhen === undefined ? [] : [disposeWhen.onDispose])]).then(() => view.tryDispose())
        return view as C
    }

    // a child view and a one-way transformation.
    mapView<K extends keyof S, T>(key: K, toT: (s: S[K]) => T | typeof NoUpdate, tEquals: (t1?: T, t2?: T) => boolean = deepEquals): StateView<T | undefined> {
        const initialT = toT(this.state[key]);
        const mapView = new StateView(initialT === NoUpdate ? undefined : initialT);
        const obs = this.addObserver(s => {
            const observedVal = s[key];
            if (! this.compare(observedVal, mapView.state)) {
                const t = toT(observedVal)
                if (t !== NoUpdate && ! tEquals(t, mapView.state)) {
                    mapView.setState(t)
                }
            }
        })
        mapView.onDispose.then(() => {
            this.removeObserver(obs)
        })
        return mapView;
    }

    constructor(state: S, disposeWhen?: Disposable) {
        super()
        this.setState(deepFreeze(state))
        this.onDispose.then(() => {
            this.clearObservers()
        })
        disposeWhen?.onDispose.then(() => {
            this.dispose()
        })
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
        disposeWhen?.onDispose.then(() => {
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
}

/**
 * An updatable StateView.
 */
export class StateHandler<S> extends StateView<S> {

    private doUpdateState(f: (s: S) => S | typeof NoUpdate, quiet?: boolean) {
        const currentState = this.state
        const newState = f(currentState);
        if (! this.compare(newState, NoUpdate)) {
            const frozenState = Object.isFrozen(newState) ? newState : deepFreeze(newState); // Note: this won't deepfreeze a shallow-frozen object. Don't pass one in.
            this.setState(frozenState as S, quiet);
        }
    }

    // handle with which to modify the state, given the old state. All observers get notified of the new state.
    updateState(f: (s: S) => S | typeof NoUpdate) {
        this.doUpdateState(f);
    }

    updateStateQuiet(f: (s: S) => S | typeof NoUpdate) {
        this.doUpdateState(f, true);
    }

    viewUpdatable<K extends keyof S, V extends S[K], C extends StateHandler<V> = StateHandler<V>>(key: K, constructor?: { new(s: S[K]): C}, disposeWhen?: Disposable): C {
        const view: StateHandler<S[K]> = constructor ? new constructor(this.state?.[key] as S[K]) : new StateHandler(this.state?.[key] as S[K]);
        const thisObs = this.addObserver(s => {
            const observedVal = (s !== null && s !== undefined) ? s[key] : (s as unknown as S[K]);
            if (! this.compare(observedVal, view.state)) {
                view.setState(observedVal)
            }
        });
        view.onDispose.then(() => {
            this.removeObserver(thisObs);
        })

        const thatObs = view.addObserver((vNew, vOld) => {
            this.removeObserver(thisObs);
            if (!this.compare(vNew, vOld)) {
                const newState = {...this.state, [key]: vNew};
                const ctor = this.state && (this.state as any).constructor;
                if (ctor && ctor.prototype) {
                    Object.setPrototypeOf(newState, ctor.prototype);
                }
                this.setState(newState);
            }
            this.addObserver(thisObs);
        })
        Promise.race([this.onDispose, ...(disposeWhen === undefined ? [] : [disposeWhen.onDispose])]).then(() => view.tryDispose())
        return view as C
    }

    constructor(state: S, disposeWhen?: Disposable) {
        super(state, disposeWhen)
    }
}

export type Observer<S> = (currentS: S, previousS: S) => void;

// type updateFn<S> = (s: S) => S | typeof NoUpdate | undefined | void
// class ChainableUpdate<S> {
//     constructor(private stateHandler: StateHandler<S>, readonly chain: updateFn<S>[] = []) {}
//
//     then(fn: updateFn<S> | ChainableUpdate<S>) {
//         if (fn instanceof ChainableUpdate) {
//             this.chain.push(...fn.chain)
//         } else {
//             this.chain.push(fn)
//         }
//         return this
//     }
//
//     commit() {
//         this.stateHandler.updateState(s => {
//             return this.chain.reduce((state, next) => {
//                 const newState = next(state)
//                 if (newState === undefined || newState === null) {
//                     return state
//                 } else return newState
//             }, s)
//         })
//     }
// }