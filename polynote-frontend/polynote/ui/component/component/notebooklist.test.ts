import {__LeafComponent} from "./notebooklist";
import {StateHandler} from "../state/state_handler";
import {ServerMessageDispatcher} from "../messaging/dispatcher";
import {fireEvent, queryHelpers, waitFor} from "@testing-library/dom";
import { LoadNotebook } from "../../../data/messages";
import {ServerStateHandler} from "../state/server_state";
import {SocketStateHandler} from "../state/socket_state";
import {SocketSession} from "../messaging/comms";

jest.mock("../messaging/comms");

test('A LeafComponent should dispatch a LoadNotebook when clicked', done => {

    const mockSocket = SocketSession.fromRelativeURL("notebookpath");
    const socketHandler = new SocketStateHandler(mockSocket);

    const dispatcher = new ServerMessageDispatcher(socketHandler);
    const leaf = {
        fullPath: "foo/bar/baz",
        value: "baz"
    };
    const comp = new __LeafComponent(dispatcher, new StateHandler(leaf));
    const leafEl  = comp.el.querySelector("a.name")!;
    expect(leafEl).toHaveAttribute('href', `notebooks/${leaf.fullPath}`);
    fireEvent(leafEl, new MouseEvent('click'));
    waitFor(() => {
        expect(mockSocket.send).toHaveBeenCalledWith(new LoadNotebook(leaf.fullPath))
    }).then(() => {
        expect(ServerStateHandler.get.getState().currentNotebook).toEqual(leaf.fullPath)
    }).then(done)
})