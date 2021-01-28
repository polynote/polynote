import {noUpdate, ObjectStateHandler, setProperty, setValue, StateHandler} from "./state_handler1";
import {deepCopy} from "../util/helpers";

describe("ObjectStateHandler", () => {

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
        }
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
        }
    }

    let handler: ObjectStateHandler<TestState>;

    beforeEach(() => {
        handler = new ObjectStateHandler<TestState>(initialState);
    })

    afterEach(() => {
        handler.dispose()
    })

    describe("addObserver", () => {
        it("is triggered by state update", () => {
            const listener = jest.fn()
            const obs = handler.addObserver(listener)
            handler.submitUpdate(setProperty("num", 2))
            expect(listener).toHaveBeenCalledTimes(1)
            expect(listener).toHaveBeenCalledWith({...initialState, num: 2}, expect.anything(), expect.anything())
            obs.dispose()
        })

        it("is not triggered by noop", () => {
            const listener = jest.fn()
            const obs = handler.addObserver(listener)
            handler.submitUpdate(noUpdate())
            expect(listener).toHaveBeenCalledTimes(0)
            obs.dispose()
        })


        it("doesn't break when state is updated inside an observer", () => {
            const numListener = jest.fn()
            const updater = handler.observeKey("str", (newValue) => handler.update({num: newValue.length}))
            const numObs = handler.observeKey("num", numListener)


            handler.update({str: "goober", num: 44})
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
            handler.submitUpdate(setProperty("str", "goodbye"))
            expect(listener).toHaveBeenCalledTimes(1)
            expect(listener).toHaveBeenCalledWith("goodbye", expect.anything(), expect.anything())
            obs.dispose()
        })

        it("is not triggered by updates to siblings", () => {
            const listener = jest.fn()
            const obs = handler.observeKey("num", listener)
            handler.submitUpdate(setProperty("str", "goodbye"))
            expect(listener).toHaveBeenCalledTimes(0)
            obs.dispose()
        })

        it("is not triggered when the value is equal", () => {
            const listener = jest.fn()
            const obs = handler.observeKey("num", listener)
            handler.update({num: handler.state.num})
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

            handler.updateKey("num", setValue<number>(42))
            expect(listener).toHaveBeenCalledTimes(1)
            expect(listener).toHaveBeenCalledWith(42, setValue(42), expect.anything())
            view.dispose()
        })

        it("observes deep changes to the key", () => {
            const view = handler.view("obj")
            const listener = jest.fn()
            const obs = view.observeKey("objStr", listener)
            handler.update({
                obj: {
                    objStr: setValue("nope")
                }
            });
            expect(listener).toHaveBeenCalledTimes(1)
            expect(listener).toHaveBeenCalledWith("nope", setValue("nope"), expect.anything())
            view.dispose()
        })

        it("doesn't get spuriously triggered", () => {
            const view = handler.view("num")
            const listener = jest.fn()
            const obs = view.addObserver(listener)
            handler.update({
                str: "shabba"
            })
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

            handler.update({
                str: "hellogoodbye",
                obj: {
                    objStr: "yupper",
                    inner: {
                        innerNum: 123
                    }
                }
            })

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

                    lens.update(67)

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

                })
            })
        })
    })


})