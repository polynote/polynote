'use strict';

import * as deepEquals from 'fast-deep-equal/es6';

export function diffArray<T>(a: T[], b: T[], equality: (a: T, b: T) => boolean = (a: T, b: T) => deepEquals(a, b)): [T[], T[]] {
    const aNotB = a.filter(x => b.findIndex(el => equality(x, el)) === -1);
    const bNotA = b.filter(x => a.findIndex(el => equality(x, el)) === -1);
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