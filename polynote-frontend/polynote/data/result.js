'use strict';

import {
    Codec, DataReader, DataWriter, discriminated, combined, arrayCodec, optional,
    str, shortStr, tinyStr, uint8, uint16, int32, ior
} from './codec'

import { ValueRepr, StringRepr, MIMERepr } from './value_repr.js'
import {int16, int64} from "./codec";

export class Result {
    static decode(data) {
        return Codec.decode(Result.codec, data);
    }

    static encode(msg) {
        return Codec.encode(Result.codec, msg);
    }
}

export class Output extends Result {
    static get msgTypeId() { return 0; }

    static unapply(inst) {
        return [inst.contentType, inst.content];
    }

    constructor(contentType, content) {
        super(contentType, content);
        this.contentType = contentType;
        this.content = content;
        Object.freeze(this);
    }
}

Output.codec = combined(str, str).to(Output);

export class Position {
    static unapply(inst) {
        return [inst.source, inst.start, inst.end, inst.point];
    }

    constructor(source, start, end, point) {
        this.source = source;
        this.start = start;
        this.end = end;
        this.point = point;
        Object.freeze(this);
    }
}

Position.codec = combined(str, int32, int32, int32).to(Position);

export class KernelReport {
    static unapply(inst) {
        return [inst.position, inst.message, inst.severity];
    }

    constructor(position, message, severity) {
        this.position = position;
        this.message = message;
        this.severity = severity;
        Object.freeze(this);
    }

    get isError() {
        return this.severity === 2;
    }
}

KernelReport.codec = combined(Position.codec, str, int32).to(KernelReport);

export class CompileErrors extends Result {
    static get msgTypeId() { return 1; }

    static unapply(inst) {
        return [inst.reports];
    }

    constructor(reports) {
        super(reports);
        this.reports = reports;
        Object.freeze(this);
    }
}

CompileErrors.codec = combined(arrayCodec(int32, KernelReport.codec)).to(CompileErrors);

// maps to JVM stack trace element
export class StackTraceElement {
    static unapply(inst) {
        return [inst.className, inst.method, inst.file, inst.line];
    }

    constructor(className, method, file, line) {
        this.className = className;
        this.method = method;
        this.file = file;
        this.line = line;
        Object.freeze(this);
    }
}

StackTraceElement.codec = combined(str, str, str, int32).to(StackTraceElement);

// maps to JVM Throwable
// WARNING: not frozen (mutable)
export class KernelError {
    static unapply(inst) {
        return [inst.className, inst.message, inst.stackTrace];
    }

    constructor(className, message, stackTrace) {
        this.className = className;
        this.message = message;
        this.stackTrace = stackTrace;
        if (this.className.includes("UnrecoverableError")) {
            this.id = "Unrecoverable Error";
            this.extraContent = "Polynote encountered an unrecoverable error. Please reload your browser window to continue :-(";
        } else {
            this.id = "Kernel Error";
            this.extraContent = "Please see the console for more details";
        }
    }
}

KernelError.codec = combined(str, str, arrayCodec(uint16, StackTraceElement.codec)).to(KernelError);

export class KernelErrorWithCause extends KernelError {
    static unapply(inst) {
        return [inst.className, inst.message, inst.stackTrace, inst.cause];
    }

    constructor(className, message, stackTrace, cause) {
        super(className, message, stackTrace, cause);
        this.cause = cause;
        Object.freeze(this);
    }
}

KernelErrorWithCause.codec = Codec.map(
    arrayCodec(uint8, KernelError.codec),
    (kernelErrors) => {
        if (kernelErrors.length === 0) return null;

        var i = kernelErrors.length - 1;
        var current = new KernelErrorWithCause(kernelErrors[i].className, kernelErrors[i].message, kernelErrors[i].stackTrace, null);

        while (i > 0) {
            const next = kernelErrors[--i];
            current = new KernelErrorWithCause(next.className, next.message, next.stackTrace, current);
        }
        return current;
    },
    (withCause) => {
        if (withCause == null) return null;
        const errs = [];
        var current = withCause;
        var i = 0;
        while (i < 16 && current != null) {
            errs.push(new KernelError(current.className, current.message, current.stackTrace));
            current = current.cause;
            i++;
        }

        return errs;
    }
);

export class RuntimeError extends Result {
    static get msgTypeId() { return 2; }

    static unapply(inst) {
        return [inst.error];
    }

    constructor(error) {
        super(error);
        this.error = error;
        Object.freeze(this);
    }
}

RuntimeError.codec = combined(KernelErrorWithCause.codec).to(RuntimeError);
RuntimeError.fromJS = (err) => new RuntimeError(new KernelErrorWithCause(err.constructor.name, err.message || err.toString(), []));

export class ClearResults extends Result {
    static get msgTypeId() { return 3; }

    static unapply(inst) {
        return [];
    }

    constructor() {
        super();
        Object.freeze(this);
    }
}

ClearResults.instance = new ClearResults();
ClearResults.codec = Object.freeze({
  encode: (value, writer) => undefined,
  decode: (reader) => ClearResults.instance
});

export class PosRange {
    static unapply(inst) {
        return [inst.start, inst.end];
    }

    constructor(start, end) {
        this.start = start;
        this.end = end;
        Object.freeze(this);
    }
}

PosRange.codec = combined(int32, int32).to(PosRange);

export class ResultValue extends Result {
    static get msgTypeId() { return 4; }

    static unapply(inst) {
        return [inst.name, inst.typeName, inst.reprs, inst.sourceCell, inst.pos];
    }

    constructor(name, typeName, reprs, sourceCell, pos) {
        super(name, typeName, reprs, sourceCell, pos);
        this.name = name;
        this.typeName = typeName;
        if (reprs instanceof Array) { // conditional is so IntelliJ knows it's an array. Dem completions.
            this.reprs = reprs;
        }
        this.sourceCell = sourceCell;
        this.pos = pos;
        Object.freeze(this);
    }

    get valueText() {
        const index = this.reprs.findIndex(repr => repr instanceof StringRepr);
        if (index < 0) return "";
        return this.reprs[index].string;
    }

    /**
     * Get a default MIME type and string, for display purposes
     */
    get displayRepr() {
        // TODO: make this smarter
        let index = this.reprs.findIndex(repr => repr instanceof MIMERepr && repr.mimeType.startsWith("text/html"));
        if (index < 0)
            index = this.reprs.findIndex(repr => repr instanceof MIMERepr && repr.mimeType.startsWith("text/"));
        if (index < 0)
            index = this.reprs.findIndex(repr => repr instanceof MIMERepr);

        if (index < 0) {
            return ["text/plain", this.valueText];
        } else {
            return MIMERepr.unapply(this.reprs[index]);
        }

    }
}

ResultValue.codec = combined(tinyStr, tinyStr, arrayCodec(uint8, ValueRepr.codec), int16, optional(PosRange.codec)).to(ResultValue);


/**
 * A result originating on the client, from a client-side interpreter. It has to tell us how to display itself and
 * how it should be saved in the notebook.
 */
export class ClientResult extends Result {
    constructor() {
        super();
    }

    display(targetEl, cell) {
        throw new Error(`Class ${this.constructor.name} does not implement display()`);
    }

    toOutput() {
        throw new Error(`Class ${this.constructor.name} does not implement toOutput()`);
    }
}


export class ExecutionInfo extends Result {
    static get msgTypeId() { return 5; }

    static unapply(inst) {
        return [inst.startTs, inst.endTs];
    }

    constructor(startTs, endTs) {
        super(startTs, endTs);

        this.startTs = startTs;
        this.endTs = endTs;

        Object.freeze(this);
    }
}

ExecutionInfo.codec = combined(int64, optional(int64)).to(ExecutionInfo);

Result.codecs = [
  Output,           // 0
  CompileErrors,    // 1
  RuntimeError,     // 2
  ClearResults,     // 3
  ResultValue,      // 4
  ExecutionInfo,    // 5
];

Result.codec = discriminated(
    uint8,
    (msgTypeId) => Result.codecs[msgTypeId].codec,
    (result) => result.constructor.msgTypeId
);


