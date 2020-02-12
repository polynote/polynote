"use strict";


import {button, div, span, TagElement, textarea} from "../util/tags";
import {
    CommentDelta,
    CommentRootHidden,
    CommentRootShown,
    GetIdentity,
    UIMessageRequest,
    UIMessageTarget
} from "../util/ui_event";
import * as uuidv4 from 'uuid/v4';

// TODO: add timestamp
// TODO: need to get avatar, author info somehow.

export class CommentRoot extends UIMessageTarget {
    private readonly container: TagElement<"div">;
    private commentsEl: TagElement<"div">;
    private readonly newComment: TagElement<"div">;
    private newCommentText: TagElement<"textarea">;
    private readonly commentContainers: Record<string, [TagElement<"div">, CellComment]> = {};

    constructor(parent: UIMessageTarget, readonly comments: CellComment[] = [], currentAuthor: string, currentAvatar?: string) {
        super(parent);
        this.newComment = div(['create-comment', 'comment'], [
            div(['header'], [
                span(['avatar'], [currentAvatar]),
                span(['author'], [currentAuthor]),
            ]),
            this.newCommentText = textarea(['comment-content'], '').listener('keydown', (evt: KeyboardEvent) => {
                if (evt.shiftKey && evt.key === "Enter") {
                    submitComment();
                    evt.stopPropagation();
                    evt.preventDefault();
                }
            }),
            div(['controls'], [
                button(['create-comment-button'], {}, ['Comment']).click(() => submitComment()),
                button(['cancel'], {}, ['Cancel']).click(() => this.hide())
            ])
        ]);
        const submitComment = () => {
            if (this.newCommentText.value) {
                const comment = CellComment.apply({
                    author: currentAuthor,
                    avatar: currentAvatar,
                    content: this.newCommentText.value,
                });
                this.add(comment);
                this.newCommentText.value = "";
                this.publish(new CommentDelta(this, [comment], []))
            }
        };
        this.container = div(['comment-container'], [
            this.commentsEl = div(['comments-list'], [this.newComment])
        ]);

        if (comments.length) {
            comments.forEach((comment) => this.add(comment));
        }
    }

    position(x: number, y: number) {
        this.container.style.left = `${x}px`;
        this.container.style.top = `${y}px`;
    }

    show() {
        this.commentsEl.innerHTML = '';
        this.comments.forEach(comment => {
            this.commentsEl.append(this.commentContainers[comment.uuid][0]);
        });
        this.commentsEl.append(this.newComment);

        if (!this.container.parentElement) {
            document.body.appendChild(this.container);
        }
        this.newCommentText.focus();
    }

    add(comment: CellComment) {
        this.comments.push(comment);
        this.commentContainers[comment.uuid] = [div(['comment'], [
            div(['header'], [
                span(['author'], [comment.author]),
                span(['avatar'], [comment.avatar]),
            ]),
            div(['comment-content'], [comment.content])
        ]), comment];
        this.show();
    }

    remove(id: string) {
        const [el, comment] = this.commentContainers[id];
        this.container.removeChild(el);
        delete this.commentContainers[id];
        this.publish(new CommentDelta(this, [], [comment]));
    }

    hide() {
        if (this.container.parentElement) this.container.parentElement.removeChild(this.container);
        this.publish(new CommentRootHidden(this));
    }
}


export class CommentButton extends UIMessageTarget {
    private readonly container: TagElement<"div">;

    constructor(private parent: UIMessageTarget, public x: number, public y: number, public onHide: () => void = () => {}) {
        super(parent);
        this.container = div(['comment-container'], [
            div(['new-comment-button'], []).click(() => this.newComment())
        ]);
        this.position(x, y);
    }

    position(x: number, y: number) {
        this.x = x;
        this.y = y;
        this.container.style.left = `${x}px`;
        this.container.style.top = `${y}px`;
    }

    newComment() {
        this.publish(new UIMessageRequest(GetIdentity, (name, avatar) => {
            const comment = new CommentRoot(this.parent, [], name, avatar);
            comment.position(this.x, this.y);
            comment.show();
            this.hide();
            this.publish(new CommentRootShown(comment));
        }));
    }

    show() {
        if (this.container.parentElement) this.container.parentElement.removeChild(this.container);
        document.body.appendChild(this.container)
    }

    hide() {
        if (this.container.parentElement) this.container.parentElement.removeChild(this.container);
        this.onHide()
    }
}

// to ensure UUID uniqueness, comments can only be created with CellComment.apply()
export class CellComment {
    private constructor(readonly uuid: string, public content: string, public author: string, public avatar?: string) {}

    static apply({content, author, avatar}: Omit<CellComment, 'uuid'>): CellComment {
        const uuid = uuidv4();
        return new CellComment(
            uuid,
            content,
            author,
            avatar,
        )
    }
}
