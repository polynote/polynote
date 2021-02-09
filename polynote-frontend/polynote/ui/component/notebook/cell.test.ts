import {NotebookMessageDispatcher} from "../../../messaging/dispatcher";
import {SocketSession} from "../../../messaging/comms";
import {Disposable, editString} from "../../../state";
import {NotebookMessageReceiver} from "../../../messaging/receiver";
import {Insert} from "../../../data/content_edit";
import {UpdateCell} from "../../../data/messages";
import {NotebookStateHandler} from "../../../state/notebook_state";
import {SocketStateHandler} from "../../../state/socket_state";
import {ClientBackup} from "../../../state/client_backup";

jest.mock("../../../messaging/comms");

let nbState: NotebookStateHandler,
    socket: SocketSession,
    socketState: SocketStateHandler,
    receiver: NotebookMessageReceiver;

let stateUpdateDisp = new Disposable()

beforeEach(() => {
    nbState = NotebookStateHandler.forPath("foo").disposeWith(stateUpdateDisp)
    ClientBackup.addNb("foo", [])
    nbState.updateHandler.globalVersion = 0 // initialize version
    socket = SocketSession.fromRelativeURL(nbState.state.path)
    socketState = new SocketStateHandler(socket)
    receiver = new NotebookMessageReceiver(socketState, nbState)

    // close the server loop for messages that bounce off it (e.g., InsertCell)
    nbState.updateHandler.addObserver(update => {
        setTimeout(() => { receiver.inject(update) }, 0)
    })
})

afterEach(() => {
    stateUpdateDisp.dispose()
    stateUpdateDisp = new Disposable()
    ClientBackup.clearBackups();
})

describe("Code cell", () => {

    it ("sends updates when its content changes", async () => {
        const cellId = await nbState.insertCell("below");
        await nbState.setCellLanguage(cellId, "scala");
        const dispatcher = new NotebookMessageDispatcher(socketState, nbState)
        const cellHandler = nbState.cellsHandler.lens(cellId);

        const waitForEdit = new Promise((resolve, reject) => {
            cellHandler.addObserver(cellState => {
                try {
                    resolve(cellState.content)
                } catch (err) {
                    reject(err);
                }
            })
        });

        cellHandler.updateField("content", editString([new Insert(0, "a")]))

        const editedContent = await waitForEdit;

        expect(socket.send).toHaveBeenCalledWith(new UpdateCell(
            expect.anything(),
            expect.anything(),
            cellId,
            [new Insert(0, "a")],
            undefined
        ))

    })

})