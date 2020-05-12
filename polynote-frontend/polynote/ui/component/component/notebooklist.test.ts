import {mocked} from "ts-jest/utils";
import {__LeafComponent} from "./notebooklist";
import {StateHandler} from "../state/state_handler";
import {ServerMessageDispatcher} from "../messaging/dispatcher";
import {fireEvent, queryHelpers} from "@testing-library/dom";
// jest.mock("../messaging/dispatcher", () => {
//     return {
//         ServerMessageDispatcher: jest.fn().mockImplementation(() => {
//
//         })
//     }
// });

test('A LeafComponent should dispatch a LoadNotebook when clicked', () => {
    const mockDispatch = jest.fn();
    const dispatcher = mocked(ServerMessageDispatcher, true).mockImplementation(() => {
        return {dispatch: mockDispatch} as any as ServerMessageDispatcher
    }) as any as ServerMessageDispatcher;
    const leaf = {
        fullPath: "foo/bar/baz",
        value: "baz"
    };
    const comp = new __LeafComponent(dispatcher, new StateHandler(leaf));
    const leafEl  = comp.el.querySelector("a.name")!;
    expect(leafEl).toHaveAttribute('href', `notebooks/${leaf.fullPath}`);
    expect(dispatcher.dispatch).toHaveBeenCalledTimes(1)
    fireEvent(leafEl, new MouseEvent('click'))
})