import {div, h2, TagElement} from "../tags";
import {NotebookInfo} from "../../state/server_state";
import {Disposable, IDisposable, UpdateResult} from "../../state";
import {CellState, NotebookStateHandler} from "../../state/notebook_state";

export interface TableOfContentsHeading {
    title: string,
    cellId: number,
    headingType: string,
}

/**
 * Manages and renders the table of contents for the current notebook.
 *
 * It would be prudent to not have to do re-render the entire TOC whenever a single text cell changes
 * We only update the state of the cell that changes state, but we still re-render the entire TOC today for simplicity
 * (because cases like insertion in the middle of a list, changing active cells, and changing cell order makes DOM manipulation non-trivial)
 * This has been ignored for now because it doesn't make a difference in terms of UI load times, even in large notebooks.
 */
export class TableOfContents extends Disposable {
    readonly el: TagElement<"div">;
    readonly header: TagElement<"h2">;

    private notebookState: NotebookStateHandler;
    private observers: IDisposable[];

    private tableOfContents: Record<number, TableOfContentsHeading[]> | undefined;
    private cellOrder: number[];
    private activeHeadingId: number;

    constructor() {
        super();

        this.onDispose.then(() => {
            this.observers.forEach(obs => obs.dispose());
        });

        this.observers = [];

        this.header = h2([], ["Table of Contents"]);
        this.el = div(["table-of-contents"], []);

        this.setHTML(true);
    }

    /**
     * Resets the table of contents given a new notebook
     */
    public setNewNotebook(nb: NotebookInfo) {
        // Clear the old state
        this.tableOfContents = undefined;
        this.cellOrder = [];
        this.activeHeadingId = -1;

        // Dispose of the old observers
        if (this.observers.length !== 0) {
            this.observers.forEach(obs => obs.dispose());
            this.observers = [];
        }

        // Save the handler and initialize the new table of contents
        this.notebookState = nb.handler;
        this.initTableOfContentsObservers();
    }

    /**
     * Initializes all the observers necessary for the table of contents, and calls the ones necessary to initialize a new notebook
     */
    private initTableOfContentsObservers() {
        this.observers.push(this.notebookState.view("activeCellId").addObserver(activeCellId => {
            this.findAndSelectNearestHeader(activeCellId);
        }));

        this.observers.push(this.notebookState.view("cellOrder").addObserver((newOrder) =>
            this.cellOrderUpdateHandler(newOrder)));
        this.cellOrderUpdateHandler(this.notebookState.state.cellOrder);

        this.observers.push(this.notebookState.view("cells").addObserver((newCells, update) =>
            this.cellUpdateHandler(newCells, update)));
        this.cellUpdateHandler(this.notebookState.state.cells);
    }

    /**
     * Handles a new notebook cell order and then re-renders the table of contents HTML accordingly
     */
    private cellOrderUpdateHandler(newOrder: number[]) {
        let newCellOrder = [];

        for (const location of Object.values(newOrder)) {
            newCellOrder.push(location);
        }

        this.cellOrder = newCellOrder;
        this.setHTML(); // Re-displays the entire table of contents
    }

    /**
     * Handles a change in a cell(s)' state by finding all updated cells and then re-renders the HTML accordingly
     */
    private cellUpdateHandler(newCells: Record<number, CellState>, update?: UpdateResult<Record<number, CellState>>) {
        let newTableOfContents: Record<number, TableOfContentsHeading[]> = [];
        let cellsToUpdate: Record<number, CellState> = {};

        // Gather a list of all text cells that must be updated
        if (this.tableOfContents === undefined) // If the table of contents has not been initialized yet, use all cells
            cellsToUpdate = newCells;
        else if (update?.fieldUpdates) {
            for (const [id, fieldUpdate] of Object.entries(update.fieldUpdates)) {
                const idAsNum = parseInt(id);

                // If a content update occurred to a text cell, or a text cell was removed
                if ((fieldUpdate?.newValue?.language === "text" && fieldUpdate.fieldUpdates?.content) ||
                    (update.removedValues !== undefined && update.removedValues[idAsNum]?.language === "text")) {
                    cellsToUpdate[idAsNum] = this.notebookState.state.cells[idAsNum];
                }
            }
        }

        // If there were any text cells with new content, update them in the table of contents
        if (Object.keys(cellsToUpdate).length > 0) {
            this.tableOfContents = this.updateTableOfContents(cellsToUpdate, update?.removedValues !== undefined);
            this.setHTML();
        }
    }

    /**
     * Updates the current table of contents data structure by finding all headings in the parameter cells and updating
     * their respective headings in a new returned dict
     */
    private updateTableOfContents(cells: Record<number, CellState>, isDelete: boolean): Record<number, TableOfContentsHeading[]> {
        let newTableOfContents: Record<number, TableOfContentsHeading[]> = this.tableOfContents !== undefined ? this.tableOfContents : {};

        for (const [id, state] of Object.entries(cells)) {
            const idAsNum = parseInt(id);

            if (isDelete) {
                delete newTableOfContents[idAsNum];
            } else {
                const headings = this.getHeadingsFromCellContent(state.content, state.id);
                newTableOfContents[idAsNum] = headings;
            }
        }

        return newTableOfContents;
    }

    /**
     * Converts each line that is a heading into a new TableOfContents element in the array
     */
    private getHeadingsFromCellContent(content: string, cellId: number): TableOfContentsHeading[] {
        let results: TableOfContentsHeading[] = [];
        const headings = content.match(/#{1,6}.+/g); // Extracts h1-h6 tags denoted with '#' at the start of each line

        headings?.forEach(function (s, index) {
            const heading = s.trim().substring(0, s.indexOf(' '));
            const title = s.trim().substring(s.indexOf(' ') + 1);

            if (heading !== null && title !== null) {
                results.push({
                    title,
                    cellId,
                    headingType: "h" + heading.length,
                })
            }
        })

        return results;
    }

    /**
     * Converts the current notebook's table of contents into HTML and renders it
     */
    public setHTML(showError: boolean = false): void {
        this.el.innerHTML = "";
        if (showError) {
            this.el.appendChild(h2([], ["To see your table of contents, switch to an active notebook."]));
        } else {
            if (this.tableOfContents !== undefined && Object.entries(this.tableOfContents).length > 0 && this.cellOrder.length > 0) {
                this.cellOrder.forEach(num => {
                    if (this.tableOfContents !== undefined && this.tableOfContents[num] !== undefined && this.notebookState.state.cells[num].language === "text") {
                        for (const el of Object.values(this.tableOfContents[num])) {
                            this.el.appendChild(this.elToTableOfContentsTag(el));
                        }
                    }
                });

                // If there was nothing to add, this must be a notebook's table of contents initialization with no valid text cells yet
                if (this.el.innerHTML === "")
                    this.el.appendChild(h2([], ["No table of contents yet. To get started, make an h1-h6 heading."]));

                this.findAndSelectNearestHeader(this.notebookState.state.activeCellId);
            } else {
                this.el.appendChild(h2([], ["No table of contents yet. To get started, make an h1-h6 heading."]));
            }
        }
    }

    /**
     * Converts a given table of contents element into the proper HTML semantic tag
     */
    private elToTableOfContentsTag(tableOfContentsEl: TableOfContentsHeading): HTMLHeadingElement {
        let h = h2([tableOfContentsEl.headingType], tableOfContentsEl.title).dataAttr('data-cellid', tableOfContentsEl.cellId.toString());
        if (tableOfContentsEl.cellId === this.activeHeadingId)
            h.classList.add('active');
        this.onHeadingClick(tableOfContentsEl.cellId, h);
        return h;
    }

    /**
     * Attaches a click handler to a given table of contents heading element. This action will:
     *   - Jump to the respective cell ID the heading represents
     *   - Attach a UI visual that that heading is currently selected
     *   - Mark the previously active heading (if applicable) as not active
     */
    private onHeadingClick(cellId: number, el: TagElement<any>) {
        el.click(() => {
            if (cellId !== this.notebookState.state.activeCellId) {
                this.notebookState.selectCell(cellId, {editing: true});
            }
        })
    }

    /**
     * Selects a header by its cell ID
     */
    private selectHeader(cellId?: number) {
        this.removeActiveClass();
        this.activeHeadingId = -1;

        if (cellId !== undefined) {
            const newActiveEls = document.body.querySelectorAll(`[data-cellid="${cellId}"]`);
            newActiveEls.forEach(el => el.classList.add('active'));
            this.activeHeadingId = cellId;
        }
    }

    /**
     * Finds the nearest cell with a header element to the current cell and selects it as focused in the UI if possible
     */
    private findAndSelectNearestHeader(activeCellId: number | undefined) {
        if (this.tableOfContents === undefined) return;

        if (activeCellId === undefined || Object.keys(this.tableOfContents).length === 0) {
            this.removeActiveClass();
            return;
        }

        let i = this.cellOrder.indexOf(activeCellId);
        if (this.tableOfContents[activeCellId] === undefined || this.tableOfContents[activeCellId].length === 0) {
            i--;
            while (i >= 0 && (this.tableOfContents[this.cellOrder[i]] === undefined || this.tableOfContents[this.cellOrder[i]].length === 0)) {
                i--;
            }
        }

        // Select the above markdown cell if it was found, otherwise select nothing and deselect the current selection
        this.selectHeader(i !== -1 && this.cellOrder[i] !== undefined ? this.cellOrder[i] : undefined);
    }

    /**
     * Helper to remove the active class from the currently active heading
     */
    private removeActiveClass() {
        const oldActiveEls = this.el.querySelectorAll('.active');
        oldActiveEls.forEach(el => el.classList.remove('active'));
    }
}