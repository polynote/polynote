import {a, button, div, h2, helpIconButton, iconButton, span, tag, TagElement} from "../tags";
import {ServerMessageDispatcher} from "../../messaging/dispatcher";
import {deepCopy, diffArray, getShortDate} from "../../util/helpers";
import {
    Disposable,
    IDisposable,
    ObjectStateHandler,
    removeKey,
    setProperty, setValue,
    StateView,
    UpdatePartial
} from "../../state";
import {ServerStateHandler} from "../../state/server_state";
import {SearchModal} from "./search";
import {NotebookScrollLocationsHandler, NotebookSortingHandler, UserPreferencesHandler} from "../../state/preferences";

export class NotebookListContextMenu{
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
                item('New notebook', [...noFile, 'create']).click(evt => this.create(evt)),
                item('Rename', [...onlyFile, 'rename']).click(evt => this.rename(evt)),
                item('Copy', [...onlyFile, 'copy']).click(evt => this.copy(evt)),
                item('Delete', [...onlyFile, 'delete']).click(evt => this.delete(evt))
            ])
        ]).listener("mousedown", evt => { evt.stopPropagation(); });
    }

    private delete(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.hide();
        if (this.targetItem) {
            this.dispatcher.deleteNotebook(this.targetItem)
        }
    }

    private rename(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.hide();
        if (this.targetItem) {
            this.dispatcher.renameNotebook(this.targetItem)
        }
    }

    private copy(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.hide();
        if (this.targetItem) {
            this.dispatcher.copyNotebook(this.targetItem)
        }
    }

    private create(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.hide();
        this.dispatcher.createNotebook(this.targetItem)
    }

    showFor(evt: Event, targetItem?: LeafEl | BranchEl) {
        if (evt instanceof MouseEvent) {
            this.el.style.left = `${evt.clientX}px`;
            this.el.style.top = `${evt.clientY}px`;
        }
        evt.preventDefault();
        evt.stopPropagation();

        this.el.classList.remove('for-file', 'for-dir', 'for-item');

        this.targetItem = targetItem?.path;

        if (targetItem) {
            this.el.classList.add('for-item');
            if (targetItem instanceof LeafEl) {
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

    private static inst: NotebookListContextMenu;
    static get(dispatcher: ServerMessageDispatcher) {
        if (! NotebookListContextMenu.inst) {
            NotebookListContextMenu.inst = new NotebookListContextMenu(dispatcher)
        }
        return NotebookListContextMenu.inst
    }
}

export class NotebookList extends Disposable {
    readonly el: TagElement<"div">;
    readonly header: TagElement<"h2">;

    private dragEnter: EventTarget | null;
    private tree: BranchEl;

    constructor(readonly dispatcher: ServerMessageDispatcher) {
        super();

        // Create a searchModal and hide it immediately - this variable enables us to save results even on modal close
        const searchModal = new SearchModal(dispatcher);
        searchModal.show();
        searchModal.hide();

        const sortButton = NotebookSortingHandler.state.descending ?
            iconButton(['arrow', 'arrow-up'], 'Sort by most recently saved', 'arrow-up', 'Sort by most recently saved') :
            iconButton(['arrow', 'arrow-down'], 'Sort by least recently saved', 'arrow-down', 'Sort by least recently saved');

        this.header = h2(['ui-panel-header', 'notebooks-list-header'], [
            'Notebooks',
            span(['left-buttons'], [
                helpIconButton([], "https://polynote.org/latest/docs/notebooks-list/"),
            ]),
            span(['right-buttons'], [
                sortButton.click(evt => {
                    evt.stopPropagation();
                    NotebookSortingHandler.updateField("descending", (currentState) => setValue(!currentState));
                    this.tree.sort();
                }),
                iconButton(['create-notebook'], 'Create new notebook', 'plus-circle', 'New').click(evt => {
                    evt.stopPropagation();
                    dispatcher.createNotebook()
                }),
            ])
        ]);

        const treeState = new BranchHandler({
            fullPath: "",
            value: "",
            lastSaved: 0,
            children: {}
        });
        this.tree = new BranchEl(dispatcher, treeState);

        this.el = div(['notebooks-list'], [div(['tree-view'], [this.tree.el])])
            .listener("contextmenu", evt => NotebookListContextMenu.get(dispatcher).showFor(evt));

        // Drag n' drop!
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(evt => {
            this.el.addEventListener(evt, this.fileHandler.bind(this), false)
        });

        const serverStateHandler = ServerStateHandler.get.fork(this);

        // disable the entire notebook list when disconnected from the server
        serverStateHandler.observeKey("connectionStatus", currentStatus => {
            if (currentStatus === "disconnected") {
                this.el.classList.add("disabled")
                this.header.classList.add("disabled")
            } else if (currentStatus === "connected") {
                this.el.classList.remove("disabled")
                this.header.classList.remove("disabled")
            }
        })

        NotebookSortingHandler.observeKey("descending", pref => this.renderSortButton(pref)).disposeWith(this)

        serverStateHandler.view("notebooks").addPreObserver(oldNotebooks => {
            const oldPaths = Object.keys(oldNotebooks);
            return newNotebooks => {
                const [removed, added] = diffArray(oldPaths, Object.keys(newNotebooks));

                added.forEach(path => treeState.addPath(path, 0));
                removed.forEach(path => treeState.removePath(path));
            }
        });

        serverStateHandler.view("notebookTimestamps").addPreObserver(oldNotebooks => {
            // Find all paths and compare them to old paths to find deleted ones
            // Find all new timestamps and compare them to find updated or newly added ones
            // We have to use two separate `diffArray`s because nested object comparisons don't play well with the state updates
            const oldPaths = Object.keys(oldNotebooks);
            const oldEntries = Object.entries(oldNotebooks);
            return newNotebooks => {
                const [removed, ] = diffArray(oldPaths, Object.keys(newNotebooks));
                const [, added] = diffArray(oldEntries, Object.entries(newNotebooks));

                added.forEach(path => {
                    treeState.removePath(path[0]);
                    treeState.addPath(path[0], path[1]);
                });
                removed.forEach(path => treeState.removePath(path));
            }
        });

        // we're ready to request the notebooks list now!
        dispatcher.requestNotebookList()
    }

    private renderSortButton(pref: boolean) {
        const newEl = pref ?
            iconButton(['arrow', 'arrow-up'], 'Sort by most recently saved', 'arrow-up', 'Sort by most recently saved') :
            iconButton(['arrow', 'arrow-down'], 'Sort by least recently saved', 'arrow-down', 'Sort by least recently saved');

        newEl.click(evt => {
            evt.stopPropagation();
            NotebookSortingHandler.updateField("descending", (currentState) => setValue(!currentState));
            this.tree.sort();
        });

        this.header.querySelector('.arrow')?.replaceWith(newEl);
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
                            this.dispatcher.createNotebook(file.name, reader.result as string);
                        } else {
                            throw new Error(`Didn't get any file contents when reading ${file.name}! `)
                        }
                    }
                })
            }
        }
    }
}

export interface Leaf {
    fullPath: string,
    value: string,
    lastSaved: number
}

export interface Branch extends Leaf {
    children: Record<string, Node>
}

export type Node = Leaf | Branch;

function isBranch(node: Node): node is Branch {
    return node && ("children" in node)
}

export class BranchHandler extends ObjectStateHandler<Branch> {
    constructor(state: Branch) {
        super(state);
    }

    addPath(path: string, lastSaved: number) {
        this.update(topState => {
            const pieces = path.split("/");
            const update: UpdatePartial<Branch> = {
                children: {
                    [pieces[0]]: {}
                }
            };
            let currentUpdate = update as any, currentState = topState as Node | undefined;
            let currentPath = "";
            for (let i = 0; i < pieces.length - 1; i++) {
                const piece = pieces[i] //as keyof UpdatePartial<Branch>;
                currentPath += piece;
                currentUpdate.children = {
                    [currentPath]: {
                        children: {}
                    }
                }
                currentUpdate = currentUpdate.children[currentPath];
                if (!currentState || !isBranch(currentState) || !currentState.children[piece]) {
                    currentUpdate.fullPath = currentPath;
                    currentUpdate.value = piece;
                    currentState = undefined;
                } else {
                    currentState = currentState.children[piece];
                }
                currentPath += "/"
            }
            const leaf = pieces[pieces.length - 1];
            currentUpdate.children[path] = {
                fullPath: path,
                value: leaf,
                lastSaved
            }
            return update;
        });
    }

    removePath(path: string) {
        function go(path: string, parent: Branch): UpdatePartial<Branch> {
            const maybeChild = parent.children[path]
            if (maybeChild) {
                return {
                    children: removeKey(path)
                }
            } else {
                return {
                    children: Object.keys(parent.children).reduce((acc, key)  => {
                        const branchOrLeaf = parent.children[key];
                        if ("children" in branchOrLeaf) {
                            acc[key] = go(path, branchOrLeaf) // 'tis a branch
                        } else {
                            acc[key] = branchOrLeaf // 'tis a leaf!
                        }
                        return acc
                    }, {} as UpdatePartial<Record<string, Node>>)
                }
            }
        }
        this.update(state => go(path, state))
    }

}

export class BranchEl extends Disposable {
    readonly el: TagElement<"li" | "ul">;
    readonly childrenEl: TagElement<"ul">;
    private readonly branchEl: TagElement<"button">;
    private children: (BranchEl | LeafEl)[] = [];
    readonly path: string;
    _lastSaved: number;
    rootNode: boolean;
    childrenState: StateView<Record<string, Leaf | Branch>>;

    constructor(private readonly dispatcher: ServerMessageDispatcher, private readonly branch: StateView<Branch>, private parent?: BranchEl) {
        super()
        const initial = branch.state;
        this.childrenEl = tag('ul', [], {}, []);
        this.path = this.branch.state.fullPath;
        this._lastSaved = 0;

        Object.values(initial.children).forEach(child => this.addChild(child));

        // if `initial.value` is empty, this is the root node so there's no outer `li`.
        if (initial.value.length > 0) {
            this.rootNode = false;
            this.el = tag('li', ['branch'], {}, [
                this.branchEl = button(['branch-outer'], {}, [
                    span(['expander'], []),
                    span(['icon'], []),
                    span(['name'], [initial.value])
                ]),
                this.childrenEl
            ]);
        } else {
            this.rootNode = true;
            this.el = this.childrenEl;
        }
        this.el.click(evt => {
            evt.stopPropagation();
            evt.preventDefault();
            this.expanded = !this.expanded;
        });

        branch.addPreObserver(prev => {
            const oldNode = deepCopy(prev)
            return (newNode, update) => {
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
            }
        }).disposeWith(this)
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
        let child: BranchEl | LeafEl;

        const childStateHandler = this.branch.view("children").view(node.fullPath).disposeWith(this);
        if ("children" in node) {
            child = new BranchEl(this.dispatcher, childStateHandler as StateView<Branch>, this);
        } else {
            child = new LeafEl(this.dispatcher, childStateHandler);
        }

        // insert this child in numerical order
        let i = 0;
        while (this.shouldInsertLower(child, this.children[i])) {
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
            .listener("contextmenu", evt => NotebookListContextMenu.get(this.dispatcher).showFor(evt, child))
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

    private shouldInsertLower(newChild: BranchEl | LeafEl, oldChild: BranchEl | LeafEl) {
        if (oldChild === undefined) return false;
        else if (newChild instanceof BranchEl) return (oldChild instanceof BranchEl && oldChild.path.localeCompare(newChild.path) < 0);
        else if (oldChild instanceof BranchEl) return true;
        else if (newChild._lastSaved < oldChild._lastSaved) return NotebookSortingHandler.state.descending;
        else return !NotebookSortingHandler.state.descending;
    }

    private lastExpandedChild(child: BranchEl | LeafEl): BranchEl | LeafEl {
        if (child instanceof LeafEl) {
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
        if (!skipChildren && current instanceof BranchEl && current.expanded && current.children.length > 0) {
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
        if (current instanceof BranchEl) {
            current.expanded = true;
        }
    }

    private collapseFolder(path: string) {
        const current = this.children.find(c => c.path === path)
        if (current instanceof BranchEl) {
            current.expanded = false;
        }
    }

    sort() {
        let children: HTMLElement = this.rootNode ? this.el : this.childrenEl;

        let i = 0;
        while (children.children[i].classList.contains("branch")) {
            (this.children[i++] as BranchEl).sort();
        }

        for (let j = children.children.length - 1; i < j; i++, j--) {
            this.swapChildren(i, j);
            this.swapChildrenElements(children.children[i], children.children[j]);
        }
    }

    private swapChildren(i: number, j: number) {
        const b = this.children[i];
        this.children[i] = this.children[j];
        this.children[j] = b;
    }

    private swapChildrenElements(nodeA: Element, nodeB: Element) {
        const parentA = nodeA.parentNode;
        const siblingA = nodeA.nextSibling === nodeB ? nodeA : nodeA.nextSibling;

        if (parentA !== null && nodeB.parentNode !== null) {
            nodeB.parentNode.insertBefore(nodeA, nodeB);
            parentA.insertBefore(nodeB, siblingA);
        }
    }
}

export class LeafEl extends Disposable {
    readonly el: TagElement<"li">;
    private leafEl: TagElement<"a">;
    readonly path: string;
    _lastSaved: number;

    constructor(private readonly dispatcher: ServerMessageDispatcher, private readonly view: StateView<Leaf>) {
        super()

        const initial = view.state;
        this.leafEl = this.getEl(initial);
        this.el = tag('li', ['leaf'], {}, [this.leafEl]);
        this.path = this.view.state.fullPath;
        this._lastSaved = this.view.state.lastSaved;

        view.addObserver(leaf => {
            if (leaf) {
                const newEl = this.getEl(leaf);
                this.leafEl.replaceWith(newEl);
                this.leafEl = newEl;
            } else {
                // this leaf was removed
                this.dispose()
            }
        }).disposeWith(this)
    }

    focus() {
        this.leafEl.focus()
    }

    private getEl(leaf: Leaf) {
        return a([], `notebooks/${leaf.fullPath}`, [
            span([], [
                span(['name'], [leaf.value]),
                span(['date'], [this._lastSaved !== 0 ? getShortDate(this._lastSaved) : ""])
            ])
        ], { preventNavigate: true })
            .click(evt => {
                evt.preventDefault();
                evt.stopPropagation();
                ServerStateHandler.loadNotebook(leaf.fullPath, true)
                    .then(() => {
                        ServerStateHandler.selectNotebook(leaf.fullPath)
                    })
            })
    }
}