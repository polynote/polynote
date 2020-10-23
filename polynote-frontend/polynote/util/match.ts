import {deepEquals} from "./helpers";

export default function match<T>(obj: T) {
    return new Matcher(obj);
}

export class MatchError {
    constructor(readonly obj: any) {
        Object.freeze(this);
    }
}

export interface Extractable<T, Args> {
  new (...args: any[]): T;
  unapply(t: T): Args
}

export class Matcher<T, R = any> {
    protected result: R | undefined;
    constructor(readonly obj: T) {}

    typed<R1>(): Matcher<T, R1> {
        if (this.result !== undefined) {
            throw new Error("Must call `typed` before defining any match cases");
        }
        return this as any as Matcher<T, R1>;
    }

    when<U extends T, C extends Extractable<U, ConstructorParameters<C>>>(type: C, fn: (...args: ConstructorParameters<C>) => R) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(...type.unapply(this.obj));
        }
        return this;
    }

    whenP(predicate: (value: T) => boolean, fn: (value: T) => R) {
        if (this.result === undefined && predicate(this.obj)) {
            this.result = fn(this.obj);
        }
        return this;
    }

    whenV(cmpValue: T, fn: (value: T) => R) {
        if (this.result === undefined && deepEquals(this.obj, cmpValue)) {
            this.result = fn(this.obj);
        }
        return this;
    }

    whenInstance<C extends (new (...args: any[]) => InstanceType<C>)>(type: C, fn: (inst: InstanceType<C>) => R) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(this.obj);
        }
        return this;
    }

    otherwise<U>(value: U): R | U {
        if (this.result !== undefined) {
            return this.result;
        } else {
            return value;
        }
    }

    get otherwiseThrow(): R {
        if (this.result !== undefined) {
            return this.result;
        } else {
            throw new MatchError(this.obj);
        }
    }
}

// Match methods in a PureMatcher must return a value and are ideally not effectful.
export function purematch<T, R>(obj: T) {
    return new PureMatcher<T, R>(obj);
}
export class PureMatcher<T, R> extends Matcher<T, R> {
    when<U extends T, C extends Extractable<U, ConstructorParameters<C>>>(type: C, fn: (...args: ConstructorParameters<C>) => R) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(...type.unapply(this.obj));
        }
        return this;
    }

    whenInstance<C extends (new (...args: any[]) => InstanceType<C>)>(type: C, fn: (inst: InstanceType<C>) => R) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(this.obj);
        }
        return this;
    }

    get otherwiseThrow(): R {
        return super.otherwiseThrow
    }
}

// just a little helper which is more ergonomic than `switch` statements. No more forgetting to `break;`!
export function matchS<R>(obj: string) {
    return new StringMatcher<R>(obj)
}
export class StringMatcher<R> {
    constructor(private obj: string) {}
    private result: R | null;

    when(str: string, fn: () => R | void): StringMatcher<R> {
        if (this.obj === str) {
            this.result = fn() || null;
        }
        return this
    }

    get otherwiseThrow(): R | null {
        if (this.result !== undefined) {
            return this.result
        } else {
            throw new MatchError(this.obj)
        }
    }

    otherwise<U>(value: U) {
        if (this.result !== undefined) {
            return this.result;
        } else {
            return value;
        }
    }
}