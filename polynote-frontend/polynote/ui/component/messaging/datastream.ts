import {
    CancelTasks, Error as ErrorMsg,
    GroupAgg,
    HandleData,
    Message, ModifyStream,
    QuantileBin,
    ReleaseHandle,
    Select,
    TableOp
} from "../../../data/messages";
import {DoubleType, LongType, NumericTypes, StructField, StructType} from "../../../data/data_type";
import {MessageListener, SocketSession} from "../../../comms";
import {DataReader, Pair} from "../../../data/codec";
import {Either, Left, Right} from "../../../data/types";
import match from "../../../util/match";
import {StreamingDataRepr} from "../../../data/value_repr";
import {
    ModifyDataStream,
    NotebookMessageDispatcher,
    RequestCancelTasks,
    RequestDataBatch,
    StopDataStream
} from "./dispatcher";
import {NotebookState, NotebookStateHandler} from "../state/notebook_state";
import {Observer, StateHandler} from "../state/state_handler";

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
 * TODO: move back into value_repr?
 */
export class DataStream {
    readonly mods: TableOp[];
    readonly dataType: StructType;
    private batchSize = 50;
    private receivedCount = 0;
    terminated = false;
    private stopAfter = Infinity;
    private listener?: (batch: any[]) => void;
    private runListener?: (batch: any[]) => void;
    private socketListener?: MessageListener;
    private onComplete?: <T>(value?: T | PromiseLike<T>) => void;
    private _onError: (reason?: any) => void = _ => {};
    private nextPromise?: {resolve: <T>(value?: T | PromiseLike<T>) => void, reject: (reason?: any) => void}; // holds a Promise's `resolve` and `reject` inputs.
    private setupPromise?: Promise<Message | void>;
    private repr: StreamingDataRepr;
    private activeStreams: StateHandler<NotebookState["activeStreams"]>;
    private observer?: Observer<NotebookState["activeStreams"]>;

    constructor(private readonly dispatcher: NotebookMessageDispatcher, private readonly nbState: NotebookStateHandler, private readonly originalRepr: StreamingDataRepr, mods?: TableOp[]) {
        this.mods = mods ?? [];
        this.repr = originalRepr;
        this.dataType = originalRepr.dataType;
        this.dataType = this.finalDataType();

        this.activeStreams = nbState.view("activeStreams")
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
                            if (newRepr) this.repr = newRepr
                        })
                })

                // clear messages now that they have been processed.
                this.activeStreams.updateState(streams => {
                    return {
                        ...streams,
                        [this.repr.handle]: []
                    }
                })
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
            this.dispatcher.dispatch(new StopDataStream(StreamingDataRepr.handleTypeId, this.repr.handle))
        }

        if (this.observer)  {
            this.activeStreams.removeObserver(this.observer)
            this.observer = undefined
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
        return new DataStream(this.dispatcher, this.nbState, this.originalRepr, [...this.mods, mod]);
    }

    bin(col: string, binCount: number, err?: number) {
        if (err === undefined) {
            err = 1.0 / binCount;
        }
        const field = this.requireField(col);
        if (NumericTypes.indexOf(field.dataType) < 0) {
            throw new Error(`Field ${col} must be a numeric type to use bin()`);
        }
        return new DataStream(this.dispatcher, this.nbState, this.originalRepr, [...this.mods, new QuantileBin(col, binCount, err)]);
    }

    select(...cols: string[]) {
        const fields = cols.map(col => this.requireField(col));
        const mod = new Select(cols);
        return new DataStream(this.dispatcher, this.nbState, this.originalRepr, [...this.mods, mod]);
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
        this.dispatcher.dispatch(new RequestCancelTasks())
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
        this.dispatcher.dispatch(new RequestDataBatch(StreamingDataRepr.handleTypeId, this.repr.handle, this.batchSize))
    }
    private decodeValues(data: ArrayBuffer[]) {
        return data.map(buf => this.repr.dataType.decodeBuffer(new DataReader(buf)));
    }

    private setupStream() {
        if (!this.setupPromise) {
            this.setupPromise = new Promise((resolve, reject) => {
                const handleId = this.repr.handle;
                this.dispatcher.dispatch(new ModifyDataStream(handleId, this.mods))
                const obs = this.activeStreams.addObserver(handles => {
                    const messages = handles[handleId]
                    if (messages.length > 0) {
                        messages.forEach(msg => {
                            if (msg instanceof ModifyStream && msg.fromHandle === handleId) {
                                resolve(msg)
                                this.activeStreams.removeObserver(obs)
                            }
                        })
                    }
                })
            })
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
