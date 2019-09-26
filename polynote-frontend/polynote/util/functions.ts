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