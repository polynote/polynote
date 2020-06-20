import {a, button, div, h2, iconButton, span, tag, TagElement} from "../../util/tags";
import {
    CopyNotebook,
    CreateNotebook, DeleteNotebook,
    LoadNotebook, RenameNotebook,
    RequestNotebooksList,
    ServerMessageDispatcher
} from "../messaging/dispatcher";
import {ServerStateHandler} from "../state/server_state";
import {diffArray, removeKey} from "../../../util/functions";
import {StateHandler} from "../state/state_handler";
import {DialogModal} from "./modal";

export class NotebookListContextMenuComponent {
    readonly el: TagElement<"div">;
    private targetItem?: string;

    private listener = () => this.hide()

    private constructor(private dispatcher: ServerMessageDispatcher) {
        const item = (label: string, classes?: string[]) => tag('li', classes, {}, [label]);
        const onlyItem = ['only-item'];
        const onlyFile = ['only-item', 'only-file'];
        const onlyDir  = ['only-item', 'only-dir'];
        const noFile   = ['no-file'];

        this.el = div(['notebook-list-context-menu'], [
            tag('ul', [], {}, [
                item('New notebook', noFile).click(evt => this.create(evt)),
                item('Rename', onlyFile).click(evt => this.rename(evt)),
                item('Copy', onlyFile).click(evt => this.copy(evt)),
                item('Delete', onlyFile).click(evt => this.delete(evt))
            ])
        ]).listener("mousedown", evt => { evt.stopPropagation(); });
    }

    private delete(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.hide();
        if (this.targetItem) {
            this.dispatcher.dispatch(new DeleteNotebook(this.targetItem))
        }
    }

    private rename(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.hide();
        if (this.targetItem) {
            this.dispatcher.dispatch(new RenameNotebook(this.targetItem))
        }
    }

    private copy(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.hide();
        if (this.targetItem) {
            this.dispatcher.dispatch(new CopyNotebook(this.targetItem))
        }
    }

    private create(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.hide();
        this.dispatcher.dispatch(new CreateNotebook())
    }

    showFor(evt: Event, targetItem?: LeafComponent | BranchComponent) {
        if (evt instanceof MouseEvent) {
            this.el.style.left = `${evt.clientX}px`;
            this.el.style.top = `${evt.clientY}px`;
        }
        evt.preventDefault();
        evt.stopPropagation();

        this.el.classList.remove('for-file', 'for-dir', 'for-item');

        if (targetItem) {
            this.el.classList.add('for-item');
            this.targetItem = targetItem.path;
            if (targetItem instanceof LeafComponent) {
                this.el.classList.add('for-file');
            } else {
                this.el.classList.add('for-dir');
            }
        }

        document.body.appendChild(this.el);
        document.body.addEventListener("mousedown", this.listener);
    }

    hide() {
        if (this.el.parentNode) {
            this.el.parentNode.removeChild(this.el);
            document.body.removeEventListener("mousedown", this.listener);
        }
    }

    private static inst: NotebookListContextMenuComponent;
    static get(dispatcher: ServerMessageDispatcher) {
        if (! NotebookListContextMenuComponent.inst) {
            NotebookListContextMenuComponent.inst = new NotebookListContextMenuComponent(dispatcher)
        }
        return NotebookListContextMenuComponent.inst
    }
}

export class NotebookList {
    readonly el: TagElement<"div">;
    readonly header: TagElement<"h2">;

    private dragEnter: EventTarget | null;
    private tree: BranchComponent;

    constructor(readonly dispatcher: ServerMessageDispatcher) {

        this.header = h2([], [
            'Notebooks',
            span(['buttons'], [
                iconButton(['create-notebook'], 'Create new notebook', 'plus-circle', 'New').click(evt => {
                    evt.stopPropagation();
                    dispatcher.dispatch(new CreateNotebook())
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
            .listener("contextmenu", evt => NotebookListContextMenuComponent.get(dispatcher).showFor(evt));

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

            added.forEach(path => treeState.addPath(path));
            removed.forEach(path => treeState.removePath(path))
        });

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
    private readonly branchEl: TagElement<"button">;
    private children: (BranchComponent | LeafComponent)[] = [];
    readonly path: string;

    constructor(private readonly dispatcher: ServerMessageDispatcher, private readonly state: StateHandler<Branch>, private parent?: BranchComponent) {
        const initial = state.getState();
        this.childrenEl = tag('ul', [], {}, []);
        this.path = this.state.getState().fullPath;

        Object.values(initial.children).forEach(child => this.addChild(child));

        // if `initial.value` is empty, this is the root node so there's no outer `li`.
        if (initial.value.length > 0) {
            this.el = tag('li', ['branch'], {}, [
                this.branchEl = button(['branch-outer'], {}, [
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
            this.expanded = !this.expanded;
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

    get expanded() {
        return this.el.classList.contains("expanded")
    }

    set expanded(expand: boolean) {
        if (expand) {
            this.el.classList.add("expanded");
        } else {
            this.el.classList.remove("expanded");
        }
    }

    focus() {
        this.branchEl.focus()
    }

    private addChild(node: Branch | Leaf) {
        let child: BranchComponent | LeafComponent;
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
            child = new BranchComponent(this.dispatcher, childStateHandler as StateHandler<Branch>, this);
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

        // add handlers
        child.el
            .listener("contextmenu", evt => NotebookListContextMenuComponent.get(this.dispatcher).showFor(evt, child))
            .listener(
                "keydown", (evt: KeyboardEvent) => {
                    switch (evt.key) {
                        case 'ArrowUp':    this.movePrev(child.path); evt.stopPropagation(); evt.preventDefault(); break;
                        case 'ArrowDown':  this.moveNext(child.path); evt.stopPropagation(); evt.preventDefault(); break;
                        case 'ArrowRight': this.expandFolder(child.path); evt.stopPropagation(); evt.preventDefault(); break;
                        case 'ArrowLeft':  this.collapseFolder(child.path); evt.stopPropagation(); evt.preventDefault(); break;
                    }
                }
            )
    }

    private lastExpandedChild(child: BranchComponent | LeafComponent): BranchComponent | LeafComponent {
        if (child instanceof LeafComponent) {
            return child
        } else {
            if (child.expanded) {
                const lastChild = child.children[child.children.length - 1]
                return this.lastExpandedChild(lastChild)
            } else return child
        }
    }

    private movePrev(path: string) {
        const currentIdx = this.children.findIndex(c => c.path === path)
        if (currentIdx > 0) {
            const prev = this.children[currentIdx - 1];
            this.lastExpandedChild(prev).focus()
        } else {
            this.focus()
        }
    }

    private moveNext(path: string, skipChildren: boolean = false) {
        const currentIdx = this.children.findIndex(c => c.path === path)

        const current = this.children[currentIdx];
        if (!skipChildren && current instanceof BranchComponent && current.expanded && current.children.length > 0) {
            current.children[0].focus()
        } else {
            if (currentIdx < this.children.length - 1) {
                const next = this.children[currentIdx + 1];
                next.focus()
            } else if (this.parent) {
                this.parent.moveNext(this.path, true)
            }
        }
    }

    private expandFolder(path: string) {
        const current = this.children.find(c => c.path === path)
        if (current instanceof BranchComponent) {
            current.expanded = true;
        }
    }

    private collapseFolder(path: string) {
        const current = this.children.find(c => c.path === path)
        if (current instanceof BranchComponent) {
            current.expanded = false;
        }
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
                this.leafEl.replaceWith(newEl);
                this.leafEl = newEl;
            } else {
                // this leaf was removed
                state.dispose()
            }
        })
    }

    focus() {
        this.leafEl.focus()
    }

    private getEl(leaf: Leaf) {
        return a(['name'], `notebooks/${leaf.fullPath}`, true, [span([], [leaf.value])])
            .click(evt => this.dispatcher.dispatch(new LoadNotebook(leaf.fullPath)))
    }
}

