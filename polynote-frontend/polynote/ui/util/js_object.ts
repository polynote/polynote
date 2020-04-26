// Helper methods to manipulate Javascript objects

'use strict';

// Based on http://adripofjavascript.com/blog/drips/object-equality-in-javascript.html
export function objectEquals(a: object, b: object): boolean {
    const aProps = Object.getOwnPropertyNames(a);
    const bProps = Object.getOwnPropertyNames(b);

    if (aProps.length != bProps.length) {
        return false;
    }

    for (let propName of aProps) {
        if ((a as any)[propName] !== (b as any)[propName]) {
            return false;
        }
    }
    return true;
}


export function mapValues(obj: object, f: (x: any) => any): object {
    let clone = {...obj};
    const props = Object.getOwnPropertyNames(clone);
    for (let propName of props) {
        (clone as any)[propName] = f((obj as any)[propName]);
    }
    return clone;
}
