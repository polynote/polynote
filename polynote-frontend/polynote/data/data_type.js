'use strict';

import {
    discriminated, combined, arrayCodec, str, uint8, int32
} from './codec'

export class DataType {
    name() { return this.constructor.name; }
}
DataType.delegatedCodec = {
    // Need to make a delegator here, because DataType.codec doesn't exist yet and forms recursive knots with i.e. StructField.
    // This defers evaluating DataType.codec, tying the knot
    encode: (value, writer) => DataType.codec.encode(value.dataType, writer),
    decode: (reader) => DataType.codec.decode(reader)
};

function SingletonDataType(msgTypeId, readBuf, name, isNumeric) {
    const inst = {
        msgTypeId,
        name: (inst) => name,
        decodeBuffer: readBuf,
        codec: {
            encode: (value, writer) => {},
            decode: (reader) => inst,
        },
        isNumeric: isNumeric || false
    };
    Object.setPrototypeOf(inst, DataType);
    inst.constructor = inst;
    return inst;
}

export const ByteType = SingletonDataType(0, reader => reader.readUint8(), 'byte');
export const BoolType = SingletonDataType(1, reader => reader.readBoolean(), 'boolean');
export const ShortType = SingletonDataType(2, reader => reader.readInt16(), 'int2', true);
export const IntType = SingletonDataType(3, reader => reader.readInt32(), 'int4', true);
export const LongType = SingletonDataType(4, reader => reader.readInt64(), 'int8', true);
export const FloatType = SingletonDataType(5, reader => reader.readFloat32(), 'float4', true);
export const DoubleType = SingletonDataType(6, reader => reader.readFloat64(), 'float8', true);
export const BinaryType = SingletonDataType(7, reader => reader.readBuffer(), 'binary');
export const StringType = SingletonDataType(8, reader => reader.readString(), 'string');

export const NumericTypes = [ShortType, IntType, LongType, FloatType, DoubleType];
Object.freeze(NumericTypes);

export class StructField {
    constructor(name, dataType) {
        this.name = name;
        this.dataType = dataType;
        Object.freeze(this);
    }
}

StructField.codec = combined(
    str,
    DataType.delegatedCodec
).to(StructField);

export class StructType extends DataType {
    static get msgTypeId() { return 9; }
    static name(inst) { return 'struct'; }
    static unapply(inst) {
        return [inst.fields];
    }

    name() { return StructType.name(this); }

    constructor(fields) {
        super(fields);
        this.fields = fields;
        Object.freeze(this);
    }

    decodeBuffer(reader) {
        const obj = {};
        this.fields.forEach(field => {
            obj[field.name] = field.dataType.decodeBuffer(reader)
        });
        return obj;
    }
}

StructType.codec = combined(arrayCodec(int32, StructField.codec)).to(StructType);

export class OptionalType extends DataType {
    static get msgTypeId() { return 10; }
    static name(inst) { return `${inst.element.constructor.name(inst.element)}?`}
    static unapply(inst) {
        return [inst.element];
    }

    name() { return OptionalType.name(this); }

    constructor(element) {
        super(element);
        this.element = element;
        Object.freeze(this);
    }

    decodeBuffer(buffer) {
        if(buffer.readBoolean()) {
            return this.element.decodeBuffer(buffer);
        }
        return null;
    }
}

OptionalType.codec = combined(DataType.delegatedCodec).to(OptionalType);

export class ArrayType extends DataType {
    static get msgTypeId() { return 11; }
    static name(inst) { return `[${inst.element.constructor.name(inst.element)}]`}
    static unapply(inst) {
        return [inst.element];
    }

    name() { return ArrayType.name(this); }

    constructor(element) {
        super(element);
        this.element = element;
        Object.freeze(this);
    }

    decodeBuffer(buffer) {
        const len = buffer.readInt32();
        const result = [];
        for (let i = 0; i < len; i++) {
            result[i] = this.element.decodeBuffer(buffer);
        }
        return result;
    }
}

ArrayType.codec = combined(DataType.delegatedCodec).to(ArrayType);

export const DateType = SingletonDataType(12, buffer => {throw "TODO"}, 'date');
export const TimestampType = SingletonDataType(13, buffer => {throw "TODO"}, 'timestamp');
export const TypeType = SingletonDataType(14, buffer => buffer.readString(), 'type');

export class MapType extends DataType {
    static get msgTypeId() { return 15; }
    static name(inst) { return `[${inst.element.constructor.name(inst.element)}]`}
    static unapply(inst) {
        return [inst.element];
    }

    name() { return MapType.name(this); }

    constructor(element) {
        super(element);
        this.element = element;
        Object.freeze(this);
    }

    decodeBuffer(buffer) {
        const len = buffer.readInt32();
        const result = [];
        for (let i = 0; i < len; i++) {
            result[i] = Object.values(this.element.decodeBuffer(buffer));
        }
        return new Map(result);
    }
}

MapType.codec = combined(StructType.codec).to(MapType);

DataType.codecs = [
    ByteType,     //  0
    BoolType,     //  1
    ShortType,    //  2
    IntType,      //  3
    LongType,     //  4
    FloatType,    //  5
    DoubleType,   //  6
    BinaryType,   //  7
    StringType,   //  8
    StructType,   //  9
    OptionalType, // 10
    ArrayType,    // 11
    DateType,     // 12
    TimestampType, // 13
    TypeType,     // 14
    MapType,      // 15
];

DataType.codec = discriminated(
    uint8,
    (msgTypeId) => DataType.codecs[msgTypeId].codec,
    (result) => result.constructor.msgTypeId
);