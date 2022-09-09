import {div, h1, h2, h3, h4, h5, h6, iconButton, span, TagElement} from "../tags";
import {ServerStateHandler} from "../../state/server_state";
import {Disposable, IDisposable, NoUpdate, setValue} from "../../state";
import {CellState, NotebookStateHandler, TOCState} from "../../state/notebook_state";
import {ServerMessageDispatcher} from "../../messaging/dispatcher";
import {NotebookListModal} from "./notebook_list";

/* TODO:
    1. Fix disposeWith statements
    2. Fix tab switching (could be related to #1). We should probably try to have an array of observers we manually clear instead on tab switch.
    3. The "cellId" doesn't have to be a part of the document, just the key in the key-value pair
 */

export class TableOfContents extends Disposable {
    readonly el: TagElement<"div">;
    readonly header: TagElement<"h2">;

    private curNBObserver: IDisposable;
    private curNBTOC: Record<number, TOCState[]> | undefined;
    private notebookState: NotebookStateHandler;
    private cellOrder: number[];

    constructor(readonly dispatcher: ServerMessageDispatcher) {
        super();

        this.curNBTOC = undefined;
        this.cellOrder = [];

        const nbListModal = new NotebookListModal(dispatcher);
        nbListModal.show();
        nbListModal.hide();

        this.header = h2(['ui-panel-header', 'notebooks-list-header'], [
            'Table of Contents',
            span(['buttons'], [
                iconButton(["search"], "Search Notebooks", "search", "Search").click(evt => {
                    evt.stopPropagation();
                    console.log();
                }),
                iconButton(["search"], "Search Notebooks", "folder", "Search").click(evt => {
                    evt.stopPropagation();
                    nbListModal.showUI();
                }),
                iconButton(['create-notebook'], 'Create new notebook', 'plus-circle', 'New').click(evt => {
                    evt.stopPropagation();
                    dispatcher.createNotebook()
                }),
            ])
        ]);

        this.el = div(["table-of-contents"], []);

        ServerStateHandler.get.view("currentNotebook").addObserver(path => {
            if (!path) return;
            if (this.curNBObserver) {
                this.curNBObserver.dispose().then(() => {
                    this.curNBTOC = undefined;
                    this.observeTOC(path);
                });
            } else {
                this.observeTOC(path);
            }
        }).disposeWith(this)
    }

    private observeTOC(path: string) {
        const nb = ServerStateHandler.getOrCreateNotebook(path);
        if (nb?.handler) {
            this.notebookState = nb.handler;

            this.notebookState.view("activeCellId").addObserver(activeCellId => {
                // TODO: Uncomment this after fixing the findAndSelectNearestHeader method
                // this.findAndSelectNearestHeader(activeCellId);
            }).disposeWith(this);

            this.notebookState.view("cellOrder").addObserver((newOrder, update) => {
                let order = [];

                for (const [id, location] of Object.entries(newOrder)) {
                    order.push(location);
                }

                this.cellOrder = order;
                this.generateTOCHTML();
            }).disposeWith(this);


            this.notebookState.view("cells").addObserver((newCells, update) => {
                let newTOC: Record<number, TOCState[]> = [];
                let cellsToUpdate: Record<number, CellState> = {};

                // Gather a list of all cells that must be updated
                if (this.curNBTOC === undefined)
                    cellsToUpdate = newCells;
                else if (update.fieldUpdates) {
                    for (const [id, fieldUpdate] of Object.entries(update.fieldUpdates)) {
                        if (fieldUpdate?.fieldUpdates?.content && fieldUpdate.newValue?.language === "text") {
                            cellsToUpdate[parseInt(id)] = this.notebookState.state.cells[parseInt(id)];
                        }
                    }
                }

                // If there were any text cells with new content, update them in the TOC
                if (Object.keys(cellsToUpdate).length > 0) {
                    newTOC = this.updateTOC(cellsToUpdate);
                    this.curNBTOC = newTOC;
                    this.notebookState.updateField("toc", () => setValue(this.curNBTOC ?? {}));
                    this.generateTOCHTML();
                }
            }).disposeWith(this);
        }
    }

    private updateTOC(cells: Record<number, CellState>): Record<number, TOCState[]> {
        let newTOC: Record<number, TOCState[]> = this.curNBTOC !== undefined ? this.curNBTOC : {};

        for (const [id, state] of Object.entries(cells)) {
            if (state.language === "text") {
                const headings = this.findHeadings(state.content, state.id);
                newTOC[parseInt(id)] = headings;
            }
        }

        return newTOC;
    }

    private findHeadings(content: string, cellId: number): TOCState[] {
        let results: TOCState[] = [];
        const headings = content.match(/#{1,6}.+/g);

        headings?.forEach(function (s, index) {
            const heading = s.trim().substring(0, s.indexOf(' '));
            const title = s.trim().substring(s.indexOf(' ') + 1);

            if (heading !== null && title !== null) {
                results.push({
                    title,
                    cellId,
                    heading: heading.length
                })
            }
        })

        return results;
    }

    private generateTOCHTML(): void {
        this.el.innerHTML = "";
        if (this.cellOrder.length > 0) {
            this.cellOrder.forEach(num => {
                if (this.curNBTOC === undefined) return;
                if (this.curNBTOC[num] !== undefined && this.notebookState.state.cells[num].language === "text") {
                    for (const [id, tocEl] of Object.entries(this.curNBTOC[num])) {
                        this.el.appendChild(this.tocElToTag(tocEl));
                    }
                }
            });
        } else {
            this.el.appendChild(h2([], ["No table of contents yet. To get started, make an h1-h6 heading."]));
        }
    }

    private tocElToTag(tocEl: TOCState): HTMLHeadingElement {
        let h: HTMLHeadingElement;

        switch (tocEl.heading) {
            case 1:
                h = h1([], tocEl.title).dataAttr('data-cellid', tocEl.cellId.toString());
                break;
            case 2:
                h = h2([], tocEl.title).dataAttr('data-cellid', tocEl.cellId.toString());
                break;
            case 3:
                h = h3([], tocEl.title).dataAttr('data-cellid', tocEl.cellId.toString());
                break;
            case 4:
                h = h4([], tocEl.title).dataAttr('data-cellid', tocEl.cellId.toString());
                break;
            case 5:
                h = h5([], tocEl.title).dataAttr('data-cellid', tocEl.cellId.toString());
                break;
            case 6:
                h = h6([], tocEl.title).dataAttr('data-cellid', tocEl.cellId.toString());
                break;
            default:
                h = h1([], tocEl.title).dataAttr('data-cellid', tocEl.cellId.toString());
                break;
        }

        this.onHeadingClick(tocEl.cellId, h);
        return h;
    }

    // Compare the newly selected cellId versus the cell order
    // Look in the ToC to see if any of those IDs (going up from the currently selected cells) has a heading.
    // Once one is found, select it.
    // If none is found, do nothing.

    private onHeadingClick(cellId: number, el: TagElement<any>) {
        el.click(() => {
            if (cellId !== this.notebookState.state.activeCellId) {
                this.notebookState.selectCell(cellId, {editing: true});
            }
            const oldActiveEl = this.el.querySelector('.active');
            oldActiveEl?.classList.remove('active');
            el.classList.add('active');
        })
    }

    private selectHeader(cellId: number) {
        const oldActiveEl = this.el.querySelector('.active');
        oldActiveEl?.classList.remove('active');

        const newActiveEl = document.body.querySelector(`[data-cellid="${cellId}"]`);
        newActiveEl?.classList.add('active');
    }

    // TODO: This algorithm is currently pretty broken. After fixing the issue with where we store the cellId, fix this.
    private findAndSelectNearestHeader(activeCellId: number | undefined) {
        if (activeCellId === undefined || this.curNBTOC === undefined) {
            const oldActiveEl = this.el.querySelector('.active');
            oldActiveEl?.classList.remove('active');
            return;
        }

        let i = this.cellOrder.indexOf(activeCellId);
        if (this.curNBTOC[i] && this.curNBTOC[i].length == 0) {
            while (i >= 0) {
                if (this.curNBTOC[i] && this.curNBTOC[i].length > 0) break;
                else i--;
            }
        }

        if (i !== -1) {
            console.log(i);
            console.log(this.curNBTOC);
            console.log(this.curNBTOC[i]);
            this.selectHeader(this.curNBTOC[i][0].cellId);
        }
    }
}