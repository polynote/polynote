'use strict';

import {
    Codec, DataReader, DataWriter, discriminated, combined, arrayCodec,
    str, shortStr, tinyStr, uint8, uint16, int32
} from './codec.js'
import {Result} from "./result";

export class DataType {}
DataType.delegatedCodec = {
    // Need to make a delegator here, because DataType.codec doesn't exist yet and forms recursive knots with i.e. StructField.
    // This defers evaluating DataType.codec, tying the knot
    encode: (value, writer) => DataType.codec.encode(value.dataType, writer),
    decode: (reader) => DataType.codec.decode(reader)
};

function SingletonDataType(msgTypeId) {
    const inst = {
        msgTypeId,
        codec: {
            encode: (value, writer) => {},
            decode: (reader) => inst,
        }
    };
    Object.setPrototypeOf(inst, DataType);
    inst.constructor = inst;
    return inst;
}

export const ByteType = SingletonDataType(0);
export const BoolType = SingletonDataType(1);
export const ShortType = SingletonDataType(2);
export const IntType = SingletonDataType(3);
export const LongType = SingletonDataType(4);
export const FloatType = SingletonDataType(5);
export const DoubleType = SingletonDataType(6);
export const BinaryType = SingletonDataType(7);
export const StringType = SingletonDataType(8);

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
    static unapply(inst) {
        return [inst.fields];
    }

    constructor(fields) {
        super(fields);
        this.fields = fields;
        Object.freeze(this);
    }
}

StructType.codec = combined(arrayCodec(int32, StructField.codec)).to(StructType);

export class OptionalType extends DataType {
    static get msgTypeId() { return 10; }
    static unapply(inst) {
        return [inst.element];
    }

    constructor(element) {
        super(element);
        this.element = element;
        Object.freeze(this);
    }
}

OptionalType.codec = combined(DataType.delegatedCodec).to(OptionalType);

export class ArrayType extends DataType {
    static get msgTypeId() { return 11; }
    static unapply(inst) {
        return [inst.element];
    }

    constructor(element) {
        super(element);
        this.element = element;
        Object.freeze(this);
    }
}

ArrayType.codec = combined(DataType.delegatedCodec).to(ArrayType);

export const DateType = SingletonDataType(12);
export const TimestampType = SingletonDataType(13);

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
];

DataType.codec = discriminated(
    uint8,
    (msgTypeId) => DataType.codecs[msgTypeId].codec,
    (result) => result.constructor.msgTypeId
);