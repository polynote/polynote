import {collect, Deferred} from "../util/helpers";
import {Disposable, IDisposable, ImmediateDisposable} from "./disposable";
import {
    Destroy, isUpdateLike,
    NoUpdate,
    SetValue,
    StateUpdate,
    UpdateKey,
    UpdateLike,
    UpdateOf,
    valueToUpdate
} from ".";
import {__getProxyTarget, __readOnlyProxyObject} from "./readonly";

export type UpdateObserver<S> = (value: S, update: UpdateLike<S>, updateSource: any) => void
export type PreObserver<S> = (value: S) => UpdateObserver<S>

/**
 * Types for describing state updates
 */

// A boolean that can only be set to true
export interface Latch {
    triggered: boolean
    trigger(): void
    down(): Latch
}

// A private implementation of Latch that can also be reset
class LatchImpl {
    private _triggered: boolean = false
    private _down?: LatchImpl

    constructor(private parent?: LatchImpl) {}

    get triggered(): boolean { return this._triggered }
    trigger(): void {
        if (this.parent) {
            this.parent.trigger();
        }
        this._triggered = true;
    }
    reset(): void {
        this._triggered = false;
    }
    down() {
        if (!this._down) {
            this._down = new LatchImpl(this);
        } else {
            this._down.reset();
        }
        return this._down;
    }
}

// Partial<V[]> confuses TypeScript – TS thinks it has iterators and array methods and such.
// This is to avoid that.
export type Partial1<S> = S extends (infer V)[] ? Record<number, V> : Partial<S>

export interface ObservableState<S> extends IDisposable {
    state: S
    addObserver(fn: UpdateObserver<S>, path?: string): IDisposable
    addPreObserver(fn: PreObserver<S>, path?: string): IDisposable
}

export interface StateView<S> extends ObservableState<S> {
    view<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateView<S[K]>
    viewOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>>
    observeKey<K extends keyof S>(key: K, fn: UpdateObserver<S[K]>, subPath?: string): IDisposable
    preObserveKey<K extends keyof S>(key: K, fn: PreObserver<S[K]>, subPath?: string): IDisposable
    filterSource(filter: (source: any) => boolean): StateView<S>
    fork(disposeContext?: IDisposable): StateView<S>
}

export interface OptionalStateView<S> extends ObservableState<S | undefined> {
    view<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>>
    viewOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>>
    observeKey<K extends keyof S>(key: K, fn: UpdateObserver<S[K] | undefined>, subPath?: string): IDisposable
    preObserveKey<K extends keyof S>(key: K, fn: PreObserver<S[K] | undefined>, subPath?: string): IDisposable
    filterSource(filter: (source: any) => boolean): OptionalStateView<S>
    fork(disposeContext?: IDisposable): OptionalStateView<S>
}

export interface UpdatableState<S> extends ObservableState<S> {
    submitUpdate(update: StateUpdate<S>, updateSource?: any, updatePath?: string): void
    update(updates: UpdateOf<S>, updateSource?: any, updatePath?: string): void
    updateAsync(updates: UpdateOf<S>, updateSource?: any, updatePath?: string): Promise<Readonly<S>>
}

export interface StateHandler<S> extends StateView<S>, UpdatableState<S> {
    lens<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateHandler<S[K]>
    lensOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>>
    updateField<K extends keyof S>(key: K, update: UpdateOf<S[K]>, updateSource?: any, updateSubpath?: string): void
    filterSource(filter: (source: any) => boolean): StateHandler<S>
    fork(disposeContext?: IDisposable): StateHandler<S>
}

export const StateHandler = {
    from<T>(state: T): StateHandler<T> { return new ObjectStateHandler(state) }
}

export interface OptionalStateHandler<S> extends OptionalStateView<S>, UpdatableState<S | undefined> {
    lens<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>>
    lensOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>>
    updateField<K extends keyof S>(key: K, update: UpdateOf<S[K] | undefined>, updateSource?: any, updateSubpath?: string): void
    filterSource(filter: (source: any) => boolean): OptionalStateHandler<S>
    fork(disposeContext?: IDisposable): OptionalStateHandler<S>
}

function filterObserver<S>(fn: UpdateObserver<S>, filter?: (src: any) => boolean): UpdateObserver<S> {
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

function keyObserver<S, K extends keyof S, V extends S[K] = S[K]>(key: K, fn: UpdateObserver<V>, filter?: (src: any) => boolean): UpdateObserver<S> {
    return (value, update, updateSource) => {
        const down = update.down<K, V>(key, value as S);
        if (down !== NoUpdate) {
            if (!filter || filter(updateSource)) {
                fn(value[key as keyof S] as V, down, updateSource)
            }
        }
    }
}

function keyPreObserver<S, K extends keyof S, V extends S[K] = S[K]>(key: K, fn: PreObserver<V>, filter?: (src: any) => boolean): PreObserver<S> {
    return preS => {
        const obs = fn(preS[key as keyof S] as V);
        return (value, update, updateSource) => {
            const down = update.down<K, V>(key, value as S);
            if (down !== NoUpdate) {
                if (!filter || filter(updateSource)) {
                    obs(value[key] as V, down, updateSource)
                }
            }
        }
    }
}

function combineFilters(first?: (source: any) => boolean, second?: (source: any) => boolean): ((source: any) => boolean) | undefined {
    if (first && second) {
        return source => first(source) && second(source)
    }
    return first || second
}

export class ObjectStateHandler<S extends Object> extends Disposable implements StateHandler<S> {

    // internal, mutable state
    private mutableState: S;

    // private read-only view of state
    private readOnlyState: S;

    // observers receive the updated state, the update, and the update source and perform side-effects
    private observers: [UpdateObserver<S>, string][] = [];

    // pre-observers receive the previous state; and return function that receive the updated state, the update, and
    // the update source, which performs side-effects. This is useful if an observer needs to copy any values from the
    // previous state, or decide what to based on the previous state.
    private preObservers: [PreObserver<S>, string][] = [];

    // public, read-only view of state
    get state(): Readonly<S> { return this.readOnlyState }

    constructor(state: S, readonly path: string = "", readonly sourceFilter: (source?: any) => boolean = () => true) {
        super();
        this.mutableState = state;
        this.readOnlyState = __readOnlyProxyObject(state);
        this.onDispose.then(() => {
            this.observers = [];
            this.preObservers = [];
        })
    }

    private isUpdating: boolean = false;
    private updateQueue: [StateUpdate<S>, any, string, Deferred<Readonly<S>> | undefined][] = [];
    private updateLatch: LatchImpl = new LatchImpl();

    protected handleUpdate(update: StateUpdate<S>, updateSource: any, updatePath: string, promise?: Deferred<Readonly<S>>): void {
        if (update === NoUpdate) {
            if (promise) {
                promise.resolve(this.state)
            }
            return;
        }

        function pathMatches(path: string): boolean {
            return path.startsWith(updatePath) || updatePath.startsWith(path)
        }

        const preObservers = collect(this.preObservers, ([obs, path]) => pathMatches(path) ? obs(this.state) : undefined);

        this.updateLatch.reset();

        const updatedObj = __getProxyTarget(update.forValue(this.mutableState).applyMutate(this.mutableState, this.updateLatch));
        if (!Object.is(this.mutableState, updatedObj)) {
            this.mutableState = updatedObj
            this.readOnlyState = __readOnlyProxyObject(this.mutableState);
        }

        if (this.updateLatch.triggered) {
            if (this.sourceFilter(updateSource)) {
                const src = updateSource ?? this;
                preObservers.forEach(observer => observer(this.state, update, src));
                this.observers.forEach(([observer, path]) => {
                    if (pathMatches(path)) {
                        observer(this.state, update, src);
                    }
                })
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

    submitUpdate(update: StateUpdate<S>, updateSource?: any, updatePath?: string): void {
        if (update === NoUpdate) {
            return;
        }

        this.updateQueue.push([update, updateSource, updatePath ?? this.path, undefined]);
        this.runUpdates()
    }

    update(updates: UpdateOf<S>, updateSource?: any, updatePath?: string): void {
        return this.submitUpdate(valueToUpdate(updates), updateSource, updatePath);
    }

    updateAsync(updates: UpdateOf<S>, updateSource?: any, updatePath?: string): Promise<Readonly<S>> {
        const promise = new Deferred<Readonly<S>>();
        this.updateQueue.push([valueToUpdate(updates), updateSource, updatePath ?? this.path, promise]);
        this.runUpdates()
        return promise;
    }

    updateField<K extends keyof S>(key: K, update: UpdateOf<S[K]>, updateSource?: any, updateSubPath?: string): void {
        if (isUpdateLike(update)) {
            this.submitUpdate(new UpdateKey(key, valueToUpdate(update)), updateSource, `${key}.` + (updateSubPath ?? ''))
        } else {
            this.update({[key]: update} as UpdateOf<S>, updateSource, `${key}.` + (updateSubPath ?? ''))
        }
    }

    private addObserverAt(fn: UpdateObserver<S>, path: string): IDisposable {
        const observer: [UpdateObserver<S>, string] = [fn, path];
        this.observers.push(observer);

        return new ImmediateDisposable(() => {
            const idx = this.observers.indexOf(observer);
            if (idx >= 0) {
                this.observers.splice(idx, 1);
            }
        })
    }

    private addPreObserverAt(fn: PreObserver<S>, path: string): IDisposable {
        const observer: [PreObserver<S>, string] = [fn, path];
        this.preObservers.push(observer);
        return new ImmediateDisposable(() => {
            const idx = this.preObservers.indexOf(observer);
            if (idx >= 0) {
                this.preObservers.splice(idx, 1)
            }
        })
    }

    addObserver(fn: UpdateObserver<S>, path?: string): IDisposable {
        return this.addObserverAt(fn, path ?? '');
    }

    addPreObserver(fn: PreObserver<S>, path?: string): IDisposable {
        return this.addPreObserverAt(fn, path ?? '');
    }

    observeKey<K extends keyof S>(key: K, fn: UpdateObserver<S[K]>, subPath?: string): IDisposable {
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

    addObserver(fn: UpdateObserver<S>, path?: string): IDisposable {
        return this.parent.addObserver(filterObserver(fn, this.sourceFilter), path).disposeWith(this);
    }

    addPreObserver(fn: PreObserver<S>, path?: string): IDisposable {
        return this.parent.addPreObserver(filterPreObserver(fn, this.sourceFilter), path).disposeWith(this);
    }

    lens<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateHandler<S[K]> {
        return this.parent.lens(key, combineFilters(this.sourceFilter, sourceFilter));
    }

    lensOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K], undefined>> {
        return this.parent.lensOpt(key, combineFilters(this.sourceFilter, sourceFilter));
    }

    observeKey<K extends keyof S>(key: K, fn: UpdateObserver<S[K]>, subPath?: string): IDisposable {
        return this.parent.observeKey(key, filterObserver(fn, this.sourceFilter), subPath).disposeWith(this);
    }

    preObserveKey<K extends keyof S>(key: K, fn: PreObserver<S[K]>, subPath?: string): IDisposable {
        return this.parent.preObserveKey(key, filterPreObserver(fn, this.sourceFilter), subPath).disposeWith(this);
    }

    submitUpdate(update: StateUpdate<S>, updateSource?: any, updatePath?: string): void {
        return this.parent.submitUpdate(update, updateSource, updatePath)
    }

    update(updates: UpdateOf<S>, updateSource?: any, updatePath?: string): void {
        return this.parent.update(updates, updateSource, updatePath)
    }

    updateAsync(updates: UpdateOf<S>, updateSource?: any, updatePath?: string): Promise<Readonly<S>> {
        return this.parent.updateAsync(updates, updateSource, updatePath);
    }

    updateField<K extends keyof S>(key: K, update: UpdateOf<S[K]>, updateSource?: any, updateSubpath?: string): void {
        return this.parent.updateField(key, update, updateSource, updateSubpath)
    }

    view<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): StateView<S[K]> {
        return this.parent.view(key, combineFilters(this.sourceFilter, sourceFilter));
    }

    viewOpt<K extends keyof S>(key: K, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K], undefined>> {
        return this.parent.viewOpt(key, combineFilters(this.sourceFilter, sourceFilter));
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

    addObserver(fn: UpdateObserver<S[K]>, path?: string): IDisposable {
        return this.parent.observeKey(this.key, filterObserver(fn, this.sourceFilter), path).disposeWith(this);
    }

    addPreObserver(fn: PreObserver<S[K]>, path?: string): IDisposable {
        return this.parent.preObserveKey(this.key, filterPreObserver(fn, this.sourceFilter), path).disposeWith(this);
    }

    observeKey<K1 extends keyof S[K]>(key: K1, fn: UpdateObserver<S[K][K1]>, subPath?: string): IDisposable {
        return this.parent.observeKey(this.key, keyObserver(key, fn, this.sourceFilter), `${key}.` + (subPath ?? '')).disposeWith(this);
    }

    preObserveKey<K1 extends keyof S[K]>(key: K1, fn: PreObserver<S[K][K1]>, subPath?: string): IDisposable {
        return this.parent.preObserveKey(this.key, keyPreObserver(key, fn, this.sourceFilter), `${key}.` + (subPath ?? '')).disposeWith(this);
    }

    view<K1 extends keyof S[K]>(key: K1, sourceFilter?: (source: any) => boolean): StateView<S[K][K1]> {
        return new KeyLens<S[K], K1>(this, key, combineFilters(this.sourceFilter, sourceFilter))
    }

    viewOpt<K1 extends keyof S[K]>(key: K1, sourceFilter?: (source: any) => boolean): OptionalStateView<Exclude<S[K][K1], undefined>> {
        return new OptionalKeyLens(this as any as OptionalStateHandler<S[K]>, key, combineFilters(this.sourceFilter, sourceFilter))
    }

    lens<K1 extends keyof S[K]>(key: K1, sourceFilter?: (source: any) => boolean): StateHandler<S[K][K1]> {
        return new KeyLens(this, key, combineFilters(this.sourceFilter, sourceFilter));
    }

    lensOpt<K1 extends keyof S[K]>(key: K1, sourceFilter?: (source: any) => boolean): OptionalStateHandler<Exclude<S[K][K1], undefined>> {
        return new OptionalKeyLens(this as any as OptionalStateHandler<S[K]>, key, combineFilters(this.sourceFilter, sourceFilter))
    }

    submitUpdate(update: StateUpdate<S[K]>, updateSource?: any, updatePath?: string): void {
        return this.parent.updateField(this.key, update as any as UpdateLike<S[K]>, updateSource, updatePath);
    }

    update(updates: UpdateOf<S[K]>, updateSource?: any, updatePath?: string) {
        return this.parent.update({[this.key]: updates} as UpdateOf<S>)
    }

    updateAsync(updates: UpdateOf<S[K]>, updateSource?: any, updatePath?: string): Promise<Readonly<S[K]>> {
        return this.parent.updateAsync({[this.key]: updates} as UpdateOf<S>, updateSource, `${this.key}.` + (updatePath ?? '')).then(
            s => s[this.key]
        )
    }

    updateField<K1 extends keyof S[K]>(key: K1, update: UpdateOf<S[K][K1]>, updateSource?: any, updateSubPath?: string) {
        return this.parent.updateField(this.key, new UpdateKey(key, valueToUpdate(update)), updateSource, `${key}.` + (updateSubPath ?? ''))
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

    addObserver(fn: UpdateObserver<V | undefined>, path?: string): IDisposable {
        return (this.parent as UpdatableState<S>).addObserver(
            filterObserver(
                (parentValue, update, updateSource) => {
                    if (parentValue !== undefined) {
                        fn(parentValue[this.key] as V, update.down<K, V>(this.key, parentValue as S) as UpdateLike<V | undefined>, updateSource);
                    } else {
                        fn(undefined, Destroy.Instance as UpdateLike<V | undefined>, updateSource);
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
                    return (parentValue, update, updateSource) => {
                        if (parentValue !== undefined) {
                            obs(parentValue[this.key] as V, update.down<K, V>(this.key, parentValue as S) as UpdateLike<V | undefined>, updateSource);
                        } else {
                            obs(undefined, Destroy.Instance as UpdateLike<V | undefined>, updateSource);
                        }
                    }
                },
                this.sourceFilter
            ), `${this.key}.` + (path ?? '')).disposeWith(this);
    }

    observeKey<K1 extends keyof V>(childKey: K1, fn: UpdateObserver<V[K1] | undefined>, subPath?: string): IDisposable {
        return this.parent.addObserver(
            filterObserver(
                (parentValue, parentUpdate, updateSource) => {
                    if (parentValue !== undefined) {
                        const value: V = parentValue[this.key] as V;
                        const update: UpdateLike<V> = (parentUpdate as UpdateLike<S>).down<K, V>(this.key, parentValue as S)
                        if (value !== undefined) {
                            const childValue: V[K1] = value[childKey]
                            const childUpdate = update.down(childKey, value)
                            fn(childValue, childUpdate as UpdateLike<V[K1] | undefined>, updateSource);
                        } else {
                            fn(undefined, new SetValue<V[K1] | undefined>(undefined), updateSource);
                        }
                    } else {
                        fn(undefined, new SetValue<V[K1] | undefined>(undefined), updateSource);
                    }
                },
                this.sourceFilter
            ), `${this.key}.` + (subPath ?? '')).disposeWith(this)
    }

    preObserveKey<K1 extends keyof V>(childKey: K1, fn: PreObserver<V[K1] | undefined>, subPath?: string): IDisposable {
        return this.parent.addPreObserver(
            filterPreObserver(
                prev => {
                    const obs = fn((prev?.[this.key] as V | undefined)?.[childKey]);
                    return (parentValue, parentUpdate, updateSource) => {
                        if (parentValue !== undefined) {
                            const value: V = parentValue[this.key] as V;
                            const update: UpdateLike<V> = (parentUpdate as UpdateLike<S>).down<K, V>(this.key, parentValue as S)
                            if (value !== undefined) {
                                const childValue: V[K1] = value[childKey]
                                const childUpdate = update.down(childKey, value)
                                obs(childValue, childUpdate as UpdateLike<V[K1] | undefined>, updateSource);
                            } else {
                                obs(undefined, new SetValue<V[K1] | undefined>(undefined), updateSource);
                            }
                        } else {
                            obs(undefined, new SetValue<V[K1] | undefined>(undefined), updateSource);
                        }
                    }
                },
                this.sourceFilter
            ), `${this.key}.` + (subPath ?? '')).disposeWith(this)
    }

    view<K1 extends keyof V>(key: K1): OptionalStateView<Exclude<V[K1], undefined>> {
        return new OptionalKeyLens<V, K1>(
            this,
            key);
    }

    viewOpt<K1 extends keyof V>(key: K1): OptionalStateView<Exclude<V[K1], undefined>> {
        return new OptionalKeyLens<V, K1>(this, key);
    }


    lens<K1 extends keyof V>(key: K1): OptionalStateHandler<Exclude<V[K1], undefined>> {
        return new OptionalKeyLens<V, K1>(
            this,
            key);
    }

    lensOpt<K1 extends keyof V>(key: K1): OptionalStateHandler<Exclude<V[K1], undefined>> {
        return new OptionalKeyLens<V, K1>(
            this,
            key);
    }

    submitUpdate(update: StateUpdate<V | undefined>, updateSource?: any, updatePath?: string): void {
        return this.parent.updateField<K>(
            this.key,
            // @ts-ignore
            update,
            updateSource,
            updatePath)
    }

    update(updates: UpdateOf<V | undefined>, updateSource?: any, updatePath?: string): void {
        return this.parent.update({[this.key]: updates} as UpdateOf<S>, `${this.key}.` + (updatePath ?? ''));
    }

    updateAsync(updates: UpdateOf<V | undefined>, updateSource?: any, updatePath?: string): Promise<Readonly<V | undefined>> {
        return this.parent.updateAsync({[this.key]: updates} as UpdateOf<S | undefined>, updateSource, `${this.key}.` + (updatePath ?? '')).then(
            maybeS => maybeS !== undefined ? maybeS[this.key] as V | undefined : undefined
        )
    }

    updateField<K1 extends keyof V>(childKey: K1, update: StateUpdate<V[K1] | undefined>, updateSource?: any, updateSubPath?: string): void {
        return this.parent.updateField<K>(
            this.key,
            // @ts-ignore
            new UpdateKey<V, K1>(childKey, update as StateUpdate<V[K1]>),
            updateSource,
            `${childKey}.` + (updateSubPath ?? ''))
    }

    filterSource(filter: (source: any) => boolean): OptionalStateHandler<V> {
        return new OptionalKeyLens<S, K, V>(this.parent, this.key, combineFilters(this.sourceFilter, filter)).disposeWith(this);
    }

    fork(disposeContext?: IDisposable): OptionalStateHandler<V> {
        const fork = new OptionalKeyLens<S, K, V>(this.parent, this.key, this.sourceFilter).disposeWith(this);
        return disposeContext ? fork.disposeWith(disposeContext) : fork;
    }
}
