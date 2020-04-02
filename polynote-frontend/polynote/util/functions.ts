'use strict';

export function diffArray<T>(a: T[], b: T[]): [T[], T[]] {
    const aNotB = a.filter(x => !b.includes(x));
    const bNotA = b.filter(x => !a.includes(x));
    return [aNotB, bNotA]
}
