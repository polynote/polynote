'use strict';

import * as fastEquals from 'fast-deep-equal/es6';
import match, {Matcher} from "./match";

export function posToRange(loc: {line: number, column: number}) {
    return {
        startLineNumber: loc.line,
        endLineNumber: loc.line,
        startColumn: loc.column,
        endColumn: loc.column
    }
}

export function deepEquals<T>(a: T, b: T, ignoreKeys?: (keyof T)[]): boolean {
    if ((a === undefined && b !== undefined) || (b === undefined && a !== undefined)) {
        return false;
    }
    if ((a === null && b !== null) || (b === null && a !== null)) {
        return false;
    }
    if (ignoreKeys && a && b) {
        a = ignoreKeys.reduce((acc: T, key: keyof T) => {
            return removeKeys(acc, key)
        }, a)
        b = ignoreKeys.reduce((acc: T, key: keyof T) => {
            return removeKeys(acc, key)
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

/**
 * A generic copy-and-update, like a case class's `copy` method.
 *
 * @param srcObj  The object to be copied
 * @param changes Optionally, a partial object of new values to be replaced in the copy
 * @return A (shallow) copy of the source object, with any given changes applied. If the source object
 *         was frozen, the copy will also be frozen.
 */
export function copyObject<T>(srcObj: T, changes?: Partial<T>): T {
    if (srcObj === null || srcObj === undefined || typeof srcObj !== 'object')
        return srcObj;
    const result: T = {...srcObj};
    if (changes !== undefined) {
        for (const prop in changes) {
            if (changes.hasOwnProperty(prop)) {
                const key = prop as keyof T;
                result[key] = changes[key]!;
            }
        }
    }
    Object.setPrototypeOf(result, Object.getPrototypeOf(srcObj));
    if (Object.isFrozen(srcObj)) {
        Object.freeze(result);
    }
    return result as T;
}

export function equalsByKey<A, B>(a: A, b: B, keys: NonEmptyArray<(keyof A & keyof B & PropertyKey)>): boolean {
    return keys.every(k => {
        if (k in (a as any) && k in (b as any)) {
            return deepEquals(a[k], b[k] as any) // TODO: is there a way to fiddle with the types so this works without any?
        } else return false
    })
}

export function removeKeys<T>(obj: T, k: (keyof T)[] | keyof T): T {
    let keyStrings: string[];
    if (k instanceof Array) {
        keyStrings = k.map(key => key.toString())
    } else {
        keyStrings = [k.toString()]
    }
    return Object.keys(obj as any).reduce((acc: T, key: string) => {
        if (! keyStrings.includes(key)) {
            return { ...acc, [key]: obj[key as keyof T] }
        }

        return acc
    }, {} as T)
}

export function mapValues<V, U>(obj:{ [K in PropertyKey]: V }, f: (x: V) => U): { [K in PropertyKey]: U } {
    return Object.fromEntries(Object.entries(obj).map(([k, v]) => [k, f(v)]))
}

export function collectFields<K extends PropertyKey, V, V1>(obj: Record<K, V>, fn: (key: K, value: V) => V1 | undefined): Record<K, V1> {
    const results: Record<K, V1> = {} as Record<K, V1>;
    for (const prop in obj) {
        if (obj.hasOwnProperty(prop)) {
            const k = prop as K;
            const result = fn(k, obj[k]);
            if (result !== undefined) {
                results[k] = result;
            }
        }
    }
    return results;
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
    if (idx > -1) {
        return [...arr.slice(0, idx), t, ...arr.slice(idx + 1)]
    } else return [t, ...arr]
}

export function arrDelete<T>(arr: T[], idx: number) {
    if (idx > -1) {
        return [...arr.slice(0, idx), ...arr.slice(idx + 1)]
    } else return arr
}

export function arrDeleteFirstItem<T>(arr: T[], item: T) {
    const idx = isObject(item) || Array.isArray(item)
        ? arr.findIndex(i => deepEquals(i, item))
        : arr.indexOf(item)

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

export function collect<T, U>(arr: T[], fun: (t: T, index: number) => U | undefined | null): U[] {
    return arr.flatMap((t, index) => {
        const newT = fun(t, index)
        if (newT !== undefined && newT !== null) {
            return [newT]
        } else return []
    })
}

export function collectDefined<T>(...arr: (T | undefined)[]): T[] {
    return arr.filter(value => value !== undefined) as T[];
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

export function arrExists<T>(arr: T[], fun: (t: T) => boolean): boolean {
    for (let i = 0; i < arr.length; i++) {
        if (fun(arr[i])) {
            return true;
        }
    }
    return false;
}

/**
 * Foreach that wont go crazy if the callback for an element causes that element to be removed.
 * It goes backwards.
 */
export function safeForEach<T>(arr: T[], fun: (t: T, index: number, arr: T[]) => void): void {
    for(let idx = arr.length - 1; idx >= 0; idx--) {
        fun(arr[idx], idx, arr)
    }
}

/**
 * Partitions an array into two arrays according to the predicate.
 *
 * @param arr   T[]
 * @param pred  A predicate of T => boolean
 * @return      A pair of T[]s. The first T[] consists of all elements that satisfy the predicate, the second T[]
 *              consists of all elements that don't. The relative order of the elements in the resulting T[] is the same
 *              as in the original T[].
 */
export function partition<T>(arr: T[], pred: (t: T) => boolean): [T[], T[]] {
    return arr.reduce<[T[], T[]]>((acc, next) => {
        if (pred(next)) {
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

export function arrayStartsWith<T>(base: T[], start: T[]) {
    if (start.length > base.length) return false;

    for (let i = 0; i < start.length; i++) {
        if (! deepEquals(base[i], start[i])) {
            return false;
        }
    }

    return true;
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
//* String Helpers
//****************

export function parseQuotedArgs(args: string): string[] {
    function parseQuoted(rest: string): [string, string] {
        const match = /^((?:[^"\\]|\\.)*)"/.exec(rest);
        if (!match || !match[1])
            return [rest, ""];
        const arg = match[1];
        return [arg.replace('\\"', '"'), rest.substr(arg.length + 1)]
    }

    function parseUnquoted(rest: string): [string, string] {
        const nextSpace = rest.indexOf(' ');
        if (nextSpace === -1)
            return [rest, ""];
        return [rest.substring(0, nextSpace), rest.substring(nextSpace + 1)];
    }

    function parseNext(rest: string): [string, string] {
        rest = rest.trimLeft();
        if (!rest)
            return ["", ""];
        if (rest.charAt(0) === '"')
            return parseQuoted(rest.substring(1));
        return parseUnquoted(rest);
    }

    const result = [];
    let remaining = args;
    while (remaining.length) {
        const next = parseNext(remaining);
        if (next[0])
            result.push(next[0]);
        remaining = next[1];
    }
    return result;
}


function quoted(str: string | undefined): string {
    if (str && (str.indexOf('"') !== -1 || str.indexOf(' ') !== -1)) {
        return ['"', str.replace('\\', '\\\\').replace('"', '\\"'), '"'].join('');
    } else {
        return str || "";
    }
}

export function joinQuotedArgs(strs: string[] | undefined): string | undefined {
    return strs?.map(quoted).join(' ')
}

//****************
//* Date Helpers
//****************

const dayFmt = new Intl.DateTimeFormat(undefined, {weekday: 'long'})

export function getHumanishDate(timestamp: number): string {
    const date = new Date(timestamp);
    const now = new Date();
    const age = now.getTime() - timestamp;
    if (age <= 604800000) {
        // if it's under a week ago
        let daysAgo = now.getDay() - date.getDay()
        if (daysAgo < 0)
            daysAgo = 7 - daysAgo
        switch (daysAgo) {
            case 0: return `Today at ${date.toLocaleTimeString(undefined, {hour: 'numeric', minute: 'numeric'})}`
            case 1: return `Yesterday at ${date.toLocaleTimeString(undefined, {hour: 'numeric', minute: 'numeric'})}`
            default: return `${dayFmt.format(date)} at ${date.toLocaleTimeString()}`
        }
    } else if (age <= 31556926000) {
        // if it's under a year ago
        return `${date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} at ${date.toLocaleTimeString(undefined, { hour: 'numeric', minute: 'numeric' }) }`
    }
    return `${date.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })} at ${date.toLocaleTimeString(undefined, { hour: 'numeric', minute: 'numeric' }) }`
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

export function nameFromPath(path: string): string {
    return path.split(/\//g).pop()!;
}

//                                                       line,   pos
export function linePosAt(str: string, offset: number): [number, number] {
    let line = 0;
    let index = 0;
    offset = Math.min(str.length - 1, offset);
    while (index < offset) {
        const nextLineIndex = str.indexOf("\n", index);
        if (nextLineIndex === -1 || nextLineIndex >= offset) {
            return [line, offset - index];
        }
        index = nextLineIndex + 1;
        line++;
    }
    return [line, 0];
}

export function TODO(): never {
    console.error("An implementation is missing!")
    throw new Error("An implementation is missing!")
}