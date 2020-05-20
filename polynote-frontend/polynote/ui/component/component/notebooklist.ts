import {a, button, div, h2, iconButton, span, tag, TagElement} from "../../util/tags";
import {CreateNotebook, LoadNotebook, RequestNotebooksList, ServerMessageDispatcher} from "../messaging/dispatcher";
import {ImportNotebook} from "../../util/ui_event";
import {ServerStateHandler} from "../state/server_state";
import {diffArray} from "../../../util/functions";
import {StateHandler} from "../state/state_handler";

export class NotebookListContextMenuComponent {
    readonly el: TagElement<"div">;

    constructor(dispatcher: ServerMessageDispatcher) {

    }

    showFor(evt: Event) {

    }
}

export class NotebookList {
    readonly el: TagElement<"div">;
    readonly header: TagElement<"h2">;

    private contextMenu: NotebookListContextMenuComponent;

    private dragEnter: EventTarget | null;
    private tree: BranchComponent;

    constructor(readonly dispatcher: ServerMessageDispatcher, serverStateHandler: ServerStateHandler) {

        this.contextMenu = new NotebookListContextMenuComponent(dispatcher);

        this.header = h2([], [
            'Notebooks',
            span(['buttons'], [
                iconButton(['create-notebook'], 'Create new notebook', 'plus-circle', 'New').click(evt => {
                    evt.stopPropagation();
                    //TODO: open modal dialog
                })
            ])
        ]);

        const treeState = new BranchHandler({
            fullPath: "",
            value: "",
            children: []
        });
        this.tree = new BranchComponent(dispatcher, treeState);

        this.el = div(['notebooks-list'], [div(['tree-view'], [this.tree.el])])
            .listener("contextmenu", evt => this.contextMenu.showFor(evt));

        // Drag n' drop!
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(evt => {
            this.el.addEventListener(evt, this.fileHandler.bind(this), false)
        });

        serverStateHandler.view("connectionStatus").addObserver(status=> {
            const disabled = status === "disconnected";
            [...this.el.querySelectorAll('.buttons button')].forEach((button: HTMLButtonElement) => button.disabled = disabled);
        });

        serverStateHandler.view("notebooks").addObserver((newNotebooks, oldNotebooks) => {
            const [removed, added] = diffArray(Object.keys(oldNotebooks), Object.keys(newNotebooks));

            console.log("start updating notebook list")
            added.forEach(path => treeState.addPath(path));
            removed.forEach(path => treeState.removePath(path))
            console.log("done updating notebook list")
        });

        serverStateHandler.addObserver(x=> console.log("got server state update", x));

        // we're ready to request the notebooks list now!
        dispatcher.dispatch(new RequestNotebooksList())
    }

    private fileHandler(evt: DragEvent) {
        // prevent browser from displaying the ipynb file.
        evt.stopPropagation();
        evt.preventDefault();

        // handle highlighting
        if (evt.type === "dragenter" || evt.type === "dragover") {
            this.dragEnter = evt.target;
            this.el.classList.add('highlight');
        } else if (evt.type === "drop" || (evt.type === "dragleave" && evt.target === this.dragEnter)) {
            this.el.classList.remove('highlight');
        }

        // actually handle the file
        if (evt.type === "drop") {
            const xfer = evt.dataTransfer;
            if (xfer) {
                const files = xfer.files;
                [...files].forEach((file) => {
                    const reader = new FileReader();
                    reader.readAsText(file);
                    reader.onloadend = () => {
                        if (reader.result) {
                            // we know it's a string because we used `readAsText`: https://developer.mozilla.org/en-US/docs/Web/API/FileReader/result
                            this.dispatcher.dispatch(new CreateNotebook(file.name, reader.result as string));
                        } else {
                            throw new Error(`Didn't get any file contents when reading ${file.name}! `)
                        }
                    }
                })
            }
        }
    }
}

type Leaf = {
    fullPath: string,
    value: string
}
type Branch = Leaf & {
    children: (Branch | Leaf)[]
};

class BranchHandler extends StateHandler<Branch> {
    constructor(state: Branch) {
        super(state);
    }

    addPath(path: string) {
        this.updateState(s => {
            console.log("adding path to notebook list", path)
            let components = path.split("/");
            let branch = s;
            let i = 0;
            while (i < components.length - 1) {
                if (branch.value === components[i]) {
                    i++;
                } else {
                    const maybeChild = branch.children.find(b => b.value === components[i]);
                    if (maybeChild && "children" in maybeChild) {
                        branch = maybeChild;
                    } else {
                        const newChild = {
                            fullPath: components.slice(0, i).join("/"),
                            value: components[i],
                            children: []
                        };
                        branch.children.push(newChild);
                        branch = newChild;
                    }
                }
            }

            // this last one must be a leaf
            branch.children.push({
                fullPath: path,
                value: components[i]
            });

            return s;
        })
    }

    removePath(path: string) {
        this.updateState(s => {
            let components = path.split("/");
            let branch = s;
            while(components.length > 0) {
                if (branch.value === components[0]) {
                    components.shift()
                } else {
                    const idx = branch.children.findIndex(b => b.value === components[0]);
                    const maybeChild = branch.children[idx];
                    if (maybeChild && "children" in maybeChild) {
                        branch = maybeChild
                    } else {
                        branch.children.splice(idx, 1);
                    }
                }
            }
            return s;
        })
    }
}

class BranchComponent {
    readonly el: TagElement<"li" | "ul">;
    readonly childrenEl: TagElement<"ul">;
    private children: (BranchComponent | LeafComponent)[] = [];

    constructor(private readonly dispatcher: ServerMessageDispatcher, private readonly state: StateHandler<Branch>) {

        const initial = state.getState();
        this.childrenEl = tag('ul', [], {}, []);
        initial.children.forEach(child => this.addChild(child));
        if (initial.value.length > 0) {
            this.el = tag('li', ['branch'], {}, [
                button(['branch-outer'], {}, [
                    span(['expander'], []),
                    span(['icon'], []),
                    span(['name'], [initial.value])
                ]),
                this.childrenEl
            ]);
        } else {
            this.el = this.childrenEl;
        }
        this.el.click(evt => this.el.classList.toggle('expanded'));

        state.addObserver((newNode, oldNode) => {
            const [removed, added] = diffArray(oldNode.children, newNode.children);
            removed.forEach(child => {
                const idx = this.children.findIndex(c => c.path === child.fullPath);
                const childEl = this.children[idx].el;
                childEl.parentElement?.removeChild(childEl);
                this.children.splice(idx, 1);
            });
            added.forEach(child => {
                this.addChild(child);
            })
        })
    }

    get path() {
        return this.state.getState().fullPath;
    }

    private addChild(node: Branch | Leaf) {
        let child;
        const childStateHandler =
            this.state.xmapView("children",
            (children: Branch[]) => children.find(c => c.fullPath === node.fullPath),
            (children: Branch[], node: Branch) => {
                const idx = children.findIndex(c => c.fullPath === node.fullPath);
                if (idx) {
                    children[idx] = node;
                }
                return children;
            });
        if ("children" in node) {
            child = new BranchComponent(this.dispatcher, childStateHandler);
        } else {
            child = new LeafComponent(this.dispatcher, childStateHandler);
        }

        // insert this child in alphabetical order
        let i = 0;
        while (this.children[i]?.path.localeCompare(child.path) < 0) {
            i++;
        }
        const nextEl = this.children[i]?.el;
        if (nextEl) {
            this.childrenEl.insertBefore(child.el, nextEl)
        } else {
            this.childrenEl.appendChild(child.el)
        }
        this.children.splice(i, 0, child);
    }
}

class LeafComponent {
    readonly el: TagElement<"li">;
    private leafEl: TagElement<"a">;

    constructor(private readonly dispatcher: ServerMessageDispatcher, private readonly state: StateHandler<Leaf>) {

        const initial = state.getState();
        this.leafEl = this.getEl(initial);
        this.el = tag('li', ['leaf'], {}, [this.leafEl]);
        this.el.click(evt => this.el.classList.toggle('expanded'));

        state.addObserver(leaf => {
            const newEl = this.getEl(leaf);
            this.el.replaceChild(newEl, this.leafEl);
            this.leafEl = newEl;
        })
    }

    get path() {
        return this.state.getState().fullPath;
    }

    private getEl(leaf: Leaf) {
        return a(['name'], `notebooks/${leaf.fullPath}`, true, [span([], [leaf.value])])
            .click(evt => this.dispatcher.dispatch(new LoadNotebook(leaf.fullPath)))
            // TODO: show context menu here? This used to do that but might fit better somewhere else.
            .listener(
                "keydown", (evt: KeyboardEvent) => {
                    // TODO: handle keypress
                }
            )
    }
}

// exports for testing. These should only ever be used in test files! If only there was package private!
export class __BranchHandler extends BranchHandler {}
export class __BranchComponent extends BranchComponent {}
export class __LeafComponent extends LeafComponent {}

