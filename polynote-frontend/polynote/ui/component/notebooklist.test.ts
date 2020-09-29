import {Branch, BranchEl, BranchHandler, LeafEl, NotebookList, NotebookListContextMenu} from "./notebooklist";
import {StateHandler} from "../../state/state_handler";
import {
    DeleteNotebook,
    RenameNotebook,
    RequestNotebooksList,
    ServerMessageDispatcher
} from "../../messaging/dispatcher";
import {fireEvent, getByText, queryAllByText, queryByText, queryHelpers, waitFor} from "@testing-library/dom";
import * as messages from "../../data/messages";
import {NotebookInfo, ServerStateHandler} from "../../state/server_state";
import {SocketStateHandler} from "../../state/socket_state";
import {SocketSession} from "../../messaging/comms";
import {ServerMessageReceiver} from "../../messaging/receiver";

jest.mock("../../messaging/comms");

const mockSocket = SocketSession.fromRelativeURL("notebookpath");
const socketHandler = new SocketStateHandler(mockSocket);

const dispatcher = new ServerMessageDispatcher(socketHandler);
const receiver = new ServerMessageReceiver();

test('A LeafComponent should dispatch a LoadNotebook when clicked', done => {
    const leaf = {
        fullPath: "foo/bar/baz",
        value: "baz"
    };
    const leafState = new StateHandler(leaf);
    const comp = new LeafEl(dispatcher, leafState);
    const leafEl  = () => comp.el.querySelector("a.name")!;
    expect(leafEl()).toHaveAttribute('href', `notebooks/${leaf.fullPath}`);

    const newPath = "foo/bar/baz2";
    leafState.updateState(() => ({
        fullPath: newPath,
        value: "baz2"
    }));

    expect(leafEl()).toHaveAttribute('href', `notebooks/${newPath}`);

    const spy = jest.spyOn(SocketSession, 'fromRelativeURL')
    fireEvent(leafEl(), new MouseEvent('click'));
    waitFor(() => {
        expect(spy).toHaveBeenCalledWith('ws/' + encodeURIComponent(newPath))
    }).then(() => {
        expect(ServerStateHandler.state.currentNotebook).toEqual(newPath)
    }).then(done)
});

describe("BranchComponent", () => {
    const branchState = new StateHandler<Branch>({
        fullPath: "foo",
        value: "foo",
        children: {}
    });
    const branch = new BranchEl(dispatcher, branchState);
    expect(branch.childrenEl).toBeEmpty();

    const leaf = {
        fullPath: "bar",
        value: "bar"
    };
    branchState.updateState(s => {
        return {
            ...s,
            children: {
                ...s.children,
                [leaf.fullPath]: leaf
            }
        }
    });
    test('is updated when its state changes', done => {
        expect(branch.childrenEl).not.toBeEmpty();
        expect(branch.childrenEl).toHaveTextContent(leaf.value);

        const newLeaf = {
            fullPath: "baz",
            value: "baz"
        };
        branchState.updateState(s => {
            return {
                ...s,
                children: {
                    ...s.children,
                    [leaf.fullPath]: newLeaf
                }
            }
        });
        expect(branch.childrenEl).toHaveTextContent(newLeaf.value);

        expect(branch.el).not.toHaveClass("expanded");
        fireEvent(branch.el, new MouseEvent('click'));
        waitFor(() => {
            expect(branch.el).toHaveClass("expanded")
        }).then(done)
    });

    test('can trigger a notebook rename', done => {
        const contextMenu = NotebookListContextMenu.get(dispatcher)
        expect(contextMenu.el).not.toBeInTheDocument()

        const leafEl = branch.childrenEl.children[0];

        fireEvent(leafEl, new MouseEvent("contextmenu"))
        waitFor(() => {
            expect(contextMenu.el).toBeInTheDocument()
        }).then(() => {
            const mockDispatch = jest.spyOn(dispatcher, 'dispatch').mockImplementation((() => {}))
            const rename = contextMenu.el.querySelector('.rename')!;
            fireEvent(rename, new MouseEvent('click'))
            return waitFor(() => {
                expect(mockDispatch).toHaveBeenCalledWith(new RenameNotebook(leaf.fullPath))
            }).then(() => {
                mockDispatch.mockRestore()
            })
        }).then(done)
    })

    test('can trigger a notebook deletion', done => {
        const contextMenu = NotebookListContextMenu.get(dispatcher)
        expect(contextMenu.el).not.toBeInTheDocument()

        const leafEl = branch.childrenEl.children[0];

        fireEvent(leafEl, new MouseEvent("contextmenu"))
        waitFor(() => {
            expect(contextMenu.el).toBeInTheDocument()
        }).then(() => {
            const mockDispatch = jest.spyOn(dispatcher, 'dispatch').mockImplementation((() => {}))
            const del = contextMenu.el.querySelector('.delete')!;
            fireEvent(del, new MouseEvent('click'))
            return waitFor(() => {
                expect(mockDispatch).toHaveBeenCalledWith(new DeleteNotebook(leaf.fullPath))
            }).then(() => {
                mockDispatch.mockRestore()
            })
        }).then(done)
    })
})

test("A BranchHandler should build a tree out of paths", () => {
    const root = {
        fullPath: "",
        value: "",
        children: {}
    };
    const branchHandler = new BranchHandler(root);
    const tree = new BranchEl(dispatcher, branchHandler);

    // first add some notebooks at root, easy peasy.
    const simpleNBs = ["foo.ipynb", "bar.ipynb", "baz.ipynb"];
    simpleNBs.forEach(nb => branchHandler.addPath(nb));
    expect(Object.values(branchHandler.state.children)).toEqual([
        {fullPath: "foo.ipynb", value: "foo.ipynb"},
        {fullPath: "bar.ipynb", value: "bar.ipynb"},
        {fullPath: "baz.ipynb", value: "baz.ipynb"},
    ]);
    expect(tree.el.children).toHaveLength(3);

    // next we will add a few directories
    const dirNBs = ["dir/one.ipynb", "dir/two.ipynb", "dir2/three.ipynb", "dir/four.ipynb"];
    dirNBs.forEach(nb => branchHandler.addPath(nb));
    expect(Object.values(branchHandler.state.children)).toEqual([
        {fullPath: "foo.ipynb", value: "foo.ipynb"},
        {fullPath: "bar.ipynb", value: "bar.ipynb"},
        {fullPath: "baz.ipynb", value: "baz.ipynb"},
        {fullPath: "dir", value: "dir", children: {
            "dir/one.ipynb": {fullPath: "dir/one.ipynb", value: "one.ipynb"},
            "dir/two.ipynb": {fullPath: "dir/two.ipynb", value: "two.ipynb"},
            "dir/four.ipynb": {fullPath: "dir/four.ipynb", value: "four.ipynb"},
        }},
        {fullPath: "dir2", value: "dir2", children: {
            "dir2/three.ipynb": {fullPath: "dir2/three.ipynb", value: "three.ipynb"},
        }}
    ]);
    expect(tree.el.children).toHaveLength(5);
    const branches = tree.el.querySelectorAll(".branch");
    expect(branches).toHaveLength(2);
    // TODO it would be nice if there was a better selector utility for this. Probably using the wrong API or something
    const dir = [...branches].find((b: HTMLElement) => queryByText(b, "dir"))!.querySelector("ul")!;
    const dir2 = [...branches].find((b: HTMLElement) => queryByText(b, "dir2"))!.querySelector("ul")!;
    expect(dir.children).toHaveLength(3);
    expect(dir2.children).toHaveLength(1);

    // next let's go nuts with some nested notebooks!
    branchHandler.addPath("dir/another.ipynb");
    branchHandler.addPath("dir/newdir/more.ipynb");
    branchHandler.addPath("dir/newdir/newer/even_more.ipynb");
    branchHandler.addPath("dir/1/2/3/4/surprisinglydeep.ipynb");
    branchHandler.addPath("dir/1/2/oh_my.ipynb");
    branchHandler.addPath("path/to/my/notebook.ipynb");
    expect(branchHandler.state.children).toEqual({
        "foo.ipynb": {fullPath: "foo.ipynb", value: "foo.ipynb"},
        "bar.ipynb": {fullPath: "bar.ipynb", value: "bar.ipynb"},
        "baz.ipynb": {fullPath: "baz.ipynb", value: "baz.ipynb"},
        "dir": {fullPath: "dir", value: "dir", children: {
                "dir/one.ipynb": {fullPath: "dir/one.ipynb", value: "one.ipynb"},
                "dir/two.ipynb": {fullPath: "dir/two.ipynb", value: "two.ipynb"},
                "dir/four.ipynb": {fullPath: "dir/four.ipynb", value: "four.ipynb"},
                "dir/another.ipynb": {fullPath: "dir/another.ipynb", value: "another.ipynb"},
                "dir/newdir": {fullPath: "dir/newdir", value: "newdir", children: {
                    "dir/newdir/more.ipynb": {fullPath: "dir/newdir/more.ipynb", value: "more.ipynb"},
                    "dir/newdir/newer": {fullPath: "dir/newdir/newer", value: "newer", children: {
                        "dir/newdir/newer/even_more.ipynb": {fullPath: "dir/newdir/newer/even_more.ipynb", value: "even_more.ipynb"},
                    }},
                }},
                "dir/1": {fullPath: "dir/1", value: "1", children: {
                    "dir/1/2": {fullPath: "dir/1/2", value: "2", children: {
                        "dir/1/2/3": {fullPath: "dir/1/2/3", value: "3", children: {
                            "dir/1/2/3/4": {fullPath: "dir/1/2/3/4", value: "4", children: {
                                "dir/1/2/3/4/surprisinglydeep.ipynb": {fullPath: "dir/1/2/3/4/surprisinglydeep.ipynb", value: "surprisinglydeep.ipynb"},
                            }},
                        }},
                        "dir/1/2/oh_my.ipynb": {fullPath: "dir/1/2/oh_my.ipynb", value: "oh_my.ipynb"},
                    }},
                }},
            }},
        "dir2": {fullPath: "dir2", value: "dir2", children: {
            "dir2/three.ipynb": {fullPath: "dir2/three.ipynb", value: "three.ipynb"},
        }},
        "path": {fullPath: "path", value: "path", children: {
            "path/to": {fullPath: "path/to", value: "to", children: {
                "path/to/my": {fullPath: "path/to/my", value: "my", children: {
                    "path/to/my/notebook.ipynb": {fullPath: "path/to/my/notebook.ipynb", value: "notebook.ipynb"},
                }},
            }},
        }},
    });

    expect(tree.el.outerHTML).toMatchSnapshot()
});

test("stress test", () => {
    const root = {
        fullPath: "",
        value: "",
        children: {}
    };
    const branchHandler = new BranchHandler(root);
    const comp = new BranchEl(dispatcher, branchHandler);
    expect(branchHandler.state).toMatchSnapshot();

    const max = 300;
    [...Array(max).keys()].map(x => {
        let path = `root/${x}`;
        if (x % 10) {
            path = `dir/${path}`
        }
        if (x % 20) {
            path = `dir2/${path}`
        }
        return path
    }).forEach(p => {
        branchHandler.addPath(p)
    });
    expect(branchHandler.state).toMatchSnapshot();
});

test("NotebookList e2e test", done => {
    const nbList = new NotebookList(dispatcher);
    expect(mockSocket.send).toHaveBeenCalledWith(new messages.ListNotebooks([])); // gets called when the notebook list is initialized.
    expect(nbList.el.querySelector('.tree-view > ul')).toBeEmpty();

    // this will trigger the receiver to update global state
    const paths = [...Array(500).keys()].map(x => {
        let path = `root/${x}`;
        if (x % 10) {
            path = `dir/${path}`
        }
        if (x % 20) {
            path = `dir2/${path}`
        }
        return path
    });
    SocketSession.global.send(new messages.ListNotebooks(paths));

    waitFor(() => {
        expect(nbList.el.querySelector('.tree-view > ul')).not.toBeEmpty();
    }).then(() => {
        expect(nbList.el.outerHTML).toMatchSnapshot()
    })
    .then(() => {
        const path = `notebooks/${paths[0]}`;
        expect(nbList.el.querySelector(`[href='${path}']`)).not.toBeEmpty()
        SocketSession.global.send(new messages.DeleteNotebook(paths[0]))
        waitFor(() => {
            expect(nbList.el.querySelector(`[href='${path}']`)).toBeNull()
        }).then(done)
    })
})


// TODO test rename, delete