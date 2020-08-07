import {CreateComment, DeleteComment, NotebookMessageDispatcher, UpdateComment} from "../messaging/dispatcher";
import {BaseDisposable, Observer, StateHandler} from "../state/state_handler";
import {CellComment} from "../../../data/data";
import {PosRange} from "../../../data/result";
import {diffArray,Deferred} from "../../../util/helpers";
import {button, div, img, span, tag, TagElement, textarea} from "../../util/tags";
import * as monaco from "monaco-editor";
import {editor} from "monaco-editor";
import TrackedRangeStickiness = editor.TrackedRangeStickiness;
import {ServerStateHandler} from "../state/server_state";
import {createCellComment} from "../comment";
import {Identity} from "../../../data/messages";



export class CommentHandler extends BaseDisposable {
    //                              id -> Comment
    readonly commentRoots: Record<string, CommentRoot> = {};
    //                              range -> id
    readonly rootRanges: Record<string, string> = {};

    constructor(dispatcher: NotebookMessageDispatcher,
                commentsState: StateHandler<Record<string, CellComment>>,
                currentSelection: StateHandler<PosRange | undefined>,
                editor: editor.ICodeEditor,
                cellId: number) {
       super()

       const handleComments = (currentComments: Record<string, CellComment>, oldComments: Record<string, CellComment> = {}) => {
           const [removed, added] = diffArray(Object.keys(oldComments), Object.keys(currentComments));

           // For added and removed comments, we only need to update the comment roots here.
           // Child comments are handled by their roots.
           added.forEach(commentId => {
               const newComment = currentComments[commentId];
               const maybeRootId = this.rootRanges[newComment.range.toString];
               if (maybeRootId === undefined) {
                   // this is a fresh new root
                   this.commentRoots[newComment.uuid] =
                       new CommentRoot(dispatcher, new StateHandler(newComment), new StateHandler([]), currentSelection, editor, cellId);
                   this.rootRanges[newComment.range.toString] = newComment.uuid;
               } else { // there is already a root at this location, but it might need to be replaced by this one
                   const maybeRoot = this.commentRoots[maybeRootId];
                   if (maybeRoot.rootState.getState().createdAt > newComment.createdAt) {
                       // this comment is older than the current root at this position, we need to remove the root and set this one in its place.
                       delete this.commentRoots[maybeRoot.uuid]
                       delete this.rootRanges[maybeRoot.range.toString]

                       this.commentRoots[newComment.uuid] =
                           new CommentRoot(dispatcher, new StateHandler(newComment), new StateHandler([]), currentSelection, editor, cellId);
                       this.rootRanges[newComment.range.toString] = newComment.uuid;
                   } // else, the comment is a child and will be handled later.
               }
           });

           removed.forEach(commentId => {
               const removedComment = oldComments[commentId];
               const maybeRoot = this.commentRoots[removedComment.uuid];
               if (maybeRoot && maybeRoot.uuid === commentId) {
                   // If this is a root comment, we delete it (it will handle deleting all it's children)
                   maybeRoot.dispose();
                   delete this.commentRoots[removedComment.uuid];
                   delete this.rootRanges[removedComment.range.toString];
               }
           });

           // at this point, all the roots exist but their ranges might not be correct. So we need to update the root ranges
           Object.values(this.commentRoots).forEach(root => {
               const maybeChanged = currentComments[root.uuid];
               if (root.range.toString !== maybeChanged.range.toString) {
                   delete this.rootRanges[root.range.toString];
                   this.rootRanges[maybeChanged.range.toString] = root.uuid
               }
           })

           // ok, now we update all the root comments and their children
           const rootChildren: Record<string, CellComment[]> = {}; // root uuid -> comment
           Object.values(currentComments).forEach(comment => {
               // at this point, we should have a root for this comment's range.
               const maybeRootId = this.rootRanges[comment.range.toString];
               const maybeRoot = this.commentRoots[maybeRootId];
               if (maybeRootId && maybeRoot) {
                   if (comment.uuid === maybeRoot.uuid) {
                       // if this is a root comment, we update its state
                       maybeRoot.rootState.updateState(() => comment);
                   } else {
                       // Otherwise, it's a child comment, so we add it to rootChildren.
                       rootChildren[maybeRoot.uuid] = [...rootChildren[maybeRoot.uuid] ?? [], comment];
                   }
               } // else, this comment's root was either deleted or its range was updated. Either way, the root will handle dealing with it so we can ignore it for now.
           });

           // Finally, we update all the root children
           Object.values(this.commentRoots).forEach(root => {
               const children = rootChildren[root.uuid] ?? [];
               root.childrenState.updateState(() => children)
           })
       }
       handleComments(commentsState.getState())
       commentsState.addObserver((current, old) => handleComments(current, old), this);

       let commentButton: CommentButtonComponent | undefined = undefined;

       const handleSelection = (currentSelection?: PosRange) => {
           const model = editor.getModel();
           if (currentSelection && currentSelection.length > 0 && model) {
               const maybeEquals = Object.keys(this.rootRanges)
                   .map(s => PosRange.fromString(s))
                   .find(r => r.equals(currentSelection));
               if (maybeEquals === undefined) {
                   // show the new comment button
                   if (commentButton) commentButton.hide();
                   commentButton = new CommentButtonComponent(dispatcher, editor, currentSelection, cellId);
                   commentButton.show();
                   return
               } // else the CommentRoot will handle showing itself.
           }
           // if we got here, we should hide the button
           if (commentButton && !commentButton.el.contains(document.activeElement)) {
               commentButton.hide();
               commentButton = undefined;
           }
       }
       handleSelection(currentSelection.getState())
       currentSelection.addObserver(s => handleSelection(s), this);
    }

    activeComment(): CommentRoot | undefined {
        return Object.values(this.commentRoots).find(root => root.visible)
    }

    hide() {
        Object.values(this.commentRoots).forEach(root => root.hide())
    }
}

abstract class MonacoRightGutterOverlay extends BaseDisposable {
    readonly el: TagElement<"div">;

    protected constructor(readonly editor: editor.ICodeEditor) {
        super()
        this.el = div(['cell-overlay'], [])
    }

    calculatePosition(editor: editor.ICodeEditor, range: PosRange): [number, number] {
        const model = editor.getModel();
        const editorEl = editor.getDomNode();
        if (model && editorEl) {
            const endPos = model.getPositionAt(range.end);
            const containerOffset = editorEl.getBoundingClientRect().left;
            const currentY = editor.getTopForPosition(endPos.lineNumber, endPos.column);
            const containerY = editorEl.getBoundingClientRect().top;

            const l = editor.getLayoutInfo();
            const x = (
                containerOffset                 // the location of this cell on the page
                + l.contentWidth                // the width of the content area
                - l.verticalScrollbarWidth      // don't want to overlay on top of the scrollbar.
            );
            const y = containerY + currentY;
            return [x, y];
        } else {
            this.hide(); // should we throw an error instead?
            return [0, 0];
        }
    }

    abstract get range(): PosRange

    hide() {
        if (this.el.parentElement) this.el.parentElement.removeChild(this.el);
    }

    show() {
        if (!this.el.parentElement) {
            document.body.appendChild(this.el)
        }

        const [x, y] = this.calculatePosition(this.editor, this.range);
        this.el.style.left = `${x}px`;
        this.el.style.top = `${y}px`;
    }
}

class CommentRoot extends MonacoRightGutterOverlay {
    readonly el: TagElement<"div">;
    private highlights: string[] = [];

    constructor(readonly dispatcher: NotebookMessageDispatcher,
                readonly rootState: StateHandler<CellComment>,
                readonly childrenState: StateHandler<CellComment[]>,
                readonly currentSelection: StateHandler<PosRange | undefined>,
                editor: editor.ICodeEditor,
                private cellId: number) {
        super(editor);
        this.handleSelection();
        currentSelection.addObserver(() => this.handleSelection(), this);

        this.el.classList.add('comment-container');

        let root = new CommentComponent(dispatcher, cellId, rootState.getState());
        const commentList = div(['comments-list'], [root.el]);
        this.el.appendChild(commentList);
        rootState.addObserver((currentRoot, previousRoot) => {
            console.log(currentRoot.uuid, "updating to new root!")
            const newRoot = new CommentComponent(dispatcher, cellId, currentRoot);
            root.el.replaceWith(newRoot.el);
            root = newRoot;

            if (currentRoot.range.toString !== previousRoot.range.toString) {
                this.handleSelection()  // TODO: sometimes this is too slow :(
                this.childrenState.getState().forEach(child => {
                    dispatcher.dispatch(new UpdateComment(cellId, child.uuid, currentRoot.range, child.content))
                })
            }
        }, this);

        const newComment = new NewCommentComponent(dispatcher, () => this.range, cellId);

        const handleNewChildren = (newChildren: CellComment[]) => {
            // replace all the children. if this causes perf issues, we will need to do something more granular.
            commentList.innerHTML = "";
            commentList.appendChild(root.el);
            newChildren
                .slice() // `newChildren` is frozen since it's coming from a state change
                // descending sort by creation time. Since createdAt is a bigInt, we can't just subtract the numbers, apparently.
                .sort((a, b) =>
                    a.createdAt === b.createdAt
                        ? 0
                        : a.createdAt > b.createdAt
                            ? 1
                            : -1
                )
                .forEach(comment => {
                    commentList.appendChild(new CommentComponent(dispatcher, cellId, comment).el);
                });
            commentList.appendChild(newComment.el);
        }

        handleNewChildren(childrenState.getState())
        childrenState.addObserver(c => handleNewChildren(c), this);

        const modelChangeListener = editor.onDidChangeModelContent(() => {
            const model = this.editor.getModel();
            if (model) {
                const modelDecorations = model.getAllDecorations();
                const maybeDecoration = modelDecorations.find(d => this.highlights.includes(d.id));
                if (maybeDecoration && !maybeDecoration.range.isEmpty()) {
                    const startPos = model.getPositionAt(this.range.start);
                    const endPos = model.getPositionAt(this.range.end);
                    const mRange = monaco.Range.fromPositions(startPos, endPos);
                    if (!monaco.Range.equalsRange(maybeDecoration.range, mRange)) {
                        // we have a highlight with the same ID, but a different range. This means there is some drift.
                        const newRange = new PosRange(model.getOffsetAt(maybeDecoration.range.getStartPosition()), model.getOffsetAt(maybeDecoration.range.getEndPosition()));
                        dispatcher.dispatch(new UpdateComment(cellId, rootState.getState().uuid, newRange, rootState.getState().content));
                    }
                } else {
                    // decoration wasn't found or was empty, so we need to delete it.
                    dispatcher.dispatch(new DeleteComment(cellId, rootState.getState().uuid));
                    this.highlights = [];

                    // if the range was empty, remove it.
                    if (maybeDecoration) model.deltaDecorations([maybeDecoration.id], [])
                }
            }
        })
        this.didDispose.then(() => modelChangeListener.dispose())
    }

    get uuid() {
        return this.rootState.getState().uuid;
    }

    get range() {
        return this.rootState.getState().range;
    }

    handleSelection() {
        const model = this.editor.getModel();
        if (model) {
            const mRange = this.range.toMRange(model);
            const currentPosition = this.editor.getPosition();
            const selected = currentPosition && mRange.containsPosition(currentPosition);
            let highlightClass = "comment-highlight";
            if (selected) {
                highlightClass = "comment-highlight-strong";
                this.show()
            } else {
                this.hide()
            }
            this.highlights = this.editor.deltaDecorations(this.highlights, [{
                range: mRange,
                options: {
                    className: highlightClass,
                    stickiness: TrackedRangeStickiness.NeverGrowsWhenTypingAtEdges,
                    hoverMessage: { value: 'Click to see comment'}
                }
            }]);
        }
    }

    public visible = false;
    show() {
        super.show()
        this.visible = true
    }

    hide() {
        super.hide()
        this.visible = false
    }

    onDispose() {
        // we need to delete all children when the root is deleted.
        this.childrenState.getState().forEach(comment => this.dispatcher.dispatch(new DeleteComment(this.cellId, comment.uuid)))
        this.hide()
        this.editor.deltaDecorations(this.highlights, []) // clear highlights
    }
}

class CommentButtonComponent extends MonacoRightGutterOverlay{
    constructor(readonly dispatcher: NotebookMessageDispatcher, editor: editor.ICodeEditor, readonly range: PosRange, readonly cellId: number) {
        super(editor);

        const button = div(['new-comment-button'], []).mousedown(evt => {
            evt.stopPropagation();
            evt.preventDefault();

            const newComment = new NewCommentComponent(dispatcher, () => range, cellId)
            newComment.display(this)
                .then(() => this.hide())
        });
        this.el.appendChild(button);
    }
}

class NewCommentComponent extends BaseDisposable {
    readonly el: TagElement<"div">;
    private currentIdentity: Identity;
    onCreate?: () => void;

    constructor(readonly dispatcher: NotebookMessageDispatcher,
                readonly range: () => PosRange,
                readonly cellId: number) {

        super()
        this.currentIdentity = ServerStateHandler.getState().identity;

        const doCreate = () => {
            this.dispatcher.dispatch(new CreateComment(cellId, createCellComment({
                range: range(),
                author: this.currentIdentity.name,
                authorAvatarUrl: this.currentIdentity?.avatar ?? undefined,
                createdAt: Date.now(),
                content: text.value
            })));
            text.value = ""
            if (this.onCreate) this.onCreate()
        };

        const controls = div(['controls', 'hide'], [
            button(['create-comment-button'], {}, ['Comment']).mousedown(evt => {
                evt.stopPropagation();
                evt.preventDefault();
                doCreate()
            }),
            button(['cancel'], {}, ['Cancel']).mousedown(() => controls.classList.add("hide"))
        ])
        const text = textarea(['comment-text'], '', '')
            .listener('keydown', (evt: KeyboardEvent) => {
                if (this.currentIdentity.name && evt.shiftKey && evt.key === "Enter") {
                    doCreate();
                    evt.stopPropagation();
                    evt.preventDefault();
                }
            }).listener('focus', evt => {
                controls.classList.remove("hide")
            }).listener('blur', () => {
                controls.classList.add("hide")
            });
        this.el = div(["comments-list"], [
            div(['create-comment', 'comment'], [
                div(['header'], [
                    ...(this.currentIdentity?.avatar ? [img(['avatar'], this.currentIdentity.avatar)] : []),
                    span(['author'], [this.currentIdentity.name]),
                ]),
                div(['comment-content'], [
                    text,
                    controls
                ])
            ])
        ])

        ServerStateHandler.view("connectionStatus", this).addObserver((currentStatus, previousStatus) => {
            if (currentStatus === "disconnected" && previousStatus === "connected") {
                this.el.classList.add("hide")
            } else if (currentStatus === "connected" && previousStatus === "disconnected") {
                this.el.classList.remove("hide")
            }
        })
    }

    // display using the provided overlay
    display(overlay: MonacoRightGutterOverlay) {
        overlay.el.innerHTML = "";
        overlay.el.appendChild(this.el);
        overlay.el.classList.add("comment-container");
        return new Promise(resolve => {
            this.onCreate = resolve
        })
    }
}

class CommentComponent {
    readonly el: TagElement<"div">;
    private currentIdentity: Identity;

    constructor(readonly dispatcher: NotebookMessageDispatcher,
                readonly cellId: number,
                readonly comment: CellComment) {

        this.currentIdentity = ServerStateHandler.getState().identity;

        this.el = this.commentElement(comment);
    }

    private commentElement(comment: CellComment) {
        const actions = div(['actions'], []);

        if (this.currentIdentity?.name === comment.author) {
            actions.click(() => {
                const listener = () => {
                    actions.removeChild(items);
                    document.body.removeEventListener("mousedown", listener);
                };
                const items = tag('ul', [], {}, [
                    tag('li', [], {}, ['Edit']).click((e) => { e.preventDefault(); e.stopPropagation(); listener(); this.setEditable(true) }),
                    tag('li', [], {}, ['Delete']).click(() => { listener(); this.delete() }),
                ]).listener("mousedown", evt => evt.stopPropagation());

                document.body.addEventListener("mousedown", listener);
                actions.appendChild(items);
            })
        } else {
            actions.disable()
        }

        return div(['comment'], [
            div(['header'], [
                comment.authorAvatarUrl ? img(['avatar'], comment.authorAvatarUrl, `[Avatar of ${comment.author}]`) : span(['avatar'], []),
                div(['author-timestamp'], [
                    span(['author'], [comment.author]),
                    span(['timestamp'], [new Date(Number(comment.createdAt)).toLocaleString("en-US", {timeZoneName: "short"})]),
                ]),
                actions
            ]),
            div(['comment-content'], [comment.content])
        ])
    }

    setEditable(b: boolean) {
        if (b) {
            const doEdit = (content: string) => {
                this.dispatcher.dispatch(new UpdateComment(this.cellId, this.comment.uuid, this.comment.range, content));
            };

            this.el.innerHTML = "";
            const text = textarea(['comment-text'], '', this.comment.content).listener('keydown', (evt: KeyboardEvent) => {
                if (this.currentIdentity?.name && evt.shiftKey && evt.key === "Enter") {
                    doEdit(text.value);
                    evt.stopPropagation();
                    evt.preventDefault();
                }
            });
            this.el.appendChild(
                div(['create-comment', 'comment'], [
                    div(['header'], [
                        ...(this.currentIdentity?.avatar ? [img(['avatar'], this.currentIdentity.avatar)] : []),
                        span(['author'], [this.currentIdentity.name]),
                    ]),
                    div(['comment-content'], [
                        text,
                        div(['controls'], [
                            button(['create-comment-button'], {}, ['Comment']).click(() => doEdit(text.value)),
                            button(['cancel'], {}, ['Cancel']).click(() => this.setEditable(false))
                        ])
                    ]),
                ])
            )
        } else {
            this.el.innerHTML = "";
            this.el.appendChild(this.commentElement(this.comment))
        }
    }

    private delete() {
        this.dispatcher.dispatch(new DeleteComment(this.cellId, this.comment.uuid))
    }
}
