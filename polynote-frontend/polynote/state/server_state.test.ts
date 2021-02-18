import {ServerStateHandler} from "./server_state";

jest.mock("../messaging/comms");

beforeEach(() => {
    ServerStateHandler.clear()
})


test('Changes to the ServerStateHandler are observable', done => {
    ServerStateHandler.get.addObserver(state => {
        expect(state.currentNotebook).toEqual("nb")
        done()
    })
    ServerStateHandler.get.updateField("currentNotebook", () => "nb")
})

test('ServerStateHandler supports views', done => {
    new Promise<void>(resolve => {
        const view = ServerStateHandler.get.view("currentNotebook");
        const obs = view.addPreObserver(prev => next => {
            expect(prev).toBeUndefined()
            expect(next).toEqual("nb")
            resolve()
        })
        ServerStateHandler.get.updateField("currentNotebook", () => "nb")
        obs.dispose()
    }).then(_ => {
        return new Promise<void>(resolve => {
            const view = ServerStateHandler.get.view("currentNotebook");
            const obs = view.addPreObserver(prev => next => {
                expect(prev).toEqual("nb")
                expect(next).toEqual("newNb")
                resolve()
            })
            ServerStateHandler.get.updateField("currentNotebook", () => "newNb")
            obs.dispose()
        })
    }).then(_ => {
        return new Promise<void>(resolve => {
            ServerStateHandler.get.view("notebooks").addPreObserver(prev => {
                expect(prev).toEqual({})
                return next => {
                    expect(next).toEqual({
                        "path": true
                    })
                    resolve()
                }
            })
            ServerStateHandler.get.update(() => ({
                notebooks: {
                    ["path"]: true
                }
            }))
        })
    }).then(done)
})