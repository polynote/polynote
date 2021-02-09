import {deepCopy, diffArray, partition} from "../util/helpers";
import {ContentEdit} from "../data/content_edit";
import {Latch, Partial1} from "./state_handler";
import {__getProxyTarget} from "./readonly";

export interface UpdateLike<S> {
    // Sentinel field so we can distinguish an update from a value
    isStateUpdate: true

    // Return a new (deep) copy of the given value with the update applied
    apply(prev: S): S

    // Apply the update to the given value, mutating in-place if possible. Return the updated value, and indicate using
    // the given latch whether a change was made.
    applyMutate(value: S, latch?: Latch): S

    // A list of keys that this update removed from an object (if applicable)
    removedKeys: (keyof S)[]

    // A dict of values that this update removed from an object (if applicable)
    removedValues?: Partial1<S>

    // A list of keys that this update added or changed in an object (if applicable)
    changedKeys: (keyof S)[]

    // A dict of values that this update added or changed in an object (if applicable)
    changedValues(value: S): Partial1<S>

    // The previous value, if S is a primitive and the old value is available
    oldValue?: S

    // Given a key of S, return an update that corresponds to the change this update would make to that key (or NoUpdate that key is unaffected)
    down<K extends keyof S, V extends S[K] = S[K]>(key: K, of: S): StateUpdate<V>

    // Set the context value of the update, before it is applied
    forValue(value: S): UpdateLike<S>
}

export type StateUpdate<S> = UpdateLike<S>;

export abstract class Update<S> implements UpdateLike<S> {
    readonly isStateUpdate: true = true

    apply(prev: S): S {
        const newState = deepCopy(prev);
        this.applyMutate(newState);
        return newState;
    }

    abstract applyMutate(value: S, latch?: Latch): S
    abstract get removedKeys(): (keyof S)[]
    abstract get changedKeys(): (keyof S)[]
    abstract down<K extends keyof S, V extends S[K] = S[K]>(key: K, of: S): StateUpdate<V>
    abstract forValue(value: S): UpdateLike<S>

    changedValues(value: S): Partial1<S> {

        const changed = (value instanceof Array ? [] : {}) as Partial<S>;
        Object.setPrototypeOf(changed, Object.getPrototypeOf(value));
        for (const key of this.changedKeys) {
            changed[key] = value[key];
        }
        return changed as Partial1<S>;
    }
}

export const NoUpdate = Object.freeze({
    isStateUpdate: true,
    apply(prev: any): any { return prev; },
    applyMutate(prev: any): any { return prev },
    removedKeys: [],
    changedKeys: [],
    down<K extends keyof any>(key: K, of: any) { return NoUpdate },
    pathMatches(listenerPath: string, basePath: string) { return false },
    forValue(value: any): typeof NoUpdate { return NoUpdate },
    changedValues(value: any): Partial<any> { return {} }
}) as UpdateLike<any>;

export class RemoveKey<S, K extends keyof S> extends Update<S> {
    constructor(readonly key: K, private _value?: S[K]) { super() }

    static unapply<S, K extends keyof S>(inst: RemoveKey<S, K>): ConstructorParameters<typeof RemoveKey> {
        return [inst.key, inst.value]
    }

    get value(): S[K] | undefined { return this._value }

    applyMutate(value: S, latch?: Latch): S {
        if (this.key in value)
            latch?.trigger();
        delete value[this.key];
        return value;
    }

    get removedKeys(): (keyof S)[] {
        return [this.key]
    }

    get removedValues(): Partial1<S> {
        return {[this.key]: this._value} as Partial1<S>;
    }

    get changedKeys(): (keyof S)[] { return [] };

    down<K1 extends keyof S, V extends S[K1] = S[K1]>(key: K1, of: S): StateUpdate<V> {
        if (key as any === this.key) {
            return destroy()
        }
        return NoUpdate
    }

    forValue(value: S): this {
        this._value = value[this.key];
        return this;
    }
}

export class UpdateKey<S, K extends keyof S> extends Update<S> {
    constructor(readonly key: K, private _update: UpdateLike<S[K]>) { super() }

    get update(): UpdateLike<S[K]> { return this._update; }

    applyMutate(value: S, latch?: Latch): S {
        value[this.key] = this._update.applyMutate(value[this.key], latch?.down());
        return value;
    }

    readonly removedKeys: (keyof S)[] = [];
    get changedKeys(): (keyof S)[] { return [this.key] }

    down<K1 extends keyof S, V extends S[K1] = S[K1]>(key: K1, of: S): StateUpdate<V> {
        if (key as any === this.key) {
            return this._update as any as StateUpdate<V>;
        }
        return NoUpdate;
    }

    forValue(value: S): UpdateLike<S> {
        this._update = this._update.forValue(value[this.key]);
        if (this._update === NoUpdate) {
            return NoUpdate;
        }
        return this;
    }

}

export class RemoveValue<V> extends Update<V[]> {
    constructor(readonly value: V, private _index: number) { super() }

    static unapply<V>(inst: RemoveValue<V>): ConstructorParameters<typeof RemoveValue> {
        return [inst.value, inst.index];
    }

    get index(): number { return this._index; }

    applyMutate(value: V[], latch?: Latch): V[] {
        if (value[this.index] !== this.value) {
            throw new Error("RemoveValue is no longer valid as array has changed");
        }
        latch?.trigger();
        value.splice(this.index, 1);
        return value;
    }

    get removedKeys(): (keyof V[])[] { return [this.index] }
    get removedValues(): Partial1<V[]> {
        return {[this._index]: this.value} as Partial1<V[]>;
    }
    get changedKeys(): (keyof V[])[] { return [] }
    down<K extends keyof V[], V1 extends V[][K] = V[][K]>(key: K, of: V[]): StateUpdate<V1> {
        if (key === this.index) {
            return destroy()
        }
        return NoUpdate
    }

    forValue(value: V[]): this {
        const idx = value.indexOf(this.value);
        if (idx >= -1) {
            this._index = idx;
        }
        return this;
    }
}

export class InsertValue<V> extends Update<V[]> {
    constructor(readonly value: V, private _targetIndex?: number) { super() }

    static unapply<V>(inst: InsertValue<V>): ConstructorParameters<typeof InsertValue> {
        return [inst.value, inst.targetIndex]
    }

    get targetIndex(): number { return this._targetIndex! }

    applyMutate(value: V[], latch?: Latch): V[] {
        latch?.trigger();
        if (value === undefined) {
            value = [];
        }

        if (this.targetIndex) {
            value.splice(this.targetIndex, 0, this.value);
        } else {
            this._targetIndex = value.length;
            value.push(this.value);
        }
        return value;
    }

    get removedKeys(): (keyof V[])[] { return [] };
    get changedKeys(): (keyof V[])[] { return [this.targetIndex] };

    down<K extends keyof V[], V1 extends V[][K] = V[][K]>(key: K, of: V[]): StateUpdate<V1> {
        if (key === this.targetIndex) {
            return setValue<V1>(of[this.targetIndex] as V1)
        }
        return NoUpdate;
    }

    forValue(value: V[]): this {
        return this;
    }
}

export class MoveArrayValue<V> extends Update<V[]> {
    private arraySize?: number;
    private _oldValue?: V[];

    get oldValue(): V[] | undefined {
        return this._oldValue;
    }

    constructor(readonly fromIndex: number, readonly toIndex: number) {
        super();
    }

    applyMutate(value: V[], latch: Latch | undefined): V[] {
        latch?.trigger();
        const elem = value.splice(this.fromIndex, 1)[0];
        value.splice(this.toIndex, 0, elem);
        return value;
    }

    get changedKeys(): (keyof V[])[] {
        const minChangedIndex = Math.min(this.fromIndex, this.toIndex);
        const maxChangedIndex = this.arraySize !== undefined ? this.arraySize - 1 : minChangedIndex;
        const result: number[] = [];
        for (let i = minChangedIndex; i <= maxChangedIndex; i++) {
            result.push(i);
        }
        return result;
    }

    down<K extends keyof V[], V1 extends V[][K]>(key: K, of: V[]): StateUpdate<V1> {
        const idx = parseInt((key as any).toString(), 10);
        if (idx >= Math.min(this.fromIndex, this.toIndex)) {
            const update = setValue<V1, V1>(of[key] as V1);
            if (this._oldValue)
                return update.forValue(this._oldValue?.[key] as V1);
            return update;
        }
        return NoUpdate;
    }

    forValue(oldValue: V[]): this {
        this.arraySize = oldValue.length;
        this._oldValue = [...oldValue];
        return this;
    }

    get removedKeys(): (keyof V[])[] {
        return [];
    }
}

export class RenameKey<S, K0 extends keyof S, K1 extends keyof S, V extends S[K0] & S[K1]> extends Update<S> {
    constructor(readonly oldKey: K0, readonly newKey: K1) { super() }

    static unapply<S, K0 extends keyof S, K1 extends keyof S, V extends S[K0] & S[K1]>(inst: RenameKey<S, K0, K1, V>): ConstructorParameters<typeof RenameKey> {
        return [inst.oldKey, inst.newKey];
    }

    applyMutate(value: S, latch?: Latch): S {
        value[this.newKey] = value[this.oldKey] as V;
        delete value[this.oldKey];
        latch?.trigger();
        return value;
    }

    // TODO: this could just rename the view instead of disposing it
    get removedKeys(): (keyof S)[] {
        return [this.oldKey];
    }

    get changedKeys(): (keyof S)[] {
        return [this.newKey];
    }

    down<K1 extends keyof S, V extends S[K1] = S[K1]>(key: K1, of: S): StateUpdate<V> {
        if ((key as any) === this.oldKey) {
            return destroy();
        } else if ((key as any) === this.newKey) {
            return new SetValue(of[key] as V);
        }
        return NoUpdate;
    }

    forValue(value: S): StateUpdate<S> {
        if (value instanceof Array && typeof this.oldKey === 'number' && typeof this.newKey === 'number') {
            const move = new MoveArrayValue(this.oldKey, this.newKey) as any as StateUpdate<S>;
            return move.forValue(value);
        }
        return this;
    }
}

export class Destroy<S> extends Update<S | undefined> {
    constructor(readonly oldValue?: S) { super() }
    static Instance: Destroy<any> = new Destroy();

    static unapply<S>(inst: Destroy<S>): ConstructorParameters<typeof Destroy> {
        return [inst.oldValue]
    }

    applyMutate(value: S | undefined, latch?: Latch): S | undefined {
        if (value !== undefined) {
            latch?.trigger();
        }
        return undefined
    }

    get removedKeys(): never[] { return [] }
    get changedKeys(): never[] { return [] }

    // @ts-ignore
    down<K1 extends keyof (S | undefined), V extends S[K1] = S[K1]>(key: K1, of: S | undefined): StateUpdate<V> {
        // @ts-ignore
        return this.oldValue === undefined ? Destroy.Instance : new Destroy(this.oldValue[key])
    }

    forValue(value: S | undefined): UpdateLike<S | undefined> {
        // @ts-ignore
        return new Destroy(value)
    }
}

export class SetValue<S> extends Update<S> {
    private _removedKeys: (keyof S)[] = [];
    private _changedKeys: (keyof S)[] = [];
    private _removedValues?: Partial<S>;

    constructor(readonly value: S, private _oldValue?: S) {
        super()
    }

    // TODO: Extractable seems to be problematic on parametric data classes when primitive types are involved.
    static unapply<T>(inst: SetValue<T>): ConstructorParameters<typeof SetValue> { return [inst.value, inst.oldValue ] }

    applyMutate(value: S, latch?: Latch): S {
        if (value !== this.value) {
            latch?.trigger();
        }
        return this.value
    }

    get oldValue() { return this._oldValue }

    get removedKeys(): (keyof S)[] { return this._removedKeys };
    get removedValues(): Partial1<S> | undefined { return this._removedValues as Partial1<S> }
    get changedKeys(): (keyof S)[] { return this._changedKeys };

    down<K1 extends keyof S, V extends S[K1] = S[K1]>(key: K1, of: S): StateUpdate<V> {
        const oldMember: S[K1] | undefined = this._oldValue?.[key];
        const setMember = new SetValue(this.value[key] as V, oldMember as (V | undefined));
        if (oldMember !== undefined) {
            return setMember.forValue(oldMember as V)
        }
        return setMember
    }

    forValue(oldValue: S): UpdateLike<S> {
        if (oldValue === this.value) {
            return NoUpdate
        }

        this._oldValue = oldValue;


        // if we set the value of an object state, find the keys that changed
        const oldType = typeof oldValue;
        const newType = typeof this.value;
        if (newType === 'object') {
            if (oldValue === null) {
                this._changedKeys = Object.keys(this.value) as (keyof S)[];
                return this;
            }

            const changed: Record<keyof S, boolean> = {} as Record<keyof S, boolean>;
            const newKeys = Object.keys(this.value) as (keyof S)[];
            if (oldType === 'object') {
                const oldKeys = Object.keys(oldValue) as (keyof S)[];
                const [_, removed] = diffArray(newKeys, oldKeys);

                for (const prop of newKeys) {
                    const key = prop as keyof S;
                    if (this.value[key] !== oldValue[key]) {
                        changed[key] = true;
                    }
                }

                this._changedKeys = Object.keys(changed) as (keyof S)[];
                this._removedKeys = removed;

                this._removedValues = {};
                for (const prop of this._removedKeys) {
                    this._removedValues[prop] = oldValue[prop]
                }
            } else {
                this._changedKeys = newKeys;
                this._removedKeys = [];
                this._removedValues = {};
            }
        } else if (oldType === 'object' && oldValue !== null) {
            this._changedKeys = Object.keys(oldValue) as (keyof S)[];
            this._removedKeys = [];
            this._removedValues = {};
        }

        return this;
    }
}

export class EditString extends Update<string> {
    constructor(readonly edits: ContentEdit[]) {
        super();
    }

    static unapply(inst: EditString): ConstructorParameters<typeof EditString> { return [inst.edits] }

    applyMutate(value: string, latch: Latch | undefined): string {
        let result = value;
        if (this.edits.length && latch) {
            latch.trigger();
        }
        for (let i = 0; i < this.edits.length; i++) {
            result = this.edits[i].apply(result);
        }
        return result;
    }

    get changedKeys(): (keyof string)[] {
        return [];
    }

    get removedKeys(): (keyof string)[] {
        return [];
    }

    down<K, V>(key: K, of: string): StateUpdate<V> {
        return NoUpdate;
    }

    forValue(value: string): UpdateLike<string> {
        return this;
    }
}


export type UpdatePartial<T> = {
    [P in keyof T]?: UpdateOf<T[P]>
}

export type UpdateOf<T> = T | UpdateLike<T> | UpdatePartial<T>

export function isUpdateLike(value: any): value is UpdateLike<any> {
    return value && value.isStateUpdate
}

function isUpdatePartial<S>(value: UpdateOf<S>): value is UpdatePartial<S> {
    return value && typeof value === 'object' && !(value as any).isStateUpdate && (value as any).constructor === Object
}

class UpdateWith<S> extends Update<S> {
    constructor(readonly fieldUpdates: UpdateOf<S>, private _removedValues: Partial<S> = {}) {
        super()
        if (typeof fieldUpdates === 'object') {
            const [removed, added] = partition(Object.keys(fieldUpdates), (k) => (fieldUpdates as any)[k] instanceof Destroy)
            this.removedKeys = removed as (keyof S)[];
            this.changedKeys = added as (keyof S)[];
        }
    }

    readonly changedKeys: (keyof S)[];
    readonly removedKeys: (keyof S)[];
    get removedValues(): Partial1<S> {
        return this._removedValues as Partial1<S>;
    }

    applyMutate(value: S, latch?: Latch): S {
        function go(item: any, updates: UpdateOf<any>): any {
            if (typeof updates === 'object') {
                item = item || {};
                for (const prop in updates) {
                    if (updates.hasOwnProperty(prop)) {
                        const update = (updates as any)[prop];
                        if (isUpdateLike(update)) {
                            item[prop] = update.applyMutate(item[prop], latch?.down());
                        } else {
                            console.warn("UpdateWith should only have updates as members! Should be using valueToUpdate.");
                            item[prop] = go(item[prop], update);
                        }
                    }
                }
                return item;
            } else {
                if (item !== updates) {
                    latch?.trigger();
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
                console.warn("UpdateWith should only have updates as members! Should be using valueToUpdate.");
                const update = (this.fieldUpdates as any)[key];
                if (typeof update === "object") {
                    return new UpdateWith(update as UpdateOf<V>, this._removedValues[key] ?? {})
                } else {
                    return new SetValue(update as V);
                }
            }
        }
        return NoUpdate;
    }

    forValue(value: S): this {
        if (value) {
            const removed: Partial<S> = {}
            for (const key of this.removedKeys) {
                removed[key] = value[key];
            }
            this._removedValues = removed;

            const updates = this.fieldUpdates as any;
            for (const key in updates) {
                if (updates.hasOwnProperty(key) && isUpdateLike(updates[key])) {
                    updates[key] = updates[key].forValue(value[key as keyof S])
                }
            }
        }
        return this;
    }

}

/**
 * If the given update value is not already an update, return the update that it specifies.
 * If the update is a direct value, wrap it in SetValue. If the update is an object specifying updates to keys,
 * recursively transform the object's values to be Updates as well.
 */
export function valueToUpdate<S>(updates: UpdateOf<S>): StateUpdate<S> {
    if (isUpdateLike(updates)) {
        return updates;
    } else if (isUpdatePartial<S>(updates)) {
        const updates1 = updates as any;
        const onlyUpdates: UpdateOf<S> = {};
        for (const prop in updates1) {
            if (updates1.hasOwnProperty(prop)) {
                const key = prop as keyof S;
                onlyUpdates[key] = valueToUpdate((updates as UpdatePartial<S>)[key]);
                if (onlyUpdates[key] === NoUpdate) {
                    delete onlyUpdates[key]
                }
            }
        }
        return new UpdateWith(onlyUpdates);
    } else {
        return setValue(updates as S)
    }
}

/**
 * Constructors for state updates
 */
export function removeKey<S, K extends keyof S>(key: K): StateUpdate<S> {
    return new RemoveKey<S, K>(key);
}

export function renameKey<S, K0 extends keyof S, K1 extends keyof S, V extends S[K0] & S[K1]>(oldKey: K0, newKey: K1): StateUpdate<S> {
    return new RenameKey<S, K0, K1, V>(oldKey, newKey);
}

export function setValue<V, V1 extends V = V>(value: V1, oldValue?: V1): StateUpdate<V> {
    return new SetValue(__getProxyTarget(value as V), __getProxyTarget(oldValue))
}

export function setProperty<S, K extends keyof S, V extends S[K] = S[K]>(key: K, value: V): StateUpdate<S> {
    return new UpdateKey<S, K>(key, new SetValue<S[K]>(value))
}

export function destroy<V>(): StateUpdate<V> {
    return Destroy.Instance as any as StateUpdate<V>
}

export function append<V, V1 extends V = V>(value: V1): StateUpdate<V[]> {
    return new InsertValue(value) as StateUpdate<V[]>
}

export function insert<V, V1 extends V = V>(value: V1, index: number): StateUpdate<V[]> {
    return new InsertValue<V>(value, index)
}

export function clearArray<V>(): StateUpdate<V[]> {
    // TODO: should this be its own op?
    return new SetValue<V[]>([])
}

export function moveArrayValue<V>(fromIndex: number, toIndex: number): StateUpdate<V[]> {
    return new MoveArrayValue(fromIndex, toIndex)
}

export function editString(edits: ContentEdit[]): StateUpdate<string> {
    return new EditString(edits)
}

export function removeFromArray<V>(arr: Readonly<V[]>, value: V, compare?: (a: V, b: V) => boolean): StateUpdate<V[]> {
    value = __getProxyTarget(value);
    arr = __getProxyTarget(arr);
    const idx = compare ? arr.findIndex(v => compare(v, value)) : arr.indexOf(value);
    if (idx >= 0) {
        return new RemoveValue(value, idx);
    }
    return NoUpdate;
}

export function removeIndex<V>(arr: Readonly<V[]>, index: number): StateUpdate<V[]> {
    arr = __getProxyTarget(arr);
    if (arr.hasOwnProperty(index)) {
        return new RemoveValue(arr[index], index)
    }
    return NoUpdate
}

export function noUpdate<S>(): StateUpdate<S> {
    return NoUpdate;
}