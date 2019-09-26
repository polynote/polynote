import {Codec, CodecContainer, combined, discriminated, int32, str, uint8} from "./codec";

export abstract class ContentEdit extends CodecContainer {
    static codec: Codec<ContentEdit>;
    static codecs: typeof ContentEdit[];
    static msgTypeId: number;
    pos: number;

    static rebase(a: ContentEdit, b: ContentEdit): ContentEdit[][] // for some reason TS can't figure out that ContentEdit === Insert | Delete so we need to help it.
    static rebase(a: Insert | Delete, b: Insert | Delete): ContentEdit[][] {
        if (a instanceof Insert) {
            if (b instanceof Insert) {
                if (a.pos < b.pos || (a.pos === b.pos && (a.content.length < b.content.length || a.content < b.content))) {
                    return [[a], [new Insert(b.pos + a.content.length, b.content)]];
                } else {
                    return [[new Insert(a.pos + b.content.length, a.content)], [b]];
                }
            } else {
                if (a.pos <= b.pos) {
                    return [[a], [new Delete(b.pos + a.content.length, b.length)]];
                } else if (a.pos < b.pos + b.length) {
                    const beforeLength = a.pos - b.pos;
                    return [[new Insert(b.pos, a.content)], [new Delete(b.pos, beforeLength), new Delete(b.pos + a.content.length, b.length - beforeLength)]];
                } else {
                    return [[new Insert(a.pos - b.length, a.content)], [b]]
                }
            }
        } else {
            if (b instanceof Insert) {
                if (b.pos <= a.pos) {
                    return [[new Delete(a.pos + b.content.length, a.length)], [b]];
                } else if (b.pos < a.pos + a.length) {
                    const beforeLength = b.pos - a.pos;
                    return [[new Delete(a.pos, beforeLength), new Delete(a.pos + b.content.length, a.length - beforeLength)], [new Insert(a.pos, b.content)]];
                } else {
                    return [[a], [new Insert(b.pos - a.length, b.content)]];
                }
            } else {
                if (a.pos + a.length <= b.pos) {
                    return [[a], [new Delete(b.pos - a.length, b.length)]];
                } else if (b.pos + b.length <= a.pos) {
                    return [[new Delete(a.pos - b.length, a.length)], [b]];
                } else if (b.pos >= a.pos && b.pos + b.length <= a.pos + a.length) {
                    return [[new Delete(a.pos, a.length - b.length)], []];
                } else if (a.pos >= b.pos && a.pos + a.length <= b.pos + b.length) {
                    return [[], [new Delete(b.pos, b.length - a.length)]];
                } else if (b.pos > a.pos) {
                    const overlap = a.pos + a.length - b.pos;
                    return [[new Delete(a.pos, a.length - overlap)], [new Delete(a.pos, b.length - overlap)]];
                } else {
                    const overlap = b.pos + b.length - b.pos;
                    return [[new Delete(b.pos, a.length - overlap)], [new Delete(b.pos, b.length - overlap)]];
                }
            }
        }
    }

    static rebaseAll(edit: ContentEdit, edits: ContentEdit[]) {
        const rebasedOther: ContentEdit[] = [];
        let rebasedEdit = [edit];
        edits.forEach((b) => {
            let bs = [b];
            rebasedEdit = rebasedEdit.flatMap((a) => {
                if (bs.length === 0) return a;
                if (bs.length === 1) {
                    const rebased = ContentEdit.rebase(a, bs[0]);
                    bs = rebased[1];
                    return rebased[0];
                } else {
                    const rebased = ContentEdit.rebaseAll(a, bs);
                    bs = rebased[1];
                    return rebased[0];
                }
            });
            rebasedOther.push(...bs);
        });
        return [rebasedEdit, rebasedOther];
    }

    // port of ContentEdits#rebase, since there's no ContentEdits class here
    static rebaseEdits(edits1: ContentEdit[], edits2: ContentEdit[]) {
        const result: ContentEdit[] = [];
        let otherEdits = edits2;
        edits1.forEach((edit) => {
            const rebased = ContentEdit.rebaseAll(edit, otherEdits);
            result.push(...rebased[0]);
            otherEdits = rebased[1];
        });
        return result;
    }

    isEmpty() {
        return false;
    }

}

export class Insert extends ContentEdit {
    static codec = combined(int32, str).to(Insert);
    static get msgTypeId() {
        return 0;
    }

    static unapply(inst: Insert): ConstructorParameters<typeof Insert> {
        return [inst.pos, inst.content];
    }

    constructor(readonly pos: number, readonly content: string) {
        super();
    }

    isEmpty() {
        return this.content.length === 0;
    }
}

export class Delete extends ContentEdit {
    static codec = combined(int32, int32).to(Delete);
    static get msgTypeId() {
        return 1;
    }

    static unapply(inst: Delete): ConstructorParameters<typeof Delete> {
        return [inst.pos, inst.length];
    }

    constructor(readonly pos: number, readonly length: number) {
        super();
    }

    isEmpty() {
        return this.length === 0;
    }
}

ContentEdit.codecs = [Insert, Delete];

ContentEdit.codec = discriminated(
    uint8,
    msgTypeId => ContentEdit.codecs[msgTypeId].codec,
    msg => (msg.constructor as typeof ContentEdit).msgTypeId
);
