import {NotebookMessageDispatcher} from "../../../messaging/dispatcher";
import {SocketSession} from "../../../messaging/comms";
import {Disposable, editString, setValue, updateProperty} from "../../../state";
import {NotebookMessageReceiver} from "../../../messaging/receiver";
import {Insert} from "../../../data/content_edit";
import {CurrentSelection, UpdateCell} from "../../../data/messages";
import {NotebookStateHandler} from "../../../state/notebook_state";
import {SocketStateHandler} from "../../../state/socket_state";
import {ClientBackup} from "../../../state/client_backup";
import {CellMetadata} from "../../../data/data";
import {PosRange} from "../../../data/result";

jest.mock("../../../messaging/comms");

let nbState: NotebookStateHandler,
    socket: SocketSession,
    socketState: SocketStateHandler,
    dispatcher: NotebookMessageDispatcher,
    receiver: NotebookMessageReceiver;

let stateUpdateDisp = new Disposable()

beforeEach(() => {
    nbState = NotebookStateHandler.forPath("foo").disposeWith(stateUpdateDisp)
    return ClientBackup.addNb("foo", []).then(() => {
        nbState.updateHandler.globalVersion = 0 // initialize version
        socket = SocketSession.fromRelativeURL(nbState.state.path)
        socketState = SocketStateHandler.create(socket).disposeWith(stateUpdateDisp)
        receiver = new NotebookMessageReceiver(socketState, nbState).disposeWith(stateUpdateDisp)
        dispatcher = new NotebookMessageDispatcher(socketState, nbState).disposeWith(stateUpdateDisp)

        // close the server loop for messages that bounce off it (e.g., InsertCell)
        nbState.updateHandler.addObserver(update => {
            setTimeout(() => { receiver.inject(update) }, 0)
        })
    })
})

afterEach(() => {
    stateUpdateDisp.dispose()
    stateUpdateDisp = new Disposable()
    return ClientBackup.clearBackups();
})

describe("Code cell", () => {

    const setupNotebook = async () => {
        const cellId = await nbState.insertCell("below", {id: 0, language: "scala", metadata: new CellMetadata()});
        (socket.send as any).mockClear();
        return nbState.cellsHandler.lens(cellId);
    }

    it ("sends updates when its content changes", async () => {
        const cellHandler = await setupNotebook();

        const waitForEdit = new Promise((resolve, reject) => {
            cellHandler.addObserver(cellState => {
                try {
                    resolve(cellState.content)
                } catch (err) {
                    reject(err);
                }
            })
        });

        await cellHandler.updateAsync(() => updateProperty("content", editString([new Insert(0, "a")])));

        const editedContent = await waitForEdit;

        expect(socket.send).toHaveBeenCalledWith(new UpdateCell(
            expect.anything(),
            expect.anything(),
            cellHandler.state.id,
            [new Insert(0, "a")],
            undefined
        ))

    })

    it ("sends presence when the selection changes", async () => {
        const cellHandler = await setupNotebook();
        await cellHandler.updateAsync(() => updateProperty("content", editString([new Insert(0, "// some content")])));
        (socket.send as any).mockClear();
        await cellHandler.updateAsync(() => updateProperty("currentSelection", setValue(new PosRange(2, 6))));

        expect(socket.send).toHaveBeenCalledWith(new CurrentSelection(cellHandler.state.id, new PosRange(2, 6)));
    })

})