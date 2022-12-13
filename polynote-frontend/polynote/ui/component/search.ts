import {Disposable, IDisposable} from "../../state";
import {div, iconButton, label, para, table, TableElement, TagElement, textbox} from "../tags";
import {ServerMessageDispatcher} from "../../messaging/dispatcher";
import {ServerStateHandler} from "../../state/server_state";
import {Modal} from "../layout/modal";

export class SearchModal extends Modal implements IDisposable {
    private queryInput: TagElement<"input">;
    private serverMessageDispatcher: ServerMessageDispatcher;
    private disposable: Disposable;
    private searchStatus: HTMLParagraphElement;
    private resultsEl: TableElement;

    constructor(serverMessageDispatcher: ServerMessageDispatcher) {
        const input = textbox(['create-notebook-section'], "Search Term", "")
        input.select();
        input.addEventListener('Accept', evt => this.submitSearch());
        input.addEventListener('Cancel', evt => this.hide());

        let resultsEl = table(['small'], {
            classes: ['line', 'file_cell'],
            rowHeading: false
        });
        let searchStatus = para([], []);
        const wrapper = div(['input-dialog'], [
            div(['inline-input-row'], [
                label(['inline-input'], 'Search', input),
                iconButton(['search'], "Search Notebooks", "search", "Search").click(() => this.submitSearch()),
            ]),
            searchStatus,
            resultsEl
        ]);
        super(wrapper, { title: "Search Notebooks", windowClasses: ['search'] });

        this.disposable = new Disposable();
        this.serverMessageDispatcher = serverMessageDispatcher;
        this.queryInput = input;
        this.searchStatus = searchStatus;
        this.resultsEl = resultsEl;

        ServerStateHandler.view("searchResults").addObserver(results => {
            // On a new query's results being received, reset the table and searchStatus
            this.searchStatus.innerText = "";
            while (this.resultsEl.rows.length > 0) {
                this.resultsEl.deleteRow(0);
            }

            if (results.length == 0) {
                this.searchStatus.innerText = "No results found";
                return;
            }

            results.forEach(result => {
                // First, extract the first instance of the query from each cell.
                // We choose to do this here instead of the backend for flexibility - if we want to display the entire cell
                // Or multiple results per cell in the future, it should be rather easy.
                const line = result.cellContent.split('\n').find(line => {
                    return line.includes(this.queryInput.value);
                })

                this.resultsEl.addRow({
                    line,
                    file_cell: `${result.path} - ${result.cellTitle ?? `Cell ${result.cellID}`}`
                })

                // Add an event listener to the newly created row to open up the proper notebook
                const newRow = this.resultsEl.rows[this.resultsEl.rows.length - 1];
                newRow.addEventListener('click', () => {
                    ServerStateHandler.loadNotebook(result.path, true)
                        .then(() => {
                            ServerStateHandler.selectNotebook(result.path)
                            const nbInfo = ServerStateHandler.getOrCreateNotebook(result.path);
                            nbInfo.handler.selectCell(result.cellID);
                        })
                    this.hide();
                })
            })

        }).disposeWith(this);
    }

    show() {
        super.show();
    }

    showUI() {
        this.container.style.display = "flex";
        this.queryInput.focus();
    }

    hide() {
        this.container.style.display = "none";
    }

    private submitSearch() {
        if (this.queryInput.value.trim().length == 0) return;

        // expand the search box so it can hold larger results
        this.resultsEl.classList.remove('small');
        this.resultsEl.classList.remove('large');

        // Start the actual search
        this.searchStatus.innerText = "Searching...";
        this.serverMessageDispatcher?.searchNotebooks(this.queryInput.value);
    }

    // implement IDisposable
    dispose() {
        return this.disposable.dispose()
    }

    get onDispose() {
        return this.disposable.onDispose
    }

    get isDisposed() {
        return this.disposable.isDisposed
    }

    tryDispose() {
        return this.disposable.tryDispose()
    }

    disposeWith(that: IDisposable): this {
        this.disposable.disposeWith(that);
        return this;
    }
}