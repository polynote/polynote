import {Branch, BranchComponent, BranchHandler, LeafComponent, NotebookList} from "./notebooklist";
import {StateHandler} from "../state/state_handler";
import {RequestNotebooksList, ServerMessageDispatcher} from "../messaging/dispatcher";
import {fireEvent, getByText, queryAllByText, queryByText, queryHelpers, waitFor} from "@testing-library/dom";
import * as messages from "../../../data/messages";
import {NotebookInfo, ServerStateHandler} from "../state/server_state";
import {SocketStateHandler} from "../state/socket_state";
import {SocketSession} from "../messaging/comms";
import {ServerMessageReceiver} from "../messaging/receiver";

jest.mock("../messaging/comms");

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
    const comp = new LeafComponent(dispatcher, leafState);
    const leafEl  = () => comp.el.querySelector("a.name")!;
    expect(leafEl()).toHaveAttribute('href', `notebooks/${leaf.fullPath}`);

    const newPath = "foo/bar/baz2";
    leafState.updateState(() => ({
        fullPath: newPath,
        value: "baz2"
    }));

    expect(leafEl()).toHaveAttribute('href', `notebooks/${newPath}`);

    fireEvent(leafEl(), new MouseEvent('click'));
    waitFor(() => {
        expect(mockSocket.send).toHaveBeenCalledWith(new messages.LoadNotebook(newPath))
    }).then(() => {
        const x = ServerStateHandler.get.getState().currentNotebook
        expect(ServerStateHandler.get.getState().currentNotebook).toEqual(newPath)
    }).then(done)
});

test('A BranchComponent should update when its state changes', done => {
    const branchState = new StateHandler<Branch>({
        fullPath: "foo",
        value: "foo",
        children: {}
    });
    const comp = new BranchComponent(dispatcher, branchState);
    expect(comp.childrenEl).toBeEmpty();

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

    expect(comp.childrenEl).not.toBeEmpty();
    expect(comp.childrenEl).toHaveTextContent(leaf.value);

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
    expect(comp.childrenEl).toHaveTextContent(newLeaf.value);

    expect(comp.el).not.toHaveClass("expanded");
    fireEvent(comp.el, new MouseEvent('click'));
    waitFor(() => {
        expect(comp.el).toHaveClass("expanded")
    }).then(done)
});

test("A BranchHandler should build a tree out of paths", () => {
    const root = {
        fullPath: "",
        value: "",
        children: {}
    };
    const branchHandler = new BranchHandler(root);
    const tree = new BranchComponent(dispatcher, branchHandler);

    // first add some notebooks at root, easy peasy.
    const simpleNBs = ["foo.ipynb", "bar.ipynb", "baz.ipynb"];
    simpleNBs.forEach(nb => branchHandler.addPath(nb));
    expect(Object.values(branchHandler.getState().children)).toEqual([
        {fullPath: "foo.ipynb", value: "foo.ipynb"},
        {fullPath: "bar.ipynb", value: "bar.ipynb"},
        {fullPath: "baz.ipynb", value: "baz.ipynb"},
    ]);
    expect(tree.el.children).toHaveLength(3);

    // next we will add a few directories
    const dirNBs = ["dir/one.ipynb", "dir/two.ipynb", "dir2/three.ipynb", "dir/four.ipynb"];
    dirNBs.forEach(nb => branchHandler.addPath(nb));
    expect(Object.values(branchHandler.getState().children)).toEqual([
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
    expect(branchHandler.getState().children).toEqual({
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
    const comp = new BranchComponent(dispatcher, branchHandler);
    expect(branchHandler.getState()).toMatchSnapshot();

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
    expect(branchHandler.getState()).toMatchSnapshot();
});

test("NotebookList e2e test", done => {
    const nbList = new NotebookList(dispatcher, ServerStateHandler.get);
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
        // expect(nbList.el.outerHTML).toMatchSnapshot()
    })
    .then(() => {
        const path = `notebooks/${paths[0]}`;
        // console.log(path)
        expect(nbList.el.querySelector(`[href='${path}']`)).not.toBeEmpty()
        SocketSession.global.send(new messages.DeleteNotebook(paths[0]))
        const x = nbList.el.outerHTML;
        // console.log(x)
        const y = nbList.el.querySelector(`[href='${path}']`);
        // console.log(y)
        waitFor(() => {
            expect(nbList.el.querySelector(`[href='${path}']`)).toBeNull()
        })
            .then(done)
    })//.then(done)
})


// TODO test rename, delete