import {Disposable} from ".";
import {SocketSession} from "../messaging/comms";
import {DeleteCell, InsertCell} from "../data/messages";
import {NotebookMessageReceiver} from "../messaging/receiver";
import {CellMetadata, NotebookCell} from "../data/data";

import {ClientBackup} from "./client_backup";
import {
    CompileErrors,
    KernelReport,
    Output,
    Position,
    ResultValue,
    RuntimeError,
    ServerErrorWithCause
} from "../data/result";
import {NotebookStateHandler} from "./notebook_state";
import {SocketStateHandler} from "./socket_state";
import {NotebookMessageDispatcher} from "../messaging/dispatcher";

jest.mock("./client_backup")
// @ts-ignore
ClientBackup.updateNb = jest.fn(() => Promise.resolve())  // silence this

jest.mock("../messaging/comms");  // use the comms manual mock

let nbState: NotebookStateHandler,
    socket: SocketSession,
    socketState: SocketStateHandler,
    dispatcher: NotebookMessageDispatcher,
    receiver: NotebookMessageReceiver;

let stateUpdateDisp = new Disposable()

beforeEach(() => {
    nbState = NotebookStateHandler.forPath("foo").disposeWith(stateUpdateDisp)
    nbState.updateHandler.globalVersion = 0 // initialize version

    socket = SocketSession.fromRelativeURL(nbState.state.path)
    socketState = SocketStateHandler.create(socket).disposeWith(stateUpdateDisp)
    dispatcher = new NotebookMessageDispatcher(socketState, nbState)
    receiver = new NotebookMessageReceiver(socketState, nbState).disposeWith(stateUpdateDisp)
})

afterEach(() => {
    stateUpdateDisp.dispose()
    stateUpdateDisp = new Disposable()
    socket.close();
})


describe('NotebookStateHandler', () => {
    test("can insert and delete cells", async () => {
        function waitForNextUpdate() {
            return new Promise(resolve => {
                const obs = nbState.updateHandler.addObserver(update => {
                    obs.tryDispose()
                    resolve(update)
                })
            })
        }

        expect(Object.keys(nbState.state.cells)).toHaveLength(0)
        expect(Object.keys(nbState.state.cellOrder)).toEqual([])

        const waitForInsert1 = waitForNextUpdate()
        await expect(nbState.insertCell("below")).resolves.toEqual(0)
        await expect(waitForInsert1).resolves.toEqual(new InsertCell(0, 1, new NotebookCell(0, "scala"), -1))

        const waitForInsert2 = waitForNextUpdate()
        await expect(nbState.insertCell("below")).resolves.toEqual(1)
        await expect(waitForInsert2).resolves.toEqual(new InsertCell(0, 2, new NotebookCell(1, "scala"), 0))

        const waitForInsert3 = waitForNextUpdate()
        await expect(nbState.insertCell("below")).resolves.toEqual(2)
        await expect(waitForInsert3).resolves.toEqual(new InsertCell(0, 3, new NotebookCell(2, "scala"), 1))

        expect(Object.keys(nbState.state.cells)).toHaveLength(3)
        expect(nbState.state.cellOrder).toEqual([0, 1, 2])

        const waitForDelete = waitForNextUpdate()
        await expect(nbState.deleteCell(1)).resolves.toEqual(1)
        await expect(waitForDelete).resolves.toEqual(new DeleteCell(0, 4, 1))

        expect(Object.keys(nbState.state.cells)).toHaveLength(2)
        expect(nbState.state.cellOrder).toEqual([0, 2])

        const waitForInsert4 = waitForNextUpdate()
        await expect(nbState.insertCell("below")).resolves.toEqual(3)
        await expect(waitForInsert4).resolves.toEqual(new InsertCell(0, 5, new NotebookCell(3, "scala"), 2))
        expect(Object.keys(nbState.state.cells)).toHaveLength(3)
        expect(nbState.state.cellOrder).toEqual([0, 2, 3])
    })
    test("contains cell index and id helpers", async () => {
        // initialize
        const init = nbState.insertCell("below")
            .then(() => nbState.insertCell("below"))
            // insert out of order
            .then(() => nbState.insertCell("below", {id: 0, language: "scala", metadata: new CellMetadata(false, false, false, false, false)}))
            .then(() => nbState.insertCell("below"))

        await expect(init).resolves.toEqual(3)

        expect(Object.keys(nbState.state.cells)).toHaveLength(4)
        const cellOrder = [0, 2, 1, 3]
        expect(nbState.state.cellOrder).toEqual(cellOrder)

        cellOrder.forEach((id, idx) => {
            expect(nbState.getCellIndex(id)).toEqual(idx)
            expect(nbState.getCellIdAtIndex(idx)).toEqual(id)
            expect(nbState.getPreviousCellId(id)).toEqual(cellOrder[idx - 1])
            expect(nbState.getNextCellId(id)).toEqual(cellOrder[idx + 1])
        })
    })

    test("can select cells", async () => {
        // initialize
        const init = nbState.insertCell("below")
            .then(() => nbState.insertCell("below"))
            // insert out of order
            .then(() => nbState.insertCell("below", {id: 0, language: "scala", metadata: new CellMetadata()}))
            .then(() => nbState.insertCell("below"))

        await expect(init).resolves.toEqual(3)

        const waitForSelect = (cellId: number) => new Promise(resolve => {
            const view = nbState.view("activeCellId")
            const obs = view.addObserver(id => {
                obs.dispose()
                resolve([cellId, id === cellId])
            })
        })

        let promise = waitForSelect(1)
        expect(nbState.selectCell(1)).toEqual(1)
        await expect(promise).resolves.toEqual([1, true])

        promise = waitForSelect(2)
        expect(nbState.selectCell(1, {relative: "above"})).toEqual(2)
        await expect(promise).resolves.toEqual([2, true])

        promise = waitForSelect(3)
        expect(nbState.selectCell(1, {relative: "below"})).toEqual(3)
        await expect(promise).resolves.toEqual([3, true])

        // insert cell with hidden code:
        await expect(nbState.insertCell("below", {id: 0, language: "scala", metadata: new CellMetadata(false, true)})).resolves.toEqual(4)
        expect(nbState.state.cellOrder).toEqual([0, 4, 2, 1, 3])

        promise = waitForSelect(2)
        expect(nbState.selectCell(0, {relative: "below", skipHiddenCode: true})).toEqual(2)
        await expect(promise).resolves.toEqual([2, true])

        promise = waitForSelect(3)
        expect(nbState.selectCell(3, {editing: true})).toEqual(3)
        await expect(promise).resolves.toEqual([3, true])
        expect(nbState.state.cells[3].editing).toEqual(true)
    })

    test("supports setting cell language", async () => {
        // initialize
        const init = nbState.insertCell("below")
            .then(() => nbState.insertCell("below"))
            // insert out of order
            .then(() => nbState.insertCell("below", {id: 0, language: "scala", metadata: new CellMetadata(false, false, false, false, false)}))
            .then(() => nbState.insertCell("below"))

        await expect(init).resolves.toEqual(3)

        const waitForLanguageChange = (cellId: number) => new Promise(resolve => {
            const view = nbState.view("cells").view(cellId)
            const obs = view.addObserver(state => {
                obs.dispose()
                resolve([cellId, state.language])
            })
        })

        const waitForPython = waitForLanguageChange(1)
        nbState.setCellLanguage(1, "python")
        await expect(waitForPython).resolves.toEqual([1, "python"])

        // add some data to the cell
        const cellWithStuff = {
            output: [new Output("test", ["stuff"])],
            results: [new ResultValue("hi", "there", [], 0)],
            error: true,
            compileErrors: [new CompileErrors([new KernelReport(new Position("", 1, 2, 3), "hi", 1)])],
            runtimeError: new RuntimeError(new ServerErrorWithCause("yo", "sup", []))
        }
        nbState.updateField("cells", () => ({
            [1]: cellWithStuff
        }))
        expect(nbState.state.cells[1]).toEqual({
            ...nbState.state.cells[1],
            ...cellWithStuff
        })

        const waitForText = waitForLanguageChange(1)
        nbState.setCellLanguage(1, "text")
        await expect(waitForText).resolves.toEqual([1, "text"])
        expect(nbState.state.cells[1]).toEqual({
            ...nbState.state.cells[1],
            id: 1,
            language: "text",
            output: [],
            results: [],
            error: false,
            compileErrors: [],
            runtimeError: undefined
        })
    })

    test("supports waiting for a cell state change", async () => {
        // initialize
        const init = nbState.insertCell("below")
            .then(() => nbState.insertCell("below"))
            // insert out of order
            .then(() => nbState.insertCell("below", {id: 0, language: "scala", metadata: new CellMetadata(false, false, false, false, false)}))
            .then(() => nbState.insertCell("below"))

        await expect(init).resolves.toEqual(3)

        const waitQueued = nbState.waitForCellChange(1, "queued")
        nbState.updateField("cells", () => ({
            [1]: {
                queued: true
            }
        }))
        await expect(waitQueued).resolves

        const waitRunning = nbState.waitForCellChange(2, "running")
        nbState.updateField("cells", () => ({
            [2]: {
                running: true
            }
        }))
        await expect(waitRunning).resolves

        const waitError = nbState.waitForCellChange(3, "error")
        nbState.updateField("cells", () => ({
            [3]: {
                error: true
            }
        }))
        await expect(waitError).resolves
    })
});