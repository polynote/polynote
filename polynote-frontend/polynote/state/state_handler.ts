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
        console.log("Disposed object", this)
        return this.deferred
    };

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
    protected setState(newState: S, updateSource?: any) {
        if (this.isDisposed) {
            throw new Error("Operations on disposed states are not supported")
        }
        if (! this.compare(newState, this._state)) {
            const oldState = this._state;
            const frozenState = Object.isFrozen(newState) ? newState : deepFreeze(newState); // Note: this won't deepfreeze a shallow-frozen object. Don't pass one in.
            this._state = frozenState;
            if (this.matchSource(updateSource, frozenState)) {
                this.observers.forEach(obs => {
                    obs(frozenState, oldState, updateSource)
                });
            }
        }
    }

    protected handleView<K extends keyof S, V extends StateView<any>>(view: V, key: keyof S, disposeWhen: Disposable | undefined, onStateChange: (view: V, s: S[K], src?: any) => void) {
        const obs = this.addObserver((s, _, updateSource) => {
            if (s === undefined) { // if state is undefined it's impossible to have a view into it!
                view.dispose()
            } else {
                if ((s as any).hasOwnProperty(key)) { // if the key was deleted we need to dispose the lens.
                    const observedVal = s[key] as S[K];
                    if (! this.compare(observedVal, view.state)) {
                        onStateChange(view, observedVal, updateSource)
                    }
                } else {
                    view.dispose()
                }
            }
        })
        view.onDispose.then(() => {
            this.removeObserver(obs)
        })
        Promise.race([this.onDispose, ...(disposeWhen === undefined ? [] : [disposeWhen.onDispose])]).then(() => {
            if (! view.isDisposed) view.dispose()
        })
    }

    // Create a child 'view' of this state. Changes to this state will propagate to the view.
    // Optionally, caller can provide the constructor to use to instantiate the view StateHandler.
    view<K extends keyof S, C extends StateView<S[K]>>(key: K, constructor?: { new(s: S[K]): C}, disposeWhen?: Disposable): C {
        const view: StateView<S[K]> = constructor ? new constructor(this.state[key]) : new StateView(this.state[key]);
        this.handleView(view, key, disposeWhen, (view, s: S[K], src) => view.setState(s, src))
        return view as C
    }

    // a child view and a one-way transformation.
    mapView<K extends keyof S, T>(key: K, toT: (s: S[K]) => T | typeof NoUpdate, tEquals: (t1?: T, t2?: T) => boolean = deepEquals): StateView<T | undefined> {
        const initialT = toT(this.state[key]);
        const mapView = new StateView(initialT === NoUpdate ? undefined : initialT);
        this.handleView(mapView, key, undefined, (mapView, s: S[K], src?: any) => {
            const t = toT(s)
            if (t !== NoUpdate && ! tEquals(t, mapView.state)) {
                mapView.setState(t, src)
            }
        })
        return mapView;
    }

    protected constructor(state: S, disposeWhen?: Disposable) {
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

    // Optional filter for update sources
    protected matchSource(updateSource: any, x: any) {
        return true;
    }
}

/**
 * An updatable StateView.
 */
export class StateHandler<S> extends StateView<S> {

    // handle with which to modify the state, given the old state. All observers get notified of the new state.
    update(f: (s: S) => S | typeof NoUpdate, updateSource?: any): StateHandler<S> {
        const currentState = this.state
        const newState = f(currentState);
        if (! this.compare(newState, NoUpdate)) {
            this.setState(newState as S, updateSource)
        }
        return this
    }

    update1<K extends keyof S, C extends StateHandler<S[K]>>(key: K, f: (s: S[K]) => S[K] | typeof NoUpdate, updateSource?: any) {
        this.lens(key).update(f, updateSource).dispose()
    }

    // A lens is like a view except changes to the lens propagate back to its parent
    lens<K extends keyof S, C extends StateHandler<S[K]>>(key: K, constructor?: { new(s: S[K]): C}, disposeWhen?: Disposable): C {
        const lens: StateHandler<S[K]> = constructor ? new constructor(this.state[key]) : new StateHandler(this.state[key]);
        this.handleView(lens, key, disposeWhen, (lens, s: S[K], src) => lens.setState(s, src))
        lens.addObserver((viewState, _, src) => {
            this.setState({
                ...this.state,
                [key]: viewState
            }, src ?? lens)
        })
        return lens as C
    }

    constructor(state: S, disposeWhen?: Disposable) {
        super(state, disposeWhen)
    }
}

export type Observer<S> = (currentS: S, previousS: S, updateSource: any) => void;
