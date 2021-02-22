import * as fc from "fast-check"
import {Arbitrary} from "fast-check"
import {ContentEdit, Delete, diffEdits, Insert} from "./content_edit";
import {arrInsert} from "../util/helpers";

describe("ContentEdits", () => {
    const editApply = (edit: Insert | Delete, str: string): string => {
        if (edit instanceof Insert) {
            return arrInsert(str.split(""), edit.pos, edit.content).join("")
        } else {
            return [...str.slice(0, edit.pos), ...str.slice(edit.pos + edit.length, str.length)].join("")
        }
    }

    const editsApply = (edits: (Insert | Delete)[], str: string): string => {
        return edits.reduce<string>((acc, next) => {
            return editApply(next, acc)
        }, str)
    }

    test("can be applied", () => {
        const str = "I like Polynote!"
        expect(editApply(new Insert(0, "yo, "), str)).toEqual("yo, I like Polynote!")
        expect(editApply(new Insert(str.length, " Do you?"), str)).toEqual("I like Polynote! Do you?")
        expect(editApply(new Insert(6, " to use"), str)).toEqual("I like to use Polynote!")
        expect(editApply(new Delete(0, 2), str)).toEqual("like Polynote!")
        expect(editApply(new Delete(2, 5), str)).toEqual("I Polynote!")
    })

    test("can be rebased", () => {
        const genEdit = (strLen: number) => {
            if (strLen > 0) {
                const genInsert = fc.tuple(fc.nat(strLen - 1), fc.unicodeString())
                    .map(([idx, s]) => new Insert(idx, s))
                const genDelete = fc.nat(strLen - 1)
                    .chain(pos => fc.tuple(fc.constant(pos), fc.integer({min: 1, max: strLen - pos})))
                    .map(([pos, len]) => new Delete(pos, len))
                return fc.oneof(genInsert, genDelete)
            } else {
                // can't generate Delete on an empty string!
                return fc.unicodeString().map(s => new Insert(0, s))
            }
        }

        const genEditBasedOnPrev = (prevEdits: (Insert | Delete)[], prevStr: string) => {
            return genEdit(prevStr.length).chain(edit => {
                const newStr = editApply(edit, prevStr)
                return fc.tuple(fc.constant([...prevEdits, edit]), fc.constant(newStr))
            })
        }

        const generator = fc.oneof(fc.string({maxLength: 1000}), fc.unicodeString({maxLength: 1000}))
            .chain(initial => {

                const edits = fc.nat(8).chain(count => {
                    return Array(count).fill(0).reduce<Arbitrary<[(Insert | Delete)[], string]>>((acc, next) => {
                        return acc.chain(([prevEdits, nextStr]) => {
                            return genEditBasedOnPrev(prevEdits, nextStr)
                        })
                    }, genEditBasedOnPrev([], initial))
                }).map(([edits, str]) => {
                    return edits
                })  // drop intermediate strings

                return fc.tuple(fc.constant(initial), edits, edits)
            })

        fc.assert(
            fc.property(generator, ([str, as, bs]) => {
                const rebasedAB = ContentEdit.rebaseEdits(as, bs) as (Insert | Delete)[]
                const rebasedBA = ContentEdit.rebaseEdits(bs, as) as (Insert | Delete)[]

                const strBThenAB = editsApply(rebasedAB, editsApply(bs, str))
                const strAThenBA = editsApply(rebasedBA, editsApply(as, str))

                expect(strBThenAB).toEqual(strAThenBA)
            })
        )
    })
})

describe("diffEdits", () => {

    test("Returned edits should produce the modified string", () => {

        function test(original: string, edited: string) {
            const diff = diffEdits(original, edited);

            let applied = original;
            diff.forEach(edit => applied = edit.apply(applied));

            expect(applied).toEqual(edited);
        }

        function genChar() {
            return Math.floor(Math.random() * 94 + 32);
        }

        function genStr(maxLen: number) {
            const len = Math.floor(Math.random() * maxLen);
            const codes = new Array(len);
            for (let i = 0; i < len; i++) {
                codes[i] = genChar();
            }
            return String.fromCharCode(...codes);
        }

        for (let i = 0; i < 20; i++) {
            test(genStr(100), genStr(100));
        }

    })
})