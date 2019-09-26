/**
 * Interface for a class constructor type that has an unapply method. Any object we create codecs for has to implement
 * this interface.
 */
export interface ExtractorConstructor<T> {
    new(...value: any): T;

    unapply<A>(instance: T): any[];
}

// inspired by https://github.com/gcanti/fp-ts
export interface Left<T> {
    readonly _kind: 'Left',
    readonly left: T
}

export interface Right<T> {
    readonly _kind: 'Right',
    readonly right: T
}

export interface Both<L, R> {
    readonly _kind: 'Both'
    readonly right: R,
    readonly left: L
}

export class Ior {
    // has to be static because type guards require arguments so we can make it a proper type guard
    static isLeft<L, R>(ior: Left<L> | Right<R> | Both<L, R>): ior is Left<L> {
        return (ior as Left<L>).left !== undefined
            && (ior as Both<L, R>).right === undefined // needed otherwise isLeft(Both) will be true
    }

    static isRight<L, R>(ior: Left<L> | Right<R> | Both<L, R>): ior is Right<R> {
        return (ior as Right<R>).right !== undefined
            && (ior as Both<L, R>).left === undefined // needed otherwise isRight(Both) will be true
    }

    static isBoth<L, R>(ior: Left<L> | Right<R> | Both<L, R>): ior is Both<L, R> {
        const b = (ior as Both<L, R>);
        return b.left !== undefined && b.right !== undefined
    }

    static left<T>(left: T) {
        return <Left<T>>{left: left};
    }

    static right<T>(right: T) {
        return <Right<T>>{right: right};
    }

    static both<L, R>(left: L, right: R) {
        return <Both<L, R>>{left: left, right: right};
    }
}

export class Either {

    static isLeft<L, R>(either: Left<L> | Right<R>): either is Left<L> {
        return (either as Left<L>).left !== undefined;
    }

    static isRight<L, R>(either: Left<L> | Right<R>): either is Right<R> {
        return (either as Right<R>).right !== undefined;
    }

    static left<T>(left: T) {
        return <Left<T>>{left: left};
    }

    static right<T>(right: T) {
        return <Right<T>>{right: right};
    }
}