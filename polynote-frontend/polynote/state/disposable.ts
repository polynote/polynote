import {Deferred} from "../util/helpers";

export interface IDisposable {
    onDispose: Promise<void>
    dispose(): Promise<void>
    tryDispose(): Promise<void>
    isDisposed: boolean
    disposeWith(that: IDisposable): this
}

export function mkDisposable<T>(t: T): T & IDisposable {
    const disposable = new Disposable();

    return Object.defineProperties(t, {
        onDispose: { value: disposable.onDispose },
        dispose: { value: () => disposable.dispose() },
        tryDispose: { value: () => disposable.tryDispose() },
        isDisposed: { get: () => disposable.isDisposed },
        disposeWith: {
            value: (that: IDisposable) => {
                disposable.disposeWith(that);
                return t;
            }
        }
    });
}

export class Disposable implements IDisposable {
    private deferred: Deferred<void> = new Deferred()
    onDispose: Promise<void> = this.deferred

    dispose(): Promise<void> {
        return this.tryDispose()
    };

    tryDispose(): Promise<void> {
        if (!this.isDisposed) {
            this.deferred.resolve();
        }
        return this.deferred;
    }

    get isDisposed() {
        return this.deferred.isSettled
    }

    disposeWith(that: IDisposable): this {
        Promise.race([this.deferred, that.onDispose]).then(() => this.tryDispose());
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

    dispose(): Promise<void> {
        return this.tryDispose();
    }

    tryDispose(): Promise<void> {
        const disposed = this.isDisposed;
        const promise = super.tryDispose();
        if (!disposed) {
            this.callback();
        }
        return promise;
    }
}