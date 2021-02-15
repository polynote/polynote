import {collect, Deferred} from "../util/helpers";
import {Disposable, IDisposable, ImmediateDisposable, mkDisposable} from "./disposable";
import {
    childResult,
    Destroy,
    NoUpdate, setToUndefined,
    SetValue,
    UpdateKey,
    UpdateLike,
    UpdateOf, UpdateResult,
    valueToUpdate
} from ".";
import {__getProxyTarget, __readOnlyProxyObject} from "./readonly";

export type Observer<S> = (value: S, update: UpdateResult<S>, updateSource: any) => void
export type PreObserver<S> = (value: S) => Observer<S>
export type Updater<S> = (currentState: Readonly<S>) => UpdateOf<S>

/**
 * Types for describing state updates
 */

// A boolean that can only be set to true
export interface Latch {
    triggered: boolean
    trigger(): void
    down(): Latch
}

export interface ObservableState<S> extends IDisposable {
    state: S
    addObserver(fn: Observer<S>, path?: string): IDisposable
    addPreObserver(fn: PreObserver<S>, path?: string): IDisposable
}

export interface StateView<S> extends ObservableState<S> {
    view<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateView<S[K]>
    viewOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>>
    observeKey<K extends keyof S>(key: K, fn: Observer<S[K]>, subPath?: string): IDisposable
    preObserveKey<K extends keyof S>(key: K, fn: PreObserver<S[K]>, subPath?: string): IDisposable
    filterSource(filter: (source: any) => boolean): StateView<S>
    fork(disposeContext?: IDisposable): StateView<S>
}

export interface OptionalStateView<S> extends ObservableState<S | undefined> {
    view<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>>
    viewOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>>
    observeKey<K extends keyof S>(key: K, fn: Observer<S[K] | undefined>, subPath?: string): IDisposable
    preObserveKey<K extends keyof S>(key: K, fn: PreObserver<S[K] | undefined>, subPath?: string): IDisposable
    filterSource(filter: (source: any) => boolean): OptionalStateView<S>
    fork(disposeContext?: IDisposable): OptionalStateView<S>
}

export interface UpdatableState<S> extends ObservableState<S> {
    /**
     * Update the state. The update is not guaranteed to have been applied by the time this method returns! (see updateAsync)
     */
    update(updates: Updater<S>, updateSource?: any, updatePath?: string): void

    /**
     * Update the state, returning a Promise which will be completed when the update has been applied and all its observers
     * have been notified.
     */
    updateAsync(updates: Updater<S>, updateSource?: any, updatePath?: string): Promise<Readonly<S>>
}

export interface StateHandler<S> extends StateView<S>, UpdatableState<S> {
    lens<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateHandler<S[K]>
    lensOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>>
    updateField<K extends keyof S>(key: K, update: Updater<S[K]>, updateSource?: any, updateSubpath?: string): void
    filterSource(filter: (source: any) => boolean): StateHandler<S>
    fork(disposeContext?: IDisposable): StateHandler<S>
}

export const StateHandler = {
    from<T extends object>(state: T): StateHandler<T> { return new ObjectStateHandler(state) }
}

export interface OptionalStateHandler<S> extends OptionalStateView<S>, UpdatableState<S | undefined> {
    lens<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>>
    lensOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>>
    updateField<K extends keyof S>(key: K, update: Updater<S[K] | undefined>, updateSource?: any, updateSubpath?: string): void
    filterSource(filter: (source: any) => boolean): OptionalStateHandler<S>
    fork(disposeContext?: IDisposable): OptionalStateHandler<S>
}

function filterObserver<S>(fn: Observer<S>, filter?: (src: any) => boolean): Observer<S> {
    if (!filter)
        return fn;
    return (value, update, updateSource) => {
        if (filter(updateSource)) {
            fn(value, update, updateSource)
        }
    }
}

function filterPreObserver<S>(fn: PreObserver<S>, filter?: (src: any) => boolean): PreObserver<S> {
    if (!filter)
        return fn;
    return pre => {
        const obs = fn(pre);
        return (value, update, updateSource) => {
            if (filter(updateSource)) {
                obs(value, update, updateSource)
            }
        }
    }
}

function keyObserver<S, K extends keyof S, V extends S[K] = S[K]>(key: K, fn: Observer<V>, filter?: (src: any) => boolean): Observer<S> {
    return (value, result, updateSource) => {
        const down = childResult(result, key);
        if (!(down.update instanceof Destroy) && down.update !== NoUpdate) {
            if (!filter || filter(updateSource)) {
                fn(value[key as keyof S] as V, down as UpdateResult<V>, updateSource)
            }
        }
    }
}

function keyPreObserver<S, K extends keyof S, V extends S[K] = S[K]>(key: K, fn: PreObserver<V>, filter?: (src: any) => boolean): PreObserver<S> {
    return preS => {
        const obs = fn(preS[key as keyof S] as V);
        return (value, result, updateSource) => {
            const down = childResult(result, key);
            if (down && down.update !== NoUpdate) {
                if (!filter || filter(updateSource)) {
                    obs(value[key] as V, down as UpdateResult<V>, updateSource)
                }
            }
        }
    }
}

function keyUpdater<S, K extends keyof S>(key: K, updater: Updater<S[K]>): Updater<S> {
    return (s: Readonly<S>) => new UpdateKey<S, K>(key, valueToUpdate(updater(s[key])))
}

function combineFilters(first?: (source: any) => boolean, second?: (source: any) => boolean): ((source: any) => boolean) | undefined {
    if (first && second) {
        return source => first(source) && second(source)
    }
    return first || second
}

function cleanPath(path: string[]): string[] {
    if (path.length && path[path.length - 1] === '') {
        path = path.slice(0, path.length - 1);
    }
    return path;
}

class ObserverDict<T> {
    private children: Record<string, ObserverDict<T>> = {};
    private observers: (T & IDisposable)[] = [];

    constructor(private path: string[] = []) {}

    add(observer: T, path: string): IDisposable {
        return this.addAt(observer, cleanPath(path.split('.')));
    }

    private addAt(observer: T, path: string[]): IDisposable {
        if (path.length === 0) {
            const disposable = mkDisposable(observer, () => {
                const idx = this.observers.indexOf(disposable);
                if (idx >= 0) {
                    this.observers.splice(idx, 1);
                }
            });
            this.observers.push(disposable);
            return disposable;
        } else {
            const first = path[0];
            if (!this.children[first]) {
                this.children[first] = new ObserverDict([...this.path, first]);
            }
            return this.children[first].addAt(observer, path.slice(1));
        }
    }

    forEach(path: string, fn: (observer: T) => void): void {
        this.forEachAt(cleanPath(path.split('.')), fn);
    }

    private forEachAt(path: string[], fn: (observer: T) => void): void {
        let x;
        if (path.length === 0) {
            x = 10;
        }
        this.observers.forEach(fn);
        if (path.length === 0) {
            for (const child of Object.values(this.children)) {
                child.forEachAt(path, fn);
            }
        } else {
            const child = path.shift()!;
            if (this.children[child]) {
                this.children[child].forEachAt(path, fn);
            }
        }
    }

    collect<U>(path: string, fn: (observer: T) => U): U[] {
        const result: U[] = [];
        this.forEach(path, obs => result.push(fn(obs)));
        return result;
    }

    clear(): void {
        this.observers.forEach(obs => obs.tryDispose());
        this.observers = [];
    }

    get length(): number {
        let len = this.observers.length;
        for (const child of Object.values(this.children)) {
            len += child.length;
        }
        return len;
    }

}

export class ObjectStateHandler<S extends object> extends Disposable implements StateHandler<S> {

    // internal, mutable state
    private mutableState: S;

    // private read-only view of state
    private readOnlyState: S;

    // observers receive the updated state, the update, and the update source and perform side-effects
    private observers: ObserverDict<Observer<S>> = new ObserverDict();

    // pre-observers receive the previous state; and return function that receive the updated state, the update, and
    // the update source, which performs side-effects. This is useful if an observer needs to copy any values from the
    // previous state, or decide what to based on the previous state.
    private preObservers: ObserverDict<PreObserver<S>> = new ObserverDict();

    // public, read-only view of state
    get state(): Readonly<S> { return this.readOnlyState }

    constructor(state: S, readonly path: string = "", readonly sourceFilter: (source?: any) => boolean = () => true) {
        super();
        this.mutableState = state;
        this.readOnlyState = __readOnlyProxyObject(state);
        this.onDispose.then(() => {
            this.observers.clear();
            this.preObservers.clear();
        })
    }

    private isUpdating: boolean = false;
    private updateQueue: [(state: Readonly<S>) => UpdateOf<S>, any, string, Deferred<Readonly<S>> | undefined][] = [];

    protected handleUpdate(updateFn: (state: Readonly<S>) => UpdateOf<S>, updateSource: any, updatePath: string, promise?: Deferred<Readonly<S>>): void {
        const update = valueToUpdate(updateFn(this.state));

        if (update === NoUpdate) {
            if (promise) {
                promise.resolve(this.state)
            }
            return;
        }

        const preObservers = this.preObservers.collect(updatePath, obs => obs(this.state));

        const updateResult = update.applyMutate(this.mutableState);
        const updatedObj = __getProxyTarget(updateResult.newValue);
        if (!Object.is(this.mutableState, updatedObj)) {
            this.mutableState = updatedObj
            this.readOnlyState = __readOnlyProxyObject(this.mutableState);
        }

        if (updateResult.update !== NoUpdate) {
            if (this.sourceFilter(updateSource)) {
                const src = updateSource ?? this;
                preObservers.forEach(observer => observer(this.state, updateResult, src));
                this.observers.forEach(updatePath, observer => observer(this.state, updateResult, src));
            }
        }

        if (promise) {
            promise.resolve(this.state);
        }
    }

    private runUpdates() {
        if (!this.isUpdating) {
            this.isUpdating = true;
            try {
                while (this.updateQueue.length > 0) {
                    const updateFields = this.updateQueue.shift();
                    if (updateFields) {
                        this.handleUpdate(...updateFields);
                    }
                }
            } finally {
                this.isUpdating = false;
            }
        }
    }

    update(updateFn: (state: S) => UpdateOf<S>, updateSource?: any, updatePath?: string): void {
        this.updateQueue.push([updateFn, updateSource, updatePath ?? this.path, undefined]);
        this.runUpdates();
    }

    updateAsync(updateFn: (state: S) => UpdateOf<S>, updateSource?: any, updatePath?: string): Promise<Readonly<S>> {
        const promise = new Deferred<Readonly<S>>();
        this.updateQueue.push([updateFn, updateSource, updatePath ?? this.path, promise]);
        this.runUpdates();
        return promise;
    }

    updateField<K extends keyof S>(key: K, updateFn: Updater<S[K]>, updateSource?: any, updateSubPath?: string): void {
        this.update(keyUpdater(key, updateFn), updateSource, `${key}.` + (updateSubPath ?? ''))
    }

    private addObserverAt(fn: Observer<S>, path: string): IDisposable {
        return this.observers.add(fn, path).disposeWith(this);
    }

    private addPreObserverAt(fn: PreObserver<S>, path: string): IDisposable {
        return this.preObservers.add(fn, path).disposeWith(this);
    }

    addObserver(fn: Observer<S>, path?: string): IDisposable {
        return this.addObserverAt(fn, path ?? '');
    }

    addPreObserver(fn: PreObserver<S>, path?: string): IDisposable {
        return this.addPreObserverAt(fn, path ?? '');
    }

    observeKey<K extends keyof S>(key: K, fn: Observer<S[K]>, subPath?: string): IDisposable {
        return this.addObserverAt(
            keyObserver(key, fn),
            `${key}.` + (subPath ?? '')
        )
    }

    preObserveKey<K extends keyof S>(key: K, fn: PreObserver<S[K]>, subPath?: string): IDisposable {
        return this.addPreObserverAt(
            keyPreObserver(key, fn),
            `${key}.` + (subPath ?? '')
        )
    }

    view<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateView<S[K]> {
        return new KeyLens(this, key, combineFilters(this.sourceFilter, sourceFilter))
    }

    viewOpt<K extends keyof S>(key : K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>> {
        return new OptionalKeyLens<S, K>(this as any as OptionalStateHandler<S>, key, combineFilters(this.sourceFilter, sourceFilter))
    }

    lens<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateHandler<S[K]> {
        return new KeyLens(this, key, combineFilters(this.sourceFilter, sourceFilter))
    }

    lensOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>> {
        return new OptionalKeyLens(this as any as OptionalStateHandler<S>, key, combineFilters(this.sourceFilter, sourceFilter))
    }

    filterSource(filter: (source: any) => boolean): StateHandler<S> {
        return new BaseHandler(this, combineFilters(this.sourceFilter, filter))
    }

    fork(disposeContext?: IDisposable): StateHandler<S> {
        return new BaseHandler(this);
    }

    get observerCount(): number { return this.observers.length + this.preObservers.length }
}

export class BaseHandler<S> extends Disposable implements StateHandler<S> {
    constructor(protected parent: StateHandler<S>, private readonly sourceFilter?: (source: any) => boolean) {
        super();
        this.disposeWith(parent);
    }

    get state(): S { return this.parent.state }

    addObserver(fn: Observer<S>, path?: string): IDisposable {
        return this.parent.addObserver(filterObserver(fn, this.sourceFilter), path).disposeWith(this);
    }

    addPreObserver(fn: PreObserver<S>, path?: string): IDisposable {
        return this.parent.addPreObserver(filterPreObserver(fn, this.sourceFilter), path).disposeWith(this);
    }

    lens<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateHandler<S[K]> {
        return this.parent.lens(key, combineFilters(this.sourceFilter, sourceFilter)).disposeWith(this);
    }

    lensOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>> {
        return this.parent.lensOpt(key, combineFilters(this.sourceFilter, sourceFilter)).disposeWith(this);
    }

    observeKey<K extends keyof S>(key: K, fn: Observer<S[K]>, subPath?: string): IDisposable {
        return this.parent.observeKey(key, filterObserver(fn, this.sourceFilter), subPath).disposeWith(this);
    }

    preObserveKey<K extends keyof S>(key: K, fn: PreObserver<S[K]>, subPath?: string): IDisposable {
        return this.parent.preObserveKey(key, filterPreObserver(fn, this.sourceFilter), subPath).disposeWith(this);
    }

    update(updateFn: Updater<S>, updateSource?: any, updatePath?: string): void {
        return this.parent.update(updateFn, updateSource, updatePath)
    }

    updateAsync(updateFn: Updater<S>, updateSource?: any, updatePath?: string): Promise<Readonly<S>> {
        return this.parent.updateAsync(updateFn, updateSource, updatePath);
    }

    updateField<K extends keyof S>(key: K, updateFn: Updater<S[K]>, updateSource?: any, updateSubpath?: string): void {
        return this.parent.updateField(key, updateFn, updateSource, updateSubpath)
    }

    view<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateView<S[K]> {
        return this.parent.view(key, combineFilters(this.sourceFilter, sourceFilter)).disposeWith(this);
    }

    viewOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>> {
        return this.parent.viewOpt(key, combineFilters(this.sourceFilter, sourceFilter)).disposeWith(this);
    }

    filterSource(filter: (source: any) => boolean): StateHandler<S> {
        return new BaseHandler(this.parent, combineFilters(this.sourceFilter, filter)).disposeWith(this);
    }

    fork(disposeContext?: IDisposable): StateHandler<S> {
        const fork = new BaseHandler(this.parent, this.sourceFilter).disposeWith(this);
        return disposeContext ? fork.disposeWith(disposeContext) : fork;
    }
}

class KeyLens<S, K extends keyof S> extends Disposable implements StateHandler<S[K]> {
    constructor(private parent: StateHandler<S>, private key: K, private sourceFilter?: (source: any) => boolean) {
        super();
        this.disposeWith(parent);
    }

    get state(): S[K] {
        return this.parent.state[this.key];
    }

    addObserver(fn: Observer<S[K]>, path?: string): IDisposable {
        return this.parent.observeKey(this.key, filterObserver(fn, this.sourceFilter), path).disposeWith(this);
    }

    addPreObserver(fn: PreObserver<S[K]>, path?: string): IDisposable {
        return this.parent.preObserveKey(this.key, filterPreObserver(fn, this.sourceFilter), path).disposeWith(this);
    }

    observeKey<K1 extends keyof S[K]>(key: K1, fn: Observer<S[K][K1]>, subPath?: string): IDisposable {
        return this.parent.observeKey(this.key, keyObserver(key, fn, this.sourceFilter), `${key}.` + (subPath ?? '')).disposeWith(this);
    }

    preObserveKey<K1 extends keyof S[K]>(key: K1, fn: PreObserver<S[K][K1]>, subPath?: string): IDisposable {
        return this.parent.preObserveKey(this.key, keyPreObserver(key, fn, this.sourceFilter), `.${key}.` + (subPath ?? '')).disposeWith(this);
    }

    view<K1 extends keyof S[K]>(key: K1, sourceFilter?: (source: any) => boolean): StateView<S[K][K1]> {
        return new KeyLens<S[K], K1>(this, key, combineFilters(this.sourceFilter, sourceFilter)).disposeWith(this)
    }

    viewOpt<K1 extends keyof S[K]>(key: K1, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K][K1], undefined>> {
        return new OptionalKeyLens(this as any as OptionalStateHandler<S[K]>, key, combineFilters(this.sourceFilter, sourceFilter)).disposeWith(this)
    }

    lens<K1 extends keyof S[K]>(key: K1, sourceFilter?: (source: any) => boolean): StateHandler<S[K][K1]> {
        return new KeyLens(this, key, combineFilters(this.sourceFilter, sourceFilter)).disposeWith(this);
    }

    lensOpt<K1 extends keyof S[K]>(key: K1, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K][K1], undefined>> {
        return new OptionalKeyLens(this as any as OptionalStateHandler<S[K]>, key, combineFilters(this.sourceFilter, sourceFilter)).disposeWith(this)
    }

    update(updateFn: Updater<S[K]>, updateSource?: any, updatePath?: string) {
        return this.parent.updateField(this.key, updateFn, updateSource, updatePath);
    }

    updateAsync(updateFn: Updater<S[K]>, updateSource?: any, updatePath?: string): Promise<Readonly<S[K]>> {
        return this.parent.updateAsync(keyUpdater(this.key, updateFn), updateSource, `${this.key}.` + (updatePath ?? '')).then(
            s => s[this.key]
        )
    }

    updateField<K1 extends keyof S[K]>(key: K1, updateFn: Updater<S[K][K1]>, updateSource?: any, updateSubPath?: string) {
        return this.parent.updateField(this.key, keyUpdater(key, updateFn), updateSource, `${key}.` + (updateSubPath ?? ''))
    }

    filterSource(filter: (source: any) => boolean): StateHandler<S[K]> {
        return new KeyLens(this.parent, this.key, combineFilters(this.sourceFilter, filter)).disposeWith(this);
    }

    fork(disposeContext?: IDisposable): StateHandler<S[K]> {
        const fork = new KeyLens(this.parent, this.key, this.sourceFilter).disposeWith(this);
        return disposeContext ? fork.disposeWith(disposeContext) : fork;
    }
}

class OptionalKeyLens<S, K extends keyof S, V extends Exclude<S[K], undefined> = Exclude<S[K], undefined>> extends Disposable implements OptionalStateHandler<V> {
    constructor(private parent: OptionalStateHandler<S>, private key: K, private sourceFilter?: (source: any) => boolean) {
        super();
        this.disposeWith(parent);
    }

    get state(): V | undefined {
        return this.parent.state !== undefined ? this.parent.state[this.key] as V : undefined;
    }

    addObserver(fn: Observer<V | undefined>, path?: string): IDisposable {
        return (this.parent as UpdatableState<S>).addObserver(
            filterObserver(
                (parentValue, updateResult, updateSource) => {
                    if (parentValue !== undefined) {
                        fn(
                            parentValue[this.key] as V,
                            childResult<S, K, V>(updateResult, this.key),
                            updateSource);
                    } else {
                        fn(undefined, {update: Destroy.Instance, newValue: undefined}, updateSource);
                    }
                },
                this.sourceFilter
            ), `${this.key}.` + (path ?? '')).disposeWith(this);
    }

    addPreObserver(fn: PreObserver<V | undefined>, path?: string): IDisposable {
        return (this.parent as UpdatableState<S>).addPreObserver(
            filterPreObserver(
                pre => {
                    const obs = fn((pre?.[this.key]) as V | undefined)
                    return (parentValue, updateResult, updateSource) => {
                        if (parentValue !== undefined) {
                            obs(
                                parentValue[this.key] as V,
                                childResult<S, K, V>(updateResult, this.key),
                                updateSource);
                        } else {
                            obs(undefined, setToUndefined, updateSource);
                        }
                    }
                },
                this.sourceFilter
            ), `${this.key}.` + (path ?? '')).disposeWith(this);
    }

    observeKey<K1 extends keyof V>(childKey: K1, fn: Observer<V[K1] | undefined>, subPath?: string): IDisposable {
        return this.parent.addObserver(
            filterObserver(
                (parentValue, parentResult, updateSource) => {
                    if (parentValue !== undefined) {
                        const value: V = parentValue[this.key] as V;
                        const result: UpdateResult<V> = childResult<S, K, V>(parentResult as UpdateResult<S>, this.key)
                        if (value !== undefined) {
                            const childValue: V[K1] = value[childKey];
                            const childUpdateResult = childResult(result, childKey);
                            fn(childValue, childUpdateResult, updateSource);
                        } else {
                            fn(undefined, setToUndefined, updateSource);
                        }
                    } else {
                        fn(undefined, setToUndefined, updateSource);
                    }
                },
                this.sourceFilter
            ), `${this.key}.${childKey}` + (subPath ?? '')).disposeWith(this)
    }

    preObserveKey<K1 extends keyof V>(childKey: K1, fn: PreObserver<V[K1] | undefined>, subPath?: string): IDisposable {
        return this.parent.addPreObserver(
            filterPreObserver(
                prev => {
                    const obs = fn((prev?.[this.key] as V | undefined)?.[childKey]);
                    return (parentValue, parentResult, updateSource) => {
                        if (parentValue !== undefined) {
                            const value: V = parentValue[this.key] as V;
                            const result: UpdateResult<V> = childResult<S, K, V>(parentResult as UpdateResult<S>, this.key)
                            if (value !== undefined) {
                                const childValue: V[K1] = value[childKey]
                                const childUpdateResult = childResult<V, K1>(result, childKey);
                                obs(childValue, childUpdateResult, updateSource);
                            } else {
                                obs(undefined, setToUndefined, updateSource);
                            }
                        } else {
                            obs(undefined, setToUndefined, updateSource);
                        }
                    }
                },
                this.sourceFilter
            ), `${this.key}.${childKey}` + (subPath ?? '')).disposeWith(this)
    }

    view<K1 extends keyof V>(key: K1): OptionalStateView<Exclude<V[K1], undefined>> {
        return new OptionalKeyLens<V, K1>(this, key).disposeWith(this);
    }

    viewOpt<K1 extends keyof V>(key: K1): OptionalStateView<Exclude<V[K1], undefined>> {
        return new OptionalKeyLens<V, K1>(this, key).disposeWith(this);
    }


    lens<K1 extends keyof V>(key: K1): OptionalStateHandler<Exclude<V[K1], undefined>> {
        return new OptionalKeyLens<V, K1>(this, key).disposeWith(this);
    }

    lensOpt<K1 extends keyof V>(key: K1): OptionalStateHandler<Exclude<V[K1], undefined>> {
        return new OptionalKeyLens<V, K1>(this, key).disposeWith(this);
    }

    update(updateFn: Updater<V | undefined>, updateSource?: any, updatePath?: string): void {
        return this.parent.updateField(this.key, updateFn, updateSource, updatePath);
    }

    updateAsync(updateFn: Updater<V | undefined>, updateSource?: any, updatePath?: string): Promise<Readonly<V | undefined>> {
        return this.parent.updateAsync(keyUpdater(this.key, updateFn as Updater<S[K]>), updateSource, `${this.key}.` + (updatePath ?? '')).then(
            maybeS => maybeS !== undefined ? maybeS[this.key] as V | undefined : undefined
        )
    }

    updateField<K1 extends keyof V>(childKey: K1, updateFn: Updater<V[K1] | undefined>, updateSource?: any, updateSubPath?: string): void {
        return this.parent.updateField(this.key, keyUpdater<V, K1>(childKey, updateFn as Updater<V[K1]>), updateSource, `${childKey}.` + (updateSubPath ?? ''))
    }

    filterSource(filter: (source: any) => boolean): OptionalStateHandler<V> {
        return new OptionalKeyLens<S, K, V>(this.parent, this.key, combineFilters(this.sourceFilter, filter)).disposeWith(this);
    }

    fork(disposeContext?: IDisposable): OptionalStateHandler<V> {
        const fork = new OptionalKeyLens<S, K, V>(this.parent, this.key, this.sourceFilter).disposeWith(this);
        return disposeContext ? fork.disposeWith(disposeContext) : fork;
    }
}
