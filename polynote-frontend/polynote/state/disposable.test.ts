import {Disposable, VoidPromise} from "./disposable";
import {waitFor} from "@testing-library/dom";

describe("Disposable", () => {
    it("can be disposed", async () => {
        const disposable = new Disposable()
        expect(disposable.isDisposed).toBeFalsy()
        disposable.dispose()
        await waitFor(() => {
            expect(disposable.isDisposed).toBeTruthy()
        })
    })
    it("can trigger functions when disposed", resolve => {
        const disposable = new Disposable()
        disposable.onDispose.then(() => resolve())
        disposable.dispose()
    })

    it("can be disposed along with another disposable", async () => {
        const disposable1 = new Disposable()
        const disposable2 = new Disposable().disposeWith(disposable1)
        disposable1.dispose();
        await disposable2.onDispose;
    })
})

describe("VoidPromise", () => {
    it("invokes callbacks when resolved", () => {
        const cb1 = jest.fn();
        const cb2 = jest.fn();
        const promise = new VoidPromise().then(cb1).then(cb2);
        promise.resolve();
        expect(cb1).toHaveBeenCalledTimes(1);
        expect(cb2).toHaveBeenCalledTimes(1);
    })

    it("can remove a callback", () => {
        const cb1 = jest.fn();
        const cb2 = jest.fn();
        const promise = new VoidPromise().then(cb1).then(cb2);
        promise.abort(cb1);
        promise.resolve();
        expect(cb1).toHaveBeenCalledTimes(0);
        expect(cb2).toHaveBeenCalledTimes(1);
    })
})