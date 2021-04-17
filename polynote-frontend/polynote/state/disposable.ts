import {Deferred} from "../util/helpers";

export interface VoidPromiseLike {
    then(fn: () => void): VoidPromiseLike
    abort(fn: () => void): void
}

export interface IDisposable {
    onDispose: VoidPromiseLike
    dispose(): VoidPromiseLike
    tryDispose(): VoidPromiseLike
    isDisposed: boolean
    disposeWith(that: IDisposable): this
}

export function mkDisposable<T>(t: T, onDispose: () => void = () => {}): T & IDisposable {
    const deferred: VoidPromise = new VoidPromise()
    const doDispose: () => VoidPromiseLike = () => {
        if (!deferred.isSettled) {
            deferred.resolve();
            onDispose();
        }
        return deferred;
    }
    Object.defineProperties(t, {
        dispose: { value: doDispose },
        tryDispose: { value: doDispose },
        isDisposed: { value: () => !deferred.isSettled },
        onDispose: { value: deferred },
        disposeWith: { value: (that: IDisposable) => {
                let weakThat: IDisposable | undefined = that;
                const l = () => {
                    doDispose();
                    if (weakThat) {
                        weakThat.onDispose.abort(l);
                        weakThat = undefined;
                    }
                }
                that.onDispose.then(l);
                deferred.then(l);
                return t;
            }}
    });
    return t as T & IDisposable;
}

/**
 * This is a fake "Promise" that does everything synchronously. It just captures observers in the `then` method, and
 * when it's settled, it invokes them.
 */
export class VoidPromise implements VoidPromiseLike {
    private _isSettled: boolean = false;
    get isSettled(): boolean { return this._isSettled }

    private observers: (() => void)[] = [];

    then(onfulfilled: () => void): VoidPromise {
        if (this._isSettled) {
            onfulfilled();
        } else {
            this.observers.push(onfulfilled);
        }
        return this;
    }

    abort(fn: () => void): void {
        if (this._isSettled) {
            return;
        }

        const idx = this.observers.indexOf(fn);
        if (idx >= 0) {
            this.observers.splice(idx, 1);
        }
    }

    resolve() {
        if (!this._isSettled) {
            this._isSettled = true;
            this.observers.forEach(obs => obs());
            this.observers = [];
        }
    }

}

export class Disposable implements IDisposable {
    private deferred: VoidPromise = new VoidPromise()
    readonly onDispose: VoidPromiseLike = this.deferred

    dispose(): VoidPromiseLike {
        return this.tryDispose()
    };

    tryDispose(): VoidPromiseLike {
        if (!this.isDisposed) {
            this.deferred.resolve();
        }
        return this.deferred;
    }

    get isDisposed() {
        return this.deferred.isSettled
    }

    disposeWith(that: IDisposable): this {
        // Promise.race([this.deferred, that.onDispose]).then(() => this.tryDispose());
        let weakThat: IDisposable | undefined = that;
        const l = () => {
            if (weakThat) {
                weakThat.onDispose.abort(l);
                weakThat = undefined;
            }
            this.dispose();
        }
        that.onDispose.then(l);
        this.deferred.then(l);
        return this;
    }
}

/**
 * A Disposable that runs a callback immediately on disposing. This is useful if disposal needs to have a synchronous
 * side-effect, as .onDispose.then(...) causes the side effect to run asynchronously.
 */
export class ImmediateDisposable extends Disposable {
    constructor(private callback: () => void) {
        super();
    }

    dispose(): VoidPromiseLike {
        return this.tryDispose();
    }

    tryDispose(): VoidPromiseLike {
        const disposed = this.isDisposed;
        if (!disposed) {
            this.callback();
        }
        return super.tryDispose();
    }
}