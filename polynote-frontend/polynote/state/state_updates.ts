import {copyObject, deepCopy, diffArray, partition} from "../util/helpers";
import {ContentEdit} from "../data/content_edit";
import {__getProxyTarget} from "./readonly";

export interface UpdateLike<S> {
    // Sentinel field so we can distinguish an update from a value
    isStateUpdate: true

    // Apply the update to the given value, mutating in-place if possible. Return the updated value, and indicate using
    // the given latch whether a change was made.
    applyMutate(value: S): UpdateResult<S>
}


// Partial<V[]> confuses TypeScript – TS thinks it has iterators and array methods and such.
// This is to avoid that.
export type Partial1<S> = S extends (infer V)[] ? Record<number, V> : Partial<S>;

// Types for expressing a mapping of keys to update results for those keys' values
export type ObjectFieldUpdates<S> = {
    [K in keyof S]?: UpdateResult<S[K]>;
}

export type FieldUpdates<S> = S extends (infer V)[] ? Record<number, UpdateResult<V>> : ObjectFieldUpdates<S>;

export interface UpdateResult<S> {
    update: UpdateLike<S>
    newValue: Readonly<S>

    // if S is a primitive, or the entire object was replaced, the previous value.
    // Otherwise, *this will be the same* as the new value (which was mutated!).
    oldValue?: Readonly<S>

    // A dict of values that this update removed from an object (if applicable)
    removedValues?: Partial1<S>

    // A dict of values that this update added to the object (if applicable)
    addedValues?: Partial1<S>

    // A dict of values that this update altered in the object (if applicable)
    changedValues?: Partial1<S>

    // A dict of child updates to fields of this object
    fieldUpdates?: FieldUpdates<S>
}

export const UpdateResult = Object.freeze({
    addedOrChangedKeys<S>(result: UpdateResult<S>): (keyof S)[] {
        const updated: (keyof S)[] = [];
        if (result.addedValues)
            updated.push(...Object.keys(result.addedValues) as (keyof S)[]);
        if (result.changedValues)
            updated.push(...Object.keys(result.changedValues) as (keyof S)[]);
        return updated;
    }
})

export type StateUpdate<S> = UpdateLike<S>;

export abstract class Update<S> implements UpdateLike<S> {
    readonly isStateUpdate: true = true

    abstract applyMutate(value: S): UpdateResult<S>
}

export const NoUpdate = Object.freeze({
    isStateUpdate: true,
    applyMutate(prev: any): UpdateResult<any> { return { update: NoUpdate, newValue: prev } }
}) as UpdateLike<any>;

function noChange<S>(value: S): UpdateResult<S> {
    return { update: NoUpdate, newValue: value }
}

function destroyed<S>(value: S): UpdateResult<S> {
    return { update: Destroy.Instance, newValue: undefined as any as S, oldValue: value, fieldUpdates: AllDestroyedUpdates as FieldUpdates<S> }
}

function setTo<S>(value: S, oldValue?: S): UpdateResult<S> {
    return new SetValue(value).applyPure(oldValue!);
}

export function childResult<S, K extends keyof S, V extends S[K] = S[K]>(result: UpdateResult<S>, key: K): UpdateResult<V> {
    const fieldUpdate: UpdateResult<S[K]> | undefined = (result.fieldUpdates as ObjectFieldUpdates<S>)?.[key];
    if (fieldUpdate) {
        return fieldUpdate as UpdateResult<V>;
    }

    if (result.update instanceof SetValue) {
        return setTo(result.newValue[key], result.oldValue?.[key]) as UpdateResult<V>;
    }

    if (result.update instanceof Destroy) {
        return destroyed<S[K] | undefined>(result.oldValue?.[key]) as UpdateResult<V>;
    }

    return noChange(result.newValue[key]) as UpdateResult<V>;
}

const AllDestroyedUpdates: FieldUpdates<any> = new Proxy({}, {
    get(target: {}, p: PropertyKey, receiver: any): UpdateResult<any> {
        return {
            update: Destroy.Instance,
            newValue: undefined as unknown as Readonly<any>,
            fieldUpdates: AllDestroyedUpdates
        }
    }
})

/**
 * A proxy which lazily answers questions about field updates in an array.
 * This is to avoid eagerly filling up a structure with updates, when they might not even be accessed.
 * @param arr        The (mutated) array
 * @param minIdx     The first index affected by the change
 * @param maxIdx     The last index affected by the change
 * @param indexShift By how much the values in the array between those two indices shifted.
 */
function arrayFieldUpdates<V>(arr: V[], minIdx: number, maxIdx: number, indexShift: number): FieldUpdates<V[]> {
    const dict: Record<number, UpdateResult<V>> = {};
    let enumerated: PropertyKey[] | undefined = undefined;
    const enumerate = (target: Record<number, UpdateResult<V>>): PropertyKey[] => {
        if (enumerated === undefined) {
            enumerated = [];
            for (let i = minIdx; i <= maxIdx; i++) {
                enumerated.push(i.toString(10));
            }
        }
        return enumerated;
    }
    return new Proxy(dict, {
        // @ts-ignore
        get(target: Record<number, UpdateResult<V>>, idx: number, receiver: any): UpdateResult<V> {
            if (idx >= minIdx && idx <= maxIdx) {
                if (!target[idx]) {
                    const oldValue = arr[idx - indexShift];
                    const newValue = arr[idx];
                    target[idx] = {
                        update: setValue(newValue),
                        newValue,
                        oldValue
                    }
                }
                return target[idx];
            }
            return noChange(arr[idx]);
        },
        // @ts-ignore
        has(target: Record<number, UpdateResult<V>>, idx: number): boolean {
            return idx >= minIdx && idx <= maxIdx;
        },
        // @ts-ignore
        ownKeys: enumerate
    })
}

export class RemoveKey<S, K extends keyof S> extends Update<S> {
    constructor(readonly key: K, private _value?: S[K]) { super() }

    static unapply<S, K extends keyof S>(inst: RemoveKey<S, K>): ConstructorParameters<typeof RemoveKey> {
        // @ts-ignore
        return [inst.key, inst.value]
    }

    get value(): S[K] | undefined { return this._value }

    applyMutate(value: S): UpdateResult<S> {
        if (value === null || value === undefined || !(this.key in (value as any)))
            return noChange(value);
        const oldValue: S[K] = value[this.key];
        delete value[this.key];
        return {
            update: this,
            newValue: value,
            removedValues: {
                [this.key]: oldValue
            } as Partial1<S>,
            fieldUpdates: {
                [this.key]: destroyed(oldValue)
            } as FieldUpdates<S>
        };
    }
}

export class UpdateKey<S, K extends keyof S> extends Update<S> {
    constructor(readonly key: K, private _update: UpdateLike<S[K]>) { super() }

    get update(): UpdateLike<S[K]> { return this._update; }

    applyMutate(oldValue: S): UpdateResult<S> {
        const childResult: UpdateResult<S[K]> = this._update.applyMutate(oldValue[this.key]);
        if (childResult.update === NoUpdate)
            return noChange(oldValue);

        const result: UpdateResult<S> = {
            update: this,
            newValue:  oldValue,
            fieldUpdates: {
                [this.key]: childResult
            } as FieldUpdates<S>
        }

        if (!(this.key in (oldValue as any))) {
            result.addedValues = {
                [this.key]: childResult.newValue
            } as Partial1<S>;
        } else if (childResult.update instanceof Destroy) {
            result.removedValues = {
                [this.key]: childResult.oldValue
            } as Partial1<S>;
        } else {
            result.changedValues = {
                [this.key]: childResult.newValue
            } as Partial1<S>
        }

        if (Object.isFrozen(oldValue)) {
            result.newValue = copyObject(oldValue, {[this.key]: childResult.newValue} as unknown as Partial<S>);
        } else if (childResult.update instanceof Destroy) {
            delete oldValue[this.key];
        } else {
            oldValue[this.key] = childResult.newValue as S[K];
        }
        return result;
    }

}

export class RemoveValue<V> extends Update<V[]> {
    constructor(readonly value: V, private _index: number) { super() }

    static unapply<V>(inst: RemoveValue<V>): ConstructorParameters<typeof RemoveValue> {
        return [inst.value, inst.index];
    }

    get index(): number { return this._index; }

    applyMutate(arr: V[]): UpdateResult<V[]> {
        const idx = this._index;
        const len = arr.length;
        if (arr[idx] !== this.value) {
            throw new Error("RemoveValue is no longer valid as array has changed");
        }

        arr.splice(idx, 1);

        return {
            update: this,
            newValue: arr,
            removedValues: {
                [this.index]: this.value
            },
            get fieldUpdates() {
                return arrayFieldUpdates(arr, idx, len, -1)
            }
        };
    }
}

export class InsertValue<V> extends Update<V[]> {
    constructor(readonly value: V, private _targetIndex?: number) { super() }

    static unapply<V>(inst: InsertValue<V>): ConstructorParameters<typeof InsertValue> {
        return [inst.value, inst.targetIndex]
    }

    get targetIndex(): number { return this._targetIndex! }

    applyMutate(arr: V[]): UpdateResult<V[]> {
        if (arr === undefined) {
            arr = [];
        }

        const targetIndex = this.targetIndex ?? arr.length;
        if (this._targetIndex !== undefined) {
            arr.splice(this.targetIndex, 0, this.value);
        } else {
            this._targetIndex = arr.length;
            arr.push(this.value);
        }

        return {
            update: this,
            newValue: arr,
            addedValues: {
                [targetIndex]: this.value
            },
            get fieldUpdates() {
                return arrayFieldUpdates(arr, targetIndex, arr.length, 1)
            }
        };
    }
}

export class MoveArrayValue<V> extends Update<V[]> {
    private movedElem?: V;
    constructor(readonly fromIndex: number, readonly toIndex: number) { super(); }

    static unapply<V>(inst: MoveArrayValue<V>): ConstructorParameters<typeof MoveArrayValue> {
        return [inst.fromIndex, inst.toIndex]
    }

    get movedValue(): V | undefined {
        return this.movedElem;
    }

    applyMutate(arr: V[]): UpdateResult<V[]> {
        if (this.fromIndex === this.toIndex) {
            return noChange(arr);
        }
        const minIdx = Math.min(this.fromIndex, this.toIndex);
        const maxIdx = Math.max(this.fromIndex, this.toIndex);
        const elem = this.movedElem = arr.splice(this.fromIndex, 1)[0];
        const targetIdx = this.toIndex > this.fromIndex ? this.toIndex - 1 : this.toIndex
        arr.splice(this.toIndex, 0, elem);
        return {
            update: this,
            newValue: arr,
            get fieldUpdates() {
                return arrayFieldUpdates(arr, minIdx, maxIdx, 1)
            }
        };
    }

}

export class ReplaceArrayValue<V> extends Update<V[]> {
    constructor(readonly value: V, private targetIndex: number) { super(); }

    static unapply<V>(inst: ReplaceArrayValue<V>): ConstructorParameters<typeof ReplaceArrayValue> {
        return [inst.value, inst.targetIndex]
    }

    applyMutate(arr: V[]): UpdateResult<V[]> {
        if (this.targetIndex >= arr.length) {
            throw new Error("ReplaceArrayValue is no longer valid as array has changed");
        }

        const removedValue = arr.splice(this.targetIndex, 1, this.value)[0]

        const targetIndex = this.targetIndex;
        return {
            update: this,
            newValue: arr,
            addedValues: {
                [targetIndex]: this.value
            },
            removedValues: {
                [targetIndex]: removedValue
            },
            fieldUpdates: {
                [targetIndex]: setTo(this.value, removedValue)
            }
        }
    }

}

export class RenameKey<S, K0 extends keyof S, K1 extends keyof S, V extends S[K0] & S[K1]> extends Update<S> {
    constructor(readonly oldKey: K0, readonly newKey: K1) { super() }

    static unapply<S, K0 extends keyof S, K1 extends keyof S, V extends S[K0] & S[K1]>(inst: RenameKey<S, K0, K1, V>): ConstructorParameters<typeof RenameKey> {
        // @ts-ignore
        return [inst.oldKey, inst.newKey];
    }

    applyMutate(obj: S): UpdateResult<S> {
        const value: V = obj[this.oldKey] as V;
        const replaced: V = (obj[this.oldKey] ?? value) as V;
        obj[this.newKey] = value;
        delete obj[this.oldKey];
        return {
            update: this,
            newValue: obj,
            removedValues: {
                [this.oldKey]: value
            } as Partial1<S>,
            addedValues: {
                [this.newKey]: value
            } as Partial1<S>,
            fieldUpdates: {
                [this.oldKey]: destroyed(value),
                [this.newKey]: setTo(value, replaced)
            } as FieldUpdates<S>
        };
    }
}

export class Destroy<S> extends Update<S | undefined> {
    constructor() { super() }
    static Instance: Destroy<any> = new Destroy();

    static unapply<S>(inst: Destroy<S>): ConstructorParameters<typeof Destroy> {
        return []
    }

    applyMutate(value: S | undefined): UpdateResult<S | undefined> {
        return {
            update: Destroy.Instance,
            newValue: undefined,
            oldValue: value,
            fieldUpdates: AllDestroyedUpdates as FieldUpdates<S>
        }
    }
}


export class SetValue<S> extends Update<S> {
    constructor(readonly value: S) {
        super()
    }

    // TODO: Extractable seems to be problematic on parametric data classes when primitive types are involved.
    static unapply<T>(inst: SetValue<T>): ConstructorParameters<typeof SetValue> { return [inst.value] }

    // if we set the value of an object state, find the keys that changed
    // this is done lazily, because the diff might not be used.
    // Method is private static because it should only be called from SetValue – that's the only case when we know
    // that old and new are not the same reference.
    private static objectDiffUpdates<S extends object>(update: UpdateLike<S>, oldObj: S, newObj: S): UpdateResult<S> {
        let removedValues: Partial<S> | undefined = undefined;
        let addedValues: Partial<S> | undefined = undefined;
        let changedValues: Partial<S> | undefined = undefined;
        let fieldUpdates: ObjectFieldUpdates<S> | undefined = undefined;
        let computed: boolean = false;

        function compute() {
            function addFieldUpdate<K extends keyof S>(key: K, fieldUpdate: UpdateResult<S[K]>) {
                fieldUpdates = fieldUpdates || {};
                fieldUpdates[key] = fieldUpdate;
            }

            function addedValue<K extends keyof S>(key: K, fieldUpdate: UpdateResult<S[K]>) {
                addFieldUpdate(key, fieldUpdate);
                addedValues = addedValues || {};
                addedValues[key] = fieldUpdate.newValue as S[K];
            }

            function removedValue<K extends keyof S>(key: K, fieldUpdate: UpdateResult<S[K]>) {
                addFieldUpdate(key, fieldUpdate);
                removedValues = removedValues || {};
                removedValues[key] = fieldUpdate.oldValue as S[K];
            }

            function changedValue<K extends keyof S>(key: K, fieldUpdate: UpdateResult<S[K]>) {
                addFieldUpdate(key, fieldUpdate);
                changedValues = changedValues || {};
                changedValues[key] = fieldUpdate.newValue as S[K];
            }

            if (!oldObj)
                oldObj = {} as any as S;

            if (!newObj)
                newObj = {} as any as S;

            function computeUpdate<K extends keyof S>(key: K) {
                const oldHas = oldObj.hasOwnProperty(key);
                const newHas = newObj.hasOwnProperty(key);
                if (oldHas && !newHas) {
                    removedValue(key, destroyed(oldObj[key]))
                } else if (oldObj[key] !== newObj[key]) {
                    let fieldUpdate: UpdateResult<S[K]>;
                    if (typeof oldObj[key] === 'object' && typeof newObj[key] === 'object') {
                        fieldUpdate = SetValue.objectDiffUpdates<S[K] & object>(
                            setValue(newObj[key] as S[K] & object) as UpdateLike<S[K] & object>,
                            oldObj[key] as S[K] & object,
                            newObj[key] as S[K] & object
                        ) as UpdateResult<S[K]>;
                    } else {
                        fieldUpdate = setTo(newObj[key], oldObj[key]);
                    }

                    if (!oldHas) {
                        addedValue(key, fieldUpdate);
                    } else {
                        changedValue(key, fieldUpdate);
                    }
                }
            }

            const merged: S = {...oldObj, ...newObj};

            for (const key in merged) {
                if (merged.hasOwnProperty(key)) {
                    computeUpdate(key)
                }
            }
        }

        return {
            update,
            newValue: newObj,
            oldValue: oldObj,
            get removedValues() {
                if (!computed)
                    compute();
                return removedValues as Partial1<S> | undefined
            },
            get addedValues() {
                if (!computed)
                    compute();
                return addedValues as Partial1<S> | undefined
            },
            get changedValues() {
                if (!computed)
                    compute();
                return changedValues as Partial1<S> | undefined
            },
            get fieldUpdates() {
                if (!computed)
                    compute();
                return fieldUpdates as FieldUpdates<S> | undefined;
            }
        }

    }

    applyPure(oldValue: S): UpdateResult<S> {
        if (oldValue === this.value) {
            return noChange(oldValue);
        } else if (typeof oldValue === 'object' || typeof this.value === 'object') {
            // @ts-ignore – it can't realize that S must extend object
            return SetValue.objectDiffUpdates<S>(this, oldValue, this.value);
        } else return this.applyPrimitive(oldValue);
    }

    applyPrimitive(oldValue: S): UpdateResult<S> {
        return {
            update: this,
            newValue: this.value,
            oldValue: oldValue
        }
    }

    applyMutate(oldValue: S): UpdateResult<S> {
        return this.applyPure(oldValue)
    }
}

export class EditString extends Update<string> {
    constructor(readonly edits: ContentEdit[]) {
        super();
    }

    static unapply(inst: EditString): ConstructorParameters<typeof EditString> { return [inst.edits] }

    applyMutate(value: string): UpdateResult<string> {
        if (this.edits.length === 0) {
            return noChange(value);
        }

        let result = value;
        for (let i = 0; i < this.edits.length; i++) {
            result = this.edits[i].apply(result);
        }
        return {
            update: this,
            newValue: result,
            oldValue: value
        };
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
    constructor(readonly fieldUpdates: UpdateOf<S>) {
        super()
    }

    applyMutate(oldValue: S): UpdateResult<S> {
        let fieldUpdateResults: ObjectFieldUpdates<S> | undefined = undefined;
        let addedValues: Partial<S> | undefined = undefined;
        let removedValues: Partial<S> | undefined = undefined;
        let changedValues: Partial<S> | undefined = undefined;
        let anyChanged: boolean = false;
        const mustCopy = typeof oldValue === 'object' && Object.isFrozen(oldValue);
        const value: any = mustCopy ? {...oldValue} : oldValue || {};

        const updates = this.fieldUpdates as any;
        if (typeof updates === 'object') {
            for (const prop in updates) {
                if (updates.hasOwnProperty(prop)) {
                    const key = prop as keyof S;
                    const update = (updates as any)[key];
                    const updateResult = update.applyMutate(value[key]);

                    if (updateResult.update === NoUpdate)
                        continue;

                    anyChanged = true;
                    fieldUpdateResults = fieldUpdateResults || {};
                    fieldUpdateResults[key] = updateResult;

                    if (updateResult.update instanceof Destroy) {
                        removedValues = removedValues || {};
                        removedValues[key] = updateResult.oldValue;
                        delete value[key];
                    } else {
                        if (!value.hasOwnProperty(key)) {
                            addedValues = addedValues || {};
                            addedValues[key] = updateResult.newValue;
                        } else {
                            changedValues = changedValues || {};
                            changedValues[key] = updateResult.newValue;
                        }
                        value[key] = updateResult.newValue;
                    }
                }
            }
            if (anyChanged) {
                if (mustCopy) {
                    Object.setPrototypeOf(value, Object.getPrototypeOf(oldValue));
                    Object.freeze(value);
                }
                const update = {
                    update: this,
                    newValue: value,
                } as UpdateResult<S>;
                if (addedValues)
                    update.addedValues = addedValues as Partial1<S>;
                if (removedValues)
                    update.removedValues = removedValues as Partial1<S>;
                if (changedValues)
                    update.changedValues = changedValues as Partial1<S>;
                if(fieldUpdateResults)
                    update.fieldUpdates = fieldUpdateResults as FieldUpdates<S>;
                return update;
            } else {
                return noChange(oldValue);
            }
        } else if (oldValue === updates) {
            return noChange(oldValue)
        } else {
            return new SetValue(updates as S).applyPrimitive(oldValue)
        }
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

export function setValue<V, V1 extends V = V>(value: V1): StateUpdate<V> {
    return new SetValue(__getProxyTarget(value as V))
}

export const setUndefined: StateUpdate<any> = new SetValue(undefined);

export const setToUndefined: UpdateResult<any> = Object.freeze({
    update: setUndefined,
    newValue: undefined as unknown as Readonly<any>
});

export function setProperty<S, K extends keyof S, V extends S[K] = S[K]>(key: K, value: V): StateUpdate<S> {
    return new UpdateKey<S, K>(key, new SetValue<S[K]>(value))
}

export function updateProperty<S, K extends keyof S>(key: K, update: UpdateOf<S[K]>): StateUpdate<S> {
    return new UpdateKey<S, K>(key, valueToUpdate(update));
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

export function replaceArrayValue<V>(value: V, index: number): StateUpdate<V[]> {
    return new ReplaceArrayValue(value, index)
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