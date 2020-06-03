import {a, button, div, h2, iconButton, span, tag, TagElement} from "../../util/tags";
import {CreateNotebook, LoadNotebook, RequestNotebooksList, ServerMessageDispatcher} from "../messaging/dispatcher";
import {ImportNotebook} from "../../util/ui_event";
import {ServerStateHandler} from "../state/server_state";
import {diffArray, removeKey} from "../../../util/functions";
import {StateHandler} from "../state/state_handler";
import * as deepEquals from 'fast-deep-equal/es6';
import * as clone from "clone";

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

    constructor(readonly dispatcher: ServerMessageDispatcher) {

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
            children: {}
        });
        this.tree = new BranchComponent(dispatcher, treeState);

        this.el = div(['notebooks-list'], [div(['tree-view'], [this.tree.el])])
            .listener("contextmenu", evt => this.contextMenu.showFor(evt));

        // Drag n' drop!
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(evt => {
            this.el.addEventListener(evt, this.fileHandler.bind(this), false)
        });

        ServerStateHandler.get.view("connectionStatus").addObserver(status=> {
            const disabled = status === "disconnected";
            [...this.el.querySelectorAll('.buttons button')].forEach((button: HTMLButtonElement) => button.disabled = disabled);
        });

        ServerStateHandler.get.view("notebooks").addObserver((newNotebooks, oldNotebooks) => {
            const [removed, added] = diffArray(Object.keys(oldNotebooks), Object.keys(newNotebooks));

            console.log("start updating notebook list", added, removed)
            added.forEach(path => treeState.addPath(path));
            removed.forEach(path => treeState.removePath(path))
            console.log("done updating notebook list")
        });

        // serverStateHandler.addObserver(x=> console.log("got server state update", x));

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

export type Leaf = {
    fullPath: string,
    value: string
}
export type Branch = Leaf & {
    children: Record<string, (Branch | Leaf)>
};

export class BranchHandler extends StateHandler<Branch> {
    constructor(state: Branch) {
        super(state);
    }

    addPath(path: string) {
        function go(remainingPath: string, components: string[], parent: Branch): Branch {
            if (remainingPath.split("/").length === 1) {
                const fullPath = components.concat(remainingPath).join("/")
                return {
                    ...parent,
                    children: {
                        ...parent.children,
                        [fullPath]: {
                            value: remainingPath,
                            fullPath,
                        }
                    }
                }
            } else {
                const comps = remainingPath.split("/")
                const childPath = comps.slice(1).join("/");
                const currentVal = comps.slice(0, 1)[0];
                const childComponents = [...components, currentVal];
                const intermediatePath = childComponents.join("/")
                const maybeChild = parent.children[intermediatePath]
                return {
                    ...parent,
                    children: {
                        ...parent.children,
                        [intermediatePath]: go(childPath, childComponents, maybeChild && "children" in maybeChild ? maybeChild : {
                            fullPath: intermediatePath,
                            value: currentVal,
                            children: {}
                        })
                    }
                }
            }
        }
        this.updateState(s => {
            return go(path, [], s)
        })
    }

    removePath(path: string) {
        function go(path: string, parent: Branch) {
            const maybeChild = parent.children[path]
            if (maybeChild) {
                return {
                    ...parent,
                    children: removeKey(parent.children, path)
                }
            } else {
                return {
                    ...parent,
                    children: Object.keys(parent.children).reduce((acc, key)  => {
                        const branchOrLeaf = parent.children[key];
                        if ("children" in branchOrLeaf) {
                            acc[key] = go(path, branchOrLeaf) // 'tis a branch
                        } else {
                            acc[key] = branchOrLeaf // 'tis a leaf!
                        }
                        return acc
                    }, {} as Record<string, Branch | Leaf>)
                }
            }
        }
        this.updateState(s => {
            return go(path, s)
        })
    }

}

export class BranchComponent {
    readonly el: TagElement<"li" | "ul">;
    readonly childrenEl: TagElement<"ul">;
    private children: (BranchComponent | LeafComponent)[] = [];
    readonly path: string;

    constructor(private readonly dispatcher: ServerMessageDispatcher, private readonly state: StateHandler<Branch>) {
        const initial = state.getState();
        this.childrenEl = tag('ul', [], {}, []);
        this.path = this.state.getState().fullPath;

        Object.values(initial.children).forEach(child => this.addChild(child));

        // if `initial.value` is empty, this is the root node so there's no outer `li`.
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
        this.el.click(evt => {
            evt.stopPropagation();
            evt.preventDefault();
            this.el.classList.toggle('expanded')
        });

        state.addObserver((newNode, oldNode) => {
            const [removed, added] = diffArray(Object.keys(oldNode.children), Object.keys(newNode.children));
            removed.forEach(child => {
                const idx = this.children.findIndex(c => c.path === oldNode.children[child].fullPath);
                const childEl = this.children[idx].el;
                childEl.parentElement?.removeChild(childEl);
                this.children.splice(idx, 1);
            });
            added.forEach(child => {
                this.addChild(newNode.children[child]);
            })
        })
    }

    private addChild(node: Branch | Leaf) {
        let child;
        // This is really slow - is it necessary?
        // const childStateHandler =
        //     this.state.xmapView("children",
        //     (children: Record<string, Branch>) => children[node.fullPath],
        //     (children: Record<string, Branch>, node: Branch) => {
        //         children[node.fullPath] = node;
        //         return children;
        //     });

        // TODO: Creation of views seems to be a tad expensive, so we might need to revisit this as it creates 2 views for every node in the notebook list!
        const childStateHandler = this.state.view("children").view(node.fullPath);
        // childStateHandler.addObserver((next, prev) => console.log("child state changed for", node.fullPath, ":", prev, next))
        if ("children" in node) {
            // const childStateHandler = new StateHandler(node)
            child = new BranchComponent(this.dispatcher, childStateHandler as StateHandler<Branch>);
        } else {
            // const childStateHandler = new StateHandler(node)
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

export class LeafComponent {
    readonly el: TagElement<"li">;
    private leafEl: TagElement<"a">;
    readonly path: string;

    constructor(private readonly dispatcher: ServerMessageDispatcher, private readonly state: StateHandler<Leaf>) {

        const initial = state.getState();
        this.leafEl = this.getEl(initial);
        this.el = tag('li', ['leaf'], {}, [this.leafEl]);
        this.path = this.state.getState().fullPath;

        state.addObserver(leaf => {
            if (leaf) {
                const newEl = this.getEl(leaf);
                this.el.replaceChild(newEl, this.leafEl);
                this.leafEl = newEl;
            } else {
                // this leaf was removed
                state.dispose()
            }
        })
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

