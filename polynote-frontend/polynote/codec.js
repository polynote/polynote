'use strict';

export class DataReader {
    constructor(buf) {
        if (buf instanceof ArrayBuffer) {
            this.buffer = new DataView(buf);
        } else if (buf instanceof DataView) {
            this.buffer = buf;
        } else {
            throw new TypeError('DataReader requires an ArrayBuffer or a DataView');
        }
        this.offset = 0;
    }

    readUint8() {
        return this.buffer.getUint8(this.offset++);
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
        const len = this.readUint32();
        return this.readStringBytes(len);
    }

    readShortString() {
        const len = this.readUint16();
        return this.readStringBytes(len);
    }

    readTinyString() {
        const len = this.readUint8();
        return this.readStringBytes(len);
    }

    readStringBytes(len) {
        const end = this.offset + len;
        const str = stringDecoder.decode(this.buffer.buffer.slice(this.offset, end));
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

export class DataWriter {
    constructor(chunkSize) {
        this.chunkSize = chunkSize || 1024;
        this.buffer = new ArrayBuffer(this.chunkSize);
        this.dataView = new DataView(this.buffer);
        this.offset = 0;
    }

    ensureBufSize(newSize) {
        if (this.buffer.byteLength < newSize) {
            const newBuf = new ArrayBuffer(this.buffer.byteLength + this.chunkSize);
            new Uint8Array(newBuf).set(new Uint8Array(this.buffer));
            this.buffer = newBuf;
            this.dataView = new DataView(this.buffer);
        }
    }

    writeUint8(value) {
        this.ensureBufSize(this.buffer.byteLength + 1);
        this.dataView.setUint8(this.offset++, value);
    }

    writeInt8(value) {
        this.ensureBufSize(this.buffer.byteLength + 1);
        this.dataView.setInt8(this.offset++, value);
    }

    writeUint16(value) {
        this.ensureBufSize(this.buffer.byteLength + 2);
        this.dataView.setUint16(this.offset, value);
        this.offset += 2;
    }

    writeInt16(value) {
        this.ensureBufSize(this.buffer.byteLength + 2);
        this.dataView.setInt16(this.offset, value);
        this.offset += 2;
    }

    writeUint32(value) {
        this.ensureBufSize(this.buffer.byteLength + 4);
        this.dataView.setUint32(this.offset, value);
        this.offset += 4;
    }

    writeInt32(value) {
        this.ensureBufSize(this.buffer.byteLength + 4);
        this.dataView.setInt32(this.offset, value);
        this.offset += 4;
    }

    writeFloat32(value) {
        this.ensureBufSize(this.buffer.byteLength + 4);
        this.dataView.setFloat32(this.offset, value);
        this.offset += 4;
    }

    writeFloat64(value) {
        this.ensureBufSize(this.buffer.byteLength + 8);
        this.dataView.setFloat32(this.offset, value);
        this.offset += 8;
    }

    writeString(value) {
        const bytes = stringEncoder.encode(value);
        this.ensureBufSize(this.buffer.byteLength + bytes.length + 4);
        this.writeUint32(bytes.length);
        this.writeStrBytes(bytes, 0xFFFFFFFF);
    }

    writeShortString(value) {
        const bytes = stringEncoder.encode(value);
        this.ensureBufSize(this.buffer.byteLength + bytes.length + 2);
        this.writeUint16(bytes.length);
        this.writeStrBytes(bytes, 0xFFFF);
    }

    writeTinyString(value) {
        const bytes = stringEncoder.encode(value);
        this.ensureBufSize(this.buffer.byteLength + bytes.length + 2);
        this.writeUint8(bytes.length);
        this.writeStrBytes(bytes, 0xFF);
    }

    writeStrBytes(bytes, maxLen) {
        if (bytes.length > maxLen) {
            throw `String byte length exceeds ${maxLen}`;
        }
        new Uint8Array(this.buffer, this.offset, bytes.length).set(bytes);
        this.offset += bytes.length;
    }

    writeBuffer(value) {
        if (value instanceof ArrayBuffer) {
            const len = value.byteLength;
            this.ensureBufSize(this.buffer.byteLength + len + 4);
            this.writeUint32(value.byteLength);
            this.offset += 4;
            new Uint8Array(this.buffer, this.offset, len).set(value);
        }
    }

    finish() {
        return new Uint8Array(this.buffer, 0, this.offset).slice(0, this.offset).buffer;
    }
}

const stringEncoder = new TextEncoder('utf-8');
const stringDecoder = new TextDecoder('utf-8');

export class Codec {
    static map(codec, to, from) {
        return Object.freeze({
            encode: (value, writer) => codec.encode(from(value), writer),
            decode: (reader) => to(codec.decode(reader))
        });
    }

    static encode(codec, obj) {
        const writer = new DataWriter();
        codec.encode(obj, writer);
        return writer.finish();
    }

    static decode(codec, buf) {
        const reader = new DataReader(buf);
        return codec.decode(reader);
    }
}

// TODO: make all of these actual Codecs and put an API there
export const str = Object.freeze({
    encode: (str, writer) => writer.writeString(str),
    decode: (reader) => reader.readString()
});

export const shortStr = Object.freeze({
    encode: (str, writer) => writer.writeShortString(str),
    decode: (reader) => reader.readShortString()
});

export const tinyStr = Object.freeze({
    encode: (str, writer) => writer.writeTinyString(str),
    decode: (reader) => reader.readTinyString()
});

export const uint8 = Object.freeze({
    encode: (value, writer) => writer.writeUint8(value),
    decode: (reader) => reader.readUint8()
});

export const int8 = Object.freeze({
    encode: (value, writer) => writer.writeInt8(value),
    decode: (reader) => reader.readInt8()
});

export const uint16 = Object.freeze({
    encode: (value, writer) => writer.writeUint16(value),
    decode: (reader) => reader.readUint16()
});

export const int16 = Object.freeze({
    encode: (value, writer) => writer.writeInt16(value),
    decode: (reader) => reader.readInt16()
});

export const uint32 = Object.freeze({
    encode: (value, writer) => writer.writeUint32(value),
    decode: (reader) => reader.readUint32()
});

export const int32 = Object.freeze({
    encode: (value, writer) => writer.writeInt32(value),
    decode: (reader) => reader.readInt32()
});

export const float32 = Object.freeze({
    encode: (value, writer) => writer.writeFloat32(value),
    decode: (reader) => reader.readFloat32()
});

export const float64 = Object.freeze({
    encode: (value, writer) => writer.writeFloat64(value),
    decode: (reader) => reader.readFloat64()
});

export const bool = Object.freeze({
    encode: (value, writer) => value ? writer.writeUint8(255) : writer.writeUint8(0),
    decode: (reader) => !!reader.readUint8()
});

export const nullCodec = Object.freeze({
    encode: (value, writer) => undefined,
    decode: (reader) => null
});

export const bufferCodec = Object.freeze({
    encode: (value, writer) => writer.writeBuffer(value),
    decode: (reader) => reader.readBuffer()
});

class CombinedCodec {
    constructor(...codecs) {
        this.codecs = codecs;
    }

    to(constr) {
        const codecs = this.codecs;
        const encode = (value, writer) => {
            const values = constr.unapply(value);
            for (var i = 0; i < codecs.length; i++) {
                codecs[i].encode(values[i], writer);
            }
        };

        const decode = (reader) => {
            const values = this.codecs.map((codec) => codec.decode(reader));
            return new constr(...values);
        };

        return Object.freeze({
            encode: encode,
            decode: decode
        });
    }
}

export function combined(...codecs) {
    return new CombinedCodec(...codecs);
}

export function arrayCodec(lengthCodec, elementCodec) {
    const encode = (value, writer) => {
        lengthCodec.encode(value.length, writer);
        for (var i = 0; i < value.length; i++) {
            elementCodec.encode(value[i], writer);
        }
    };

    const decode = (reader) => {
        const length = lengthCodec.decode(reader);
        const arr = new Array(length);
        for (var i = 0; i < length; i++) {
            arr[i] = elementCodec.decode(reader);
        }
        return arr;
    };

    return Object.freeze({
        encode: encode,
        decode: decode
    });
}

export class Ior {
    constructor(left, right) {
        this.left = left;
        this.right = right;
    }

    fold(leftFnOrObj, rightFn, bothFn) {
        const [l, r, b] = (leftFnOrObj.left && leftFnOrObj.right && leftFnOrObj.both)
                            ? [leftFnOrObj.left, leftFnOrObj.right, leftFnOrObj.both] : [leftFnOrObj, rightFn, bothFn];

        if (this.left !== undefined && this.right !== undefined) {
            return b(this.left, this.right);
        } else if (this.left !== undefined) {
            return l(this.left);
        } else if (this.right !== undefined) {
            return r(this.right);
        } else {
            throw "Ior defines neither left nor right";
        }
    }

    static left(left) {
        return new Ior(left, undefined);
    }

    static right(right) {
        return new Ior(undefined, right);
    }

    static both(left, right) {
        return new Ior(left, right);
    }
}

export function ior(leftCodec, rightCodec) {
    const encode = (value, writer) => {
        if (value.left !== undefined && value.right !== undefined) {
            writer.writeInt8(2);
            leftCodec.encode(value.left, writer);
            rightCodec.encode(value.right, writer);
        } else if (value.left !== undefined) {
            writer.writeInt8(0);
            leftCodec.encode(value.left, writer);
        } else if (value.right !== undefined) {
            writer.writeInt8(1);
            rightCodec.encode(value.right, writer);
        } else {
            throw "Neither left nor right of ior is defined"
        }
    };

    const decode = (reader) => {
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

export function mapCodec(lengthCodec, keyCodec, valueCodec) {
    class Pair {
        static unapply(inst) {
            return [inst.first, inst.second];
        }

        constructor(first, second) {
            this.first = first;
            this.second = second;
            Object.freeze(this);
        }
    }

    Pair.codec = combined(keyCodec, valueCodec).to(Pair);

    const underlying = arrayCodec(lengthCodec, Pair.codec);

    const encode = (value, writer) => {
        const pairs = [];
        for (var k in value) {
            if (value.hasOwnProperty(k)) {
                pairs.push(new Pair(k, value[k]));
            }
        }
        return underlying.encode(pairs, writer);
    };

    const decode = (reader) => {
        const pairs = underlying.decode(reader);
        const obj = {};
        for (var pair of pairs) {
            obj[pair.first] = pair.second;
        }
        return obj;
    };

    return Object.freeze({
        encode: encode,
        decode: decode
    });
}

export function optional(elementCodec) {
    const encode = (value, writer) => {
        if (value === null) {
            writer.writeUint8(0x00);
        } else {
            writer.writeUint8(0xFF);
            elementCodec.encode(value, writer);
        }
    };

    const decode = (reader) => {
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

export function discriminated(discriminatorCodec, selectCodec, selectDiscriminator) {
    const encode = (value, writer) => {
        const discriminator = selectDiscriminator(value);
        discriminatorCodec.encode(discriminator, writer);
        selectCodec(discriminator).encode(value, writer);
    };

    const decode = (reader) => {
        const discriminator = discriminatorCodec.decode(reader);
        return selectCodec(discriminator).decode(reader);
    };

    return Object.freeze({
        encode: encode,
        decode: decode
    });
}