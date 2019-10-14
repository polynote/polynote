import {ImportNotebook, UIMessage, UIMessageTarget, CreateNotebook, TriggerItem, UIToggle} from "../util/ui_event";
import {div, h2, iconButton, span, tag, TagElement} from "../util/tags";
import {storage} from "../util/storage";

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

export class NotebookListUI extends UIMessageTarget {
    readonly el: TagElement<"div">;
    private treeView: TagElement<"div">;
    private tree: Tree<DirectoryNode | NotebookNode>;
    private treeEl: TagElement<"ul">;
    private dragEnter: EventTarget | null;

    constructor() {
        super();
        this.el = div(
            ['notebooks-list', 'ui-panel'], [
                h2([], [
                    'Notebooks',
                    span(['buttons'], [
                        iconButton(['import-notebook'], 'Import a notebook', '', 'Import').click(evt => {
                            evt.stopPropagation();
                            this.publish(new ImportNotebook());
                        }),
                        iconButton(['create-notebook'], 'Create new notebook', '', 'New').click(evt => {
                            evt.stopPropagation();
                            this.publish(new CreateNotebook());
                        })
                    ])
                ]).click(evt => this.collapse()),
                div(['ui-panel-content'], [
                    this.treeView = div(['tree-view'], [])
                ])
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
                let itemEl: NotebookListNode | null = null;
                if (typeof item === "string") {
                    // leaf - item is the complete path
                    itemEl = Object.assign(
                        tag('li', ['leaf'], {}, [
                            span(['name'], [itemName]).click(evt => {
                                this.publish(new TriggerItem(item));
                            })
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
                            ]), {
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