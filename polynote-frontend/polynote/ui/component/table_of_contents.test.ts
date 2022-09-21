import {TableOfContents, TableOfContentsHeading} from "./table_of_contents";
import {NotebookStateHandler} from "../../state/notebook_state";
import {Disposable, editString, updateProperty} from "../../state";
import {NotebookInfo, ServerStateHandler} from "../../state/server_state";
import {CellMetadata} from "../../data/data";
import {Insert} from "../../data/content_edit";
import {SocketSession} from "../../messaging/comms";
import {SocketStateHandler} from "../../state/socket_state";
import {NotebookMessageReceiver} from "../../messaging/receiver";
import {NotebookMessageDispatcher} from "../../messaging/dispatcher";
import {ClientBackup} from "../../state/client_backup";

let stateUpdateDisp = new Disposable();
let nbState: NotebookStateHandler,
    tableOfContents: TableOfContents,
    tableOfContentsHeadings: TableOfContentsHeading[],
    nb: NotebookInfo,
    socket: SocketSession,
    socketState: SocketStateHandler,
    dispatcher: NotebookMessageDispatcher,
    receiver: NotebookMessageReceiver;

beforeEach(() => {
    nbState = NotebookStateHandler.forPath("foo").disposeWith(stateUpdateDisp);
    jest.spyOn(ServerStateHandler, 'getOrCreateNotebook').mockImplementation(() => ({handler: nbState, loaded: true}));

    tableOfContents = new TableOfContents();
    tableOfContentsHeadings = [];
    nb = ServerStateHandler.getOrCreateNotebook('foo');
    tableOfContents.setNewNotebook(nb);

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
    stateUpdateDisp.dispose();
    stateUpdateDisp = new Disposable();
    return ClientBackup.clearBackups();
})

/**
 * Helper to verify the table of contents' HTML.
 * Iterates through the HTML and the list of expected elements to ensure there are no extraneous elements.
 * Also verifies correct ordering of headings.
 */
function verifyTableOfContentsHTML() {
    const els = tableOfContents.el.querySelectorAll('h2');

    els.forEach((el, i) => {
        expect(el).toContainHTML(tableOfContentsHeadings[i].title);
        expect(el.getAttribute('class')).toBe(tableOfContentsHeadings[i].headingType);
        expect(el.getAttribute('data-cellid')).toBe(tableOfContentsHeadings[i].cellId.toString());
    });

    tableOfContentsHeadings.forEach((ex, i) => {
        expect(els[i]).toContainHTML(ex.title);
        expect(els[i].getAttribute('class')).toBe(ex.headingType);
        expect(els[i].getAttribute('data-cellid')).toBe(ex.cellId.toString());
    })
}

describe("TableOfContents", () => {
    test('Shows notification on empty page', () => {
        expect(tableOfContents.el).toContainHTML("No table of contents yet. To get started, make an h1-h6 heading.");
    });

    test("Ignores non-headings and non-text cells", async () => {
        await nbState.insertCell("below", {id: 0, language: "scala", metadata: new CellMetadata()});
        await nbState.cellsHandler.lens(0).updateAsync(() => updateProperty("content", editString([new Insert(0, "# some h1")])));

        await nbState.insertCell("below", {id: 1, language: "text", metadata: new CellMetadata()});
        await nbState.cellsHandler.lens(1).updateAsync(() => updateProperty("content", editString([new Insert(0, "not a real h1")])));

        expect(tableOfContents.el).toContainHTML("No table of contents yet. To get started, make an h1-h6 heading.");
    });

    test('Shows heading when inserted', async () => {
        await nbState.insertCell("below", {id: 0, language: "text", metadata: new CellMetadata()});
        await nbState.cellsHandler.lens(0).updateAsync(() => updateProperty("content", editString([new Insert(0, "# some h1")])));

        tableOfContentsHeadings.push({title: "some h1", headingType: "h1", cellId: 0});

        verifyTableOfContentsHTML();
    });

    test('Shows headings when multiple are inserted', async () => {
        await nbState.insertCell("above", {id: 0, language: "text", metadata: new CellMetadata()});
        await nbState.cellsHandler.lens(0).updateAsync(() => updateProperty("content", editString([new Insert(0, "## some h2")])));
        await nbState.cellsHandler.lens(0).updateAsync(() => updateProperty("content", editString([new Insert(10, "\n### some h3")])));

        tableOfContentsHeadings.push({title: "some h2", headingType: "h2", cellId: 0});
        tableOfContentsHeadings.push({title: "some h3", headingType: "h3", cellId: 0});

        verifyTableOfContentsHTML();
    });

    test('Shows correct headings when one is deleted', async () => {
        await nbState.insertCell("above", {id: 0, language: "text", metadata: new CellMetadata()});
        await nbState.cellsHandler.lens(0).updateAsync(() => updateProperty("content", editString([new Insert(0, "## some h2")])));
        await nbState.insertCell("above", {id: 1, language: "text", metadata: new CellMetadata()});
        await nbState.cellsHandler.lens(1).updateAsync(() => updateProperty("content", editString([new Insert(0, "### some h3")])));
        await nbState.deleteCell(1);

        tableOfContentsHeadings.push({title: "some h2", headingType: "h2", cellId: 0});

        verifyTableOfContentsHTML();
    });
});