import {EditBuffer} from "./edit_buffer";
import {DeleteCell} from "./messages";

describe("EditBuffer", () => {
    const firstUpdate = new DeleteCell(0, 0, 0)
    const secondUpdate = new DeleteCell(2, 2, 2)
    const edits = new EditBuffer()
        .push(0, firstUpdate)
        .push(1, secondUpdate);
    const expectedVersions = [{version: 0, edit: firstUpdate}, {version: 1, edit: secondUpdate}]

    test("stores updates in order", () => {
        expect(edits.versions).toEqual(expectedVersions)
    })

    test("properly discards versions", () => {
        expect(edits.duplicate.discard(-1).versions).toEqual(expectedVersions)
        expect(edits.duplicate.discard(0).versions).toEqual(expectedVersions)
        expect(edits.duplicate.discard(1).versions).toEqual([{version: 1, edit: secondUpdate}])
        expect(edits.duplicate.discard(2).versions).toEqual([])
        expect(edits.duplicate.discard(3).versions).toEqual([])
    })

    test("retrieves a range of versions", () => {
        // note that `range` is a little weird, it's exclusive of the first parameter and inclusive of the second, that is: (from, to]
        expect(edits.range(0, -1)).toEqual([])
        expect(edits.range(0, 0)).toEqual([])
        expect(edits.range(1, 1)).toEqual([])
        expect(edits.range(2, 1)).toEqual([])
        expect(edits.range(-2, -1)).toEqual([])
        expect(edits.range(1, 2)).toEqual([])
        expect(edits.range(-1, 0)).toEqual([firstUpdate])
        expect(edits.range(-1, 1)).toEqual([firstUpdate, secondUpdate])
        expect(edits.range(-1, 2)).toEqual([firstUpdate, secondUpdate])
        expect(edits.range(-2, 1)).toEqual([firstUpdate, secondUpdate])
        expect(edits.range(-2, 2)).toEqual([firstUpdate, secondUpdate])
    })
})