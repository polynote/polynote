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
    ServerStateHandler.get.update(s => ({...s, currentNotebook: "nb"}))
})

test('ServerStateHandler supports views', done => {
    new Promise(resolve => {
        const view = ServerStateHandler.get.view("currentNotebook");
        const obs = view.addObserver((next, prev) => {
            expect(prev).toBeUndefined()
            expect(next).toEqual("nb")
            resolve()
        })
        ServerStateHandler.get.update(s => ({...s, currentNotebook: "nb"}))
        view.removeObserver(obs)
    }).then(_ => {
        return new Promise(resolve => {
            const view = ServerStateHandler.get.view("currentNotebook");
            const obs = view.addObserver((next, prev) => {
                expect(prev).toEqual("nb")
                expect(next).toEqual("newNb")
                resolve()
            })
            ServerStateHandler.get.update(s => ({...s, currentNotebook: "newNb"}))
            view.removeObserver(obs)
        })
    }).then(_ => {
        return new Promise(resolve => {
            ServerStateHandler.get.view("notebooks").addObserver((next, prev) => {
                expect(prev).toEqual({})
                expect(next).toEqual({
                    "path": true
                })
                resolve()
            })
            ServerStateHandler.get.update(s => {
                return {
                    ...s,
                    notebooks: {
                        ...s.notebooks,
                        ["path"]: true
                    }
                }
            })
        })
    }).then(done)
})