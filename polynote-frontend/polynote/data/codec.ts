'use strict';

import {Both, Either, ExtractorConstructor, Ior, Left, Right} from "./codec_types";

// Add `getBigInt64` to DataView
declare global {
    interface DataView {
        getBigInt64(byteOffset: number, littleEndian?: boolean): bigint;
        setBigInt64(byteOffset: number, value: bigint, littleEndian?: boolean): void;
    }
}

export class DataReader {
    buffer: DataView;
    offset: number;
    private stringDecoder = new TextDecoder('utf-8');

    constructor(buf: ArrayBuffer | DataView) {
        if (buf instanceof ArrayBuffer) {
            this.buffer = new DataView(buf);
        } else {
            this.buffer = buf;
        }
        this.offset = 0;
    }

    readUint8() {
        return this.buffer.getUint8(this.offset++);
    }

    readBoolean() {
        return !!this.readUint8();
    }

    readInt8() {
        return this.buffer.getInt8(this.offset++);
    }

    readUint16() {
        const result = this.buffer.getUint16(this.offset);
        this.offset += 2;
        return result;
    }

    readInt16() {
        const result = this.buffer.getInt16(this.offset);
        this.offset += 2;
        return result;
    }

    readUint32() {
        const result = this.buffer.getUint32(this.offset);
        this.offset += 4;
        return result;
    }

    readInt32() {
        const result = this.buffer.getInt32(this.offset);
        this.offset += 4;
        return result;
    }

    // NOTE: JavaScript can't represent all 64-bit integers...
    readInt64() {
        const result = this.buffer.getBigInt64(this.offset);
        this.offset += 8;
        return result;
    }

    // For e.g. plotting, it's probably OK if giant numbers lose a bit of precision.
    readUnsafeInt64() {
        const result = this.readInt64();
        return Number(result);
    }

    readFloat32() {
        const result = this.buffer.getFloat32(this.offset);
        this.offset += 4;
        return result;
    }

    readFloat64() {
        const result = this.buffer.getFloat64(this.offset);
        this.offset += 8;
        return result;
    }

    readString() {
        const len = this.readInt32();
        if (len >= 0) {
            return this.readStringBytes(len);
        } else {
            return null;
        }
    }

    readShortString() {
        const len = this.readUint16();
        return this.readStringBytes(len);
    }

    readTinyString() {
        const len = this.readUint8();
        return this.readStringBytes(len);
    }

    readStringBytes(len: number) {
        const end = this.offset + len;
        const str = this.stringDecoder.decode(this.buffer.buffer.slice(this.offset, end));
        this.offset = end;
        return str;
    }

    readBuffer() {
        const len = this.readUint32();
        const end = this.offset + len;
        const buf = this.buffer.buffer.slice(this.offset, end);
        this.offset = end;
        return buf;
    }
}

export const NumberGuards = {
    uint8: {
        max: 2**8 - 1,
        min: 0
    },
    uint16: {
        max: 2**16 - 1,
        min: 0
    },
    uint32: {
        max: 2**32 - 1,
        min: 0
    },
    int8: {
        max: 2**7 - 1,
        min: -(2**7)
    },
    int16: {
        max: 2**15 - 1,
        min: -(2**15)
    },
    int32: {
        max: 2**31 - 1,
        min: -(2**31)
    },
    int64: {
        max: 2n ** 63n - 1n,
        min: - (2n ** 63n)
    },
    float32: {
        max: 3.4028234663852886e+38, // java.lang.float.MAX_VALUE
        min: -3.4028234663852886e+38
    },
    float64: {
        max: 1.7976931348623157e+308, // java.lang.Double.MAX_VALUE
        min: -1.7976931348623157e+308
    }
}

export class DataWriter {
    chunkSize: number;
    buffer: ArrayBuffer;
    dataView: DataView;
    offset: number;

    private stringEncoder = new TextEncoder();
    constructor(chunkSize?: number) {
        this.chunkSize = chunkSize ?? 1024;
        this.buffer = new ArrayBuffer(this.chunkSize);
        this.dataView = new DataView(this.buffer);
        this.offset = 0;
    }

    ensureBufSize(newSize: number) {
        if (this.buffer.byteLength < newSize) {
            const newBuf = new ArrayBuffer(this.buffer.byteLength + this.chunkSize * Math.ceil((newSize - this.buffer.byteLength) / this.chunkSize));
            new Uint8Array(newBuf).set(new Uint8Array(this.buffer));
            this.buffer = newBuf;
            this.dataView = new DataView(this.buffer);
        }
    }

    numberGuard(key: keyof typeof NumberGuards, value: number | bigint) {
        const max: number | bigint = NumberGuards[key].max;
        if (value > max ) {
            throw new Error(`${value} is greater than ${max}`)
        }
        const min: number | bigint = NumberGuards[key].min;
        if (value < min ) {
            throw new Error(`${value} is less than ${min}`)
        }
    }

    writeUint8(value: number) {
        this.numberGuard("uint8", value);
        this.ensureBufSize(this.buffer.byteLength + 1);
        this.dataView.setUint8(this.offset++, value);
    }

    writeInt8(value: number) {
        this.numberGuard("int8", value);
        this.ensureBufSize(this.buffer.byteLength + 1);
        this.dataView.setInt8(this.offset++, value);
    }

    writeUint16(value: number) {
        this.numberGuard("uint16", value);
        this.ensureBufSize(this.buffer.byteLength + 2);
        this.dataView.setUint16(this.offset, value);
        this.offset += 2;
    }

    writeInt16(value: number) {
        this.numberGuard("int16", value);
        this.ensureBufSize(this.buffer.byteLength + 2);
        this.dataView.setInt16(this.offset, value);
        this.offset += 2;
    }

    writeUint32(value: number) {
        this.numberGuard("uint32", value);
        this.ensureBufSize(this.buffer.byteLength + 4);
        this.dataView.setUint32(this.offset, value);
        this.offset += 4;
    }

    writeInt32(value: number) {
        this.numberGuard("int32", value);
        this.ensureBufSize(this.buffer.byteLength + 4);
        this.dataView.setInt32(this.offset, value);
        this.offset += 4;
    }

    writeInt64(value: bigint) {
        this.numberGuard("int64", value);
        this.ensureBufSize(this.buffer.byteLength + 8);
        this.dataView.setBigInt64(this.offset, BigInt(value));
        this.offset += 8;
    }

    writeFloat32(value: number) {
        this.numberGuard("float32", value);
        this.ensureBufSize(this.buffer.byteLength + 4);
        this.dataView.setFloat32(this.offset, value);
        this.offset += 4;
    }

    writeFloat64(value: number) {
        this.numberGuard("float64", value);
        this.ensureBufSize(this.buffer.byteLength + 8);
        this.dataView.setFloat64(this.offset, value);
        this.offset += 8;
    }

    writeString(value: string | null) {
        if (value === null) {
            this.writeInt32(-1);
        } else {
            const bytes = this.stringEncoder.encode(value);
            this.ensureBufSize(this.buffer.byteLength + bytes.length + 4);
            this.writeInt32(bytes.length);
            this.writeStrBytes(bytes, 0xFFFFFFFF);
        }
    }

    writeShortString(value: string) {
        const bytes = this.stringEncoder.encode(value);
        this.ensureBufSize(this.buffer.byteLength + bytes.length + 2);
        this.writeUint16(bytes.length);
        this.writeStrBytes(bytes, 0xFFFF);
    }

    writeTinyString(value: string) {
        const bytes = this.stringEncoder.encode(value);
        this.ensureBufSize(this.buffer.byteLength + bytes.length + 2);
        this.writeUint8(bytes.length);
        this.writeStrBytes(bytes, 0xFF);
    }

    writeStrBytes(bytes: ArrayLike<number>, maxLen: number) {
        if (bytes.length > maxLen) {
            throw `String byte length exceeds ${maxLen}`;
        }
        new Uint8Array(this.buffer, this.offset, bytes.length).set(bytes);
        this.offset += bytes.length;
    }

    // TODO: is there a better supertype for this?
    writeBuffer(value: ArrayLike<number> & { readonly byteLength: number }) {
        const len = value.byteLength;
        this.ensureBufSize(this.buffer.byteLength + len + 4);
        this.writeUint32(value.byteLength);
        this.offset += 4;
        new Uint8Array(this.buffer, this.offset, len).set(value);
    }

    finish() {
        return new Uint8Array(this.buffer, 0, this.offset).slice(0, this.offset).buffer;
    }
}

export abstract class Codec<T> {
    static map<A, B>(codec: Codec<A>, to: (a: A) => B, from: (b: B) => A) {
        return Object.freeze({
            encode: (value: B, writer: DataWriter) => codec.encode(from(value), writer),
            decode: (reader: DataReader) => to(codec.decode(reader))
        });
    }

    static encode<U>(codec: Codec<U>, obj: U) {
        const writer = new DataWriter();
        codec.encode(obj, writer);
        return writer.finish();
    }

    static decode<U>(codec: Codec<U>, buf: ArrayBuffer | DataView) {
        const reader = new DataReader(buf);
        return codec.decode(reader);
    }

    abstract encode(obj: T, writer: DataWriter): void;
    abstract decode(reader: DataReader): T;
}

export const str: Codec<string | null> = Object.freeze({
    encode: (str: string, writer: DataWriter) => writer.writeString(str),
    decode: (reader: DataReader) => reader.readString()
});

export const shortStr: Codec<string> = Object.freeze({
    encode: (str: string, writer: DataWriter) => writer.writeShortString(str),
    decode: (reader: DataReader) => reader.readShortString()
});

export const tinyStr: Codec<string> = Object.freeze({
    encode: (str: string, writer: DataWriter) => writer.writeTinyString(str),
    decode: (reader: DataReader) => reader.readTinyString()
});

export const uint8: Codec<number> = Object.freeze({
    encode: (value: number, writer: DataWriter) => writer.writeUint8(value),
    decode: (reader: DataReader) => reader.readUint8()
});

export const int8: Codec<number> = Object.freeze({
    encode: (value: number, writer: DataWriter) => writer.writeInt8(value),
    decode: (reader: DataReader) => reader.readInt8()
});

export const uint16: Codec<number>  = Object.freeze({
    encode: (value: number, writer: DataWriter) => writer.writeUint16(value),
    decode: (reader: DataReader) => reader.readUint16()
});

export const int16: Codec<number>  = Object.freeze({
    encode: (value: number, writer: DataWriter) => writer.writeInt16(value),
    decode: (reader: DataReader) => reader.readInt16()
});

export const uint32: Codec<number>  = Object.freeze({
    encode: (value: number, writer: DataWriter) => writer.writeUint32(value),
    decode: (reader: DataReader) => reader.readUint32()
});

export const int32: Codec<number>  = Object.freeze({
    encode: (value: number, writer: DataWriter) => writer.writeInt32(value),
    decode: (reader: DataReader) => reader.readInt32()
});

export const int64: Codec<bigint>  = Object.freeze({
    encode: (value: bigint, writer: DataWriter) => writer.writeInt64(value),
    decode: (reader: DataReader) => reader.readInt64()
});

export const float32: Codec<number>  = Object.freeze({
    encode: (value: number, writer: DataWriter) => writer.writeFloat32(value),
    decode: (reader: DataReader) => reader.readFloat32()
});

export const float64: Codec<number>  = Object.freeze({
    encode: (value: number, writer: DataWriter) => writer.writeFloat64(value),
    decode: (reader: DataReader) => reader.readFloat64()
});

export const bool: Codec<boolean> = Object.freeze({
    encode: (value: boolean, writer: DataWriter) => value ? writer.writeUint8(255) : writer.writeUint8(0),
    decode: (reader: DataReader) => !!reader.readUint8()
});

export const nullCodec: Codec<null> = Object.freeze({
    encode: (value: null, writer: DataWriter) => undefined,
    decode: (reader: DataReader) => null
});

export const bufferCodec: Codec<ArrayBuffer> = Object.freeze({
    // TODO: hope `length` is correct here!
    encode: (value: ArrayBuffer, writer: DataWriter) => writer.writeBuffer({...value, length: value.byteLength}),
    decode: (reader: DataReader) => reader.readBuffer()
});

class CombinedCodec {
    codecs: Codec<any>[];
    constructor(...codecs: Codec<any>[]) {
        this.codecs = codecs;
    }

    to<T>(constr: ExtractorConstructor<T>): Codec<T> {
        const codecs = this.codecs;
        const encode = (value: T, writer: DataWriter) => {
            const values = constr.unapply(value);
            for (let i = 0; i < codecs.length; i++) {
                codecs[i].encode(values[i], writer);
            }
        };

        const decode = (reader: DataReader) => {
            const values = this.codecs.map((codec) => codec.decode(reader));
            return new constr(...values);
        };

        return Object.freeze({
            encode: encode,
            decode: decode
        });
    }
}

export function combined(...codecs: Codec<any>[]) {
    return new CombinedCodec(...codecs);
}

export function arrayCodec<T>(lengthCodec: Codec<number>, elementCodec: Codec<T>): Codec<T[]> {
    const encode = (value: T[], writer: DataWriter) => {
        lengthCodec.encode(value.length, writer);
        for (let i = 0; i < value.length; i++) {
            elementCodec.encode(value[i], writer);
        }
    };

    const decode = (reader: DataReader): T[] => {
        const length = lengthCodec.decode(reader);
        const arr = new Array(length);
        for (let i = 0; i < length; i++) {
            arr[i] = elementCodec.decode(reader);
        }
        return arr;
    };

    return Object.freeze({
        encode: encode,
        decode: decode
    });
}

export function ior<L, R>(leftCodec: Codec<L>, rightCodec: Codec<R>) {
    const encode = (value: Left<L> | Right<R> | Both<L, R>, writer: DataWriter) => {
        if (Ior.isBoth(value)) {
            writer.writeInt8(2);
            leftCodec.encode(value.left, writer);
            rightCodec.encode(value.right, writer);
        } else if (Ior.isLeft(value)) {
            writer.writeInt8(0);
            leftCodec.encode(value.left, writer);
        } else {
            writer.writeInt8(1);
            rightCodec.encode(value.right, writer);
        }
    };

    const decode = (reader: DataReader) => {
        const discriminator = reader.readUint8();
        switch (discriminator) {
            case 0:
                return Ior.left(leftCodec.decode(reader));
            case 1:
                return Ior.right(rightCodec.decode(reader));
            case 2:
                const left = leftCodec.decode(reader);
                const right = rightCodec.decode(reader);
                return Ior.both(left, right);
            default:
                throw `Invalid discriminator for ior (${discriminator})`
        }
    };

    return Object.freeze({encode, decode})
}

export class Pair<A, B> {
    static codec = <A, B>(firstCodec: Codec<A>, secondCodec: Codec<B>) => combined(firstCodec, secondCodec).to<Pair<A,B>>(Pair);
    static unapply<A, B>(inst: Pair<A, B>): [A, B] {
        return [inst.first, inst.second];
    }

    constructor(public first: A, public second: B) {
        Object.freeze(this);
    }
}

export function mapCodec<K extends string, V>(lengthCodec: Codec<number>, keyCodec: Codec<K>, valueCodec: Codec<V>) {

    const underlying = arrayCodec(lengthCodec, Pair.codec(keyCodec, valueCodec));

    const encode = (value: Record<K, V>, writer: DataWriter) => {
        const pairs = [];
        for (let k in value) {
            if (value.hasOwnProperty(k)) {
                pairs.push(new Pair(k, value[k]));
            }
        }
        return underlying.encode(pairs, writer);
    };

    const decode = (reader: DataReader) => {
        const pairs = underlying.decode(reader);
        const obj = {} as Record<K, V>;
        for (let pair of pairs) {
            obj[pair.first] = pair.second;
        }
        return obj;
    };

    return Object.freeze({
        encode: encode,
        decode: decode
    });
}

export function optional<T>(elementCodec: Codec<T>) {
    const encode = (value: T, writer: DataWriter) => {
        if (value === null || value === undefined) {
            writer.writeUint8(0x00);
        } else {
            writer.writeUint8(0xFF);
            elementCodec.encode(value, writer);
        }
    };

    const decode = (reader: DataReader) => {
        const isPresent = reader.readUint8();
        if (isPresent !== 0) {
            return elementCodec.decode(reader);
        } else {
            return null;
        }
    };

    return Object.freeze({
        encode: encode,
        decode: decode
    });
}

export function either<L, R>(leftCodec: Codec<L>, rightCodec: Codec<R>) {
    const encode = (value: Left<L> | Right<R>, writer: DataWriter) => {
        if (Either.isLeft(value)) {
            writer.writeUint8(0x00); // false indicates Left
            leftCodec.encode(value.left, writer);
        } else {
            writer.writeUint8(0xFF); // true indicates Right
            rightCodec.encode(value.right, writer);
        }
    };

    const decode = (reader: DataReader) => {
        const isRight = reader.readUint8();
        if (isRight !== 0) {
            return Either.right(rightCodec.decode(reader));
        } else {
            return Either.left(leftCodec.decode(reader));
        }
    };

    return Object.freeze({
        encode: encode,
        decode: decode
    });
}

export function discriminated<T>(discriminatorCodec: Codec<number>, selectCodec: (n: number) => Codec<T>, selectDiscriminator: (t: T) => number) {
    const encode = (value: T, writer: DataWriter) => {
        const discriminator = selectDiscriminator(value);
        discriminatorCodec.encode(discriminator, writer);
        selectCodec(discriminator).encode(value, writer);
    };

    const decode = (reader: DataReader) => {
        const discriminator = discriminatorCodec.decode(reader);
        return selectCodec(discriminator).decode(reader);
    };

    return Object.freeze({
        encode: encode,
        decode: decode
    });
}

abstract class HasCodec {
    static codec: Codec<CodecContainer>;
    static unapply: (inst: any) => any[];
    protected constructor(...args: any[]) {};
}

export abstract class CodecContainer extends HasCodec {
    static codecs: typeof HasCodec[];
    static codec: Codec<HasCodec>;
    constructor(...args: any[]) {
        super(...args);
    }
}
