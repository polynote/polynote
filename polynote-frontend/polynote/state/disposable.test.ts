import {Disposable} from "./disposable";
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