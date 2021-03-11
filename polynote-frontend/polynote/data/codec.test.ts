import * as fc from "fast-check"
import {
    arrayCodec,
    bool,
    bufferCodec,
    Codec,
    combined,
    DataReader,
    DataWriter,
    either,
    float32,
    float64,
    int16,
    int32,
    int64,
    int8,
    ior,
    mapCodec,
    nullCodec,
    NumberGuards,
    optional,
    Pair,
    shortStr,
    str,
    tinyStr,
    uint16,
    uint32,
    uint8
} from "./codec";
import {Either, Ior} from "./codec_types";

describe("A Codec", () => {

    const roundtrip = <T>(codec: Codec<T>, value: T) => {
        expect(Codec.decode(codec, Codec.encode(codec, value))).toEqual(value)
    }

    const safeUnicode = () => fc.unicode().filter(char => char !== "﻿") // Zero-width non-breaking space doesn't roundtrip through the TextEncoder.

    test("can be defined", () => {
        const codec = new class extends Codec<number> {
            decode(reader: DataReader): number {
                return reader.readUint8();
            }

            encode(obj: number, writer: DataWriter): void {
                writer.writeUint8(obj)
            }
        }

        roundtrip(codec, 3)
    })

    describe("supports", () => {

        describe("strings", () => {
            test("tinyStr", () => {
                fc.assert(
                    fc.property(fc.string(), value => {
                        roundtrip(tinyStr, value)
                    })
                )
                fc.assert(
                    fc.property(safeUnicode(), value => {
                        roundtrip(tinyStr, value)
                    })
                )
                roundtrip(tinyStr, "a".repeat(2**8 - 1))
                expect(() => roundtrip(tinyStr, "a".repeat(2**8))).toThrow("256 is greater than 255")
            })

            test("shortStr", () => {
                fc.assert(
                    fc.property(fc.string(), value => {
                        roundtrip(shortStr, value)
                    })
                )
                fc.assert(
                    fc.property(safeUnicode(), value => {
                        roundtrip(shortStr, value)
                    })
                )
                roundtrip(shortStr, "a".repeat(2**8))
                roundtrip(shortStr, "a".repeat(2**16 - 1))
                expect(() => roundtrip(shortStr, "a".repeat(2**16))).toThrow("65536 is greater than 65535")
            })

            test("str", () => {
                fc.assert(
                    fc.property(fc.string(), value => {
                        roundtrip(str, value)
                    })
                )
                fc.assert(
                    fc.property(safeUnicode(), value => {
                        roundtrip(str, value)
                    })
                )
                roundtrip(str, "a".repeat(2**16))
            })
        })

        describe("numbers", () => {

            const ints = {uint8, uint16, uint32, int8, int16, int32}

            // all the `number` int codecs
            Object.entries(ints).forEach(([codecName, codec]) => {
                test(codecName, () => {
                    const props = NumberGuards[codecName as keyof typeof NumberGuards]
                    fc.assert(
                        fc.property(fc.integer(props.min as number, props.max as number), value => {
                            roundtrip(codec, value)
                        })
                    )
                })
            })

            // int64 uses bigint so it's special
            test("int64", () => {
                const props = NumberGuards["int64"]
                fc.assert(
                    fc.property(fc.bigInt(props.min as bigint, props.max as bigint), value => {
                        roundtrip(int64, value)
                    })
                )
            })

            // float32 uses the `float` generator
            test("float32", () => {
                const props = NumberGuards["float32"]
                fc.assert(
                    fc.property(fc.float({min: props.min as number, max: props.max as number, next: true}), value => {
                        roundtrip(float32, value)
                    })
                )
            })

            // float64 uses the `double` generator
            test("float64", () => {
                const props = NumberGuards["float64"]
                fc.assert(
                    fc.property(fc.double({min: props.min as number, max: props.max as number, next: true}), value => {
                        roundtrip(float64, value)
                    })
                )
            })
        })

        test("bools", () => {
            roundtrip(bool, false)
            roundtrip(bool, true)
        })

        test("nulls", () => {
            roundtrip(nullCodec, null)
        })

        test("buffers", () => {
            const buf = new DataView(new ArrayBuffer(10))
            buf.setUint8(1, 42)
            roundtrip(bufferCodec, buf.buffer)
        })

        test("combinations", () => {
            class IntStr {
                constructor(readonly int: number, readonly str: string) {}
                static unapply(inst: IntStr): [number, string] {
                    return [inst.int, inst.str]
                }
            }
            const codec = combined(int16, str).to(IntStr)
            roundtrip(codec, new IntStr(0, "hi"))
        })

        test("arrays", () => {
            roundtrip(arrayCodec(int8, int8), [])
            roundtrip(arrayCodec(int8, int8), [-100, 0, 1])
            expect(() => roundtrip(arrayCodec(uint8, int8), new Array(2**8))).toThrow("256 is greater than 255")
            roundtrip(arrayCodec(uint16, int8), new Array(2**8).fill(0))
        })

        test("iors", () => {
            roundtrip(ior(str, int8), Ior.both("left", 0))
            roundtrip(ior(str, int8), Ior.left("left"))
            roundtrip(ior(str, int8), Ior.right(0))
        })

        test("pairs", () => {
            roundtrip(Pair.codec(str, int8), new Pair("left", 0))
        })

        test("maps", () => {
            roundtrip(mapCodec(uint8, shortStr, int8), {})
            roundtrip(mapCodec(uint8, shortStr, int8), {a: 1, b: 0})
        })

        test("optionals", () => {
            roundtrip(optional(str), "hi")
            roundtrip(optional(str), null)
        })

        test("eithers", () => {
            roundtrip(either(str, uint8), Either.left("hi"))
            roundtrip(either(str, uint8), Either.right(0))
        })
    })

})

