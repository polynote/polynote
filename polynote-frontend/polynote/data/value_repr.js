'use strict';

import {
    DataReader, discriminated, combined, bufferCodec, optional, str, uint8, int32, uint32, Pair
} from './codec'

import match from "../util/match"

import {GroupAgg, HandleData, ModifyStream, QuantileBin, Select} from "./messages";
import { DataType, DoubleType, LongType, NumericTypes, StructField, StructType} from "./data_type";

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

export class MIMERepr extends ValueRepr {
    static get msgTypeId() { return 1; }

    static unapply(inst) {
        return [inst.mimeType, inst.content];
    }

    constructor(mimeType, content) {
        super(mimeType, content);
        this.mimeType = mimeType;
        this.content = content;
        Object.freeze(this);
    }
}

MIMERepr.codec = combined(str, str).to(MIMERepr);

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

    decode() {
        return this.dataType.decodeBuffer(new DataReader(this.data));
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
LazyDataRepr.handleTypeId = 0;

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
UpdatingDataRepr.handleTypeId = 1;

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

StreamingDataRepr.codec = combined(int32, DataType.codec, optional(uint32)).to(StreamingDataRepr);
StreamingDataRepr.handleTypeId = 2;

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
    (dataType) => dataType.constructor.msgTypeId
);

export class DataBatch extends CustomEvent {
    constructor(batch) {
        Object.freeze(batch);
        super('DataBatch', {detail: {batch}});
        this.batch = batch;
    }
}

function requestNext() {
    this.socket.send(new HandleData(this.path, StreamingDataRepr.handleTypeId, this.repr.handle, this.batchSize, []))
}

function setupStream() {
    if (!this.socketListener) {
        const decodeValues = data => data.map(buf => this.repr.dataType.decodeBuffer(new DataReader(buf)));

        this.socketListener = this.socket.addMessageListener(HandleData, (path, handleType, handleId, count, data) => {
            if (path === this.path && handleType === StreamingDataRepr.handleTypeId && handleId === this.repr.handle) {
                const batch = decodeValues(data);
                this.dispatchEvent(new DataBatch(batch));
                if (this.nextPromise) {
                    this.nextPromise.resolve(batch);
                    this.nextPromise = null;
                }

                if (batch.length < count) {
                    this.kill();
                }
            }
        });
    }

    if (!this.setupPromise) {
        if (this.mods && this.mods.length > 0) {
            this.setupPromise = this.socket.request(new ModifyStream(this.path, this.repr.handle, this.mods, null)).then(mod => this.repr = mod.newRepr);
        } else {
            this.setupPromise = Promise.resolve();
        }
    }

    return this.setupPromise;
}

export const QuartilesType = new StructType([
    new StructField("min", DoubleType),
    new StructField("q1", DoubleType),
    new StructField("median", DoubleType),
    new StructField("mean", DoubleType),
    new StructField("q3", DoubleType),
    new StructField("max", DoubleType)
]);

function finalDataType() {
    let dataType = this.repr.dataType;
    const mods = this.mods;

    if (!mods || !mods.length)
        return dataType;

    if (!(dataType instanceof StructType)) {
        throw Error("Illegal state: table modifications on a non-struct stream")
    }

    for (let mod of this.mods) {
        match(mod)
            .when(GroupAgg, (groupCols, aggregations) => {
                const groupFields = groupCols.map(name => requireField.call(this, name));
                const aggregateFields = aggregations.map(pair => {
                    const [name, agg] = Pair.unapply(pair);
                    let aggregatedType = requireField.call(this, name).dataType;
                    if (!aggregatedType) {
                        throw new Error(`Field ${name} not present in data type`);
                    }
                    switch (agg) {
                        case "count":
                        case "approx_count_distinct":
                            aggregatedType = LongType;
                            break;
                        case "quartiles":
                            aggregatedType = QuartilesType;
                            break;
                    }
                    return new StructField(`${agg}(${name})`, aggregatedType);
                });
                dataType = new StructType([...groupFields, ...aggregateFields]);
            })
            .when(QuantileBin, (column, binCount, _) => {
                dataType = new StructType([...dataType.fields, new StructField(`${column}_quantized`, DoubleType)]);
            })
            .when(Select, (columns) => {
                const fields = columns.map(name => requireField.call(this, name));
                dataType = new StructType(fields);
            });
    }
    return dataType;
}

function requireField(name) {
    const field = this.dataType.fields.find(field => field.name === name);
    if (!field) {
        throw new Error(`Field ${name} not present in data type`);
    }
    return field;
}

/**
 * An API for streaming data out of a StreamingDataRepr
 */
export class DataStream extends EventTarget {
    constructor(path, repr, socket, mods) {
        super();
        this.path = path;
        this.repr = repr;
        this.socket = socket;
        this.mods = mods || [];
        this.dataType = repr.dataType;
        this.dataType = finalDataType.call(this);

        this.batchSize = 50;

        this.receivedCount = 0;
        this.terminated = false;
        this.stopAfter = Infinity;
    }

    batch(batchSize) {
        batchSize = +batchSize;
        if (!batchSize || batchSize < 0) {
            throw "Expected batch size > 0"
        }
        this.batchSize = batchSize;
        return this;
    }

    to(fn) {
        if (this.listener) {
            this.removeEventListener('DataBatch', this.listener);
        }
        this.listener = this.addEventListener('DataBatch', (evt) => {fn(evt.batch)});
        return this;
    }

    limit(n) {
        this.stopAfter = n;
        return this;
    }

    kill() {
        this.terminated = true;
        if (this.listener) {
            this.removeEventListener('DataBatch', this.listener);
        }
        if (this.onComplete) {
            this.onComplete();
        }
        if (this.nextPromise) {
            this.nextPromise.reject("Stream was terminated")
        }
    }

    aggregate(groupCols, aggregations) {
        if (!(aggregations instanceof Array)) {
            aggregations = [aggregations];
        }

        const fields = this.dataType.fields;

        const aggPairs = aggregations.flatMap(agg => {
            const pairs = [];
            for (let key of Object.keys(agg)) {
                if (!fields.find(field => field.name === key)) {
                    throw new Error(`Field ${key} not present in data type`);
                }
                pairs.push(new Pair(key, agg[key]));
            }
            return pairs;
        });

        const mod = new GroupAgg(groupCols, aggPairs);
        return new DataStream(this.path, this.repr, this.socket, [...this.mods, mod]);
    }

    bin(col, binCount, err) {
        if (err === undefined) {
            err = 1.0 / binCount;
        }
        const field = requireField.call(this, col);
        if (NumericTypes.indexOf(field.dataType) < 0) {
            throw new Error(`Field ${col} must be a numeric type to use bin()`);
        }
        return new DataStream(this.path, this.repr, this.socket, [...this.mods, new QuantileBin(col, binCount, err)]);
    }

    select(...cols) {
        const fields = cols.map(col => requireField.call(this, col));
        const mod = new Select(cols);
        return new DataStream(this.path, this.repr, this.socket, [...this.mods, mod]);
    }

    /**
     * Run the stream until it's killed or it completes
     */
    run() {
        if (this.runListener) {
            throw "Stream is already running";
        }

        this.runListener = this.addEventListener('DataBatch', (evt) => {
           this.receivedCount += evt.batch.length;
           if (this.receivedCount >= this.stopAfter) {
               this.kill();
           }
           if (!this.terminated) {
               requestNext.call(this);
           }
        });

        return setupStream.call(this).then(_ => new Promise((resolve, reject) => {
            this.onComplete = resolve;
            this.onError = reject;
            requestNext.call(this);
        }));
    }

    /**
     * Fetch the next batch from the stream, for a stream that isn't being automatically run via run()
     */
    requestNext() {

        if (this.runListener) {
            throw "Stream is already running";
        }
        
        if (this.nextPromise) {
            throw "Next batch is already being awaited";
        }
        
        return setupStream.call(this).then(_ => new Promise(
            (resolve, reject) => {
                this.nextPromise = {resolve, reject};
                requestNext.call(this);
            }
        ));
    }


}