import {CreateComment, DeleteComment, NotebookMessageDispatcher, UpdateComment} from "../messaging/dispatcher";
import {StateHandler} from "../state/state_handler";
import {CellComment} from "../../../data/data";
import {PosRange} from "../../../data/result";
import {diffArray} from "../../../util/functions";
import {button, div, img, span, tag, TagElement, textarea} from "../../util/tags";
import * as monaco from "monaco-editor";
import {editor} from "monaco-editor";
import TrackedRangeStickiness = editor.TrackedRangeStickiness;
import {ServerStateHandler} from "../state/server_state";
import {createCellComment} from "../comment";
import {Identity} from "../../../data/messages";



export class CommentHandler {
    //                            Range -> Comment
    readonly commentRoots: Record<string, CommentRoot> = {};

    constructor(dispatcher: NotebookMessageDispatcher,
                commentsState: StateHandler<Record<string, CellComment>>,
                currentSelection: StateHandler<PosRange | undefined>,
                editor: editor.ICodeEditor,
                cellId: number) {

       commentsState.addObserver((oldComments, currentComments) => {
           const [removed, added] = diffArray(Object.keys(oldComments), Object.keys(currentComments));

           // For added and removed comments, we only need to update the comment roots here.
           // Child comments are handled by their roots.
           added.forEach(commentId => {
               const newComment = currentComments[commentId];
               const maybeRoot = this.commentRoots[newComment.range.toString];
               if (maybeRoot === undefined || maybeRoot.rootState.getState().createdAt > newComment.createdAt) {
                   this.commentRoots[newComment.range.toString] =
                       new CommentRoot(dispatcher, new StateHandler(newComment), new StateHandler([]), currentSelection, editor, cellId);
               }
           });

           removed.forEach(commentId => {
               const removedComment = oldComments[commentId];
               const maybeRoot = this.commentRoots[removedComment.range.toString];
               if (maybeRoot) {
                   // If this is a root comment, we delete it and it will delete all it's children
                   maybeRoot.dispose();
                   delete this.commentRoots[removedComment.range.toString];
               }
           });

           // ok, now we update all the root comments and their children
           const rootChildren: Record<string, CellComment[]> = {};
           Object.values(currentComments).forEach(comment => {
               // at this point we should have a root for this comment's range.
               const root = this.commentRoots[comment.range.toString];
               if (comment.uuid === root.uuid) {
                   // If this is the root comment, we set its state.
                   root.rootState.setState(comment);
               } else {
                   // Otherwise, it's a child comment so we collect it up here.
                   rootChildren[root.range.toString] = [...rootChildren[root.uuid] ?? [], comment];
               }
           });

           // Finally, we update all the root children
           Object.entries(rootChildren).forEach(([rootRange, children]) => {
               const root = this.commentRoots[rootRange];
               root.childrenState.setState(children);

           })
       });
       
       let commentButton: CommentButtonComponent | undefined = undefined;

       currentSelection.addObserver((_, currentSelection) => {
           const model = editor.getModel();
           if (currentSelection && currentSelection.length > 0 && model) {
               const maybeEquals = Object.keys(this.commentRoots)
                   .map(s => PosRange.fromString(s))
                   .find(r => r.equals(currentSelection));
               if (maybeEquals === undefined) {
                   // show the new comment button
                   if (commentButton) commentButton.hide();
                   commentButton = new CommentButtonComponent(dispatcher, editor, currentSelection, cellId);
                   commentButton.show();
                   return
               }
           }
           // if we got here, we should hide the button
           if (commentButton) {
               commentButton.hide();
               commentButton = undefined;
           }
       })

    }

}

abstract class MonacoRightGutterOverlay {
    readonly el: TagElement<"div">;

    protected constructor(readonly editor: editor.ICodeEditor, readonly range: PosRange) {
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
                cellId: number) {
        super(editor, rootState.getState().range);
        this.setHighlight();
        currentSelection.addObserver((_, __) => {
            this.setHighlight();
        });

        this.el.classList.add('comment-container');

        let root = new CommentComponent(dispatcher, cellId, rootState.getState());
        const commentList = div(['comments-list'], [root.el]);
        this.el.appendChild(commentList);
        rootState.addObserver((_, changedRoot) => {
            const newRoot = new CommentComponent(dispatcher, cellId, changedRoot);
            commentList.replaceChild(newRoot.el, root.el);
            root = newRoot;
        });

        childrenState.getState()
            .sort((a, b) => b.createdAt - a.createdAt)
            .forEach(comment => {
                this.el.appendChild(new CommentComponent(dispatcher, cellId, comment).el);
            });
        childrenState.addObserver((_, newChildren) => {
            // replace all the children. if this causes perf issues, we will need to do something more granular.
            commentList.innerHTML = "";
            commentList.appendChild(root.el);
            newChildren
                .sort((a, b) => b.createdAt - a.createdAt)
                .forEach(comment => {
                    commentList.appendChild(new CommentComponent(dispatcher, cellId, comment).el);
                });
            commentList.appendChild(newComment.el);
        });

        const newComment = new NewCommentComponent(dispatcher, this.range, cellId);
        commentList.append(newComment.el);

        editor.onDidChangeModelContent(() => {
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
                        // TODO: update the children too?
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
    }

    get uuid() {
        return this.rootState.getState().uuid;
    }

    get range() {
        return this.rootState.getState().range;
    }

    setHighlight() {
        const model = this.editor.getModel();
        if (model) {
            const mRange = this.range.toMRange(model);
            const currentPosition = this.editor.getPosition();
            const highlightClass = currentPosition && mRange.containsPosition(currentPosition) ? "comment-highlight-strong" : "comment-highlight";
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

    dispose() {

    }

    show() {

    }

    hide() {

    }
}

class CommentButtonComponent extends MonacoRightGutterOverlay{
    constructor(readonly dispatcher: NotebookMessageDispatcher, editor: editor.ICodeEditor, readonly range: PosRange, readonly cellId: number) {
        super(editor, range);

        const button = div(['new-comment-button'], []).click(evt => {
            evt.stopPropagation();
            evt.preventDefault();

            this.el.replaceChild(new NewCommentComponent(dispatcher, range, cellId).el, button);
        });
        this.el.appendChild(button);
    }
}

class NewCommentComponent {
    readonly el: TagElement<"div">;
    private currentIdentity?: Identity;

    constructor(readonly dispatcher: NotebookMessageDispatcher,
                readonly range: PosRange,
                readonly cellId: number) {

        this.currentIdentity = ServerStateHandler.get.getState().identity;

        this.el.classList.add('comment-container');

        const doCreate = () => {
            this.dispatcher.dispatch(new CreateComment(cellId, createCellComment({
                range: range,
                author: this.currentIdentity?.name ?? "Unknown User",
                authorAvatarUrl: this.currentIdentity?.avatar ?? undefined,
                createdAt: Date.now(),
                content: text.value
            })));
        };

        const text = textarea(['comment-text'], '', '').listener('keydown', (evt: KeyboardEvent) => {
            if (this.currentIdentity?.name && evt.shiftKey && evt.key === "Enter") {
                doCreate();
                evt.stopPropagation();
                evt.preventDefault();
            }
        });
        this.el = div(['create-comment', 'comment'], [
            div(['header'], [
                ...(this.currentIdentity?.avatar ? [img(['avatar'], this.currentIdentity.avatar)] : []),
                span(['author'], [this.currentIdentity?.name ?? "Unknown user"]),
            ]),
            div(['comment-content'], [
                text,
                div(['controls'], [
                    button(['create-comment-button'], {}, ['Comment']).click(() => doCreate()),
                    button(['cancel'], {}, ['Cancel']).click(() => this.el.parentElement!.blur())
                ])
            ])
        ])

    }
}

class CommentComponent {
    readonly el: TagElement<"div">;
    private currentIdentity?: Identity;

    constructor(readonly dispatcher: NotebookMessageDispatcher,
                readonly cellId: number,
                readonly comment: CellComment) {

        this.currentIdentity = ServerStateHandler.get.getState().identity;

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
            const doEdit = () => {
                this.dispatcher.dispatch(new UpdateComment(this.cellId, this.comment.uuid, this.comment.range, this.comment.content));
            };

            this.el.innerHTML = "";
            const text = textarea(['comment-text'], '', this.comment.content).listener('keydown', (evt: KeyboardEvent) => {
                if (this.currentIdentity?.name && evt.shiftKey && evt.key === "Enter") {
                    doEdit();
                    evt.stopPropagation();
                    evt.preventDefault();
                }
            });
            this.el.appendChild(
                div(['create-comment', 'comment'], [
                    div(['header'], [
                        ...(this.currentIdentity?.avatar ? [img(['avatar'], this.currentIdentity.avatar)] : []),
                        span(['author'], [this.currentIdentity?.name ?? "Unknown user"]),
                    ]),
                    div(['comment-content'], [
                        text,
                        div(['controls'], [
                            button(['create-comment-button'], {}, ['Comment']).click(() => doEdit()),
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
