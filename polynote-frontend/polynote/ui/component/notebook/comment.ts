import {Disposable, removeKey, setValue, StateHandler, StateView, UpdateLike, UpdateResult} from "../../../state";
import {CellComment} from "../../../data/data";
import {PosRange} from "../../../data/result";
import {arrExists, collectFields, copyObject} from "../../../util/helpers";
import {button, div, img, span, tag, TagElement, textarea} from "../../tags";
import * as monaco from "monaco-editor";
import {editor} from "monaco-editor";
import {Identity} from "../../../data/messages";
import {v4 as uuidv4} from "uuid";
import TrackedRangeStickiness = editor.TrackedRangeStickiness;
import {ServerStateHandler} from "../../../state/server_state";

export type CommentID = string

// to ensure UUID uniqueness, make sure to only create new comments with createCellComment!
export function createCellComment({range, author, authorAvatarUrl, createdAt, content}: Omit<CellComment, 'uuid'>): CellComment {
    const uuid = uuidv4();
    return new CellComment(
        uuid,
        range,
        author,
        authorAvatarUrl,
        createdAt,
        content,
    )
}

export class CommentHandler extends Disposable {
    //                              id -> Comment
    readonly commentRoots: Record<string, CommentRoot> = {};
    //                              range -> id
    readonly rootRanges: Record<string, string> = {};
    private commentButton?: CommentButton;

    constructor(commentState: StateHandler<Record<string, CellComment>>,
                selectionState: StateView<PosRange | undefined>,
                editor: editor.ICodeEditor) {
       super();
       const allCommentsState = commentState.fork(this);
       const currentSelection = selectionState.fork(this);

       const handleComments = (currentComments: Record<string, CellComment>, updateResult?: UpdateResult<Record<string, CellComment>>) => {
           // console.log("comments changed:", currentComments, oldComments)
           const removed = Object.keys(updateResult?.removedValues ?? {});
           const added = updateResult ? Object.keys(updateResult?.addedValues ?? {}) : Object.keys(currentComments);

           // first pass to update range of existing roots:
           Object.keys(this.commentRoots).forEach(rootId => {
               const rootRange = Object.entries(this.rootRanges).find(([k, v]) => v === rootId)?.[0]
               const rangeForId = currentComments[rootId]?.range?.rangeStr;

               // check if we need to update this range
               if (rangeForId && rootRange !== undefined && rootRange !== rangeForId) {
                   this.rootRanges[rangeForId] = rootId
               }
           })

           // For added and removed comments, we only need to update the comment roots here.
           // Child comments are handled by their roots.
           added.forEach(commentId => {
               const newComment = currentComments[commentId];
               const maybeRootId = this.rootRanges[newComment.range.rangeStr];
               if (maybeRootId === undefined) {
                   // this is a fresh new root
                   this.commentRoots[newComment.uuid] =
                       new CommentRoot(newComment.uuid, allCommentsState, currentSelection, editor);
                   this.rootRanges[newComment.range.rangeStr] = newComment.uuid;
               } else { // there is already a root at this location, but it might need to be replaced by this one
                   const maybeRoot = this.commentRoots[maybeRootId];
                   if (maybeRoot.createdAt > newComment.createdAt) {
                       // this comment is older than the current root at this position, we need to remove the root and set this one in its place.
                       delete this.commentRoots[maybeRoot.uuid]
                       delete this.rootRanges[maybeRoot.range.rangeStr]
                       maybeRoot.dispose()

                       this.commentRoots[newComment.uuid] =
                           new CommentRoot(newComment.uuid, allCommentsState, currentSelection, editor);
                       this.rootRanges[newComment.range.rangeStr] = newComment.uuid;
                   } // else, the comment is a child and will be handled later.
               }
           });

           removed.forEach(commentId => {
               const removedComment = updateResult?.removedValues?.[commentId];
               if (removedComment) {
                   const maybeRoot = this.commentRoots[removedComment.uuid];
                   if (maybeRoot && maybeRoot.uuid === commentId) {
                       // If this is a root comment, we delete it and it's children
                       // First, the children
                       maybeRoot.rootChildren(currentComments).forEach(childComment => allCommentsState.update(() => removeKey(childComment.uuid)))

                       // then the root itself.
                       maybeRoot.dispose();
                       delete this.commentRoots[removedComment.uuid];
                       delete this.rootRanges[removedComment.range.rangeStr];
                   }
               } else {
                   // this means something is implemented wrong in the update that was used
                   console.warn("A comment was removed, but it couldn't be retrieved from update.removedValues.", updateResult);
               }
           });

           // at this point, all the roots exist but their ranges might not be correct. So we need to update the root ranges
           Object.values(this.commentRoots).forEach(root => {
               const maybeChanged = currentComments[root.uuid];
               if (root.range.rangeStr !== maybeChanged.range.rangeStr) {
                   delete this.rootRanges[root.range.rangeStr];
                   this.rootRanges[maybeChanged.range.rangeStr] = root.uuid
               }
           })
       }
       handleComments(allCommentsState.state)
       allCommentsState.addObserver((current, updateResult) => handleComments(current, updateResult));

       const handleSelection = (currentSelection?: PosRange) => {
           const model = editor.getModel();
           if (currentSelection && currentSelection.length > 0 && model) {
               const maybeEquals = Object.keys(this.rootRanges)
                   .map(s => PosRange.fromString(s))
                   .find(r => r.equals(currentSelection));
               if (maybeEquals === undefined) {
                   // show the new comment button
                   if (this.commentButton) this.commentButton.hide();
                   this.commentButton = new CommentButton(allCommentsState, editor, currentSelection);
                   this.commentButton.show();
                   return
               } // else the CommentRoot will handle showing itself.
           }
           // if we got here, we should hide the button
           if (this.commentButton && !this.commentButton.el.contains(document.activeElement)) {
               this.commentButton.hide();
               this.commentButton = undefined;
           }
       }
       handleSelection(currentSelection.state)
       currentSelection.addObserver(s => handleSelection(s));
    }

    activeComment(): boolean {
        return Object.values(this.commentRoots).some(root => root.visible) || (this.commentButton?.clicked ?? false)
    }

    hide() {
        Object.values(this.commentRoots).forEach(root => root.hide())
        this.commentButton?.hide()
    }

    // Cause comments to recompute
    triggerCommentUpdate() {
        Object.values(this.commentRoots).forEach(root => root.triggerCommentUpdate())
    }
}

abstract class MonacoRightGutterOverlay extends Disposable {
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

// a CommentRoot is the base comment at a given selection in a cell, defined as the oldest comment at that selection
// range. all other comments at that selection will be its children
class CommentRoot extends MonacoRightGutterOverlay {
    readonly el: TagElement<"div">;
    private highlights: string[] = [];
    private children: Record<string, Comment> = {};
    private rootState: StateHandler<CellComment>;
    private rootComment: Comment;
    range: PosRange;
    private readonly allCommentsState: StateHandler<Record<string, CellComment>>;
    private readonly currentSelection: StateView<PosRange | undefined>

    constructor(readonly uuid: string,
                commentsState: StateHandler<Record<string, CellComment>>,
                selectionState: StateView<PosRange | undefined>,
                editor: editor.ICodeEditor) {
        super(editor);
        const allCommentsState = this.allCommentsState = commentsState.fork(this);
        const currentSelection = this.currentSelection = selectionState.fork(this);

        this.rootState = allCommentsState.lens(uuid);

        this.range = this.rootState.state.range;
        this.rootState.addObserver((curr: CellComment, updateResult: UpdateResult<CellComment>) => {
            this.range = curr.range;
        });

        this.disposeWith(this.rootState);

        this.handleSelection();
        currentSelection.addObserver(() => this.handleSelection());

        this.el.classList.add('comment-container');

        this.rootComment = new Comment(uuid, allCommentsState);
        const commentList = div(['comments-list'], [this.rootComment.el]);
        this.el.appendChild(commentList);
        this.rootState.addPreObserver(prev => {
            const prevId = prev?.uuid ;
            return currentRoot => {
                if (currentRoot && currentRoot.uuid !== prevId) {
                    // console.log(currentRoot.uuid, "updating to new root!", currentRoot, previousRoot)
                    const newRoot = new Comment(currentRoot.uuid, allCommentsState);
                    this.rootComment.el.replaceWith(newRoot.el);
                    this.rootComment.commentState.dispose();
                    this.rootComment = newRoot;
                }
            }
        });

        const newComment = new NewComment(allCommentsState, () => this.range);

        const handledChangedComments = (maybeChildren: Record<string, CellComment>, updateResult?: UpdateResult<Record<string, CellComment>>) => {
            const children = this.rootChildren(maybeChildren)
            const removedIds = Object.keys(updateResult?.removedValues ?? {});
            const changedIds = updateResult ? UpdateResult.addedOrChangedKeys(updateResult) : Object.keys(maybeChildren);
            // check if any child has changed
            if (updateResult === undefined || removedIds.length > 0 || changedIds.length > 0) {
                // replace all the children. if this causes perf issues, we will need to do something more granular.
                commentList.innerHTML = "";
                commentList.appendChild(this.rootComment.el);
                // console.log(this, "got changed children", children)
                children
                    .slice() // `maybeChildren` is frozen since it's coming from a state change
                    // descending sort by creation time. Since createdAt is a bigInt, we can't just subtract the numbers, apparently.
                    .sort((a, b) =>
                        a.createdAt === b.createdAt
                            ? 0
                            : a.createdAt > b.createdAt
                            ? 1
                            : -1
                    )
                    .forEach(comment => {
                        const maybeChild = this.children[comment.uuid]
                        if (maybeChild) {
                            commentList.appendChild(maybeChild.el)
                        } else {
                            const newChild = new Comment(comment.uuid, allCommentsState)
                            commentList.appendChild(newChild.el)
                            this.children[comment.uuid] = newChild
                        }
                    });
                commentList.appendChild(newComment.el);
            }
        }

        handledChangedComments(this.allCommentsState.state)
        allCommentsState.addObserver((curr, updateResult) => handledChangedComments(curr, updateResult))
        
        if (this.visible) {
            newComment.text.focus()
        }

        this.onDispose.then(() => {
            this.hide()
            this.editor.deltaDecorations(this.highlights, []) // clear highlights
        })
    }

    // locate the children of a CommentRoot by comparing ranges of text (there can only be one root at a given range)
    rootChildren(allComments = this.allCommentsState.state) {
        return Object.values(allComments).filter(comment => comment.uuid !== this.uuid && comment.range.rangeStr === this.range.rangeStr);
    }


    get createdAt() {
        return this.rootState.state.createdAt;
    }

    triggerCommentUpdate() {
        // console.log("Triggered root update for", this.uuid)
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
                    // update all comments that share a range with this root
                    this.allCommentsState.update(state => collectFields(
                        state,
                        (id, comment) => comment.range.equals(this.range) ? setValue(copyObject(comment, {range: newRange})) : undefined
                    ))
                }
            } else {
                // decoration wasn't found or was empty, so we need to delete it.
                this.rootComment.delete()
                this.highlights = [];

                // if the range was empty, remove it.
                if (maybeDecoration) model.deltaDecorations([maybeDecoration.id], [])
            }
        }
    }

    handleSelection() {
        const model = this.editor.getModel();
        if (model) {
            const mRange = this.range.toMRange(model);
            const currentPosition = this.editor.getPosition();
            const selected = currentPosition
                && mRange.containsPosition(currentPosition)
                && this.currentSelection.state?.toMRange(model).containsPosition(currentPosition);
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
}

class CommentButton extends MonacoRightGutterOverlay{
    clicked: boolean = false;
    constructor(readonly commentsState: StateHandler<Record<string, CellComment>>, editor: editor.ICodeEditor, readonly range: PosRange) {
        super(editor);

        const button = div(['new-comment-button'], []).mousedown(evt => {
            evt.stopPropagation();
            evt.preventDefault();

            this.clicked = true;

            const newComment = new NewComment(commentsState, () => range, this.hide.bind(this))
            newComment.display(this)
                .then(() => this.hide())
        });
        this.el.appendChild(button);
    }

    hide() {
        this.clicked = false;
        super.hide();
    }
}

class NewComment extends Disposable {
    readonly el: TagElement<"div">;
    private currentIdentity: Identity;
    onCreate?: () => void;
    text: TagElement<"textarea">;

    constructor(readonly commentsState: StateHandler<Record<string, CellComment>>,
        readonly range: () => PosRange,
        readonly hide?: () => void) {
        super()
        this.currentIdentity = ServerStateHandler.state.identity;

        const doCreate = () => {
            const comment = createCellComment({
                range: range(),
                author: this.currentIdentity.name,
                authorAvatarUrl: this.currentIdentity?.avatar ?? undefined,
                createdAt: Date.now(),
                content: this.text.value
            })
            this.commentsState.updateField(comment.uuid, () => setValue(comment));
            this.text.value = ""
            if (this.onCreate) this.onCreate()
        };

        const controls = div(['controls', 'hide'], [
            button(['create-comment-button'], {}, ['Comment']).mousedown(evt => {
                evt.stopPropagation();
                evt.preventDefault();
                doCreate()
            }),
            button(['cancel'], {}, ['Cancel']).mousedown(() => {
                if (!hide) {
                    controls.classList.add("hide");
                    this.text.value = "";
                    return;
                }
                hide(); 
            })
        ])
        this.text = textarea(['comment-text'], '', '')
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
                    this.text,
                    controls
                ])
            ])
        ])

        ServerStateHandler.view("connectionStatus").addObserver((currentStatus) => {
            if (currentStatus === "disconnected") {
                this.el.classList.add("hide")
            } else if (currentStatus === "connected") {
                this.el.classList.remove("hide")
            }
        }).disposeWith(this)
    }

    // display using the provided overlay
    display(overlay: MonacoRightGutterOverlay) {
        overlay.el.innerHTML = "";
        overlay.el.appendChild(this.el);
        overlay.el.classList.add("comment-container");
        this.text.focus()
        return new Promise<void>(resolve => {
            this.onCreate = resolve
        })
    }
}

class Comment extends Disposable {
    readonly el: TagElement<"div">;
    private currentIdentity: Identity;
    private editing: boolean = false;
    commentState: StateHandler<CellComment>;
    private readonly allCommentsState: StateHandler<Record<string, CellComment>>

    constructor(readonly uuid: string,
                commentsState: StateHandler<Record<string, CellComment>>) {

        super();

        this.currentIdentity = ServerStateHandler.state.identity;
        const allCommentsState = this.allCommentsState = commentsState.fork(this);
        this.commentState = allCommentsState.lens(uuid)

        this.commentState.onDispose.then(() => {
            if (! this.isDisposed) this.dispose()
        })

        this.el = this.commentElement(this.commentState.state);
        this.commentState.addObserver((curr, updateResult) => {
            if (this.editing && arrExists(UpdateResult.addedOrChangedKeys(updateResult), key => key !== 'range')) {
                this.setComment(curr)
            }
        })
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
            this.editing = true;
            const doEdit = (content: string) => {
                this.commentState.update(comment => setValue(copyObject(comment, {content})))
                this.setEditable(false)
            };

            this.el.innerHTML = "";
            const text = textarea(['comment-text'], '', this.commentState.state.content).listener('keydown', (evt: KeyboardEvent) => {
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
            this.editing = false;
            this.setComment(this.commentState.state)
        }
    }

    setComment(comment: CellComment) {
        this.el.innerHTML = "";
        this.el.appendChild(this.commentElement(comment))
    }

    delete() {
        this.allCommentsState.update(() => removeKey(this.commentState.state.uuid))
    }
}
