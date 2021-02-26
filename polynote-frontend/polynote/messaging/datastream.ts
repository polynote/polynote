import {
    Error as ErrorMsg,
    GroupAgg,
    HandleData, Histogram,
    Message, ModifyStream,
    QuantileBin, Sample, SampleN,
    Select,
    TableOp
} from "../data/messages";
import {DataType, DoubleType, LongType, NumericTypes, StructField, StructType, UnsafeLongType} from "../data/data_type";
import {DataReader, Pair} from "../data/codec";
import {Either} from "../data/codec_types";
import match from "../util/match";
import {StreamingDataRepr} from "../data/value_repr";
import {NotebookMessageDispatcher,} from "./dispatcher";
import {Disposable, IDisposable, removeKey, StateHandler} from "../state";
import {NotebookState, NotebookStateHandler} from "../state/notebook_state";
import {deepCopy, removeKeys} from "../util/helpers";

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
 */
export class DataStream extends Disposable {
    private readonly mods: TableOp[];
    readonly dataType: DataType;
    private batchSize = 50;
    private receivedCount = 0;
    terminated = false;
    private stopAfter = Infinity;
    private listener?: (batch: any[]) => void;
    private runListener?: (batch: any[]) => void;
    private onComplete?: <T>(value?: T | PromiseLike<T>) => void;
    private _onError: (reason?: any) => void = _ => {};
    private nextPromise?: {resolve: <T>(value?: T | PromiseLike<T>) => void, reject: (reason?: any) => void}; // holds a Promise's `resolve` and `reject` inputs.
    private setupPromise?: Promise<Message | void>;
    private repr: StreamingDataRepr;
    private activeStreams: StateHandler<NotebookState["activeStreams"]>;
    private observer?: IDisposable;

    constructor(
        private readonly dispatcher: NotebookMessageDispatcher,
        private readonly nbState: NotebookStateHandler,
        private readonly originalRepr: StreamingDataRepr,
        mods?: TableOp[],
        currentDataType?: DataType,
        private unsafeLongs: boolean = false
    ) {
        super();
        this.repr = originalRepr;
        this.dataType = currentDataType ?? originalRepr.dataType;
        this.mods = mods ?? [];

        this.activeStreams = nbState.lens("activeStreams").disposeWith(this);
    }

    private attachListener() {
        if (this.observer) {
            this.observer.tryDispose();
        }

        this.observer = this.activeStreams.addObserver(handles => {
            const data = handles[this.repr.handle];
            if (data && data.length > 0) {
                data.forEach(message => {
                    match(message)
                        .when(HandleData, (handleType, handleId, count, data) => {
                            if (handleType === StreamingDataRepr.handleTypeId && handleId === this.repr.handle) {
                                const succeed = (data: ArrayBuffer[]) => {
                                    const batch = this.decodeValues(data);
                                    if (this.nextPromise) {
                                        this.nextPromise.resolve(batch);
                                        this.nextPromise = undefined;
                                    }

                                    this.terminated = batch.length < count;
                                    if (this.listener) this.listener(batch);
                                    if (this.runListener) this.runListener(batch);
                                    if (this.terminated) {
                                        this.kill();
                                    }
                                };

                                const fail = (err: ErrorMsg) => this._onError(err);

                                Either.fold(data, fail, succeed);
                            } else {
                                throw new Error(`Unexpected HandleData message! ${[handleType, handleId, count, data]}`)
                            }
                        })
                        .when(ModifyStream, (fromHandle, ops, newRepr) => {
                            if (newRepr) {
                                this.repr = newRepr
                            }
                        })
                })

                // clear messages now that they have been processed.
                this.activeStreams.update(() => removeKey(this.repr.handle))
            }
        })
    }

    batch(batchSize: number) {
        if (batchSize < 0) {
            throw "Expected batch size > 0"
        }
        this.batchSize = batchSize;
        return this;
    }

    to<T>(fn: (t: T[]) => void) {
        this.listener = (batch: T[]) => {fn(batch)};
        return this;
    }

    limit(n: number) {
        this.stopAfter = n;
        return this;
    }

    kill() {
        this.terminated = true;
        if (this.repr.handle != this.originalRepr.handle) {
            this.dispatcher.stopDataStream(StreamingDataRepr.handleTypeId, this.repr.handle)
        }

        if (this.observer)  {
            this.observer.dispose();
        }
        if (this.onComplete) {
            this.onComplete();
        }
        if (this.nextPromise) {
            this.nextPromise.reject("Stream was terminated")
        }

        this.tryDispose();
    }

    private withOps(ops: TableOp[], forceUnsafeLongs?: boolean): DataStream {
        const newType = DataStream.finalDataType(this.dataType, ops);
        return new DataStream(this.dispatcher, this.nbState, this.originalRepr, [...this.mods, ...ops], newType, forceUnsafeLongs ?? this.unsafeLongs);
    }

    aggregate(groupCols: string[], aggregations: Record<string, string> | Record<string, string>[]): DataStream {
        if (!(this.dataType instanceof StructType))
            return this;

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
        return this.withOps([mod]);
    }

    bin(col: string, binCount: number, err?: number) {
        if (err === undefined) {
            err = 1.0 / binCount;
        }
        const field = this.requireField(col);
        if (NumericTypes.indexOf(field.dataType) < 0) {
            throw new Error(`Field ${col} must be a numeric type to use bin()`);
        }
        const mod = new QuantileBin(col, binCount, err);
        return this.withOps([mod]);
    }

    select(...cols: string[]) {
        const fields = cols.map(col => this.requireField(col));
        const mod = new Select(cols);
        return this.withOps([mod]);
    }

    /**
     * Sample (without replacement) at the given rate
     * @param sampleRate sample rate, 0 < n < 1
     */
    sample(sampleRate: number) {
        return this.withOps([new Sample(sampleRate)]);
    }

    /**
     * Sample (without replacement) approximately n items
     * @param n The number of items to sample
     */
    sampleN(n: number) {
        return this.withOps([new SampleN(n)]);
    }

    histogram(field: string, binCount: number) {
        binCount = Math.floor(binCount);
        return this.withOps([new Histogram(field, binCount)]);
    }

    useUnsafeLongs() {
        return this.withOps([], true);
    }

    onError(fn: (cause: any) => void) {
        const prevOnError = this._onError;
        this._onError = prevOnError ? (cause: any) => { prevOnError(cause); fn(cause); } : fn;
    }

    /**
     * Run the stream until it's killed or it completes
     */
    run() {
        if (this.runListener) {
            throw "Stream is already running";
        }

        this.runListener = <T>(batch: T[]) => {
            this.receivedCount += batch.length;
            if (this.receivedCount >= this.stopAfter) {
                this.kill();
            }
            if (!this.terminated) {
                this._requestNext();
            }
        };

        return this.setupStream().then(() => new Promise((resolve, reject) => {
            this.onComplete = resolve;
            const prevOnError = this._onError;
            this._onError = prevOnError ? (cause: any) => { prevOnError(cause); reject(cause) } : reject;
            this._requestNext();
        }));
    }

    abort() {
        this.dispatcher.cancelTasks()
        this.kill();
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
        this.dispatcher.requestDataBatch(StreamingDataRepr.handleTypeId, this.repr.handle, this.batchSize)
    }
    private decodeValues(data: ArrayBuffer[]) {
        return data.map(buf => this.repr.dataType.decodeBuffer(new DataReader(buf)));
    }

    private setupStream() {
        if (!this.setupPromise) {
            this.setupPromise = new Promise<ModifyStream>((resolve, reject) => {
                const handleId = this.repr.handle;
                this.dispatcher.modifyDataStream(handleId, this.mods)
                const obs = this.activeStreams.addObserver(handles => {
                    const messages = handles[handleId]
                    if (messages.length > 0) {
                        messages.forEach(message => {
                            if (message instanceof ModifyStream && message.fromHandle === handleId) {
                                if (message.newRepr) {
                                    if (this.unsafeLongs) {
                                        let newDataType = message.newRepr.dataType;
                                        if (newDataType instanceof StructType)
                                            newDataType = newDataType.replaceType(typ => typ === LongType ? UnsafeLongType : undefined);
                                        else if (newDataType === LongType)
                                            newDataType = UnsafeLongType;

                                        this.repr = new StreamingDataRepr(
                                            message.newRepr.handle,
                                            newDataType,
                                            message.newRepr.knownSize)
                                    } else {
                                        this.repr = message.newRepr;
                                    }
                                }
                                resolve(message)
                                obs.dispose();
                            }
                        })
                    }
                })
            }).then(message => {
                this.attachListener();
                return message;
            })
        }

        return this.setupPromise;
    }

    private static finalDataType(type: DataType, mods: TableOp[]): DataType {
        if (!(type instanceof StructType)) {
            return type;
        }

        let dataType: StructType = type;

        if (!mods || !mods.length)
            return dataType;

        for (let mod of mods) {
            match(mod)
                .when(GroupAgg, (groupCols: string[], aggregations: Pair<string, string>[]) => {
                    const groupFields = groupCols.map(name => DataStream.requireField(dataType, name));
                    const aggregateFields = aggregations.map(pair => {
                        const [name, agg] = Pair.unapply(pair);
                        let aggregatedType = DataStream.requireField(dataType, name).dataType;
                        switch (agg) {
                            case "count":
                            case "approx_count_distinct":
                                aggregatedType = LongType;
                                break;
                            case "quartiles":
                                aggregatedType = QuartilesType;
                                break;
                            case "mean":
                                aggregatedType = DoubleType;
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
                    const fields = columns.map(name => DataStream.requireField(dataType, name));
                    dataType = new StructType(fields);
                })
                .when(Histogram, () => Histogram.dataType);
        }
        return dataType;
    }

    private requireField(name: string) {
        return DataStream.requireField(this.dataType, name);
    }

    private static requireField(dataType: DataType, name: string): StructField {
        if (!(dataType instanceof StructType)) {
            throw new Error(`Field ${name} not present in scalar type ${dataType.typeName()}`);
        }
        const path = name.indexOf('(') >= 0 ? [name] : name.split('.');
        const fieldName = path.shift();
        const field = dataType.fields.find((field: StructField) => field.name === fieldName);
        if (!field) {
            throw new Error(`Field ${name} not present in data type`);
        }

        if (!path.length) {
            return field;
        } else if (field.dataType instanceof StructType) {
            return DataStream.requireField(field.dataType, path.join('.'));
        } else {
            throw new Error(`No field ${path[0]} in ${fieldName} (${field.dataType.typeName()} is not a struct)`)
        }
    }
}
