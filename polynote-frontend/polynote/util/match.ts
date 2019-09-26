export default function match<T>(obj: T) {
    return new Matcher(obj);
}

export class MatchError {
    constructor(readonly obj: any) {
        Object.freeze(this);
    }
}

export interface Extractable<T> {
  new (...args: any[]): T;
  unapply(t: T): any[]
}

export class Matcher<T> {
    private result: any | null;
    constructor(readonly obj: T) {}

    // TODO: is there any magic way to get types for `args`? We *know* the types - they're the result of the `type`'s unapply!
    when<U extends T, Return>(type: Extractable<U>, fn: (...args: ConstructorParameters<Extractable<U>>) => any) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(...type.unapply(this.obj)) || null;
        }
        return this;
    }

    otherwise<U>(value: U) {
        if (this.result !== undefined) {
            return this.result;
        } else {
            return value;
        }
    }

    get otherwiseThrow() {
        if (this.result !== undefined) {
            return this.result;
        } else {
            throw new MatchError(this.obj);
        }
    }
}