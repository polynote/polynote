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

export class Matcher<T> {
    protected result: any | null;
    constructor(readonly obj: T) {}

    when<U extends T, C extends Extractable<U, ConstructorParameters<C>>>(type: C, fn: (...args: ConstructorParameters<C>) => any) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(...type.unapply(this.obj)) || null;
        }
        return this;
    }

    whenInstance<C extends (new (...args: any[]) => InstanceType<C>)>(type: C, fn: (inst: InstanceType<C>) => any) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(this.obj) || null;
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

// Match methods in a PureMatcher must return a value and are ideally not effectful.
export function purematch<T, R>(obj: T) {
    return new PureMatcher<T, R>(obj);
}
export class PureMatcher<T, R> extends Matcher<T> {
    when<U extends T, C extends Extractable<U, ConstructorParameters<C>>>(type: C, fn: (...args: ConstructorParameters<C>) => R | null) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(...type.unapply(this.obj)) || null;
        }
        return this;
    }

    whenInstance<C extends (new (...args: any[]) => InstanceType<C>)>(type: C, fn: (inst: InstanceType<C>) => R | null) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(this.obj) || null;
        }
        return this;
    }

    get otherwiseThrow(): R | null {
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
}