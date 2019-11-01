import {
    ImportNotebook,
    UIMessage,
    UIMessageTarget,
    CreateNotebook,
    RenameNotebook,
    DeleteNotebook,
    TriggerItem,
    UIToggle,
    ModalClosed
} from "../util/ui_event";
import {button, div, h2, iconButton, span, tag, TagElement, textbox} from "../util/tags";
import {storage} from "../util/storage";
import {Modal} from "./modal";

interface NotebookListPrefs {
    collapsed: boolean
}

type Tree<T> = {
    [k: string]: Tree<T> | T
}

type NotebookNode = TagElement<"li"> & {item: string }
type DirectoryNode = TagElement<"li"> & {path: string[], listEl: TagElement<"ul">, pathStr: string}
type NotebookListNode = NotebookNode | DirectoryNode
function isDirNode(node: Element): node is DirectoryNode {
    return (node as DirectoryNode).pathStr !== undefined
}

class NotebookListContextMenu extends UIMessageTarget {
    readonly el: TagElement<"div">;
    private targetItem?: string;

    private listener = (evt: Event) => this.hide();

    constructor(parent: NotebookListUI) {
        super(parent);
        const item = (label: string, classes?: string[]) => tag('li', classes, {}, [label]);
        const onlyItem = ['only-item'];
        const onlyFile = ['only-item', 'only-file'];
        const onlyDir  = ['only-item', 'only-dir'];
        const noFile   = ['no-file'];

        this.el = div(['notebook-list-context-menu'], [
            tag('ul', [], {}, [
                item('New notebook', noFile).click(evt => this.create(evt)),
                item('Rename', onlyFile).click(evt => this.rename(evt)),
                item('Delete', onlyFile).click(evt => this.delete(evt))
            ])
        ]).listener("mousedown", evt => { evt.stopPropagation(); });
    }

    private delete(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        if (this.targetItem)
            this.publish(new DeleteNotebook(this.targetItem));
        this.hide();
    }

    private rename(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        if (this.targetItem)
            this.publish(new RenameNotebook(this.targetItem));
        this.hide();
    }

    private create(evt?: Event) {
        if (evt) {
            evt.stopPropagation();
        }
        this.publish(new CreateNotebook(this.targetItem));
        this.hide();
    }

    showFor(evt: Event, targetItem?: NotebookListNode) {
        if (evt instanceof MouseEvent) {
            this.el.style.left = `${evt.clientX}px`;
            this.el.style.top = `${evt.clientY}px`;
        }
        evt.preventDefault();
        evt.stopPropagation();

        this.el.classList.remove('for-file', 'for-dir', 'for-item');

        if (targetItem) {
            this.el.classList.add('for-item');
            if ('item' in targetItem) {
                this.targetItem = targetItem.item;
                this.el.classList.add('for-file');
            } else if ('pathStr' in targetItem) {
                this.targetItem = targetItem.pathStr;
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

}

export class NotebookListUI extends UIMessageTarget {
    readonly el: TagElement<"div">;
    private treeView: TagElement<"div">;
    private tree: Tree<DirectoryNode | NotebookNode>;
    private treeEl: TagElement<"ul">;
    private dragEnter: EventTarget | null;
    private contextMenu: NotebookListContextMenu = new NotebookListContextMenu(this);

    constructor() {
        super();
        this.el = div(
            ['notebooks-list', 'ui-panel'], [
                h2([], [
                    'Notebooks',
                    span(['buttons'], [
                        iconButton(['import-notebook'], 'Import a notebook', 'file-import', 'Import').click(evt => {
                            evt.stopPropagation();
                            this.publish(new ImportNotebook());
                        }),
                        iconButton(['create-notebook'], 'Create new notebook', 'plus-circle', 'New').click(evt => {
                            evt.stopPropagation();
                            this.publish(new CreateNotebook());
                        })
                    ])
                ]).click(evt => this.collapse()),
                div(['ui-panel-content'], [
                    this.treeView = div(['tree-view'], [])
                ]).listener("contextmenu", evt => this.contextMenu.showFor(evt))
            ]
        );

        // Drag n' drop!
        ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(evt => {
            this.el.addEventListener(evt, this.fileHandler.bind(this), false)
        });
    }

    setDisabled(disable: boolean) {
        if (disable) {
            [...this.el.querySelectorAll('.buttons button')].forEach((button: HTMLButtonElement) => button.disabled = true);
        } else {
            [...this.el.querySelectorAll('.buttons button')].forEach((button: HTMLButtonElement) => button.disabled = false);
        }
    }

    // Check storage to see whether this should be collapsed. Sends events, so must be called AFTER the element is created.
    init() {
        const prefs = this.getPrefs();
        if (prefs && prefs.collapsed) {
            this.collapse(true);
        }
    }

    getPrefs(): NotebookListPrefs {
        return storage.get("NotebookListUI") as NotebookListPrefs
    }

    setPrefs(obj: NotebookListPrefs) {
        storage.set("NotebookListUI", {...this.getPrefs(), ...obj})
    }

    setItems(items: string[]) {
        if (this.tree) {
            // remove current items
            this.treeView.innerHTML = '';
        }

        const tree = NotebookListUI.parseItems(items);

        const [itemTree, treeEl] = this.buildTree(tree, [], tag('ul', [], {}, []));
        this.tree = itemTree;
        this.treeEl = treeEl;
        this.treeView.appendChild(treeEl);
    }

    static parseItems(items: string[]) {
        const tree: Tree<string> = {};

        for (const item of items) {
            const itemPath = item.split(/\//g);
            let currentTree = tree;

            while (itemPath.length > 1) {
                const pathSegment = itemPath.shift()!;
                if (!currentTree[pathSegment]) {
                    currentTree[pathSegment] = {};
                }
                currentTree = currentTree[pathSegment] as Tree<string>;
            }

            currentTree[itemPath[0]] = item;
        }
        return tree;
    }

    buildTree(treeObj: Tree<string>, path: string[], listEl: TagElement<"ul">): [Tree<DirectoryNode | NotebookNode>, TagElement<"ul">] {

        const resultTree: Tree<NotebookListNode> = {};

        for (const itemName in treeObj) {
            if (treeObj.hasOwnProperty(itemName)) {
                const item = treeObj[itemName];
                let itemEl: NotebookListNode;
                if (typeof item === "string") {
                    // leaf - item is the complete path
                    itemEl = Object.assign(
                        tag('li', ['leaf'], {}, [
                            span(['name'], [itemName]).click(evt => {
                                this.publish(new TriggerItem(item));
                            }).listener("contextmenu", evt => this.contextMenu.showFor(evt, itemEl))
                        ]), {
                          item: item
                        });

                    resultTree[itemName] = itemEl;
                    listEl.appendChild(itemEl);
                } else {
                    const itemPath = [...path, itemName];
                    const pathStr = itemPath.join('/');
                    let subListEl = null;
                    for (const child of listEl.children) {
                        if (isDirNode(child) && child.pathStr === pathStr) {
                            subListEl = child.listEl;
                            itemEl = child;
                            break;
                        }
                    }

                    if (subListEl === null) {
                        subListEl = tag('ul', [], {}, []);
                        itemEl = Object.assign(
                            tag('li', ['branch'], {}, [
                                span(['branch-outer'], [
                                    span(['expander'], []).click(evt => this.toggle(itemEl!)),
                                    span(['icon'], []),
                                    span(['name'], [itemName])
                                ]),
                                subListEl
                            ]).listener("contextmenu", evt => this.contextMenu.showFor(evt, itemEl)), {
                               path: itemPath,
                               listEl: subListEl,
                               pathStr: pathStr
                            });

                        itemEl.appendChild(subListEl);
                        listEl.appendChild(itemEl);
                    }

                    const [itemTree, itemList] = this.buildTree(item, itemPath, subListEl);
                    resultTree[itemName] = itemTree;
                }
            }
        }
        return [resultTree, listEl];
    }

    addItem(path: string) {
        this.buildTree(NotebookListUI.parseItems([path]), [], this.treeEl);
    }

    toggle(el?: TagElement<"li">) {
        if (!el) return;
        el.classList.toggle('expanded');
    }

    collapse(force: boolean = false) {
        const prefs = this.getPrefs();
        if (force) {
            this.publish(new UIToggle('NotebookList', /* force */ true));
        } else if (prefs && prefs.collapsed) {
            this.setPrefs({collapsed: false});
            this.publish(new UIToggle('NotebookList'));
        } else {
            this.setPrefs({collapsed: true});
            this.publish(new UIToggle('NotebookList'));
        }
    }

    fileHandler(evt: DragEvent) {
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
                            this.publish(new ImportNotebook(file.name, reader.result as string));
                        } else {
                            throw new Error(`Didn't get any file contents when reading ${file.name}! `)
                        }
                    }
                })
            }
        }
    }
}

export class CreateNotebookDialog extends Modal {

    private pathInput: TagElement<"input">;
    private dialogContent: TagElement<"div">;
    private onComplete: (path: string) => void;
    private onCancel: () => void;
    private path?: string;

    private static INSTANCE: CreateNotebookDialog;

    static prompt(path?: string): Promise<string> {
        if (!CreateNotebookDialog.INSTANCE) {
            CreateNotebookDialog.INSTANCE = new CreateNotebookDialog();
        }
        const inst = CreateNotebookDialog.INSTANCE;
        inst.path = path;
        return new Promise((complete, cancel) => {
            inst.onComplete = complete;
            inst.onCancel = cancel;
            inst.show();
            inst.pathInput.focus();
            inst.pathInput.value = path ? `${path}/` : '';
            inst.pathInput.selectionStart = inst.pathInput.selectionEnd = inst.pathInput.value.length;
        });
    }

    constructor() {
        const dialogWrapper  = div(['create-notebook-dialog'], []);
        super(dialogWrapper, { title: 'Create notebook' });
        this.onComplete = (str) => null;
        this.onCancel = () => null;
        this.dialogContent = div([], [
            this.pathInput = textbox([], 'path/to/New notebook name'),
            div(['buttons'], [
                button(['dialog-button'], {}, 'Cancel').click(evt => this.cancel()),
                ' ',
                button(['dialog-button'], {}, 'Create').click(evt => this.complete(this.path ? [this.path, this.pathInput.value].join('/') : this.pathInput.value))])
        ]);
        dialogWrapper.appendChild(this.dialogContent);

        this.subscribe(ModalClosed, () => this.cancel());
    }

    cancel() {
        const onCancel = this.onCancel;
        this.onCancel = () => null;
        this.onComplete = _ => null;
        this.hide();
        onCancel();
    }

    complete(path: string) {
        const onComplete = this.onComplete;
        this.onCancel = () => null;
        this.onComplete = _ => null;
        this.hide();
        onComplete(path);
    }
}