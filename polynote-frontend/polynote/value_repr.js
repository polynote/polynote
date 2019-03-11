'use strict';

import {
    Codec, DataReader, DataWriter, discriminated, combined, arrayCodec, bufferCodec, optional,
    str, shortStr, tinyStr, uint8, uint16, int32, uint32
} from './codec.js'

import { DataType } from './data_type.js'
import {HandleData} from "./messages";

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


/**
 * An API for streaming data out of a StreamingDataRepr
 */
export class DataStream extends EventTarget {
    constructor(path, repr, socket) {
        super();
        this.path = path;
        this.repr = repr;
        this.socket = socket;

        this.batchSize = 50;

        this.receivedCount = 0;
        this.terminated = false;
        this.stopAfter = Infinity;

        const decodeValues = data => data.map(buf => repr.dataType.decodeBuffer(new DataReader(buf)));

        this.socketListener = this.socket.addMessageListener(HandleData, (path, handleType, handleId, count, data) => {
            if (path === this.path && handleType === StreamingDataRepr.handleTypeId && handleId === repr.handle) {
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
        if (this.nextPromise) {
            this.nextPromise.reject("Stream was terminated")
        }
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
        requestNext.call(this);
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
        
        return new Promise(
            (resolve, reject) => {
                this.nextPromise = {resolve, reject};
                requestNext.call(this);
            }
        );
    }


}