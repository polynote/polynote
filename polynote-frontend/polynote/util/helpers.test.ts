import {
    arrayStartsWith,
    arrDelete,
    arrDeleteFirstItem,
    arrInsert,
    arrReplace,
    changedKeys,
    collect,
    deepCopy,
    deepEquals,
    deepFreeze,
    Deferred,
    diffArray,
    equalsByKey,
    isEmpty,
    isObject,
    linePosAt,
    mapSome,
    mapValues,
    parseQuotedArgs,
    partition,
    removeKeys,
    safeForEach,
    unzip,
    unzip3
} from "./helpers";
import * as messages from "../data/messages";
import * as fc from "fast-check"
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

        fc.assert(
            fc.property(fc.object(), obj => {
                expect(isObject(obj)).toBeTruthy()
            })
        )
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

        fc.assert(
            fc.property(fc.array(fc.nat()).chain(arr => fc.tuple(fc.shuffledSubarray(arr), fc.shuffledSubarray(arr))), ([arrA, arrB]) => {
                const [aNotB, bNotA] = diffArray(arrA, arrB)

                expect(aNotB.every(item => arrA.includes(item) && ! arrB.includes(item))).toBeTruthy()
                expect(bNotA.every(item => arrB.includes(item) && ! arrA.includes(item))).toBeTruthy()
            })
        )
    })
    it("allows callers to pass in their own equality functions", () => {
        expect(diffArray<any>([1, 2, 3, 4], ["1", "2", "3", "4"], (a: any, b: any) => deepEquals(a.toString(), b.toString()))).toEqual([[], []])

        fc.assert(
            fc.property(fc.array(fc.nat()).chain(arr => fc.tuple(fc.shuffledSubarray(arr), fc.shuffledSubarray(arr).map(x => x.map(i => i.toString())))), ([arrA, arrB]) => {
                const [aNotB, bNotA] = diffArray<any>(arrA, arrB, (a: any, b: any) => deepEquals(a.toString(), b.toString()))

                expect(aNotB.every(item => arrA.includes(item) && ! arrB.includes(item))).toBeTruthy()
                expect(bNotA.every(item => arrB.includes(item) && ! arrA.includes(item))).toBeTruthy()
            })
        )
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

        fc.assert(
            fc.property(fc.anything(), anything => {
                checkFrozen(deepFreeze(anything))
            })
        )
    })
})

describe("deepCopy", () => {
    const innerObj = { "four": 4 };
    const arr = [1, 2, 3, innerObj];
    const outerObj = { "a": 1, "b": true, "c": "hi", "d": innerObj, "e": arr, "f": null, "g": undefined }
    it ("returns a deep copy of an object", () => {
        const cp = deepCopy(outerObj);
        expect(deepEquals(outerObj, cp)).toBeTruthy();
        expect(outerObj === cp).toBeFalsy();
        expect(outerObj.d === cp.d).toBeFalsy();
        expect(outerObj.e === cp.e).toBeFalsy();
        expect(outerObj.e[3] === cp.e[3]).toBeFalsy();
    })
})

describe("equalsByKey", () => {
    it("returns whether two objects are equal based on a subset of keys", () => {
        expect(equalsByKey({a: 1, b: 2, c: 3}, {a: 1, b: 100, q: 25}, ["a"])).toBeTruthy()
        expect(equalsByKey({a: 1, b: 2, c: 3}, {a: 2, b: 2, c: 3}, ["a"])).toBeFalsy()
        const empty: {a?: number, b?: number, c?: number} = {}
        expect(equalsByKey(empty, {a: 2, b: 2, c: 3}, ["a"])).toBeFalsy()

        const generator=
            fc.tuple(fc.object(), fc.anything(), fc.boolean()) // generate [random object, a random value, whether to use random value]
                .filter(([obj, _]) => ! isEmpty(obj)) // filter empty objects
                .chain(([obj, anything, useAnything]) => {
                    // create an arbitrary subset of the random object
                    const keys = Object.keys(obj);
                    // arbitrarily create a sub-object of this one
                    const newObj = fc.subarray(keys, {minLength: 1})
                        .map(keys =>
                            Object.fromEntries(keys.map(k => {
                                const maybeValue = useAnything ? anything : obj[k]
                                return [k, maybeValue]
                            })));
                    const whichObj = fc.oneof(fc.constantFrom(obj), newObj);
                    return fc.tuple(whichObj, whichObj)
                })
        fc.assert(
            fc.property(generator, ([a, b]) => {
                const aKeys = Object.keys(a);
                const bKeys = Object.keys(b);
                const sharedKeys = aKeys.filter(x => bKeys.includes(x))

                if (sharedKeys.length > 0) {
                    // @ts-ignore  (bypass NonEmptyArray type check)
                    expect(equalsByKey(a, b, sharedKeys)).toEqual(sharedKeys.every(k => deepEquals(a[k], b[k])))
                }
            })
        )
    })
})

describe("removeKey", () => {
    it("removes a key from an object", () => {
        expect(removeKeys({a: 1, b: 2, c: 3}, "a")).toEqual({b: 2, c: 3})
        expect(removeKeys({[1]: 1, b: 2, c: 3}, 1)).toEqual({b: 2, c: 3})
        expect(removeKeys({[1]: 1, b: 2, c: 3}, "b")).toEqual({[1]: 1, c: 3})

        const generator = fc.object() // generate object
            .chain(obj => {
                // pick keys to remove
                const remove = fc.subarray(Object.keys(obj))
                return fc.tuple(fc.constant(obj), remove)
            })
        fc.assert(
            fc.property(generator, ([obj, remove]) => {
                const filteredObj = removeKeys(obj, remove)
                expect(Object.keys(filteredObj).every(k => ! remove.includes(k))).toBeTruthy()
                expect(Object.entries(filteredObj).every(([k, v]) => deepEquals(v, obj[k])))
            })
        )
    })
})

describe("mapValues", () => {
    it("applies a function to each value in a map", () => {
        expect(mapValues({a: 1, b: 2, c: 3}, x => x.toString())).toEqual({a: "1", b: "2", c: "3"})

        fc.assert(
            fc.property(fc.object(), obj => {
                const fun = (x: any) => {
                    try { return x.toString() } catch (e) { return e}
                }
                const mapped = mapValues(obj, x => fun(x))
                expect(Object.entries(mapped)).toEqual(Object.entries(obj).map(([k, v]) => [k, fun(v)]))
            })
        )
    })
})

describe("arrInsert", () => {
    it("inserts an element into an array at the specified index", () => {
        expect(arrInsert([0, 1, 2, 3, 4], 2, 100)).toEqual([0, 1, 100, 2, 3, 4])
        expect(arrInsert([0, 1, 2, 3, 4], 0, 100)).toEqual([100, 0, 1, 2, 3, 4])
        expect(arrInsert([0, 1, 2, 3, 4], 5, 100)).toEqual([0, 1, 2, 3, 4, 100])
        expect(arrInsert([0, 1, 2, 3, 4], 100, 100)).toEqual([0, 1, 2, 3, 4, 100])
        expect(arrInsert([0, 1, 2, 3, 4], -100, 100)).toEqual([100, 0, 1, 2, 3, 4])

        expect(arrInsert([{},"",{},{},[],{},null], 8, false)).toEqual([{},"",{},{},[],{},null, false])

        const generator = fc.tuple(fc.array(fc.anything()), fc.integer(), fc.anything())
        fc.assert(
            fc.property(generator, ([arr, idx, ins]) => {
                const inserted = arrInsert(arr, idx, ins)

                // arrInsert maps all indices to [0, arr.length]
                if (idx >= inserted.length) {
                    idx = inserted.length - 1
                }
                if (idx < 0) {
                    idx = 0
                }

                expect(inserted.length).toEqual(arr.length + 1)
                expect(inserted[idx]).toEqual(ins)
                expect(arr.every(i => inserted.includes(i))).toBeTruthy()
            })
        )
    })
})

describe("arrReplace", () => {
    it("replaces the element of an array at the specified index", () => {
        expect(arrReplace([0, 1, 2, 3, 4], 2, 100)).toEqual([0, 1, 100, 3, 4])
        expect(arrReplace([0, 1, 2, 3, 4], 0, 100)).toEqual([100, 1, 2, 3, 4])
        expect(arrReplace([0, 1, 2, 3, 4], 4, 100)).toEqual([0, 1, 2, 3, 100])
        expect(arrReplace([0, 1, 2, 3, 4], 100, 100)).toEqual([0, 1, 2, 3, 4, 100])
        expect(arrReplace([0, 1, 2, 3, 4], -100, 100)).toEqual([100, 0, 1, 2, 3, 4])

        const generator = fc.tuple(fc.array(fc.anything()), fc.integer(), fc.anything())
        fc.assert(
            fc.property(generator, ([arr, idx, ins]) => {
                const replaced = arrReplace(arr, idx, ins)

                // arrReplace actually inserts if the index is too large or small
                if (idx >= arr.length || idx < 0) {
                    expect(replaced.length).toEqual(arr.length + 1)
                } else {
                    expect(replaced.length).toEqual(arr.length)
                }


                // arrReplace maps all indices to [0, replaced.length]
                if (idx >= replaced.length) {
                    idx = replaced.length - 1
                }
                if (idx < 0) {
                    idx = 0
                }

                expect(replaced[idx]).toEqual(ins)
                expect(replaced.every((item, index) => {
                    if (idx === index) return true
                    else return arr.includes(item)
                })).toBeTruthy()
            })
        )
    })
})

describe("arrDelete", () => {
    it("deletes the element of an array at the specified index", () => {
        expect(arrDelete([0, 1, 2, 3, 4], 2)).toEqual([0, 1, 3, 4])
        expect(arrDelete([0, 1, 2, 3, 4], 0)).toEqual([1, 2, 3, 4])
        expect(arrDelete([0, 1, 2, 3, 4], 4)).toEqual([0, 1, 2, 3])
        expect(arrDelete([0, 1, 2, 3, 4], 100)).toEqual([0, 1, 2, 3, 4])
        expect(arrDelete([0, 1, 2, 3, 4], -100)).toEqual([0, 1, 2, 3, 4])

        const generator = fc.tuple(fc.array(fc.anything()), fc.integer())
        fc.assert(
            fc.property(generator, ([arr, idx]) => {
                const deleted = arrDelete(arr, idx)

                // arrDelete does nothing if the provided idx is out of bounds
                if (idx >= arr.length || idx < 0) {
                    expect(deleted.length).toEqual(arr.length)
                } else {
                    expect(deleted.length).toEqual(arr.length - 1)
                }

                expect(deleted.every(item => arr.includes(item))).toBeTruthy()
            })
        )
    })
})

describe("arrDeleteItem", () => {
    it("deletes the element of an array matching the provided item", () => {
        expect(arrDeleteFirstItem([0, 1, 2, 3, 4], 2)).toEqual([0, 1, 3, 4])
        expect(arrDeleteFirstItem([0, 1, 2, 3, 4], 0)).toEqual([1, 2, 3, 4])
        expect(arrDeleteFirstItem([0, 1, 2, 3, 4], 4)).toEqual([0, 1, 2, 3])
        expect(arrDeleteFirstItem([0, 1, 2, 3, 4], 100)).toEqual([0, 1, 2, 3, 4])
        expect(arrDeleteFirstItem([{a: 1, b: 2}, {a: 2, b: 2}], {a: 1, b: 2})).toEqual([{a: 2, b: 2}])

        const generator = fc.array(fc.anything())
            .chain(arr => {
                // select an item to delete
                const item = fc.subarray(arr)
                return fc.tuple(
                    fc.oneof(fc.constant(arr), fc.constant([...arr, ...arr])), // keep the generated array but duplicate it occasionally.
                    fc.oneof(item, fc.anything())) // the item is not guaranteed to exist in the array
            })
        fc.assert(
            fc.property(generator, ([arr, item]) => {
                const deleted = arrDeleteFirstItem(arr, item)

                const shouldDelete = arr.some(i => deepEquals(i, item))

                if (shouldDelete) {
                    expect(deleted.length).toEqual(arr.length - 1)
                } else {
                    expect(deleted.length).toEqual(arr.length)
                }

                expect(deleted.every(item => arr.includes(item))).toBeTruthy()
            })
        )
    })
})

describe("unzip", () => {
    it("turns an array of tuples into a tuple of arrays", () => {
        expect(unzip([[1, 2], [3, 4], [5, 6]])).toEqual([[1, 3, 5], [2, 4, 6]])
        expect(unzip([])).toEqual([[], []])

        fc.assert(
            fc.property(fc.array(fc.tuple(fc.anything(), fc.anything())), arr => {
                const unzipped = unzip(arr);
                const [a1, a2] = unzipped;
                expect(a1.length === a2.length)
                expect(arr).toEqual(a1.map((i, idx) => [i, a2[idx]]))
            })
        )
    })
})

describe("unzip3", () => {
    it("turns an array of tuples into a tuple of arrays", () => {
        fc.assert(
            fc.property(fc.array(fc.tuple(fc.anything(), fc.anything(), fc.anything())), arr => {
                const unzipped = unzip3(arr);
                const [a1, a2, a3] = unzipped;
                expect(a1.length === a2.length && a1.length === a3.length)
                expect(arr).toEqual(a1.map((i, idx) => [i, a2[idx], a3[idx]]))
            })
        )
    })
})

describe("collect", () => {
    it("applies a partial function to an array", () => {
        expect(collect([1, 2, 3, 4, 5, 6], i => i % 2 === 0 ? i : undefined)).toEqual([2, 4, 6])

        const prop = fc.array(fc.nat())
            .chain(arr => {
                return fc.constant([...arr, ...arr.map(i => -i)])  // ensure that half the numbers are negative
            })
        fc.assert(
            fc.property(prop, arr => {
                const pos = collect(arr, i => i > 0 ? i : undefined)

                expect(pos.every(i => i > 0)).toBeTruthy()
                expect(pos.length).toEqual(arr.filter(i => i !== 0).length / 2)
            })
        )
    })
})

describe("partition", () => {
    it("splits an array into a tuple of, first, the elements which satisfy the predicate and second, the elements which do not", () => {
        expect(partition([1, 2, 3, 4, 5, 6], i => i % 2 === 0)).toEqual([[2, 4, 6], [1, 3, 5]])

        const prop = fc.array(fc.nat())
            .chain(arr => {
                return fc.constant([...arr, ...arr.map(i => -i)])  // ensure that half the numbers are negative
            })
        fc.assert(
            fc.property(prop, arr => {
                const [pos, neg] = partition(arr, i => i >= 0)

                expect(neg.every(i => i < 0)).toBeTruthy()
                expect(pos.every(i => i >= 0)).toBeTruthy()
                expect(pos.filter(i => i !== 0).length).toEqual(neg.length) // half the numbers (barring zero) are negative
            })
        )
    })
})

describe("mapSome", () => {
    it("applies a function to elements of an array that satisfy some predicate", () => {
        expect(mapSome([1, 2, 3, 4, 5, 6], i => i % 2 === 0, i => i * 100)).toEqual([1, 200, 3, 400, 5, 600])

        fc.assert(
            fc.property(fc.array(fc.anything()), arr => {
                const fun = (num: number) => num * 100
                const pred = (x: any) => (typeof x === "number" && !isNaN(x))
                const mapped = mapSome(arr, pred, fun)

                expect(mapped.every((i, idx) => {
                    if (pred(i)) {
                        return i === fun(arr[idx] as number)
                    } else {
                        return pred(i) === pred(arr[idx]) && deepEquals(i, arr[idx])
                    }
                })).toBeTruthy()
            })
        )
    })
})

describe("arrayStartsWith", () => {
    it("Checks whether an array starts with another array", () => {
        expect(arrayStartsWith([1, 2, 3, 4], [1])).toBeTruthy()
        expect(arrayStartsWith([1, 2, 3, 4], [1, 2])).toBeTruthy()
        expect(arrayStartsWith([1, 2, 3, 4], [1, 2, 3])).toBeTruthy()
        expect(arrayStartsWith([1, 2, 3, 4], [1, 2, 3, 4])).toBeTruthy()
        expect(arrayStartsWith([1, 2, 3, 4], [1, 2, 3, 4, 5])).toBeFalsy()
        expect(arrayStartsWith([1, 2, 3, 4], [0, 1, 2, 3])).toBeFalsy()
        expect(arrayStartsWith([1, 2, 3, 4], [])).toBeTruthy()
        expect(arrayStartsWith([], [])).toBeTruthy()
        expect(arrayStartsWith([1], [])).toBeTruthy()
        expect(arrayStartsWith([], [1])).toBeFalsy()

        const prop = fc.array(fc.anything())
            .chain(arr => {
                const arrStart = fc.nat(arr.length).map(n => arr.slice(0, n).map(i => {
                    // stupid shallow clone to break `===` with members of `arr`
                    if (isObject(i)) {
                        return {...i}
                    } else if (Array.isArray(i)) {
                        return (i as Array<any>).slice(0)
                    } return i
                }))
                return fc.tuple(fc.constant(arr), arrStart)
            })
        fc.assert(
            fc.property(prop, ([arr, arrStart]) => {
                expect(arrayStartsWith(arr, arrStart)).toBeTruthy()
            })
        )
    })
})

describe("parseQuotedArgs", () => {
    it ("parses simple space-separated arguments", () => {
        expect(parseQuotedArgs(`first second third`)).toEqual(['first', 'second', 'third'])
    })

    it ("allows quoting things", () => {
        expect(parseQuotedArgs(`first "two words" third`)).toEqual(['first', 'two words', 'third'])
    })

    it ("allows escaping things inside quotes", () => {
        expect(parseQuotedArgs(`first "there is an \\" escaped quote" third`)).toEqual(['first', `there is an " escaped quote`, 'third'])
    })
})

describe("Deferred", () => {
    it("is a Promise that can be created and resolved later", done => {
        function checkPromise<T>(p: Promise<T>, check: (t: T) => boolean) {
            return p.then(t => {
                expect(check(t)).toBeTruthy()
            })
        }
        const d = new Deferred()
        expect(d.isSettled).toBeFalsy()
        checkPromise(d, onlyHi => onlyHi === "hi")
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

describe("linePosAt", () => {
    it("determines line position given a string and an offset", () => {
        expect(linePosAt("", 0)).toEqual([0, 0])
        expect(linePosAt("", 100)).toEqual([0, 0])
        expect(linePosAt("", -1)).toEqual([0, 0])
        expect(linePosAt("\n\n\n\n\n", 3)).toEqual([3, 0])
        expect(linePosAt("\n\n\n\n\n", 0)).toEqual([0, 0])
        expect(linePosAt("\n\n\n\n\n", -1)).toEqual([0, 0])
        expect(linePosAt("\n\n\n\n\n", 100)).toEqual([4, 0])
        expect(linePosAt("abcde", 3)).toEqual([0, 3])
        expect(linePosAt("abcde", 0)).toEqual([0, 0])
        expect(linePosAt("abcde", -1)).toEqual([0, 0])
        expect(linePosAt("abcde", 100)).toEqual([0, 4])
        expect(linePosAt("abc\ndef\nghi", 5)).toEqual([1, 1])
        expect(linePosAt("abc\ndef\nghi", 11)).toEqual([2, 2])
        expect(linePosAt("abc\ndef\nghi", -1)).toEqual([0, 0])
        expect(linePosAt("abc\ndef\nghi", 100)).toEqual([2, 2])
    })
})

describe("safeForEach", () => {

    it("iterates the elements in reverse order", () => {
        const arr: number[] = [1, 2, 3, 4, 5]
        const arr2: number[] = [];

        safeForEach(arr, elem => arr2.push(elem));

        expect(arr2).toEqual([5, 4, 3, 2, 1]);
    })

    it("works even if current item gets removed", () => {

        const arr: number[] = [1, 2, 3, 4, 5];
        const arr2: number[] = [];

        safeForEach(arr, elem => {
            arr2.push(elem);
            arr.splice(arr.indexOf(elem), 1);
        });

        expect(arr2).toEqual([5, 4, 3, 2, 1]);
    })

})