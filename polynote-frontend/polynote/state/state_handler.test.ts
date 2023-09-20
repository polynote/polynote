import {noUpdate, ObjectStateHandler, removeKey, setValue} from ".";
import {ProxyStateView} from "./state_handler";
import {deepCopy} from "../util/helpers";

interface TestState {
    str: string
    num: number
    maybeNum?: number
    obj: {
        inner: {
            innerStr: string
            maybeInnerStr?: string
            innerNum: number
        }
        objStr: string
        objNum: number
        maybeObjStr?: string
    },
    extraField: Record<string, any>
}

const initialState: TestState = {
    str: "hello",
    num: 1,
    maybeNum: 2,
    obj: {
        inner: {
            innerStr: "inner",
            maybeInnerStr: "present",
            innerNum: 3
        },
        objStr: "yup",
        objNum: 4
    },
    extraField: {
        extraObj: {
            extraObjField: "hello"
        }
    }
}

let handler: ObjectStateHandler<TestState>;

describe("ObjectStateHandler", () => {

    beforeEach(() => {
        handler = new ObjectStateHandler<TestState>(deepCopy(initialState));
    })

    afterEach(() => {
        handler.dispose()
    })

    describe("addObserver", () => {
        it("is triggered by state update", () => {
            const listener = jest.fn()
            const obs = handler.addObserver(listener)
            handler.updateField("num", () => 2)
            expect(listener).toHaveBeenCalledTimes(1)
            expect(listener).toHaveBeenCalledWith({...initialState, num: 2}, expect.anything(), expect.anything())
            obs.dispose()
        })

        it("is not triggered by noop", () => {
            const listener = jest.fn()
            const obs = handler.addObserver(listener)
            handler.update(() => noUpdate())
            expect(listener).toHaveBeenCalledTimes(0)
            obs.dispose()
        })


        it("doesn't break when state is updated inside an observer", () => {
            const numListener = jest.fn()
            const updater = handler.observeKey("str", (newValue) => handler.updateField("num", () => newValue.length))
            const numObs = handler.observeKey("num", numListener)


            handler.update(() => ({str: "goober", num: 44}))
            expect(handler.state.num).toEqual("goober".length)
            expect(numListener).toHaveBeenCalledTimes(2)
            expect(numListener).toHaveBeenNthCalledWith(1, 44, expect.anything(), expect.anything())
            expect(numListener).toHaveBeenNthCalledWith(2, "goober".length, expect.anything(), expect.anything())
        })

        it("disposes", done => {
            const listener = jest.fn()
            const obs = handler.addObserver(listener)
            const disposed = jest.fn()
            obs.onDispose.then(disposed).then(done).then(() => expect(disposed).toHaveBeenCalledTimes(1))
            obs.dispose()
        })
    })

    describe("observeKey", () => {
        it("is triggered by update to that key", () => {
            const listener = jest.fn()
            const obs = handler.observeKey("str", listener)
            handler.updateField("str", () => "goodbye")
            expect(listener).toHaveBeenCalledTimes(1)
            expect(listener).toHaveBeenCalledWith("goodbye", expect.anything(), expect.anything())
            obs.dispose()
        })

        it("is not triggered by updates to siblings", () => {
            const listener = jest.fn()
            const obs = handler.observeKey("num", listener)
            handler.updateField("str", () => "goodbye")
            expect(listener).toHaveBeenCalledTimes(0)
            obs.dispose()
        })

        it("is not triggered when the value is equal", () => {
            const listener = jest.fn()
            const obs = handler.observeKey("num", listener)
            handler.updateField("num", num => num)
            expect(listener).toHaveBeenCalledTimes(0)
            obs.dispose()
        })
    })

    describe("view", () => {

        it("observes changes to the key and disposes listeners", done => {
            const view = handler.view("num")
            const listener = jest.fn()
            const obs = view.addObserver(listener)

            const disposed = jest.fn()
            obs.onDispose.then(disposed).then(done).then(() => {
                expect(disposed).toHaveBeenCalledTimes(1)
                expect(handler.observerCount).toEqual(0)
            })

            const oldNum = handler.state.num
            handler.updateField("num", () => setValue(42))
            expect(listener).toHaveBeenCalledTimes(1)
            expect(listener).toHaveBeenCalledWith(42, {newValue: 42, oldValue: initialState.num, update: setValue(42)}, expect.anything())
            view.dispose()
        })

        it("observes deep changes to the key", () => {
            const view = handler.view("obj")
            const listener = jest.fn()
            const obs = view.observeKey("objStr", listener)
            handler.update(() => ({
                obj: {
                    objStr: setValue("nope")
                }
            }));
            expect(listener).toHaveBeenCalledTimes(1)
            expect(listener).toHaveBeenCalledWith("nope", {newValue: "nope", oldValue: "yup", update: setValue("nope")}, expect.anything())
            view.dispose()
        })

        it("doesn't get spuriously triggered", () => {
            const view = handler.view("num")
            const listener = jest.fn()
            const obs = view.addObserver(listener)
            handler.update(() => ({
                str: "shabba"
            }))
            expect(listener).toHaveBeenCalledTimes(0)
            view.dispose()
        })

    })

    describe("updateWith", () => {
        it("updates multiple fields and triggers listeners everywhere", () => {
            const listenStr = jest.fn()
            const obsStr = handler.observeKey("str", listenStr)
            const viewObj = handler.view("obj")
            const listenObjStr = jest.fn()
            const obsObjStr = viewObj.observeKey("objStr", listenObjStr)
            const viewInner = viewObj.view("inner")
            const listenInnerNum = jest.fn()
            const obsInnerNum = viewInner.observeKey("innerNum", listenInnerNum)

            // test that observers aren't triggered if their thing isn't updated
            const listenNum = jest.fn()
            const obsNum = handler.observeKey("num", listenNum);

            handler.update(() => ({
                str: "hellogoodbye",
                obj: {
                    objStr: "yupper",
                    inner: {
                        innerNum: 123
                    }
                }
            }))

            expect(handler.state.str).toEqual("hellogoodbye")
            expect(handler.state.obj.objStr).toEqual("yupper")
            expect(handler.state.obj.inner.innerNum).toEqual(123)

            expect(listenStr).toHaveBeenCalledWith("hellogoodbye", expect.anything(), expect.anything())
            expect(listenNum).toHaveBeenCalledTimes(0)
            expect(listenObjStr).toHaveBeenCalledWith("yupper", expect.anything(), expect.anything())
            expect(listenInnerNum).toHaveBeenCalledWith(123, expect.anything(), expect.anything())
        })

    })

    describe("lens", () => {
        describe("lens of value", () => {
            describe("update", () => {
                it("updates the value", () => {
                    const lens = handler.lens("num")
                    const numListener = jest.fn()
                    const obsNum = handler.observeKey("num", numListener)
                    const lensListener = jest.fn()
                    const obsLens = lens.addObserver(lensListener)

                    lens.update(() => 67)

                    expect(numListener).toHaveBeenCalledWith(67, expect.anything(), expect.anything())
                    expect(lensListener).toHaveBeenCalledWith(67, expect.anything(), expect.anything())
                    expect(handler.state.num).toEqual(67)
                })
            })
        })

        describe("lens of object", () => {
            describe("update", () => {
                it("updates fields", () => {
                    const lens = handler.lens("obj")
                    const objListener = jest.fn()
                    const strListener = jest.fn()
                    handler.observeKey("obj", objListener)
                    lens.observeKey("objStr", strListener)
                    lens.updateField("objStr", () => setValue("nope"))

                    expect(objListener).toHaveBeenCalledTimes(1)
                    expect(strListener).toHaveBeenCalledTimes(1)
                    expect(strListener).toHaveBeenCalledWith("nope", expect.anything(), expect.anything())
                    expect(objListener).toHaveBeenCalledWith({...initialState.obj, objStr: "nope"}, expect.anything(), expect.anything())
                })
            })
        })
    })

    describe("preobserver", () => {
        it("receives the state before updates are applied",() => {
            let objLens = handler.lens("obj");
            let objNumLens = objLens.lens("objNum");

            let innerCallback = jest.fn();
            let preListener = jest.fn(() => innerCallback);
            let preObs = objNumLens.addPreObserver(preListener);

            let oldValue = initialState.obj.objNum;
            let newValue = 5;

            handler.update(() => ({
                obj: {
                    objNum: newValue
                }
            }));

            expect(preListener).toHaveBeenCalledTimes(1);
            expect(preListener).toHaveBeenCalledWith(oldValue);

            expect(innerCallback).toHaveBeenCalledTimes(1);
            expect(innerCallback).toHaveBeenCalledWith(newValue, expect.anything(), expect.anything());
            preObs.dispose();
        })

        it ("disposes when its parent key is deleted", () => {

            let innerCallback = jest.fn();
            let preListener = jest.fn(() => innerCallback);

            let extraFieldLens = handler.lens("extraField");
            let extraObjLens = extraFieldLens.lens("extraObj");
            let extraObjFieldLens = extraObjLens.lens("extraObjField");

            let preObserver = extraObjFieldLens.addPreObserver(preListener);

            handler.update((oldState) => {
                return {
                    extraField: {
                        extraObj: removeKey<any, any>("extraObjField")
                    }
                };
            });

            expect(preListener).toHaveBeenCalledTimes(1);

        })
    })


})

describe("ProxyStateView", () => {
    const initialState2: TestState = {
        ...initialState,
        str: "goodbye",
        maybeNum: undefined,
        obj: {
            ...initialState.obj,
            inner: {
                ...initialState.obj.inner,
                innerNum: 32
            },
            objNum: 42
        }
    }

    let handler2: ObjectStateHandler<TestState>;

    beforeEach(() => {
        handler = new ObjectStateHandler<TestState>(deepCopy(initialState))
        handler2 = new ObjectStateHandler<TestState>(deepCopy(initialState2))
    })

    afterEach(() => {
        handler.dispose()
        handler2.dispose()
    })

    it ("notifies observers of new values when parent handler is changed", () => {
        const proxy = new ProxyStateView(handler)

        // verify that observers on the proxy get notified
        const obsStr = jest.fn()
        const obsNum = jest.fn()
        const obsMaybeNum = jest.fn()
        const obsObjNum = jest.fn()
        const obsInnerNum = jest.fn()

        proxy.observeKey("str", obsStr)
        proxy.observeKey("num", obsNum)
        proxy.observeKey("maybeNum", obsMaybeNum)
        proxy.view("obj").observeKey("objNum", obsObjNum)
        proxy.view("obj").view("inner").observeKey("innerNum", obsInnerNum)

        proxy.setParent(handler2)

        // verify that observers on the proxy get notified about changes to the new parent
        handler2.lens("obj").updateField("objNum", () => 88)

        // verify that observers on the proxy don't get notified about changes on the old parent
        handler.updateField("str", () => setValue("hello and goodbye"))

        expect(obsStr).toHaveBeenCalledTimes(1)
        expect(obsStr).toHaveBeenCalledWith(initialState2.str, expect.anything(), expect.anything())

        expect(obsNum).toHaveBeenCalledTimes(0)

        expect(obsMaybeNum).toHaveBeenCalledTimes(1)
        expect(obsMaybeNum).toHaveBeenCalledWith(initialState2.maybeNum, expect.anything(), expect.anything())

        expect(obsObjNum).toHaveBeenCalledTimes(2)
        expect(obsObjNum).toHaveBeenCalledWith(initialState2.obj.objNum, expect.anything(), expect.anything())
        expect(obsObjNum).toHaveBeenCalledWith(88, expect.anything(), expect.anything())

        expect(obsInnerNum).toHaveBeenCalledTimes(1)
        expect(obsInnerNum).toHaveBeenCalledWith(initialState2.obj.inner.innerNum, expect.anything(), expect.anything())
    })

    it ("notifies pre-observers of new values when parent handler is changed", () => {
        const proxy = new ProxyStateView(handler)

        // verify that observers on the proxy get notified
        const obsStr = jest.fn()
        const obsNum = jest.fn()
        const obsMaybeNum = jest.fn()
        const obsObjNum = jest.fn()
        const obsInnerNum = jest.fn()

        const preObsStr = jest.fn(() => obsStr)
        const preObsNum = jest.fn(() => obsNum)
        const preObsMaybeNum = jest.fn(() => obsMaybeNum)
        const preObsObjNum = jest.fn(() => obsObjNum)
        const preObsInnerNum = jest.fn(() => obsInnerNum)

        proxy.preObserveKey("str", preObsStr)
        proxy.preObserveKey("num", preObsNum)
        proxy.preObserveKey("maybeNum", preObsMaybeNum)
        proxy.view("obj").preObserveKey("objNum", preObsObjNum)
        proxy.view("obj").view("inner").preObserveKey("innerNum", preObsInnerNum)

        proxy.setParent(handler2)

        // verify that observers on the proxy get notified about changes to the new parent
        handler2.lens("obj").updateField("objNum", () => 88)

        // verify that observers on the proxy don't get notified about changes on the old parent
        handler.updateField("str", () => setValue("hello and goodbye"))

        expect(preObsStr).toHaveBeenCalledTimes(1)
        expect(preObsStr).toHaveBeenCalledWith(initialState.str)
        expect(obsStr).toHaveBeenCalledTimes(1)
        expect(obsStr).toHaveBeenCalledWith(initialState2.str, expect.anything(), expect.anything())

        expect(obsNum).toHaveBeenCalledTimes(0)

        expect(preObsMaybeNum).toHaveBeenCalledTimes(1)
        expect(preObsMaybeNum).toHaveBeenCalledWith(initialState.maybeNum)
        expect(obsMaybeNum).toHaveBeenCalledTimes(1)
        expect(obsMaybeNum).toHaveBeenCalledWith(initialState2.maybeNum, expect.anything(), expect.anything())

        expect(preObsObjNum).toHaveBeenCalledTimes(2)
        expect(preObsObjNum).toHaveBeenCalledWith(initialState.obj.objNum)
        expect(preObsObjNum).toHaveBeenCalledWith(initialState2.obj.objNum)
        expect(obsObjNum).toHaveBeenCalledTimes(2)
        expect(obsObjNum).toHaveBeenCalledWith(initialState2.obj.objNum, expect.anything(), expect.anything())
        expect(obsObjNum).toHaveBeenCalledWith(88, expect.anything(), expect.anything())

        expect(preObsInnerNum).toHaveBeenCalledTimes(1)
        expect(preObsInnerNum).toHaveBeenCalledWith(initialState.obj.inner.innerNum)
        expect(obsInnerNum).toHaveBeenCalledTimes(1)
        expect(obsInnerNum).toHaveBeenCalledWith(initialState2.obj.inner.innerNum, expect.anything(), expect.anything())
    })

})


//
// // Old state handler tests – Keeping this around in case we want to reuse these tests
//
// describe("StateHandler", () => {
//     it("holds some state value that can be updated", () => {
//         const s = StateHandler.from(1)
//         expect(s.state).toEqual(1)
//         s.update(() => 2)
//         expect(s.state).toEqual(2)
//     })
//     it("allows observation of state changes", done => {
//         const s = StateHandler.from(1)
//         s.addObserver((newState: number, oldState: number) => {
//             expect(newState).toEqual(2)
//             expect(oldState).toEqual(1)
//             done()
//         }, s)
//         s.update(() => 2)
//     })
//     it("doesn't call observers when the state isn't changed", () => {
//         const s = StateHandler.from(1)
//         s.addObserver(() => {
//             throw new Error("you better not change the state!!")
//         }, s)
//         expect(() => s.update(() => 2)).toThrow()
//         expect(s.state).toEqual(2)
//         expect(() => s.update(() => 2)).not.toThrow()
//     })
//     test("observers can be removed / cleared", () => {
//         const s = StateHandler.from(1)
//         let obsStateChange = false;
//         const obs = s.addObserver(() => {
//             if (obsStateChange) {
//                 throw new Error("error from obs")
//             }
//             obsStateChange = true;
//         }, s)
//         let anonStateChange = false;
//         s.addObserver(() => {
//             if (anonStateChange) {
//                 throw new Error("error from anonymous")
//             }
//             anonStateChange = true;
//         }, s)
//         s.update(() => 2)
//         expect(s.state).toEqual(2)
//         expect(() => s.update(() => 3)).toThrowError("error from obs")
//         expect(s.state).toEqual(3)
//         s.removeObserver(obs)
//         expect(() => s.update(() => 4)).toThrowError("error from anonymous")
//         s.clearObservers()
//         s.update(() => 5)
//         expect(s.state).toEqual(5)
//     })
//     it("can be disposed", async () => {
//         const s = StateHandler.from(1)
//         const obFn = jest.fn()
//         s.addObserver(obFn, s)
//         s.update(() => 2)
//         expect(obFn).toHaveBeenCalledTimes(1)
//         expect(obFn).toHaveBeenCalledWith(2, 1, undefined)
//         s.dispose()
//         s.onDispose.then(() => {
//             expect(obFn).toHaveBeenCalledTimes(1)
//         })
//     })
//     it("can be linked to the lifecycle of another Disposable", done => {
//         const d = new Disposable()
//         const s = StateHandler.from(1)
//         d.onDispose.then(() => s.dispose())
//         const obFn = jest.fn()
//         s.addObserver(obFn, s)
//         s.update(() => 2)
//         expect(obFn).toHaveBeenCalledTimes(1)
//         expect(obFn).toHaveBeenCalledWith(2, 1, undefined)
//         d.dispose()  // disposed `d`, now expect `s` to also be disposed
//         s.onDispose.then(() => {
//             expect(obFn).toHaveBeenCalledTimes(1)
//             done()
//         })
//     })
//     it("supports views of object states", () => {
//         const sh = StateHandler.from<{a?: number, b?: number}>({a: undefined, b: 2})
//         const view = sh.view("a")
//         expect(sh.state).toEqual({a: undefined, b: 2})
//         expect(view.state).toEqual(undefined)
//
//         const obState = jest.fn()
//         sh.addObserver(obState, sh)
//
//         const obA = jest.fn()
//         view.addObserver(obA, sh)
//
//         sh.update(s => ({...s, a: 1}))
//         expect(view.state).toEqual(1)
//
//         sh.update(s => ({...s, a: 2}))
//         expect(view.state).toEqual(2)
//
//         sh.update(s => ({...s, b: 100}))
//         expect(view.state).toEqual(2) // stays the same
//
//         sh.update(s => ({...s, a: undefined}))
//         expect(view.state).toEqual(undefined)
//
//         sh.update(s => ({...s, a: 100}))
//         expect(view.state).toEqual(100)
//
//         expect(obState).toHaveBeenCalledTimes(5)
//         expect(obA).toHaveBeenCalledTimes(4)
//     })
//     it("supports nested views", () => {
//         const sh = StateHandler.from<{s: {a: Record<string, number>}, c: number}>({
//             s: {
//                 a: {
//                     b: 1
//                 },
//             },
//             c: 2
//         })
//         const sView = sh.view("s")
//         const obS = jest.fn()
//         sView.addObserver(obS, sh)
//         const aView = sView.view("a")
//         const obA = jest.fn()
//         aView.addObserver(obA, sh)
//         sh.update(state => ({...state, s: {...state.s, a: {...state.s.a, b: 2}}}))
//
//         expect(obS).toHaveBeenCalledWith({a: {b: 2}}, {a: {b: 1}}, undefined)
//         expect(obA).toHaveBeenCalledWith({b: 2}, {b: 1}, undefined)
//
//         sh.update(state => ({...state, s: {...state.s, a: {...state.s.a, b: 2, foo: 100}}}))
//         expect(obA).toHaveBeenCalledWith({b: 2, foo: 100}, {b: 2}, undefined)
//
//         const fooView = sView.view("a").view("foo")
//         const obFoo  = jest.fn()
//         fooView.addObserver(obFoo, sh)
//         expect(fooView["_state"]).toEqual(100)
//
//         sh.update(state => ({...state, s: {...state.s, a: {...state.s.a, b: 2, foo: 200, bar: 1000}}}))
//         expect(obFoo).toHaveBeenCalledWith(200, 100, undefined)
//
//         const barView = sView.view("a").view("bar")
//         const obBar  = jest.fn()
//         barView.addObserver(obBar, sh)
//         expect(barView["_state"]).toEqual(1000)
//
//     })
//     it("supports mapViews", () => {
//         const sh = StateHandler.from<{a?: number, b?: number}>({a: undefined, b: 2})
//         const view = sh.mapView("a", a => a?.toString())
//
//         expect(sh.state).toEqual({a: undefined, b: 2})
//         expect(view.state).toEqual(undefined)
//
//         const obState = jest.fn()
//         sh.addObserver(obState, sh)
//
//         const obA = jest.fn()
//         view.addObserver(obA, sh)
//
//         sh.update(s => ({...s, a: 1}))
//         expect(view.state).toEqual("1")
//
//         sh.update(s => ({...s, a: 2}))
//         expect(view.state).toEqual("2")
//
//         sh.update(s => ({...s, b: 100}))
//         expect(view.state).toEqual("2") // stays the same
//
//         sh.update(s => ({...s, a: undefined}))
//         expect(view.state).toEqual(undefined)
//
//         sh.update(s => ({...s, a: 100}))
//         expect(view.state).toEqual("100")
//
//         expect(obState).toHaveBeenCalledTimes(5)
//         expect(obA).toHaveBeenCalledTimes(4)
//     })
//     test("views can also synchronize with another Disposable", () => {
//         const d = new Disposable()
//         const parent = StateHandler.from({a: 1, b: 2})
//         const view = parent.view("a");
//         const obFn = jest.fn()
//         view.addObserver(obFn, d)
//         parent.update(s => ({...s, a: 2}))
//         expect(obFn).toHaveBeenCalledTimes(1)
//         expect(obFn).toHaveBeenCalledWith(2, 1, undefined)
//         d.dispose()
//         d.onDispose.then(() => {
//             expect(obFn).toHaveBeenCalledTimes(1)
//         })
//     })
//     test("view stop updating after their key is removed", done => {
//         const d = new Disposable()
//         const parent = StateHandler.from({a: 1, b: 2})
//         const view = parent.view("a");
//         const obFn = jest.fn()
//         view.addObserver(obFn, d)
//         parent.update(s => ({...s, a: 2}))
//         expect(obFn).toHaveBeenCalledTimes(1)
//         parent.update(s => {
//             const st = {...s}
//             delete st["a"]
//             return st
//         })
//         // to ensure that the view promise settles
//         setTimeout(() => {
//             expect(obFn).toHaveBeenCalledTimes(1)
//             parent.update(s => ({...s, a: 3}))
//             parent.update(s => ({...s, a: 4}))
//             parent.update(s => ({...s, a: 5}))
//             expect(obFn).toHaveBeenCalledTimes(1)
//             done()
//         }, 0)
//
//     })
//     test("views can be wrapped", () => {
//         const parent = StateHandler.from({a: 1, b: 2});
//
//         const view = parent.view("a")
//         const viewObs = jest.fn()
//         view.addObserver(viewObs, parent)
//
//         class AddingStorageHandler extends StateWrapper<number> {
//             readonly someProperty: number = 100;
//             constructor(view: StateView<number>) {
//                 super(view)
//             }
//
//             setState(newState: number) {
//                 super.setState(newState + 10);
//             }
//         }
//         const addingView = new AddingStorageHandler(parent.view("a"));
//         expect(addingView.state).toEqual(11)
//         expect(addingView.someProperty).toEqual(100);
//         expect(addingView instanceof AddingStorageHandler).toBeTruthy();
//
//         const addingViewObs = jest.fn()
//         addingView.addObserver(addingViewObs, addingView)
//         parent.update(() => ({a: 2, b: 2}));
//         expect(viewObs).toHaveBeenCalledWith(2, 1, undefined)
//         expect(addingViewObs).toHaveBeenCalledWith(12, 11, undefined)
//
//         expect(view.state).toEqual(2)
//         expect(addingView.state).toEqual(12)
//     });
//     test("equality comparison can be extended", () => {
//         const state = () => ({a: 1, b: 2})
//         const stringyState = {a: "1", b: "2"} as any
//         const sh = StateHandler.from(state())
//         sh.addObserver(() => {
//             throw new Error("you better not change the state!!")
//         }, sh)
//         expect(() => sh.update(() => stringyState)).toThrow()  // since state is a new object, this throws because equality is not deep.
//
//         const stringy = class<S> extends StateHandler<S> {
//             protected compare(s1?: any, s2?: any): boolean {
//                 return s1?.toString() === s2?.toString()
//             }
//         }.from({a: 1, b: 2})
//         stringy.addObserver(() => {
//             throw new Error("you better not change the state!!")
//         }, sh)
//         expect(() => stringy.update(() => stringyState)).not.toThrow()
//     })
// })