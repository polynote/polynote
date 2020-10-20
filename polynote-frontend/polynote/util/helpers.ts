'use strict';

import * as fastEquals from 'fast-deep-equal/es6';
import {StateHandler} from "../state/state_handler";
import match, {Extractable, Matcher} from "./match";

export function deepEquals<T>(a: T, b: T, ignoreKeys?: (keyof T)[]): boolean {
    if ((a === undefined && b !== undefined) || (b === undefined && a !== undefined)) {
        return false;
    }
    if ((a === null && b !== null) || (b === null && a !== null)) {
        return false;
    }
    if (ignoreKeys && a && b) {
        a = ignoreKeys.reduce((acc: T, key: keyof T) => {
            return removeKey(acc, key)
        }, a)
        b = ignoreKeys.reduce((acc: T, key: keyof T) => {
            return removeKey(acc, key)
        }, b)
    }
    return fastEquals(a, b)
}

// Shallow Equality that handles empty objects / arrays
export function shallowEquals<T>(a: T, b: T): boolean {
    if (a === b) return true

    if (isObject(a) && isObject(b) && isEmpty(a) && isEmpty(b)) {
        return true
    }

    return Array.isArray(a) && Array.isArray(b) && isEmpty(a) && isEmpty(b);

}

//*********************
//* Object helpers
//*********************

// Checks if variable is an object (and not an array, even though arrays are technically objects). Maybe there's a more correct name for this method.
export function isObject(obj: any): obj is object {
    return obj && typeof obj === "object" && !Array.isArray(obj)
}

export function isEmpty(obj: object): boolean {
    return obj && Object.keys(obj).length === 0
}

export function diffArray<T>(a: T[], b: T[], equals: (a: T, b: T) => boolean = (a: T, b: T) => deepEquals(a, b)): [T[], T[]] {
    const aNotB = a.filter(x => b.findIndex(el => equals(x, el)) === -1);
    const bNotA = b.filter(x => a.findIndex(el => equals(x, el)) === -1);
    return [aNotB, bNotA]
}

export function changedKeys<T extends Record<string, any>>(oldT: T, newT: T): (keyof T)[] {
    if (deepEquals(oldT, newT)) {
        return []
    } else {
        return Object.keys(oldT).reduce((acc: string[], next: string) => {
            if (!deepEquals(oldT[next], newT[next])) {
                acc.push(next)
            }
            return acc
        }, [])
    }
}

export function deepFreeze<T>(obj: T) {
    function go(obj: T) {
        if (obj instanceof StateHandler) {
            throw new Error("Attempting to freeze a StateHandler – is there a StateHandler embedded in the state?")
        }
        if (obj && typeof obj === "object") {
            Object.values(obj).forEach(v => go(v))
            return Object.isFrozen(obj) ? obj : Object.freeze(obj)
        } else {
            return obj
        }
    }

    return go(obj)
}

export function deepCopy<T>(obj: T, keepFrozen: boolean = false): T {
    if (obj instanceof Array) {
        return [...obj].map(item => deepCopy(item)) as any as T;
    } else if (obj === null || typeof obj === 'undefined') {
        return obj;
    } else if (typeof obj === 'object' && (!keepFrozen || !Object.isFrozen(obj))) {
        const result: any = {};
        const objAny = obj as any;
        for (let key of Object.getOwnPropertyNames(objAny)) {
            result[key] = deepCopy(objAny[key]);
        }
        Object.setPrototypeOf(result, Object.getPrototypeOf(obj));
        return result as T;
    }
    return obj;
}

export function equalsByKey<A, B>(a: A, b: B, keys: NonEmptyArray<(keyof A & keyof B)>): boolean {
    return keys.every(k => {
        if (k in a && k in b) {
            return deepEquals(a[k], b[k] as any) // TODO: is there a way to fiddle with the types so this works without any?
        } else return false
    })
}

export function removeKey<T>(obj: T, k: keyof T): T {
    return Object.keys(obj).reduce((acc: T, key: string) => {
        if (key !== k.toString()) {
            return { ...acc, [key]: obj[key as keyof T] }
        }

        return acc
    }, {} as T)
}

export function mapValues<V, U>(obj:{ [K in PropertyKey]: V }, f: (x: V) => U): { [K in PropertyKey]: U } {
    return Object.fromEntries(Object.entries(obj).map(([k, v]) => [k, f(v)]))
}

//*********************
//* Array helpers
//*********************

export function arrInsert<T>(arr: T[], idx: number, t: T) {
    if (idx > -1) {
        return [...arr.slice(0, idx), t, ...arr.slice(idx)]
    } else return [t, ...arr]
}

export function arrReplace<T>(arr: T[], idx: number, t: T) {
    return [...arr.slice(0, idx), t, ...arr.slice(idx + 1)]
}

export function arrDelete<T>(arr: T[], idx: number) {
    return [...arr.slice(0, idx), ...arr.slice(idx + 1)]
}

export function arrDeleteFirstItem<T>(arr: T[], item: T) {
    const idx = arr.indexOf(item);
    if (idx >= 0) {
        return [...arr.slice(0, idx), ...arr.slice(idx + 1)]
    } else return arr
}

export function unzip<A, B>(arr: [A, B][]): [A[], B[]] {
    return arr.reduce<[A[], B[]]>(([as, bs], [a, b]) => {
        return [[...as, a], [...bs, b]]
    }, [[], []])
}

export function unzip3<A, B, C>(arr: [A, B, C][]): [A[], B[], C[]] {
    return arr.reduce<[A[], B[], C[]]>(([as, bs, cs], [a, b, c]) => {
        return [[...as, a], [...bs, b], [...cs, c]]
    }, [[], [], []])
}

export function collect<T, U>(arr: T[], fun: (t: T) => U | undefined | null): U[] {
    return arr.flatMap(t => {
        const newT = fun(t)
        if (newT !== undefined && newT !== null) {
            return [newT]
        } else return []
    })
}

export function collectMatch<T, R>(arr: T[], fn: (matcher: Matcher<T>) => Matcher<T, R>): R[] {
    const result: R[] = [];
    arr.forEach(value => {
        const matched = fn(new Matcher(value)).orUndefined;
        if (matched) {
            result.push(matched);
        }
    })
    return result;
}

export function collectInstances<T, R extends T>(arr: T[], constructor: new (...args: any[]) => R): R[] {
    const result: R[] = [];
    arr.forEach(t => {
        if (t instanceof constructor) {
            result.push(t);
        }
    });
    return result;
}

export function collectFirstMatch<T, R>(arr: T[], fn: (matcher: Matcher<T>) => Matcher<T, R>): R | undefined {
    for (let i = 0; i < arr.length; i++) {
        const result = fn(match(arr[i])).orUndefined;
        if (result)
            return result;
    }
    return undefined;
}

export function findInstance<T, U>(arr: T[], u: new (...args: any[]) => U): U | undefined {
    const result = arr.find(t => t instanceof u);
    return result ? result as any as U : undefined;
}

export function partition<T>(arr: T[], fun: (t: T) => boolean): [T[], T[]] {
    return arr.reduce<[T[], T[]]>((acc, next) => {
        if (fun(next)) {
            return [[...acc[0], next], acc[1]]
        } else {
            return [acc[0], [...acc[1], next]]
        }
    }, [[], []])
}

export function mapSome<T>(arr: T[], cond: (t: T) => boolean, fn: (t: T) => T): T[] {
    return arr.map(item => {
        if (cond(item)) {
            return fn(item)
        } else return item
    })
}

//****************
//* String Helpers
//****************

/**
 * Split a string by line breaks, keeping the line breaks in the results
 */
export function splitWithBreaks(outputStr: string): string[] {
    const result: string[] = [];
    const matches = outputStr.match(/[^\n]+\n?/g);
    if (matches)
        matches.forEach(line => result.push(line));
    return result;
}

export function positionIn(str: string, line: number, column: number): number {
    const lines = splitWithBreaks(str);
    const targetLine = Math.min(line - 1, lines.length);
    let pos = 0;
    let currentLine = 0;
    for (currentLine = 0; currentLine < targetLine; currentLine++) {
        pos += lines[currentLine].length;
    }

    if (currentLine < lines.length) {
        pos += column;
    }
    return pos;
}

//****************
//* Other Helpers
//****************

export function isDescendant(el: HTMLElement, maybeAncestor: HTMLElement, bound?: HTMLElement): boolean {
    let current: HTMLElement | null = el;
    while (current && current !== bound) {
        if (current === maybeAncestor) {
            return true;
        }
        current = current.parentElement;
    }
    return false;
}

export function mapOpt<T, U>(value: T | undefined, fn: (arg: T) => U): U | undefined {
    if (value !== undefined) {
        return fn(value);
    }
    return undefined;
}

export type InterfaceOf<T> =
    T extends string ? T :
    T extends Array<infer U> ? Array<InterfaceOf<U>> :
    T extends Object ? {
        [P in keyof T]: InterfaceOf<T[P]>
    } :
    T

export class Deferred<T> implements Promise<T> {
    private _promise: Promise<T>;
    resolve: (value?: (PromiseLike<T> | T)) => void;
    reject: (reason?: any) => void;

    isSettled: boolean = false;

    // To implement Promise
    readonly [Symbol.toStringTag]: string;

    constructor() {
        this._promise = new Promise<T>((resolve, reject) => {
            // assign the resolve and reject functions to `this`
            // making them usable on the class instance
            this.resolve = resolve;
            this.reject = reject;
        });
        // bind `then` and `catch` to implement the same interface as Promise
        this.then = this._promise.then.bind(this._promise);
        this.catch = this._promise.catch.bind(this._promise);
        this[Symbol.toStringTag] = 'Promise';

        this.finally(() => {
            this.isSettled = true
        })
    }

    then<TResult1 = T, TResult2 = never>(onfulfilled?: ((value: T) => (PromiseLike<TResult1> | TResult1)) | undefined | null, onrejected?: ((reason: any) => (PromiseLike<TResult2> | TResult2)) | undefined | null): Promise<TResult1 | TResult2> {
        return this._promise.then(onfulfilled, onrejected);
    }

    catch<TResult = never>(onrejected?: ((reason: any) => (PromiseLike<TResult> | TResult)) | undefined | null): Promise<T | TResult> {
        return this._promise.catch(onrejected);
    }

    finally(onfinally?: (() => void) | undefined | null): Promise<T> {
        return this._promise.finally(onfinally);
    }

}