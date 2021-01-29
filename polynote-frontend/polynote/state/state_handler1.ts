import {deepCopy, partition} from "../util/helpers";
import {Disposable, IDisposable, mkDisposable} from "./state_handler";
import {ContentEdit} from "../data/content_edit";

export type UpdateObserver<S> = (value: S, update: UpdateLike<S>, updateSource: any) => void
type DisposableObserver<S> = UpdateObserver<S> & IDisposable

/**
 * Types for describing state updates
 */

// A boolean that can only be set to true
export interface Latch {
    triggered: boolean
    trigger(): void
}

class LatchImpl {
    private _triggered: boolean = false
    get triggered(): boolean { return this._triggered }
    trigger(): void {
        this._triggered = true;
    }
    reset(): void {
        this._triggered = false;
    }
}

export interface UpdateLike<S> {
    // Sentinel field so we can distinguish an update from a value
    isStateUpdate: true

    // Return a new (deep) copy of the given value with the update applied
    apply(prev: S): S

    // Apply the update to the given value, mutating in-place if possible. Return the updated value, and indicate using
    // the given latch whether a change was made.
    applyMutate(value: S, latch: Latch): S

    // A list of keys that this update will remove from an object (if applicable)
    removedKeys: (keyof S)[]

    // A list of keys that this update will add or change in an object (if applicable)
    addedKeys: (keyof S)[]

    // Given a key of S, return an update that corresponds to the change this update would make to that key (or NoUpdate that key is unaffected)
    down<K extends keyof S, V extends S[K] = S[K]>(key: K, of: S): StateUpdate<V>

}

export type StateUpdate<S> = UpdateLike<S>;

const dummyLatch = new LatchImpl()
export abstract class Update<S> implements UpdateLike<S> {
    readonly isStateUpdate: true = true
    apply(prev: S): S {
        const newState = deepCopy(prev);
        this.applyMutate(newState, dummyLatch);
        return newState;
    }
    abstract applyMutate(value: S, latch: Latch): S
    abstract get removedKeys(): (keyof S)[]
    abstract get addedKeys(): (keyof S)[]
    abstract down<K extends keyof S, V extends S[K] = S[K]>(key: K, of: S): StateUpdate<V>
}

// implements empty added & removed & down, for convenience
abstract class PrimitiveUpdate<S> extends Update<S> {
    get removedKeys(): (keyof S)[] { return [] }
    get addedKeys(): (keyof S)[] { return [] }
    down<K extends keyof S, V extends S[K] = S[K]>(key: K, of: S): StateUpdate<V> {
        return NoUpdate
    }
}

export const NoUpdate = Object.freeze({
    isStateUpdate: true,
    apply(prev: any): any { return prev; },
    applyMutate(prev: any): any { return prev },
    removedKeys: [],
    addedKeys: [],
    down<K extends keyof any>(key: K, of: any) { return NoUpdate },
    pathMatches(listenerPath: string, basePath: string) { return false }
}) as UpdateLike<any>;

export class RemoveKey<S, K extends keyof S> extends Update<S> {
    constructor(readonly key: K) { super() }

    applyMutate(value: S): S {
        delete value[this.key];
        return value;
    }

    get removedKeys(): (keyof S)[] {
        return [this.key]
    }

    readonly addedKeys: (keyof S)[] = [];

    down<K1 extends keyof S, V extends S[K1] = S[K1]>(key: K1, of: S): StateUpdate<V> {
        if (key as any === this.key) {
            return destroy()
        }
        return NoUpdate
    }
}

export class UpdateKey<S, K extends keyof S> extends Update<S> {
    constructor(readonly key: K, readonly update: UpdateLike<S[K]>) { super() }

    applyMutate(value: S, latch: Latch): S {
        value[this.key] = this.update.applyMutate(value[this.key], latch);
        return value;
    }

    readonly removedKeys: (keyof S)[] = [];
    get addedKeys(): (keyof S)[] { return [this.key] }
    down<K1 extends keyof S, V extends S[K1] = S[K1]>(key: K1, of: S): StateUpdate<V> {
        if (key as any === this.key) {
            return this.update as any as StateUpdate<V>;
        }
        return NoUpdate;
    }

}

export class RemoveValue<V, S extends V[]> extends Update<S> {
    constructor(readonly value: V, readonly index: number) { super() }
    applyMutate(value: S, latch: Latch): S {
        if (value[this.index] !== this.value) {
            throw new Error("RemoveValue is no longer valid as array has changed");
        }
        latch.trigger();
        value.splice(this.index, 1);
        return value;
    }

    get removedKeys(): (keyof S)[] { return [this.index] }
    get addedKeys(): (keyof S)[] { return [] }
    down<K extends keyof S, V1 extends S[K] = S[K]>(key: K, of: S): StateUpdate<V1> {
        if (key === this.index) {
            return destroy()
        }
        return NoUpdate
    }
}

export class Append<V, S extends V[]> extends Update<S> {
    constructor(readonly value: V, readonly targetIndex: number) { super() }
    applyMutate(value: S, latch: Latch): S {
        if (value.length !== this.targetIndex) {
            throw new Error("Append is no longer valid as array has changed")
        }
        latch.trigger();
        value.push(this.value);
        return value;
    }

    get removedKeys(): (keyof S)[] { return [] };
    get addedKeys(): (keyof S)[] { return [] };

    down<K extends keyof S, V extends S[K] = S[K]>(key: K, of: S): StateUpdate<V> {
        if (key === this.targetIndex) {
            return new SetValue<V>(of[this.targetIndex] as V)
        }
        return NoUpdate;
    }
}

export class RenameKey<S, K0 extends keyof S, K1 extends keyof S, V extends S[K0] & S[K1]> extends Update<S> {
    constructor(readonly oldKey: K0, readonly newKey: K1) { super() }

    applyMutate(value: S, latch: Latch): S {
        value[this.newKey] = value[this.oldKey] as V;
        delete value[this.oldKey];
        latch.trigger();
        return value;
    }

    // TODO: this could just rename the view instead of disposing it
    get removedKeys(): (keyof S)[] {
        return [this.oldKey];
    }

    get addedKeys(): (keyof S)[] {
        return [this.newKey];
    }

    down<K1 extends keyof S, V extends S[K1] = S[K1]>(key: K1, of: S): StateUpdate<V> {
        if ((key as any) === this.oldKey) {
            return Destroy.Instance as StateUpdate<V>;
        } else if ((key as any) === this.newKey) {
            return new SetValue(of[key] as V);
        }
        return NoUpdate;
    }
}

export class Destroy<S> extends Update<S | undefined> {
    static Instance: Destroy<any> = new Destroy();
    applyMutate(value: S | undefined, latch: Latch): S | undefined {
        if (value !== undefined) {
            latch.trigger();
        }
        return undefined
    }

    get removedKeys(): never[] { return [] }
    get addedKeys(): never[] { return [] }

    // @ts-ignore
    down<K1 extends keyof (S | undefined), V extends S[K1] = S[K1]>(key: K1, of: S | undefined): StateUpdate<V> { return Destroy.Instance }
}

export class SetValue<S> extends Update<S> {
    constructor(readonly value: S) { super() }
    applyMutate(value: S, latch: Latch): S {
        if (value !== this.value) {
            latch.trigger();
        }
        return this.value
    }

    readonly removedKeys: (keyof S)[] = [];
    readonly addedKeys: (keyof S)[] = [];

    down<K1 extends keyof S, V extends S[K1] = S[K1]>(key: K1, of: S): StateUpdate<V> {
        return new SetValue(this.value[key] as V);
    }
}

export type UpdatePartial<T> = {
    [P in keyof T]?: UpdateOf<T[P]>
}

export type UpdateOf<T> = UpdateLike<T> | UpdatePartial<T>

function isUpdateLike(value: any): value is UpdateLike<any> {
    return value.isStateUpdate
}

export class UpdateWith<S> extends Update<S> {
    constructor(readonly fieldUpdates: UpdateOf<S>) {
        super()
        Object.freeze(fieldUpdates)
        if (typeof fieldUpdates === 'object') {
            const [removed, added] = partition(Object.keys(fieldUpdates), (k) => (fieldUpdates as any)[k] instanceof Destroy)
            this.removedKeys = removed as (keyof S)[];
            this.addedKeys = added as (keyof S)[];
        }
    }

    readonly addedKeys: (keyof S)[];
    readonly removedKeys: (keyof S)[];

    applyMutate(value: S, latch: Latch): S {
        function go(item: any, updates: UpdateOf<any>): any {
            if (typeof updates === 'object') {
                for (const prop in updates) {
                    if (updates.hasOwnProperty(prop)) {
                        const update = (updates as any)[prop];
                        if (isUpdateLike(update)) {
                            item[prop] = update.applyMutate(item[prop], latch);
                        } else {
                            item[prop] = go(item[prop], update);
                        }
                    }
                }
                return item;
            } else {
                if (item !== updates) {
                    latch.trigger();
                }
                return updates;
            }
        }
        return go(value, this.fieldUpdates);
    }

    down<K extends keyof S, V extends S[K] = S[K]>(key: K, of: S): StateUpdate<V> {
        if (key in this.fieldUpdates) {
            if (isUpdateLike((this.fieldUpdates as any)[key])) {
                return (this.fieldUpdates as any)[key] as StateUpdate<V>;
            } else {
                const update = (this.fieldUpdates as any)[key];
                if (typeof update === "object") {
                    return new UpdateWith(update as UpdateOf<V>)
                } else {
                    return new SetValue(update as V);
                }
            }
        }
        return NoUpdate;
    }

}

/**
 * Constructors for state updates
 */
export function removeKey<S, K extends keyof S>(key: K): StateUpdate<S> {
    return new RemoveKey<S, K>(key);
}

export function replaceKey<S, K0 extends keyof S, K1 extends keyof S, V extends S[K0] & S[K1]>(oldKey: K0, newKey: K1): StateUpdate<S> {
    return new RenameKey<S, K0, K1, V>(oldKey, newKey);
}

export function setValue<V, V1 extends V = V>(value: V1): StateUpdate<V> {
    return new SetValue(value as V)
}

export function setProperty<S, K extends keyof S>(key: K, value: S[K]): StateUpdate<S> {
    return new UpdateKey<S, K>(key, new SetValue(value))
}

export function destroy<V>(): StateUpdate<V> {
    return Destroy.Instance
}

export function append<V>(arr: V[], value: V): StateUpdate<V[]> {
    return new Append<V, V[]>(value, arr.length)
}

export function removeFromArray<V>(arr: V[], value: V): StateUpdate<V[]> {
    const idx = arr.indexOf(value);
    if (idx >= 0) {
        return new RemoveValue(value, idx);
    }
    return NoUpdate;
}

export function removeIndex<V>(arr: V[], index: number): StateUpdate<V[]> {
    if (arr.hasOwnProperty(index)) {
        return new RemoveValue(arr[index], index)
    }
    return NoUpdate
}

export function noUpdate<S>(): StateUpdate<S> {
    return NoUpdate;
}

//////////////////////////////////////
// Constructors for read-only views //
//////////////////////////////////////
type Refreshable<S> = { refreshStateView: (keys?: (keyof S)[]) => void }

/**
 * Read-only view of any value
 */
function readOnlyView<S>(value: S): S {
    switch (typeof value) {
        case "object": return readOnlyObject(value);
        default: return value;
    }
}

/**
 * Read-only view of an object
 */
function readOnlyObject<S>(obj: S): S & Refreshable<S> {
    const view: S = {} as S & Refreshable<S>;
    const props: PropertyDescriptorMap = {};
    const memberViews: S = {} as S;
    for (let key of Object.keys(obj)) {
        const prop: keyof S = key as keyof S;
        props[key] = {
            enumerable: true,
            get() {
                if (!(key in memberViews))
                    memberViews[prop] = readOnlyView(obj[prop]);
                return memberViews[prop];
            }
        };
    }
    Object.defineProperties(view, props);

    const refreshStateView = function (keys?: (keyof S)[]): void {
        const keysToUpdate = keys ?? Object.keys(obj);
        for (let key of keysToUpdate) {
            const prop = key as keyof S;
            delete memberViews[prop]
        }
    }

    Object.defineProperty(view, 'refreshStateView', {value: refreshStateView});
    Object.setPrototypeOf(view, Object.getPrototypeOf(obj));

    return view as S & Refreshable<S>;
}

export interface ObservableState<S> extends IDisposable {
    state: S
    addObserver(fn: UpdateObserver<S>, path?: string): IDisposable
}

export interface StateView<S> extends ObservableState<S> {
    view<K extends keyof S>(key: K): StateView<S[K]>
    viewOpt<K extends keyof S>(key: K): OptionalStateView<Exclude<S[K], undefined>>
    observeKey<K extends keyof S>(key: K, fn: UpdateObserver<S[K]>, subPath?: string): IDisposable
}

export interface OptionalStateView<S> extends ObservableState<S | undefined> {
    view<K extends keyof S>(key: K): OptionalStateView<Exclude<S[K], undefined>>
    viewOpt<K extends keyof S>(key: K): OptionalStateView<Exclude<S[K], undefined>>
    observeKey<K extends keyof S>(key: K, fn: UpdateObserver<S[K] | undefined>, subPath?: string): IDisposable
}

export interface UpdatableState<S> extends ObservableState<S> {
    submitUpdate(update: StateUpdate<S>, updateSource?: any, updatePath?: string): void
    update(updates: UpdateOf<S>, updateSource?: any, updatePath?: string): void
}

export interface StateHandler<S> extends StateView<S>, UpdatableState<S> {
    lens<K extends keyof S>(key: K): StateHandler<S[K]>
    lensOpt<K extends keyof S>(key: K): OptionalStateHandler<Exclude<S[K], undefined>>
    updateKey<K extends keyof S>(key: K, update: UpdateLike<S[K]>, updateSource?: any, updateSubpath?: string): void
}

export interface OptionalStateHandler<S> extends OptionalStateView<S>, UpdatableState<S | undefined> {
    lens<K extends keyof S>(key: K): OptionalStateHandler<Exclude<S[K], undefined>>
    lensOpt<K extends keyof S>(key: K): OptionalStateHandler<Exclude<S[K], undefined>>
    updateKey<K extends keyof S>(key: K, update: UpdateLike<S[K] | undefined>, updateSource?: any, updateSubpath?: string): void
}

function keyObserver<S, K extends keyof S, V extends S[K] = S[K]>(key: K, fn: UpdateObserver<V>): UpdateObserver<S> {
    return (value, update, updateSource) => {
        const down = update.down<K, V>(key, value);
        if (down !== NoUpdate) {
            fn(value[key] as V, down, updateSource)
        }
    }
}

export class ObjectStateHandler<S extends Object> extends Disposable implements StateHandler<S> {

    // internal, mutable state
    private readonly mutableState: S;

    // private, refreshable read-only view of state
    private readonly readOnlyState: S & Refreshable<S>;

    private observers: [DisposableObserver<S>, string][] = [];

    // public, read-only view of state
    get state(): S { return this.readOnlyState }

    constructor(state: S, readonly path: string = "", readonly matchSource: (source?: any) => boolean = () => true) {
        super();
        this.mutableState = state;
        this.readOnlyState = readOnlyObject(state);
        this.onDispose.then(() => {
            this.observers.forEach(([obs, _]) => obs.dispose());
            this.observers = [];
        })
    }

    private isUpdating: boolean = false;
    private updateQueue: [StateUpdate<S>, any, string][] = [];
    private updateLatch: LatchImpl = new LatchImpl();

    protected updateState(update: StateUpdate<S>, updateSource: any, updatePath: string): void {
        if (update === NoUpdate) {
            return;
        }

        this.updateQueue.push([update, updateSource, updatePath]);
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

    protected handleUpdate(update: StateUpdate<S>, updateSource: any, updatePath: string): void {
        if (update === NoUpdate) {
            return;
        }
        this.updateLatch.reset();
        update.applyMutate(this.mutableState, this.updateLatch);
        if (this.updateLatch.triggered) {
            this.readOnlyState.refreshStateView(update.addedKeys);
            this.readOnlyState.refreshStateView(update.removedKeys);

            if (this.matchSource(updateSource)) {
                const src = updateSource ?? this;
                this.observers.forEach(([observer, path]) => {
                    if (path.startsWith(updatePath)) {
                        observer(this.state, update, src);
                    }
                })
            }
        }
    }

    submitUpdate(update: StateUpdate<S>, updateSource?: any, updatePath?: string): void {
        this.updateState(update, updateSource ?? this, updatePath || this.path);
    }

    update(updates: UpdateOf<S>, updateSource?: any, updatePath?: string): void {
        return this.submitUpdate(new UpdateWith(updates))
    }

    updateKey<K extends keyof S>(key: K, update: StateUpdate<S[K]>, updateSource?: any, updateSubPath?: string): void {
        this.updateState(new UpdateKey(key, update), updateSource, `${key}.` + (updateSubPath || ''))
    }

    private addObserverAt(fn: UpdateObserver<S>, path: string): IDisposable {
        const fnDisposable = mkDisposable(fn).disposeWith(this);
        const observer: [DisposableObserver<S>, string] = [fnDisposable, path];
        this.observers.push(observer);
        fnDisposable.onDispose.then(() => {
            const idx = this.observers.indexOf(observer);
            if (idx >= 0) {
                this.observers.splice(idx, 1);
            }
        });
        return fnDisposable;
    }

    addObserver(fn: UpdateObserver<S>, path?: string): IDisposable {
        return this.addObserverAt(fn, path ?? '');
    }

    observeKey<K extends keyof S>(key: K, fn: UpdateObserver<S[K]>, subPath?: string): IDisposable {
        return this.addObserverAt(
            keyObserver(key, fn),
            `${key}.` + (subPath ?? '')
        )
    }

    view<K extends keyof S>(key: K): StateView<S[K]> {
        return new KeyView(this, key)
    }

    viewOpt<K extends keyof S>(key : K): OptionalStateView<Exclude<S[K], undefined>> {
        return new OptionalKeyView<S, K>(this as OptionalStateHandler<S>, key)
    }

    lens<K extends keyof S>(key: K): StateHandler<S[K]> {
        return new KeyView(this, key)
    }

    lensOpt<K extends keyof S>(key: K): OptionalStateHandler<Exclude<S[K], undefined>> {
        return new OptionalKeyView(this as OptionalStateHandler<S>, key)
    }

    get observerCount(): number { return this.observers.length }
}

class KeyView<S, K extends keyof S> extends Disposable implements StateHandler<S[K]> {
    constructor(private parent: StateHandler<S>, private key: K) {
        super();
        this.disposeWith(parent);
    }

    get state(): S[K] {
        return this.parent.state[this.key];
    }

    addObserver(fn: UpdateObserver<S[K]>, path?: string): IDisposable {
        return this.parent.observeKey(this.key, fn, path).disposeWith(this);
    }

    observeKey<K1 extends keyof S[K]>(key: K1, fn: UpdateObserver<S[K][K1]>, subPath?: string): IDisposable {
        return this.parent.observeKey(this.key, keyObserver(key, fn), `${key}.` + (subPath ?? '')).disposeWith(this);
    }

    view<K1 extends keyof S[K]>(key: K1): StateView<S[K][K1]> {
        return new KeyView<S[K], K1>(this, key)
    }

    viewOpt<K1 extends keyof S[K]>(key: K1): OptionalStateView<Exclude<S[K][K1], undefined>> {
        return new OptionalKeyView(this as any as OptionalStateHandler<S[K]>, key)
    }

    lens<K1 extends keyof S[K]>(key: K1): StateHandler<S[K][K1]> {
        return new KeyView(this, key);
    }

    lensOpt<K1 extends keyof S[K]>(key: K1): OptionalStateHandler<Exclude<S[K][K1], undefined>> {
        return new OptionalKeyView(this as any as OptionalStateHandler<S[K]>, key)
    }

    submitUpdate(update: StateUpdate<S[K]>, updateSource?: any, updatePath?: string): void {
        return this.parent.updateKey(this.key, update as any as UpdateLike<S[K]>, updateSource, updatePath);
    }

    update(updates: UpdateOf<S[K]>, updateSource?: any, updatePath?: string) {
        return this.parent.update({[this.key]: updates} as UpdateOf<S>)
    }

    updateKey<K1 extends keyof S[K]>(key: K1, update: StateUpdate<S[K][K1]>, updateSource?: any, updateSubPath?: string) {
        return this.parent.updateKey(this.key, new UpdateKey(key, update), updateSource, `${key}.` + (updateSubPath ?? ''))
    }
}

class OptionalKeyView<S, K extends keyof S, V extends Exclude<S[K], undefined> = Exclude<S[K], undefined>> extends Disposable implements OptionalStateHandler<V>, OptionalStateView<V> {
    constructor(private parent: OptionalStateHandler<S>, private key: K) {
        super();
        this.disposeWith(parent);
    }

    get state(): V | undefined {
        return this.parent.state !== undefined ? this.parent.state[this.key] as V : undefined;
    }

    addObserver(fn: UpdateObserver<V | undefined>, path?: string): IDisposable {
        return (this.parent as UpdatableState<S>).addObserver((parentValue, update, updateSource) => {
            if (parentValue !== undefined) {
                fn(parentValue[this.key] as V, update.down<K, V>(this.key, parentValue) as UpdateLike<V | undefined>, updateSource);
            } else {
                fn(undefined, Destroy.Instance as UpdateLike<V | undefined>, updateSource);
            }
        }, `${this.key}.` + (path ?? '')).disposeWith(this);
    }

    observeKey<K1 extends keyof V>(childKey: K1, fn: UpdateObserver<V[K1] | undefined>, subPath?: string): IDisposable {
        return this.parent.addObserver((parentValue, parentUpdate, updateSource) => {
            if (parentValue !== undefined) {
                const value: V = parentValue[this.key] as V;
                const update: UpdateLike<V> = (parentUpdate as UpdateLike<S>).down<K, V>(this.key, parentValue)
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
        }, `${this.key}.` + (subPath ?? '')).disposeWith(this)
    }

    view<K1 extends keyof V>(key: K1): OptionalStateView<Exclude<V[K1], undefined>> {
        return new OptionalKeyView<V, K1>(
            this,
            key);
    }

    viewOpt<K1 extends keyof V>(key: K1): OptionalStateView<Exclude<V[K1], undefined>> {
        return new OptionalKeyView<V, K1>(this, key);
    }


    lens<K1 extends keyof V>(key: K1): OptionalStateHandler<Exclude<V[K1], undefined>> {
        return new OptionalKeyView<V, K1>(
            this,
            key);
    }

    lensOpt<K1 extends keyof V>(key: K1): OptionalStateHandler<Exclude<V[K1], undefined>> {
        return new OptionalKeyView<V, K1>(
            this,
            key);
    }

    submitUpdate(update: StateUpdate<V | undefined>, updateSource?: any, updatePath?: string): void {
        return this.parent.updateKey<K>(
            this.key,
            // @ts-ignore
            update,
            updateSource,
            updatePath)
    }

    update(updates: UpdateOf<V | undefined>, updateSource?: any, updatePath?: string): void {
        return this.parent.update({[this.key]: updates} as UpdateOf<S>);
    }

    updateKey<K1 extends keyof V>(childKey: K1, update: StateUpdate<V[K1] | undefined>, updateSource?: any, updateSubPath?: string): void {
        return this.parent.updateKey<K>(
            this.key,
            // @ts-ignore
            new UpdateKey<V, K1>(childKey, update as StateUpdate<V[K1]>),
            updateSource,
            `${childKey}.` + (updateSubPath ?? ''))
    }
}
