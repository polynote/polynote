import {
    arrDelete, arrDeleteItem,
    arrInsert,
    arrReplace,
    changedKeys, collect,
    deepFreeze, Deferred,
    diffArray,
    equalsByKey,
    isObject, mapSome,
    mapValues, partition,
    removeKey, unzip
} from "./helpers";
import * as deepEquals from 'fast-deep-equal/es6';
import * as messages from "../data/messages";
import {ServerErrorWithCause} from "../data/result";

describe("isObject", () => {
    test("isObject differentiates between objects and non-objects", () => {
        expect(isObject({})).toBeTruthy()
        class Foo {}
        const foo = new Foo
        expect(isObject(foo)).toBeTruthy()
        expect(isObject(1)).toBeFalsy()
        expect(isObject(undefined)).toBeFalsy()
        expect(isObject(null)).toBeFalsy()
    })
    it("isn't fooled by arrays even though they are technically objects", () => {
        expect(isObject([])).toBeFalsy()
    })
})

describe("diffArray", () => {
    it("finds the difference between two arrays", () => {
        expect(diffArray([1, 2, 3, 4], [4, 3, 5, 1])).toEqual([[2], [5]])
        expect(diffArray([1, 2, 3, 4], [])).toEqual([[1, 2, 3, 4], []])
        expect(diffArray([], [1, 2, 3, 4])).toEqual([[], [1, 2, 3, 4]])
        expect(diffArray<any>([1, 2, 3, 4], [undefined, null, "hi there", "2"])).toEqual([[1, 2, 3, 4], [undefined, null, "hi there", "2"]])
    })
    it("allows callers to pass in their own equality functions", () => {
        expect(diffArray<any>([1, 2, 3, 4], ["1", "2", "3", "4"], (a: any, b: any) => deepEquals(a.toString(), b.toString()))).toEqual([[], []])
    })
})

describe("changedKeys", () => {
    it("finds all the keys in the first object that differ in the second", () => {
        expect(changedKeys({a: 1, b: 2, c: 3}, {a: 1, b: 100, q: 25})).toEqual(["b", "c"])
        expect(changedKeys({a: 1, b: 2, c: 3}, {})).toEqual(["a", "b", "c"])
        expect(changedKeys({}, {a: 1, b: 2, c: 3})).toEqual([])
    })
})

describe("deepFreeze", () => {
    it("freezes all the values in objects passed into it", () => {
        class Foo {
            someFunction() {}
            someProperty: 2
            constructor() {}
        }
        const obj = {
            a: 1,
            b: [
                {
                    c: 1,
                    d: {
                        e: "hi",
                        f: new messages.Error(1, new ServerErrorWithCause("SomeError", "Some error of some kind", []))
                    }
                },
                {
                    g: new messages.ParamInfo("param", "info")
                }
            ],
            h: "idk",
            i: new Foo()
        }
        const frozenObj = deepFreeze(obj)

        expect(() => obj.b.push("foo" as any)).toThrow()

        function checkFrozen(obj: any) {
            expect(Object.isFrozen(obj)).toBeTruthy()
            if (isObject(obj) || Array.isArray(obj)) {
                Object.values(obj).forEach(checkFrozen)
            }
        }

        checkFrozen(frozenObj)
    })
})

describe("equalsByKey", () => {
    it("returns whether two objects are equal based on a subset of keys", () => {
        expect(equalsByKey({a: 1, b: 2, c: 3}, {a: 1, b: 100, q: 25}, ["a"])).toBeTruthy()
        expect(equalsByKey({a: 1, b: 2, c: 3}, {a: 2, b: 2, c: 3}, ["a"])).toBeFalsy()
    })
})

describe("removeKey", () => {
    it("removes a key from an object", () => {
        expect(removeKey({a: 1, b: 2, c: 3}, "a")).toEqual({b: 2, c: 3})
        expect(removeKey({[1]: 1, b: 2, c: 3}, 1)).toEqual({b: 2, c: 3})
        expect(removeKey({[1]: 1, b: 2, c: 3}, "b")).toEqual({[1]: 1, c: 3})
    })
})

describe("mapValues", () => {
    it("applies a function to each value in a map", () => {
        expect(mapValues({a: 1, b: 2, c: 3}, x => x.toString())).toEqual({a: "1", b: "2", c: "3"})
    })
})

describe("arrInsert", () => {
    it("inserts an element into an array at the specified index", () => {
        expect(arrInsert([0, 1, 2, 3, 4], 2, 100)).toEqual([0, 1, 100, 2, 3, 4])
        expect(arrInsert([0, 1, 2, 3, 4], 0, 100)).toEqual([100, 0, 1, 2, 3, 4])
        expect(arrInsert([0, 1, 2, 3, 4], 5, 100)).toEqual([0, 1, 2, 3, 4, 100])
        expect(arrInsert([0, 1, 2, 3, 4], 100, 100)).toEqual([0, 1, 2, 3, 4, 100])
        expect(arrInsert([0, 1, 2, 3, 4], -100, 100)).toEqual([100, 0, 1, 2, 3, 4])
    })
})

describe("arrReplace", () => {
    it("replaces the element of an array at the specified index", () => {
        expect(arrReplace([0, 1, 2, 3, 4], 2, 100)).toEqual([0, 1, 100, 3, 4])
        expect(arrReplace([0, 1, 2, 3, 4], 0, 100)).toEqual([100, 1, 2, 3, 4])
        expect(arrReplace([0, 1, 2, 3, 4], 4, 100)).toEqual([0, 1, 2, 3, 100])
        expect(arrReplace([0, 1, 2, 3, 4], 100, 100)).toEqual([0, 1, 2, 3, 4, 100])
        expect(arrReplace([0, 1, 2, 3, 4], -100, 100)).toEqual([100, 0, 1, 2, 3, 4])
    })
})

describe("arrDelete", () => {
    it("deletes the element of an array at the specified index", () => {
        expect(arrDelete([0, 1, 2, 3, 4], 2)).toEqual([0, 1, 3, 4])
        expect(arrDelete([0, 1, 2, 3, 4], 0)).toEqual([1, 2, 3, 4])
        expect(arrDelete([0, 1, 2, 3, 4], 4)).toEqual([0, 1, 2, 3])
        expect(arrDelete([0, 1, 2, 3, 4], 100)).toEqual([0, 1, 2, 3, 4])
        expect(arrDelete([0, 1, 2, 3, 4], -100)).toEqual([0, 1, 2, 3, 4])
    })
})

describe("arrDeleteItem", () => {
    it("deletes the element of an array matching the provided item", () => {
        expect(arrDeleteItem([0, 1, 2, 3, 4], 2)).toEqual([0, 1, 3, 4])
        expect(arrDeleteItem([0, 1, 2, 3, 4], 0)).toEqual([1, 2, 3, 4])
        expect(arrDeleteItem([0, 1, 2, 3, 4], 4)).toEqual([0, 1, 2, 3])
        expect(arrDeleteItem([0, 1, 2, 3, 4], 100)).toEqual([0, 1, 2, 3, 4])
    })
})

describe("unzip", () => {
    it("turns an array of tuples into a tuple of arrays", () => {
        expect(unzip([[1, 2], [3, 4], [5, 6]])).toEqual([[1, 3, 5], [2, 4, 6]])
        expect(unzip([])).toEqual([[], []])
    })
})

describe("collect", () => {
    it("applies a partial function to an array", () => {
        expect(collect([1, 2, 3, 4, 5, 6], i => i % 2 === 0 ? i : undefined)).toEqual([2, 4, 6])
    })
})

describe("partition", () => {
    it("splits an array into a tuple of, first, the elements which satisfy the predicate and second, the elements which do not", () => {
        expect(partition([1, 2, 3, 4, 5, 6], i => i % 2 === 0)).toEqual([[2, 4, 6], [1, 3, 5]])
    })
})

describe("mapSome", () => {
    it("applies a function to elements of an array that satisfy some predicate", () => {
        expect(mapSome([1, 2, 3, 4, 5, 6], i => i % 2 === 0, i => i * 100)).toEqual([1, 200, 3, 400, 5, 600])
    })
})

describe("Deferred", () => {
    it("is a Promise that can be created and resolved later", done => {
        function iOnlyTakePromises<T>(p: Promise<T>, success: (t: T) => boolean) {
            return p.then(t => {
                expect(success(t)).toBeTruthy()
            })
        }
        const d = new Deferred()
        expect(d.isSettled).toBeFalsy()
        iOnlyTakePromises(d, onlyHi => onlyHi === "hi")
            .then(() => {
                expect(d.isSettled).toBeTruthy()
            })
            .then(() => {
                throw new Error("catch me!")
            })
            .catch(e => {
                expect(e.message).toEqual("catch me!")
            })
            .finally(done)
        d.resolve("hi")
    })
})
