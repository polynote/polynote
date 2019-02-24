'use strict';

import {
    Codec, DataReader, DataWriter, discriminated, combined, arrayCodec, bufferCodec,
    str, shortStr, tinyStr, uint8, uint16, int32, uint32
} from './codec.js'

import { DataType } from './data_type.js'

export class ValueRepr {}

export class StringRepr extends ValueRepr {
    static get msgTypeId() { return 0; }

    static unapply(inst) {
        return [inst.string];
    }

    constructor(string) {
        super(string);
        this.string = string;
        Object.freeze(this);
    }
}

StringRepr.codec = combined(str).to(StringRepr);

export class HTMLRepr extends ValueRepr {
    static get msgTypeId() { return 1; }

    static unapply(inst) {
        return [inst.html];
    }

    constructor(html) {
        super(html);
        this.html = html;
        Object.freeze(this);
    }
}

HTMLRepr.codec = combined(str).to(HTMLRepr);

export class DataRepr extends ValueRepr {
    static get msgTypeId() { return 2; }

    static unapply(inst) {
        return [inst.dataType, inst.data];
    }

    constructor(dataType, data) {
        super(dataType, data);
        this.dataType = dataType;
        this.data = data;
        Object.freeze(this);
    }
}

DataRepr.codec = combined(DataType.codec, bufferCodec).to(DataRepr);

export class LazyDataRepr extends ValueRepr {
    static get msgTypeId() { return 3; }

    static unapply(inst) {
        return [inst.handle, inst.dataType];
    }

    constructor(handle, dataType) {
        super(handle, dataType);
        this.handle = handle;
        this.dataType = dataType;
        Object.freeze(this);
    }
}

LazyDataRepr.codec = combined(int32, DataType.codec).to(LazyDataRepr);

export class UpdatingDataRepr extends ValueRepr {
    static get msgTypeId() { return 4; }

    static unapply(inst) {
        return [inst.handle, inst.dataType];
    }

    constructor(handle, dataType) {
        super(handle, dataType);
        this.handle = handle;
        this.dataType = dataType;
        Object.freeze(this);
    }
}

UpdatingDataRepr.codec = combined(int32, DataType.codec).to(UpdatingDataRepr);

export class StreamingDataRepr extends ValueRepr {
    static get msgTypeId() { return 5; }

    static unapply(inst) {
        return [inst.handle, inst.dataType, inst.knownSize];
    }

    constructor(handle, dataType, knownSize) {
        super(handle, dataType, knownSize);
        this.handle = handle;
        this.dataType = dataType;
        this.knownSize = knownSize;
        Object.freeze(this);
    }
}

StreamingDataRepr.codec = combined(int32, DataType.codec, uint32).to(StreamingDataRepr);

ValueRepr.codecs = [
    StringRepr,        // 0
    HTMLRepr,          // 1
    DataRepr,          // 2
    LazyDataRepr,      // 3
    UpdatingDataRepr,  // 4
    StreamingDataRepr, // 5
];

ValueRepr.codec = discriminated(
    uint8,
    (msgTypeId) => ValueRepr.codecs[msgTypeId].codec,
    (dataType) => dataType.constructor.msgTypeId
);