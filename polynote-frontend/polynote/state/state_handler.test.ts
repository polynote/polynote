import {Disposable, Observer, StateHandler, StateView, StateWrapper} from "./state_handler";
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
        const s = StateHandler.from(1)
        expect(s.state).toEqual(1)
        s.update(() => 2)
        expect(s.state).toEqual(2)
    })
    it("allows observation of state changes", done => {
        const s = StateHandler.from(1)
        s.addObserver((newState: number, oldState: number) => {
            expect(newState).toEqual(2)
            expect(oldState).toEqual(1)
            done()
        }, s)
        s.update(() => 2)
    })
    it("doesn't call observers when the state isn't changed", () => {
        const s = StateHandler.from(1)
        s.addObserver(() => {
            throw new Error("you better not change the state!!")
        }, s)
        expect(() => s.update(() => 2)).toThrow()
        expect(s.state).toEqual(2)
        expect(() => s.update(() => 2)).not.toThrow()
    })
    test("observers can be removed / cleared", () => {
        const s = StateHandler.from(1)
        let obsStateChange = false;
        const obs = s.addObserver(() => {
            if (obsStateChange) {
                throw new Error("error from obs")
            }
            obsStateChange = true;
        }, s)
        let anonStateChange = false;
        s.addObserver(() => {
            if (anonStateChange) {
                throw new Error("error from anonymous")
            }
            anonStateChange = true;
        }, s)
        s.update(() => 2)
        expect(s.state).toEqual(2)
        expect(() => s.update(() => 3)).toThrowError("error from obs")
        expect(s.state).toEqual(3)
        s.removeObserver(obs)
        expect(() => s.update(() => 4)).toThrowError("error from anonymous")
        s.clearObservers()
        s.update(() => 5)
        expect(s.state).toEqual(5)
    })
    it("can be disposed", async () => {
        const s = StateHandler.from(1)
        const obFn = jest.fn()
        s.addObserver(obFn, s)
        s.update(() => 2)
        expect(obFn).toHaveBeenCalledTimes(1)
        expect(obFn).toHaveBeenCalledWith(2, 1, undefined)
        s.dispose()
        s.onDispose.then(() => {
            expect(obFn).toHaveBeenCalledTimes(1)
        })
    })
    it("can be linked to the lifecycle of another Disposable", done => {
        const d = new Disposable()
        const s = StateHandler.from(1)
        d.onDispose.then(() => s.dispose())
        const obFn = jest.fn()
        s.addObserver(obFn, s)
        s.update(() => 2)
        expect(obFn).toHaveBeenCalledTimes(1)
        expect(obFn).toHaveBeenCalledWith(2, 1, undefined)
        d.dispose()  // disposed `d`, now expect `s` to also be disposed
        s.onDispose.then(() => {
            expect(obFn).toHaveBeenCalledTimes(1)
            done()
        })
    })
    it("supports views of object states", () => {
        const sh = StateHandler.from({a: 1, b: 2})
        const view = sh.view("a")
        expect(sh.state).toEqual({a: 1, b: 2})
        expect(view.state).toEqual(1)

        const obState = jest.fn()
        sh.addObserver(obState, sh)

        const obA = jest.fn()
        view.addObserver(obA, sh)

        sh.update(s => ({...s, a: 2}))
        expect(view.state).toEqual(2)

        sh.update(s => ({...s, b: 100}))
        expect(view.state).toEqual(2) // stays the same

        expect(obState).toHaveBeenCalledTimes(2)
        expect(obA).toHaveBeenCalledTimes(1)
    })
    it("supports nested views", () => {
        const sh = StateHandler.from<{s: {a: Record<string, number>}, c: number}>({
            s: {
                a: {
                    b: 1
                },
            },
            c: 2
        })
        const sView = sh.view("s")
        const obS = jest.fn()
        sView.addObserver(obS, sh)
        const aView = sView.view("a")
        const obA = jest.fn()
        aView.addObserver(obA, sh)
        sh.update(state => ({...state, s: {...state.s, a: {...state.s.a, b: 2}}}))

        expect(obS).toHaveBeenCalledWith({a: {b: 2}}, {a: {b: 1}}, undefined)
        expect(obA).toHaveBeenCalledWith({b: 2}, {b: 1}, undefined)

        sh.update(state => ({...state, s: {...state.s, a: {...state.s.a, b: 2, foo: 100}}}))
        expect(obA).toHaveBeenCalledWith({b: 2, foo: 100}, {b: 2}, undefined)

        const fooView = sView.view("a").view("foo")
        const obFoo  = jest.fn()
        fooView.addObserver(obFoo, sh)
        expect(fooView["_state"]).toEqual(100)

        sh.update(state => ({...state, s: {...state.s, a: {...state.s.a, b: 2, foo: 200, bar: 1000}}}))
        expect(obFoo).toHaveBeenCalledWith(200, 100, undefined)

        const barView = sView.view("a").view("bar")
        const obBar  = jest.fn()
        barView.addObserver(obBar, sh)
        expect(barView["_state"]).toEqual(1000)

    })
    it("supports mapViews", () => {
        const sh = StateHandler.from({a: 1, b: 2})
        const view = sh.mapView("a", a => a.toString())

        expect(sh.state).toEqual({a: 1, b: 2})
        expect(view.state).toEqual("1")

        const obState = jest.fn()
        sh.addObserver(obState, sh)

        const obA = jest.fn()
        view.addObserver(obA, sh)

        sh.update(s => ({...s, a: 2}))
        expect(view.state).toEqual("2")

        sh.update(s => ({...s, b: 100}))
        expect(view.state).toEqual("2") // stays the same

        expect(obState).toHaveBeenCalledTimes(2)
        expect(obA).toHaveBeenCalledTimes(1)
    })
    test("views can also synchronize with another Disposable", () => {
        const d = new Disposable()
        const parent = StateHandler.from({a: 1, b: 2})
        const view = parent.view("a");
        const obFn = jest.fn()
        view.addObserver(obFn, d)
        parent.update(s => ({...s, a: 2}))
        expect(obFn).toHaveBeenCalledTimes(1)
        expect(obFn).toHaveBeenCalledWith(2, 1, undefined)
        d.dispose()
        d.onDispose.then(() => {
            expect(obFn).toHaveBeenCalledTimes(1)
        })
    })
    test("views can be wrapped", () => {
        const parent = StateHandler.from({a: 1, b: 2});

        const view = parent.view("a")
        const viewObs = jest.fn()
        view.addObserver(viewObs, parent)

        class AddingStorageHandler extends StateWrapper<number> {
            readonly someProperty: number = 100;
            constructor(view: StateView<number>) {
                super(view)
            }

            setState(newState: number) {
                super.setState(newState + 10);
            }
        }
        const addingView = new AddingStorageHandler(parent.view("a"));
        expect(addingView.state).toEqual(11)
        expect(addingView.someProperty).toEqual(100);
        expect(addingView instanceof AddingStorageHandler).toBeTruthy();

        const addingViewObs = jest.fn()
        addingView.addObserver(addingViewObs, addingView)
        parent.update(() => ({a: 2, b: 2}));
        expect(viewObs).toHaveBeenCalledWith(2, 1, undefined)
        expect(addingViewObs).toHaveBeenCalledWith(12, 11, undefined)

        expect(view.state).toEqual(2)
        expect(addingView.state).toEqual(12)
    });
    test("equality comparison can be extended", () => {
        const state = () => ({a: 1, b: 2})
        const stringyState = {a: "1", b: "2"} as any
        const sh = StateHandler.from(state())
        sh.addObserver(() => {
            throw new Error("you better not change the state!!")
        }, sh)
        expect(() => sh.update(() => stringyState)).toThrow()  // since state is a new object, this throws because equality is not deep.

        const stringy = class<S> extends StateHandler<S> {
            protected compare(s1?: any, s2?: any): boolean {
                return s1?.toString() === s2?.toString()
            }
        }.from({a: 1, b: 2})
        stringy.addObserver(() => {
            throw new Error("you better not change the state!!")
        }, sh)
        expect(() => stringy.update(() => stringyState)).not.toThrow()
    })
})