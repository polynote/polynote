"use strict";

export default function match(obj) {
    return new Matcher(obj);
}

export class MatchError {
    constructor(obj) {
        this.obj = obj;
        Object.freeze(this);
    }
}

export class Matcher {
    constructor(obj) {
        this.obj = obj;
    }

    when(type, fn) {
        if (this.result === undefined && this.obj instanceof type) {
            this.result = fn(...type.unapply(this.obj)) || null;
        }
        return this;
    }

    otherwise(value) {
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