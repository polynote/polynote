import {append, destroy, ReplaceArrayValue, setValue, UpdateKey, UpdatePartial, valueToUpdate} from "./state_updates";
import {deepCopy} from "../util/helpers";
import * as fc from "fast-check"

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

describe("replaceArrayValue", () => {
    it("replaces the value at the specific index in an array", () => {
        const arr = [0, 1, 2, 3, 4, 5]

        const update = new ReplaceArrayValue(100, 0)

        const result = update.applyMutate(arr);

        expect(result.newValue[0]).toEqual(100)
        expect(Object.values(result.addedValues!)[0]).toEqual(100);
        expect(Object.values(result.removedValues!)[0]).toEqual(0);

        // is this overkill for just replacing array values? ¯\_(ツ)_/¯
        // generate an arbitrary array, index, and replacement value
        const generator = fc.array(fc.nat(), {minLength: 1, maxLength: 100}).chain(arr => fc.tuple(fc.constant(arr), fc.nat(arr.length - 1), fc.nat()))

        fc.assert(
            fc.property(generator, ([arr, idx, newValue]) => {
                const update = new ReplaceArrayValue(newValue, idx)
                const prevValue = arr[idx];
                const result = update.applyMutate(arr);

                expect(result.newValue[idx]).toEqual(newValue)
                expect(Object.values(result.addedValues!)[0]).toEqual(newValue);
                expect(Object.values(result.removedValues!)[0]).toEqual(prevValue);
            })
        )
    })
})
