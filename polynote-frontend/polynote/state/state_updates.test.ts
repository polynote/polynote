import {setValue} from "./state_updates";

describe("SetValue", () => {

    it ("provides an object diff when it's an object", () => {

        const updated = {
            hi: "one",
            hello: "two",
            addedField: "yup",
            deep: {
                first: "hi",
                second: "hello",
                array: [1, 2]
            }
        }

        const original = {
            hi: "one",
            hello: "three",
            removedField: "nope",
            deep: {
                first: "hi",
                second: "howdy",
                array: [2, 3, 4]
            }
        }

        const update = setValue(updated)

        const result = update.applyMutate(original)

        expect(result.oldValue).toEqual(original)
        expect(result.newValue).toEqual(updated)

        expect(result.removedValues).toEqual({removedField: original.removedField})
        expect(result.addedValues).toEqual({addedField: updated.addedField})

        const fieldUpdates = result.fieldUpdates as any

        const updatedKeys = Object.keys(fieldUpdates)
        expect(updatedKeys.length).toEqual(4);
        for (const k of ["hello", "addedField", "removedField", "deep"]) {
            expect(updatedKeys).toContain(k);
        }

        const deepFieldUpdates = fieldUpdates.deep.fieldUpdates
        const deepUpdatedKeys = Object.keys(deepFieldUpdates)
        expect(deepUpdatedKeys.length).toEqual(2);
        for (const k of ["second", "array"]) {
            expect(deepUpdatedKeys).toContain(k);
        }

        expect(deepFieldUpdates.second).toEqual({
            update: setValue(updated.deep.second),
            newValue: updated.deep.second,
            oldValue: original.deep.second
        })


    })

})