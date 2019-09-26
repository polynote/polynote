import {
    arrayCodec,
    Codec,
    CodecContainer,
    combined,
    DataReader,
    DataWriter,
    discriminated,
    int32,
    str,
    uint8
} from './codec'

export abstract class DataType extends CodecContainer {
    static codec: Codec<DataType>;
    static codecs: typeof DataType[];
    static delegatedCodec = {
        // Need to make a delegator here, because DataType.codec doesn't exist yet and forms recursive knots with i.e. StructField.
        // This defers evaluating DataType.codec, tying the knot
        encode: (value: DataType, writer: DataWriter) => DataType.codec.encode(value, writer),
        decode: (reader: DataReader): DataType => DataType.codec.decode(reader)
    };
    static msgTypeId: number;
    isNumeric = false;

    // NOTE: renamed from `name()` -> `typeName()` since typescript complains about overriding `Function.name`
    typeName(): string{
        return DataType.typeName(this);
    }
    static typeName(inst: DataType): string { return (inst.constructor as typeof DataType).typeName(inst)}

    abstract decodeBuffer(reader: DataReader): any // any way to type this better?

}

// Factory that creates DataTypes as Singleton classes that are instances of their own class constructor
// Thus, they have access to both static and instance properties defined in SDT.
// NOTE: in order for this trick to work, we can't have any static methods - Object.assign only assigns properties!
function SingletonDataType<T>(msgTypeId: number, readBuf: (reader: DataReader) => T, name: string, isNumeric: boolean = false) {
    class SDT extends DataType {
        static msgTypeId = msgTypeId;
        static typeName = () => { return name };
        static codec: Codec<SDT>;

        decodeBuffer(reader: DataReader) {
            return readBuf(reader);
        }
        static isNumeric = isNumeric;
    }
    const sdt = new SDT();

    SDT.codec = {
        encode: (value: SDT, writer: DataWriter) => {},
        decode: (reader: DataReader): SDT => sdt,
    };

    // NOTE: this only assigns properties on SDT - so static methods *won't go through*.
    return Object.assign(sdt, SDT);
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

export const NumericTypes: DataType[] = [ShortType, IntType, LongType, FloatType, DoubleType];
Object.freeze(NumericTypes);

export class StructField {
    static codec = combined(str, DataType.delegatedCodec).to(StructField);

    static unapply(inst: StructField): ConstructorParameters<typeof StructField> {
        return [inst.name, inst.dataType];
    }

    constructor(readonly name: string, readonly dataType: DataType) {
        Object.freeze(this);
    }
}

export class StructType extends DataType {
    static codec = combined(arrayCodec(int32, StructField.codec)).to(StructType);
    static get msgTypeId() { return 9; }
    static typeName(inst: StructType) { return 'struct'; }
    static unapply(inst: StructType): ConstructorParameters<typeof StructType> {
        return [inst.fields];
    }

    typeName() { return StructType.typeName(this); }

    constructor(readonly fields: StructField[]) {
        super();
        Object.freeze(this);
    }

    decodeBuffer(reader: DataReader) {
        const obj: Record<string, any> = {};
        this.fields.forEach(field => {
            obj[field.name] = field.dataType.decodeBuffer(reader)
        });
        return obj;
    }
}

export class OptionalType extends DataType {
    static codec = combined(DataType.delegatedCodec).to(OptionalType);
    static get msgTypeId() { return 10; }
    static typeName(inst: OptionalType) { return `${(inst.element.constructor as typeof DataType).typeName(inst.element)}?`}
    static unapply(inst: OptionalType): ConstructorParameters<typeof OptionalType> {
        return [inst.element];
    }

    typeName() { return OptionalType.typeName(this); }

    constructor(readonly element: DataType) {
        super();
        Object.freeze(this);
    }

    decodeBuffer(reader: DataReader) {
        if(reader.readBoolean()) {
            return this.element.decodeBuffer(reader);
        }
        return null;
    }
}

export class ArrayType extends DataType {
    static codec = combined(DataType.delegatedCodec).to(ArrayType);
    static get msgTypeId() { return 11; }
    static typeName(inst: ArrayType) { return `[${(inst.element.constructor as typeof DataType).typeName(inst.element)}]`}
    static unapply(inst: ArrayType): ConstructorParameters<typeof ArrayType> {
        return [inst.element];
    }

    typeName() { return ArrayType.typeName(this); }

    constructor(readonly element: DataType) {
        super();
        Object.freeze(this);
    }

    decodeBuffer(reader: DataReader) {
        const len = reader.readInt32();
        const result = [];
        for (let i = 0; i < len; i++) {
            result[i] = this.element.decodeBuffer(reader);
        }
        return result;
    }
}

export const DateType = SingletonDataType(12, buffer => {throw "TODO"}, 'date');
export const TimestampType = SingletonDataType(13, buffer => {throw "TODO"}, 'timestamp');
export const TypeType = SingletonDataType(14, buffer => buffer.readString(), 'type');

export class MapType extends DataType {
    static get msgTypeId() { return 15; }
    static typeName(inst: MapType) { return `[${(inst.element.constructor as typeof DataType).typeName(inst.element)}]`}
    static unapply(inst: MapType): ConstructorParameters<typeof MapType>{
        return [inst.element];
    }

    typeName() { return MapType.typeName(this); }

    constructor(readonly element: StructType) {
        super();
        Object.freeze(this);
    }

    decodeBuffer(reader: DataReader) {
        const len = reader.readInt32();
        const result: [string, any][][] = [];
        for (let i = 0; i < len; i++) {
            result[i] = Object.entries(this.element.decodeBuffer(reader))
        }
        return new Map<string, any>(result.flat());
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
    (result) => (result.constructor as typeof DataType).msgTypeId
);