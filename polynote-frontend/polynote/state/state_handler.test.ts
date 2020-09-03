import {Disposable, Observer, StateHandler, StateView} from "./state_handler";
import {waitFor} from "@testing-library/dom";
import {deepEquals} from "../util/helpers";

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
    it("can't be disposed more than once", async () => {
        const disposable = new Disposable()
        disposable.dispose()
        await waitFor(() => {
            expect(() => disposable.dispose()).toThrow()
        })
    })
})

describe("StateHandler", () => {
    it("holds some state value that can be updated", () => {
        const s = new StateHandler(1)
        expect(s.state).toEqual(1)
        s.updateState(() => 2)
        expect(s.state).toEqual(2)
    })
    it("allows observation of state changes", done => {
        const s = new StateHandler(1)
        s.addObserver((newState, oldState) => {
            expect(newState).toEqual(2)
            expect(oldState).toEqual(1)
            done()
        })
        s.updateState(() => 2)
    })
    it("doesn't call observers when the state isn't changed", () => {
        const s = new StateHandler(1)
        s.addObserver(() => {
            throw new Error("you better not change the state!!")
        })
        expect(() => s.updateState(() => 2)).toThrow()
        expect(s.state).toEqual(2)
        expect(() => s.updateState(() => 2)).not.toThrow()
    })
    test("observers can be removed / cleared", () => {
        const s = new StateHandler(1)
        let obsStateChange = false;
        const obs = s.addObserver(() => {
            if (obsStateChange) {
                throw new Error("error from obs")
            }
            obsStateChange = true;
        })
        let anonStateChange = false;
        s.addObserver(() => {
            if (anonStateChange) {
                throw new Error("error from anonymous")
            }
            anonStateChange = true;
        })
        s.updateState(() => 2)
        expect(s.state).toEqual(2)
        expect(() => s.updateState(() => 3)).toThrowError("error from obs")
        expect(s.state).toEqual(3)
        s.removeObserver(obs)
        expect(() => s.updateState(() => 4)).toThrowError("error from anonymous")
        s.clearObservers()
        s.updateState(() => 5)
        expect(s.state).toEqual(5)
    })
    it("can be disposed", async () => {
        const s = new StateHandler(1)
        const obFn = jest.fn()
        s.addObserver(obFn)
        s.updateState(() => 2)
        expect(obFn).toHaveBeenCalledTimes(1)
        expect(obFn).toHaveBeenCalledWith(2, 1)
        s.dispose()
        s.onDispose.then(() => {
            expect(() => s.state).toThrowError()
            expect(() => s.updateState(() => 3)).toThrowError()
            expect(obFn).toHaveBeenCalledTimes(1)
        })
    })
    it("can be linked to the lifecycle of another Disposable", done => {
        const d = new Disposable()
        const s = new StateHandler(1, d)
        const obFn = jest.fn()
        s.addObserver(obFn)
        s.updateState(() => 2)
        expect(obFn).toHaveBeenCalledTimes(1)
        expect(obFn).toHaveBeenCalledWith(2, 1)
        d.dispose()  // disposed `d`, now expect `s` to also be disposed
        s.onDispose.then(() => {
            expect(() => s.updateState(() => 3)).toThrowError()
            expect(obFn).toHaveBeenCalledTimes(1)
            done()
        })
    })
    it("supports views of object states", () => {
        const sh = new StateHandler({a: 1, b: 2})
        const view = sh.view("a")
        expect(sh.state).toEqual({a: 1, b: 2})
        expect(view.state).toEqual(1)

        const obState = jest.fn()
        sh.addObserver(obState)

        const obA = jest.fn()
        view.addObserver(obA)

        sh.updateState(s => ({...s, a: 2}))
        expect(view.state).toEqual(2)

        sh.updateState(s => ({...s, b: 100}))
        expect(view.state).toEqual(2) // stays the same

        expect(obState).toHaveBeenCalledTimes(2)
        expect(obA).toHaveBeenCalledTimes(1)
    })
    it("supports mapViews", () => {
        const sh = new StateHandler({a: 1, b: 2})
        const view = sh.mapView("a", a => a.toString())

        expect(sh.state).toEqual({a: 1, b: 2})
        expect(view.state).toEqual("1")

        const obState = jest.fn()
        sh.addObserver(obState)

        const obA = jest.fn()
        view.addObserver(obA)

        sh.updateState(s => ({...s, a: 2}))
        expect(view.state).toEqual("2")

        sh.updateState(s => ({...s, b: 100}))
        expect(view.state).toEqual("2") // stays the same

        expect(obState).toHaveBeenCalledTimes(2)
        expect(obA).toHaveBeenCalledTimes(1)
    })
    test("views are disposed when parent is disposed", () => {
        const parent = new StateHandler({a: 1, b: 2})
        const view = parent.view("a");
        const obFn = jest.fn()
        view.addObserver(obFn)
        parent.updateState(s => ({...s, a: 2}))
        expect(obFn).toHaveBeenCalledTimes(1)
        expect(obFn).toHaveBeenCalledWith(2, 1)
        parent.dispose()
        view.onDispose.then(() => {
            expect(() => view.state).toThrowError()
            expect(obFn).toHaveBeenCalledTimes(1)
        })
    })
    test("views can also synchronize with another Disposable", () => {
        const d = new Disposable()
        const parent = new StateHandler({a: 1, b: 2})
        const view = parent.view("a", undefined, d);
        const obFn = jest.fn()
        view.addObserver(obFn)
        parent.updateState(s => ({...s, a: 2}))
        expect(obFn).toHaveBeenCalledTimes(1)
        expect(obFn).toHaveBeenCalledWith(2, 1)
        d.dispose()
        view.onDispose.then(() => {
            expect(() => view.state).toThrowError()
            expect(obFn).toHaveBeenCalledTimes(1)
        })
    })
    test("views can be passed a constructor", () => {
        const parent = new StateHandler({a: 1, b: 2});

        const view = parent.view("a")
        const viewObs = jest.fn()
        view.addObserver(viewObs)

        class AddingStorageHandler extends StateView<number> {
            readonly someProperty: number = 100;
            constructor( initial: number) {
                super(initial);
            }

            setState(newState: number) {
                super.setState(newState + 10);
            }
        }
        const addingView = parent.view("a", AddingStorageHandler);
        expect(addingView.state).toEqual(11)
        expect(addingView.someProperty).toEqual(100);
        expect(addingView instanceof AddingStorageHandler).toBeTruthy();

        const addingViewObs = jest.fn()
        addingView.addObserver(addingViewObs)
        parent.updateState(() => ({a: 2, b: 2}));
        expect(viewObs).toHaveBeenCalledWith(2, 1)
        expect(addingViewObs).toHaveBeenCalledWith(12, 11)

        expect(view.state).toEqual(2)
        expect(addingView.state).toEqual(12)
    });
    test("equality comparison can be extended", () => {
        const state = () => ({a: 1, b: 2})
        const sh = new StateHandler(state())
        sh.addObserver(() => {
            throw new Error("you better not change the state!!")
        })
        expect(() => sh.updateState(() => state())).toThrow()  // since state is a new object, this throws because equality is not deep.

        const deep = new class<S> extends StateHandler<S> {
            protected compare(s1: any, s2: any): boolean {
                return deepEquals(s1, s2)
            }
        }({a: 1, b: 2})
        deep.addObserver(() => {
            throw new Error("you better not change the state!@")
        })
        expect(() => deep.updateState(() => state())).not.toThrow()
    })
})