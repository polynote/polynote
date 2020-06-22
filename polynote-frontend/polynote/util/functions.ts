'use strict';

import * as deepEquals from 'fast-deep-equal/es6';

//*********************
//* Object helpers
//*********************

// Checks if variable is an object (and not an array, even though arrays are technically objects). Maybe there's a more correct name for this method.
export function isObject(obj: any): obj is object {
    return obj && typeof obj === "object" && !Array.isArray(obj)
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

export function equalsByKey<A, B>(a: A, b: B, keys: (keyof A & keyof B)[]): boolean {
    return keys.every(k => {
        if (k in a && k in b) {
            return deepEquals(a[k], b[k])
        } else return false
    })
}

export function removeKey<T>(obj: T, k: keyof T): T {
    return Object.keys(obj).reduce((acc: T, key: string) => {
        if (key !== k) {
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

export function unzip<A, B>(arr: [A, B][]): [A[], B[]] {
    return arr.reduce<[A[], B[]]>(([as, bs], [a, b]) => {
        as.push(a)
        bs.push(b)
        return [as, bs]
    }, [[], []])
}

export function collect<T, U>(arr: T[], fun: (t: T) => U | undefined | null): U[] {
    return arr.flatMap(t => {
        const newT = fun(t)
        if (newT) {
            return [newT]
        } else return []
    })
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