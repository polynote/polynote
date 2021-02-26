import {append, destroy, setValue, UpdateKey, UpdatePartial, valueToUpdate} from "./state_updates";
import {deepCopy} from "../util/helpers";

interface Example {
    hi: string,
    hello: string,
    removedField?: string,
    addedField?: string,
    deep: {
        first: string,
        second: string,
        array: number[]
    }
}

describe("SetValue", () => {

    it("provides an object diff when it's an object", () => {

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

        const update = setValue(updated)

        const result = update.applyMutate(original)

        expect(result.oldValue).toEqual(original)
        expect(result.newValue).toEqual(updated)

        expect(result.removedValues).toEqual({removedField: original.removedField})
        expect(result.addedValues).toEqual({addedField: updated.addedField})
        expect(result.changedValues).toEqual({hello: updated.hello, deep: updated.deep})

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

describe("UpdateWith", () => {

    it("provides an object diff when it's an object", () => {

        const original: Example = {
            hi: "one",
            hello: "three",
            removedField: "nope",
            deep: {
                first: "hi",
                second: "howdy",
                array: [1, 2]
            }
        }

        const updates: UpdatePartial<Example> = {
            hello: "two",
            addedField: "yup",
            removedField: destroy(),
            deep: {
                second: setValue("hello"),
                array: append(5)
            }
        }

        const expected = {
            hi: "one",
            hello: "two",
            addedField: "yup",
            deep: {
                first: "hi",
                second: "hello",
                array: [1, 2, 5]
            }
        }

        const update = valueToUpdate(updates);
        const mutatedOriginal = deepCopy(original);
        const result = update.applyMutate(mutatedOriginal);

        expect(result.newValue).toEqual(expected);
        expect("removedField" in result.newValue).toEqual(false);
        expect(result.removedValues).toEqual({removedField: original.removedField})
        expect(result.addedValues).toEqual({addedField: expected.addedField})
        expect(result.changedValues).toEqual({hello: expected.hello, deep: expected.deep});

        const deepUpdate = result.fieldUpdates!.deep!;
        expect(deepUpdate.changedValues).toEqual({second: expected.deep.second, array: expected.deep.array});

    })

})

describe("UpdateKey", () => {
    it("removes the field when it's destroyed", () => {
        const original: Example = {
            hi: "one",
            hello: "three",
            removedField: "nope",
            deep: {
                first: "hi",
                second: "howdy",
                array: [1, 2]
            }
        }

        const update = new UpdateKey<Example, "removedField">("removedField", destroy());

        const result = update.applyMutate(original);

        expect("removedField" in result.newValue).toEqual(false);
    })
})