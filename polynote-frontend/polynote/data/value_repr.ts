'use strict';

import {
    DataReader, discriminated, combined, bufferCodec, optional, str, uint8, int32, uint32, Pair, Codec, CodecContainer
} from './codec'

import match from "../util/match"

import {GroupAgg, HandleData, Message, ModifyStream, QuantileBin, Select, TableOp} from "./messages";
import {MessageListener, SocketSession} from "../comms";
import {DataType, DoubleType, LongType, NumericTypes, StructField, StructType} from "./data_type";

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
    static codec = combined(int32, DataType.codec).to(LazyDataRepr);
    static get handleTypeId() { return 0; }
    static get msgTypeId() { return 3; }

    static unapply(inst: LazyDataRepr): ConstructorParameters<typeof LazyDataRepr> {
        return [inst.handle, inst.dataType];
    }

    constructor(readonly handle: number, readonly dataType: DataType) {
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

export class DataBatch<T> extends CustomEvent<T[]> {
    constructor(readonly batch: T[]) {
        super('DataBatch', {detail: batch});
        Object.freeze(batch);
    }
}

export const QuartilesType = new StructType([
    new StructField("min", DoubleType),
    new StructField("q1", DoubleType),
    new StructField("median", DoubleType),
    new StructField("mean", DoubleType),
    new StructField("q3", DoubleType),
    new StructField("max", DoubleType)
]);

/**
 * An API for streaming data out of a StreamingDataRepr
 * TODO: remove `socket` references!
 */
export class DataStream extends EventTarget {
    readonly mods: TableOp[];
    readonly dataType: StructType;
    private batchSize = 50;
    private receivedCount = 0;
    terminated = false;
    private stopAfter = Infinity;
    private listener?: EventListener;
    private runListener?: EventListener;
    private socketListener?: MessageListener;
    private onComplete?: <T>(value?: T | PromiseLike<T>) => void;
    private onError?: (reason?: any) => void;
    private nextPromise?: {resolve: <T>(value?: T | PromiseLike<T>) => void, reject: (reason?: any) => void}; // holds a Promise's `resolve` and `reject` inputs.
    private setupPromise?: Promise<Message | void>;
    constructor(readonly path: string, private repr: StreamingDataRepr, readonly socket: SocketSession, mods?: TableOp[]) {
        super();
        this.path = path;
        this.repr = repr;
        this.socket = socket;
        this.mods = mods || [];
        this.dataType = repr.dataType;
        this.dataType = this.finalDataType();
    }

    batch(batchSize: number) {
        if (batchSize < 0) {
            throw "Expected batch size > 0"
        }
        this.batchSize = batchSize;
        return this;
    }

    to<T>(fn: (t: T[]) => void) {
        if (this.listener) {
            this.removeEventListener('DataBatch', this.listener);
        }
        this.listener = (evt: DataBatch<T>) => {fn(evt.batch)};
        this.addEventListener('DataBatch', this.listener);
        return this;
    }

    limit(n: number) {
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

    aggregate(groupCols: string[], aggregations: Record<string, string> | Record<string, string>[]) {
        if (!(aggregations instanceof Array)) {
            aggregations = [aggregations];
        }

        const fields = this.dataType.fields;

        const aggPairs = aggregations.flatMap(agg => {
            const pairs = [];
            for (let key of Object.keys(agg)) {
                if (!fields.find((field: StructField) => field.name === key)) {
                    throw new Error(`Field ${key} not present in data type`);
                }
                pairs.push(new Pair(key, agg[key]));
            }
            return pairs;
        });

        const mod = new GroupAgg(groupCols, aggPairs);
        return new DataStream(this.path, this.repr, this.socket, [...this.mods, mod]);
    }

    bin(col: string, binCount: number, err?: number) {
        if (err === undefined) {
            err = 1.0 / binCount;
        }
        const field = this.requireField(col);
        if (NumericTypes.indexOf(field.dataType) < 0) {
            throw new Error(`Field ${col} must be a numeric type to use bin()`);
        }
        return new DataStream(this.path, this.repr, this.socket, [...this.mods, new QuantileBin(col, binCount, err)]);
    }

    select(...cols: string[]) {
        const fields = cols.map(col => this.requireField(col));
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

        this.runListener = <T>(evt: DataBatch<T>) => {
           this.receivedCount += evt.batch.length;
           if (this.receivedCount >= this.stopAfter) {
               this.kill();
           }
           if (!this.terminated) {
               this._requestNext();
           }
        };

        this.addEventListener('DataBatch', this.runListener);

        return this.setupStream().then(() => new Promise((resolve, reject) => {
            this.onComplete = resolve;
            this.onError = reject;
            this._requestNext();
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
        
        return this.setupStream().then(_ => new Promise(
            (resolve, reject) => {
                this.nextPromise = {resolve, reject};
                this._requestNext();
            }
        ));
    }

    private _requestNext() {
        this.socket.send(new HandleData(this.path, StreamingDataRepr.handleTypeId, this.repr.handle, this.batchSize, []))
    }

    private setupStream() {
        if (!this.socketListener) {
            const decodeValues = (data: ArrayBuffer[]) => data.map(buf => this.repr.dataType.decodeBuffer(new DataReader(buf)));

            this.socketListener = this.socket.addMessageListener(HandleData, (path, handleType, handleId, count, data) => {
                if (path === this.path && handleType === StreamingDataRepr.handleTypeId && handleId === this.repr.handle) {
                    const batch = decodeValues(data);
                    this.dispatchEvent(new DataBatch(batch));
                    if (this.nextPromise) {
                        this.nextPromise.resolve(batch);
                        this.nextPromise = undefined;
                    }

                    if (batch.length < count) {
                        this.kill();
                    }
                }
            });
        }

        if (!this.setupPromise) {
            if (this.mods && this.mods.length > 0) {
                this.setupPromise = this.socket.request(new ModifyStream(this.path, this.repr.handle, this.mods)).then(mod => {
                    if (mod.newRepr) this.repr = mod.newRepr
                });
            } else {
                this.setupPromise = Promise.resolve();
            }
        }

        return this.setupPromise;
    }

    private finalDataType() {
        let dataType = this.repr.dataType;
        const mods = this.mods;

        if (!mods || !mods.length)
            return dataType;

        if (!(dataType instanceof StructType)) {
            throw Error("Illegal state: table modifications on a non-struct stream")
        }

        for (let mod of this.mods) {
            match(mod)
                .when(GroupAgg, (groupCols: string[], aggregations: Pair<string, string>[]) => {
                    const groupFields = groupCols.map(name => this.requireField(name));
                    const aggregateFields = aggregations.map(pair => {
                        const [name, agg] = Pair.unapply(pair);
                        let aggregatedType = this.requireField(name).dataType;
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
                .when(QuantileBin, (column: string, binCount: number, _) => {
                    dataType = new StructType([...dataType.fields, new StructField(`${column}_quantized`, DoubleType)]);
                })
                .when(Select, (columns: string[]) => {
                    const fields = columns.map(name => this.requireField(name));
                    dataType = new StructType(fields);
                });
        }
        return dataType;
    }

    private requireField(name: string) {
        const field = this.dataType.fields.find((field: StructField) => field.name === name);
        if (!field) {
            throw new Error(`Field ${name} not present in data type`);
        }
        return field;
    }
}