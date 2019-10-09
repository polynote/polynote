'use strict';

export function isEqual(a: any, b: any) {
    if (a === b)
        return true;

    if (a instanceof Array || a instanceof Object) {
        if (a.constructor !== b.constructor)
            return false;

        for (const i in a) {
            if (!isEqual(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    return false;
}

// Use to avoid the common error of `x || y` or `if (x) {...}` and forgetting about `0`
// TODO: once typescript releases 3.7 use the new `??` operator instead of this!!
// export function _<T, U>(x: T, orElse: U): T | U {
//     if (x !== null && x !== undefined) {
//         return x
//     } else {
//         return orElse
//     }
// }