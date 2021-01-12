import {arrayStartsWith, deepEquals, deepFreeze, Deferred, shallowEquals} from "../util/helpers";

export interface IDisposable {
    onDispose: Promise<void>,
    dispose: () => Deferred<void>,
    isDisposed: boolean
}
export class Disposable implements IDisposable {
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

export class StateView<S> {

    protected _state: S

    // the current value of S
    get state(): S {
        return this._state
    }

    /**
     * Handle with which to set the state and notify observers of the new state.
     *
     * Note: this should be a setter but typescript won't allow a protected setter with a public getter, sigh.
     */
    protected setState(newState: S, updateSource?: any, updatePath: string[] = this.path, quiet?: boolean) {
        if (! this.compare(newState, this._state)) {
            const oldState = this._state;
            const frozenState = Object.isFrozen(newState) ? newState : deepFreeze(newState); // Note: this won't deepfreeze a shallow-frozen object. Don't pass one in.
            this._state = frozenState;

            if (!quiet) {
                // view Observers are always called (to ensure state is accurately updated)
                this.viewObservers.forEach(([obs, desc]) => {
                    obs(frozenState, oldState, updateSource)
                });
                // Check if matchSource filtered this update out.
                if (this.matchSource(updateSource, frozenState)) {
                    // update observers IFF the current path is <= the update path. Otherwise, the update should not apply.
                    if (arrayStartsWith(this.path, updatePath)) {
                        this.observers.forEach(([obs, desc]) => {
                                obs(frozenState, oldState, updateSource)
                            });
                        }
                    }
                }
        }
    }

    private views: Record<keyof any, StateView<S[keyof S]>> = {};

    // Create a child 'view' of this state. Changes to this state will propagate to the view.
    // Optionally, caller can provide the constructor to use to instantiate the view StateHandler.
    view<K extends keyof S>(key: K): StateView<S[K]> {
        const maybeView = this.views[key];
        if (maybeView) {
            if (! this.compare(maybeView.state, this.state[key])) {
                // This might happen when a view is created within a state change.
                // Not quite sure what to do about this, so we throw for now.
                console.trace("View state doesn't match parent! View state:", maybeView.state, "parent state", this.state[key], "parent", this, "key", key)
                throw new Error("View state doesn't match parent! This shouldn't happen, if you see this please let the Polynote Developers know and provide the log above.")
            }
            return maybeView as StateView<S[K]>
        } else {
            const view: StateView<S[K]> = new StateView(this.state[key], [...this.path, key.toString()]);
            const viewDispose = new Disposable()

            const obs = this.addObserver((s, _, updateSource) => {
                if (s === undefined) { // if state is undefined it's impossible to have a view into it!
                    if (! viewDispose.isDisposed) viewDispose.dispose()
                } else {
                    if ((s as any).hasOwnProperty(key)) { // if the key was deleted we need to dispose the view.
                        const observedVal = s[key] as S[K];
                        if (! this.compare(observedVal, view.state)) {
                            view.setState(observedVal, updateSource, this.path)
                        }
                    } else {
                        if (! viewDispose.isDisposed) viewDispose.dispose()
                    }
                }
            }, viewDispose, `handleView of key ${key}`, 'viewObserver')
            viewDispose.onDispose.then(() => {
                console.log("removed view:", view)
                view.clearObservers() // any observers of this view are no longer relevant
                delete this.views[key]
                this.removeObserver(obs)
            })

            this.views[key] = view;
            return view
        }
    }

    // a child view and a one-way transformation.
    mapView<K extends keyof S, T>(key: K, toT: (s: S[K]) => T | typeof NoUpdate, tEquals: (t1?: T, t2?: T) => boolean = deepEquals): MapView<S[K], T> {
        const view = this.view(key)
        return new MapView(view, toT, tEquals)
    }

    protected constructor(state: S, readonly path: string[] = ["root"]) {
        this.setState(deepFreeze(state))
    }

    // Comparison function to use. Subclasses can provide a different comparison function (e.g., deepEquals) but should
    // be aware of the performance implications.
    protected compare(s1: any, s2: any) {
        // return deepEquals(s1, s2)
        return shallowEquals(s1, s2)
    }

    protected observers: [Observer<S>, string][] = [];
    protected viewObservers: [Observer<S>, string][] = [];  // view Observers get updated first

    /**
     * Add an Observer to this State Handler.
     * The Observer will be removed following `disposeWhen`'s disposal.
     *
     * @param disposeWhen
     * @param f
     */
    addObserver(f: Observer<S>, disposeWhen: IDisposable, description: string = "obs", type: "observer" | "viewObserver" = "observer"): [Observer<S>, string] {
        const obs: [Observer<S>, string] = [f, description]
        if (type === "observer") {
            this.observers.push(obs);
        } else {
            this.viewObservers.push(obs)
        }
        disposeWhen.onDispose.then(() => {
            this.removeObserver(obs)
        })
        return obs;
    }

    removeObserver(f: [Observer<S>, string]): void {
        const idx = this.observers.indexOf(f);
        if (idx >= 0) {
            this.observers.splice(idx, 1)
        } else {
            // maybe it's a viewobserver
            const idx = this.viewObservers.indexOf(f);
            if (idx >= 0) {
                this.viewObservers.splice(idx, 1)
            }
        }
    }

    clearObservers(): void {
        this.observers = [];
    }

    // Optional filter for update sources. Prevents observers from being triggered if the update source doesn't match.
    protected matchSource(updateSource: any, x: any) {
        return updateSource !== this;
    }
}

export class StateWrapper<S> extends StateView<S> implements IDisposable {

    constructor(view: StateView<S>, private disposable: IDisposable = new Disposable()) {
        super(view.state, view.path);

        view.addObserver((s, _, src) => {
            this.setState(s, src, view.path)
        }, this.disposable, `wrapper observer of ${this.path}`, "viewObserver")

    }

    // implement IDisposable
    dispose() {
        console.log("StateWrapper disposed", this, this.state)
        return this.disposable.dispose()
    }

    get onDispose() {
        return this.disposable.onDispose
    }

    get isDisposed() {
        return this.disposable.isDisposed
    }
}

/**
 * An updatable StateView.
 */
export class StateHandler<S> extends StateWrapper<S> {

    // handle with which to modify the state, given the old state. All observers get notified of the new state.
    update(f: (s: S) => S | typeof NoUpdate, updateSource?: any, path = this.path, quiet?: boolean): StateHandler<S> {
        const currentState = this.state
        const newState = f(currentState);
        if (! this.compare(newState, NoUpdate) && ! this.compare(currentState, newState)) {
            this.setState(newState as S, updateSource, path, quiet);
        }
        return this
    }

    update1<K extends keyof S, C extends StateHandler<S[K]>>(key: K, f: (s: S[K]) => S[K] | typeof NoUpdate, updateSource?: any) {
        this.update(s => ({...s, [key]: f(s[key])}), updateSource, [...this.path, key.toString()])
    }

    // A lens is like a view except changes to the lens propagate back to its parent
    lens<K extends keyof S, C extends StateHandler<S[K]>>(key: K): StateHandler<S[K]> {
        const view = this.view(key)
        const lens: StateHandler<S[K]> = new StateHandler(view);
        lens.addObserver((viewState, _, src) => {
            this.setState({
                ...this.state,
                [key]: viewState
            }, src ?? lens, lens.path)
        }, this, `lens of key ${key}`, "viewObserver")
        this.onDispose.then(() => {
            if (! lens.isDisposed) lens.dispose()
        })
        return lens as C
    }

    // handle with which to modify the state, given the old state. All observers get notified of the new state.
    updateState(f: (s: S) => S | typeof NoUpdate) {
        this.update(f);
    }

    updateStateQuiet(f: (s: S) => S | typeof NoUpdate) {
        this.update(f, undefined, this.path, true);
    }

    // TODO: replace with lens
    viewUpdatable<K extends keyof S, V extends S[K], C extends StateHandler<V> = StateHandler<V>>(key: K, constructor?: { new(s: S[K]): C}, disposeWhen?: Disposable): C {
        const d: IDisposable = disposeWhen ?? this
        const view: StateHandler<S[K]> = constructor ? new constructor(this.state?.[key] as S[K]) : new StateHandler(StateHandler.from(this.state?.[key] as S[K]));
        const thisObs = this.addObserver(s => {
            const observedVal = (s !== null && s !== undefined) ? s[key] : (s as unknown as S[K]);
            if (! this.compare(observedVal, view.state)) {
                view.setState(observedVal)
            }
        }, d);
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
            this.addObserver(thisObs[0], d, thisObs[1]);
        }, d)
        return view as C
    }

    constructor(private _view: StateView<S>) {
        super(_view)
    }

    static from<S>(s: S, disposable: IDisposable = new Disposable()) {
        return new this(new StateView(s))
    }
}

export type Observer<S> = (currentS: S, previousS: S, updateSource: any) => void;


// Map view from U to S
export class MapView<U, S> extends StateWrapper<S | undefined> {
    equals?: (s1?: S, s2?: S) => boolean;

    constructor(view: StateView<U>, toS: (u: U) => S | typeof NoUpdate, equals?: (s1?: S, s2?: S) => boolean) {

        const s = toS(view.state)
        super(new StateView(s === NoUpdate ? undefined : s, view.path));

        view.addObserver((u, _, src) => {
            const s = toS(u)
            if (s !== NoUpdate && !this.compare(s, this.state)) {
                this.setState(s, src)
            }
        }, this)

        this.equals = equals;

    }

    protected compare(s1: any, s2: any): boolean {
        return (this.equals ?? super.compare)(s1, s2)
    }
}