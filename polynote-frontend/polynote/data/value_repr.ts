'use strict';

import {
    DataReader, discriminated, combined, bufferCodec, optional, str, uint8, int32, uint32, Codec, CodecContainer
} from './codec'
import {DataType, StructType} from "./data_type";

export abstract class ValueRepr extends CodecContainer {
    static codec: Codec<ValueRepr>;
    static codecs: typeof ValueRepr[];
    static msgTypeId: number;
}

export class StringRepr extends ValueRepr {
    static codec = combined(str).to(StringRepr);
    static get msgTypeId() { return 0; }

    static unapply(inst: StringRepr): ConstructorParameters<typeof StringRepr> {
        return [inst.string];
    }

    constructor(readonly string: string) {
        super();
        Object.freeze(this);
    }
}

export class MIMERepr extends ValueRepr {
    static codec = combined(str, str).to(MIMERepr);
    static get msgTypeId() { return 1; }

    static unapply(inst: MIMERepr): ConstructorParameters<typeof MIMERepr> {
        return [inst.mimeType, inst.content];
    }

    constructor(readonly mimeType: string, readonly content: string) {
        super();
        Object.freeze(this);
    }
}

export class DataRepr extends ValueRepr {
    static codec = combined(DataType.codec, bufferCodec).to(DataRepr);
    static get msgTypeId() { return 2; }

    static unapply(inst: DataRepr): ConstructorParameters<typeof DataRepr> {
        return [inst.dataType, inst.data];
    }

    constructor(readonly dataType: DataType, readonly data: ArrayBuffer) {
        super();
        Object.freeze(this);
    }

    decode() {
        return this.dataType.decodeBuffer(new DataReader(this.data));
    }
}

export class LazyDataRepr extends ValueRepr {
    static codec = combined(int32, DataType.codec, optional(int32)).to(LazyDataRepr);
    static get handleTypeId() { return 0; }
    static get msgTypeId() { return 3; }

    static unapply(inst: LazyDataRepr): ConstructorParameters<typeof LazyDataRepr> {
        return [inst.handle, inst.dataType, inst.knownSize];
    }

    constructor(readonly handle: number, readonly dataType: DataType, readonly knownSize: number | null) {
        super();
        Object.freeze(this);
    }
}

export class UpdatingDataRepr extends ValueRepr {
    static codec = combined(int32, DataType.codec).to(UpdatingDataRepr);
    static get handleTypeId() { return 1; }
    static get msgTypeId() { return 4; }

    static unapply(inst: UpdatingDataRepr): ConstructorParameters<typeof UpdatingDataRepr> {
        return [inst.handle, inst.dataType];
    }

    constructor(readonly handle: number, readonly dataType: DataType) {
        super();
        Object.freeze(this);
    }
}

export class StreamingDataRepr extends ValueRepr {
    static codec = combined(int32, DataType.codec, optional(uint32)).to(StreamingDataRepr);
    static get handleTypeId() { return 2; }
    static get msgTypeId() { return 5; }

    static unapply(inst: StreamingDataRepr): ConstructorParameters<typeof StreamingDataRepr> {
        return [inst.handle, inst.dataType, inst.knownSize];
    }

    constructor(readonly handle: number, readonly dataType: StructType, readonly knownSize?: number) {
        super();
        Object.freeze(this);
    }
}

ValueRepr.codecs = [
    StringRepr,        // 0
    MIMERepr,          // 1
    DataRepr,          // 2
    LazyDataRepr,      // 3
    UpdatingDataRepr,  // 4
    StreamingDataRepr, // 5
];

ValueRepr.codec = discriminated(
    uint8,
    (msgTypeId) => ValueRepr.codecs[msgTypeId].codec,
    (dataType) => (dataType.constructor as typeof ValueRepr).msgTypeId
);


