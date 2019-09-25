'use strict';

import {
    arrayCodec, bool, bufferCodec, Codec, CodecContainer, combined, discriminated, either, float64, int16, int32,
    mapCodec, optional, Pair, shortStr, str, tinyStr, uint16, uint32, uint8
} from './codec'

import {KernelErrorWithCause, Output, PosRange, Result} from './result'
import {StreamingDataRepr} from "./value_repr";
import {isEqual} from "../util/functions";
import {CellMetadata, NotebookCell, NotebookConfig} from "./data";
import {ContentEdit} from "./content_edit";
import {Left, Right} from "./types";
import {DataType} from "./data_type";

export abstract class Message extends CodecContainer {
    static codec: Codec<Message>;
    static codecs: typeof Message[];
    static msgTypeId: number;

    static decode(data: ArrayBuffer | DataView) {
        return Codec.decode(Message.codec, data);
    }

    static encode(msg: Message) {
        return Codec.encode(Message.codec, msg);
    }

    static unapply(inst: Message): any[] {return []}

    isResponse(other: Message): boolean {
        return false;
    }
}

export class Error extends Message {
    static codec = combined(uint16, KernelErrorWithCause.codec).to(Error);
    static get msgTypeId() { return 0; }

    static unapply(inst: Error): ConstructorParameters<typeof Error> {
        return [inst.code, inst.error];
    }

    constructor(readonly code: number, readonly error: KernelErrorWithCause) {
        super();
        Object.freeze(this);
    }
}


export class LoadNotebook extends Message {
    static codec = combined(shortStr).to(LoadNotebook);
    static get msgTypeId() { return 1; }

    static unapply(inst: LoadNotebook): ConstructorParameters<typeof LoadNotebook> {
        return [inst.path];
    }

    constructor(readonly path: string) {
        super();
        Object.freeze(this);
    }
}


export class NotebookCells extends Message {
    static codec =
        combined(shortStr, arrayCodec(uint16, NotebookCell.codec), optional(NotebookConfig.codec)).to(NotebookCells);
    static get msgTypeId() { return 2; }

    static unapply(inst: NotebookCells): ConstructorParameters<typeof NotebookCells> {
        return [inst.path, inst.cells, inst.config];
    }

    constructor(readonly path: string, readonly cells: NotebookCell[], readonly config?: NotebookConfig) {
        super();
        Object.freeze(this);
    }
}


export class RunCell extends Message {
    static codec = combined(shortStr, arrayCodec(uint16, uint16)).to(RunCell);
    static get msgTypeId() { return 3; }

    static unapply(inst: RunCell): ConstructorParameters<typeof RunCell> {
        return [inst.notebook, inst.ids];
    }

    constructor(readonly notebook: string, readonly ids: number[]) {
        super();
        Object.freeze(this);
    }
}

export class CellResult extends Message {
    static codec = combined(shortStr, int16, Result.codec).to(CellResult);
    static get msgTypeId() { return 4; }

    static unapply(inst: CellResult): ConstructorParameters<typeof CellResult> {
        return [inst.notebook, inst.id, inst.result]
    }

    constructor(readonly notebook: string, readonly id: number, readonly result: Result) {
        super();
        Object.freeze(this);
    }
}


export class NotebookUpdate extends Message {
    readonly path: string;
    readonly globalVersion: number;
    readonly localVersion: number;

    // any way to give this a better type? :(
    static unapply(inst: NotebookUpdate): any[] { return [inst]; }

    /**
     * Transform a so that it has the same effect when applied after b. Returns transformed a.
     * @seeScala polynote.messages.NotebookUpdate#rebase
     */
    static rebase(a: NotebookUpdate, b: NotebookUpdate | NotebookUpdate[]): NotebookUpdate {
        if (b instanceof Array) {
            let accum = a;
            b.forEach(update => {
                accum = NotebookUpdate.rebase(accum, update);
            });
            return accum;
        }

        if (a instanceof InsertCell && b instanceof InsertCell && a.after === b.after) {
            return new InsertCell(a.path, a.globalVersion, a.localVersion, b.cell, a.after);
        } else if (a instanceof UpdateCell && b instanceof UpdateCell && a.id === b.id) {
            return new UpdateCell(a.path, a.globalVersion, a.localVersion, a.id, ContentEdit.rebaseEdits(a.edits, b.edits), a.metadata || b.metadata);
        } else {
            return a;
        }
    }

}

export class UpdateCell extends NotebookUpdate {
    static codec =
        combined(shortStr, uint32, uint32, int16, arrayCodec(uint16, ContentEdit.codec), optional(CellMetadata.codec)).to(UpdateCell);
    static get msgTypeId() { return 5; }

    static unapply(inst: UpdateCell): ConstructorParameters<typeof UpdateCell> {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.id, inst.edits, inst.metadata];
    }

    constructor(readonly path: string, readonly globalVersion: number, readonly localVersion: number, readonly id: number,
                readonly edits: ContentEdit[], readonly metadata?: CellMetadata) {
        super();
        Object.freeze(this);
    }
}

export class InsertCell extends NotebookUpdate {
    static codec = combined(shortStr, uint32, uint32, NotebookCell.codec, int16).to(InsertCell);
    static get msgTypeId() { return 6; }

    static unapply(inst: InsertCell): ConstructorParameters<typeof InsertCell> {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.cell, inst.after];
    }

    constructor(readonly path: string, readonly globalVersion: number, readonly localVersion: number,
                readonly cell: NotebookCell, readonly after: number) {
        super();
        Object.freeze(this);
    }
}

export class ParamInfo {
    static codec = combined(tinyStr, shortStr).to(ParamInfo);
    static unapply(inst: ParamInfo): ConstructorParameters<typeof ParamInfo> {
        return [inst.name, inst.type];
    }

    constructor(readonly name: string, readonly type: string) {
        Object.freeze(this);
    }
}

export class CompletionCandidate {
    static codec =
        combined(tinyStr, arrayCodec(uint8, tinyStr), arrayCodec(uint8, arrayCodec(uint8, ParamInfo.codec)), shortStr, uint8).to(CompletionCandidate);
    static unapply(inst: CompletionCandidate): ConstructorParameters<typeof CompletionCandidate> {
        return [inst.name, inst.typeParams, inst.params, inst.type, inst.completionType];
    }

    constructor(readonly name: string, readonly typeParams: string[], readonly params: ParamInfo[][], readonly type: string, readonly completionType: number) {
        Object.freeze(this);
    }
}


export class CompletionsAt extends Message {
    static codec = combined(shortStr, int16, int32, arrayCodec(uint16, CompletionCandidate.codec)).to(CompletionsAt);

    static get msgTypeId() { return 7; }

    static unapply(inst: CompletionsAt): ConstructorParameters<typeof CompletionsAt> {
        return [inst.notebook, inst.id, inst.pos, inst.completions];
    }

    constructor(readonly notebook: string, readonly id: number, readonly pos: number, readonly completions: CompletionCandidate[]) {
        super();
        Object.freeze(this);
    }
}

export class ParameterHint {
    static codec = combined(tinyStr, tinyStr, optional(shortStr)).to(ParameterHint);
    static unapply(inst: ParameterHint): ConstructorParameters<typeof ParameterHint> {
        return [inst.name, inst.typeName, inst.docString];
    }

    constructor(readonly name: string, readonly typeName: string, readonly docString?: string) {
        Object.freeze(this);
    }
}

export class ParameterHints {
    static codec = combined(tinyStr, optional(shortStr), arrayCodec(uint8, ParameterHint.codec)).to(ParameterHints);
    static unapply(inst: ParameterHints): ConstructorParameters<typeof ParameterHints> {
        return [inst.name, inst.docString, inst.parameters];
    }

    constructor(readonly name: string, readonly docString?: string, readonly parameters: ParameterHint[] = []) {
        Object.freeze(this);
    }
}

export class Signatures {
    static codec = combined(arrayCodec(uint8, ParameterHints.codec), uint8, uint8).to(Signatures);
    static unapply(inst: Signatures): ConstructorParameters<typeof Signatures> {
        return [inst.hints, inst.activeSignature, inst.activeParameter];
    }

    constructor(readonly hints: ParameterHints[], readonly activeSignature: number, readonly activeParameter: number) {
        Object.freeze(this);
    }
}

export class ParametersAt extends Message {
    static codec = combined(shortStr, int16, int32, optional(Signatures.codec)).to(ParametersAt);
    static get msgTypeId() { return 8; }

    static unapply(inst: ParametersAt): ConstructorParameters<typeof ParametersAt> {
        return [inst.notebook, inst.id, inst.pos, inst.signatures];
    }

    constructor(readonly notebook: string, readonly id: number, readonly pos: number, readonly signatures?: Signatures) {
        super();
        Object.freeze(this);
    }
}

export abstract class KernelStatusUpdate extends CodecContainer {}

export class SymbolInfo {
    static codec = combined(tinyStr, tinyStr, tinyStr, arrayCodec(uint8, tinyStr)).to(SymbolInfo);
    static unapply(inst: SymbolInfo): ConstructorParameters<typeof SymbolInfo> {
        return [inst.name, inst.typeName, inst.valueText, inst.availableViews];
    }

    constructor(readonly name: string, readonly typeName: string, readonly valueText: string, readonly availableViews: string[]) {
        Object.freeze(this);
    }
}

export class UpdatedSymbols extends KernelStatusUpdate {
    static codec = combined(arrayCodec(uint8, SymbolInfo.codec), arrayCodec(uint8, tinyStr)).to(UpdatedSymbols);
    static get msgTypeId() { return 0; }

    static unapply(inst: UpdatedSymbols): ConstructorParameters<typeof UpdatedSymbols> {
        return [inst.newOrUpdated, inst.removed];
    }

    constructor(readonly newOrUpdated: SymbolInfo[], readonly removed: string[]) {
        super();
        Object.freeze(this);
    }
}

export const TaskStatus = Object.freeze({
    Complete: 0,
    Running: 1,
    Queued: 2,
    Error: 3
});

export class TaskInfo {
    static codec = combined(tinyStr, tinyStr, shortStr, uint8, uint8).to(TaskInfo);
    static unapply(inst: TaskInfo): ConstructorParameters<typeof TaskInfo> {
        return [inst.id, inst.label, inst.detail, inst.status, inst.progress];
    }

    constructor(readonly id: string, readonly label: string, readonly detail: string, readonly status: number,
                readonly progress: number) {
        Object.freeze(this);
    }
}

export class UpdatedTasks extends KernelStatusUpdate {
    static codec = combined(arrayCodec(uint8, TaskInfo.codec)).to(UpdatedTasks);
    static get msgTypeId() { return 1; }

    static unapply(inst: UpdatedTasks): ConstructorParameters<typeof UpdatedTasks> {
        return [inst.tasks];
    }

    constructor(readonly tasks: TaskInfo[]) {
        super();
        Object.freeze(this);
    }
}

export class KernelBusyState extends KernelStatusUpdate {
    static codec = combined(bool, bool).to(KernelBusyState);
    static get msgTypeId() { return 2; }

    static unapply(inst: KernelBusyState): ConstructorParameters<typeof KernelBusyState> {
        return [inst.busy, inst.alive];
    }

    constructor(readonly busy: boolean, readonly alive: boolean) {
        super();
        Object.freeze(this);
    }
}

export class KernelInfo extends KernelStatusUpdate {
    static codec = combined(mapCodec(uint8, shortStr, str)).to(KernelInfo);
    static get msgTypeId() { return 3; }

    static unapply(inst: KernelInfo): ConstructorParameters<typeof KernelInfo> {
        return [inst.content];
    }

    constructor(readonly content: Record<string, string>) {
        super();
        Object.freeze(this);
    }
}

export class ExecutionStatus extends KernelStatusUpdate {
    static codec = combined(int16, optional(PosRange.codec)).to(ExecutionStatus);
    static get msgTypeId() { return 4; }

    static unapply(inst: ExecutionStatus): ConstructorParameters<typeof ExecutionStatus> {
        return [inst.cellId, inst.pos];
    }

    constructor(readonly cellId: number, readonly pos?: PosRange) {
        super();
        Object.freeze(this);
    }
}

KernelStatusUpdate.codecs = [
    UpdatedSymbols,   // 0
    UpdatedTasks,     // 1
    KernelBusyState,  // 2
    KernelInfo,       // 3
    ExecutionStatus,  // 4
];

KernelStatusUpdate.codec = discriminated(
    uint8,
    msgTypeId => KernelStatusUpdate.codecs[msgTypeId].codec,
    msg => (msg.constructor as typeof Message).msgTypeId
);

export class KernelStatus extends Message {
    static codec = combined(shortStr, KernelStatusUpdate.codec).to(KernelStatus);
    static get msgTypeId() { return 9; }
    static unapply(inst: KernelStatus): ConstructorParameters<typeof KernelStatus> {
        return [inst.path, inst.update];
    }

    constructor(readonly path: string, readonly update: KernelStatusUpdate) {
        super();
        Object.freeze(this);
    }
}

export class UpdateConfig extends NotebookUpdate {
    static codec = combined(shortStr, uint32, uint32, NotebookConfig.codec).to(UpdateConfig);
    static get msgTypeId() { return 10; }
    static unapply(inst: UpdateConfig): ConstructorParameters<typeof UpdateConfig> {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.config];
    }

    constructor(readonly path: string, readonly globalVersion: number, readonly localVersion: number, readonly config: NotebookConfig) {
        super();
        Object.freeze(this);
    }
}

export class SetCellLanguage extends NotebookUpdate {
    static codec = combined(shortStr, uint32, uint32, int16, tinyStr).to(SetCellLanguage);
    static get msgTypeId() { return 11; }
    static unapply(inst: SetCellLanguage): ConstructorParameters<typeof SetCellLanguage> {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.id, inst.language];
    }

    constructor(readonly path: string, readonly globalVersion: number, readonly localVersion: number,
                readonly id: number, readonly language: string) {
        super();
        Object.freeze(this);
    }
}

export class StartKernel extends Message {
    static codec = combined(shortStr, uint8).to(StartKernel);
    static get msgTypeId() { return 12; }
    static unapply(inst: StartKernel): ConstructorParameters<typeof StartKernel> {
        return [inst.path, inst.level];
    }

    constructor(readonly path: string, readonly level: number) {
        super();
        Object.freeze(this);
    }

    static get NoRestart() { return 0; }
    static get WarmRestart() { return 1; }
    static get ColdRestart() { return 2; }
    static get Kill() { return 3; }
}

export class ListNotebooks extends Message {
    static codec = combined(arrayCodec(int32, shortStr)).to(ListNotebooks);
    static get msgTypeId() { return 13; }
    static unapply(inst: ListNotebooks): ConstructorParameters<typeof ListNotebooks> {
        return [inst.notebooks];
    }

    constructor(readonly notebooks: string[]) {
        super();
        Object.freeze(this);
    }
}

export class CreateNotebook extends Message {
    static codec = combined(shortStr, optional(either(shortStr, str))).to(CreateNotebook);
    static get msgTypeId() { return 14; }
    static unapply(inst: CreateNotebook): ConstructorParameters<typeof CreateNotebook> {
        return [inst.path, inst.uriOrContents];
    }

    constructor(readonly path: string, readonly uriOrContents?: Left<string> | Right<string>) {
        super();
        Object.freeze(this);
    }
}

export class DeleteCell extends NotebookUpdate {
    static codec = combined(shortStr, uint32, uint32, int16).to(DeleteCell);
    static get msgTypeId() { return 15; }
    static unapply(inst: DeleteCell): ConstructorParameters<typeof DeleteCell> {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.id];
    }

    constructor(readonly path: string, readonly globalVersion: number, readonly localVersion: number, readonly id: number) {
        super();
        Object.freeze(this);
    }
}

export class ServerHandshake extends Message {
    static codec = combined(mapCodec(uint8, tinyStr, tinyStr), tinyStr, tinyStr).to(ServerHandshake);
    static get msgTypeId() { return 16; }
    static unapply(inst: ServerHandshake): ConstructorParameters<typeof ServerHandshake> {
        return [inst.interpreters, inst.serverVersion, inst.serverCommit];
    }

    constructor(readonly interpreters: Record<string, string>, readonly serverVersion: string, readonly serverCommit: string) {
        super();
        this.interpreters = interpreters;
        this.serverVersion = serverVersion;
        this.serverCommit = serverCommit;
        Object.freeze(this);
    }
}


export class HandleData extends Message {
    static codec = combined(shortStr, uint8, int32, int32, arrayCodec(int32, bufferCodec)).to(HandleData);
    static get msgTypeId() { return 17; }
    static unapply(inst: HandleData): ConstructorParameters<typeof HandleData>{
        return [inst.path, inst.handleType, inst.handle, inst.count, inst.data];
    }

    constructor(readonly path: string, readonly handleType: number, readonly handle: number, readonly count: number,
                readonly data: ArrayBuffer[]) {
        super();
        Object.freeze(this);
    }
}


export class CancelTasks extends Message {
    static codec = combined(shortStr).to(CancelTasks);
    static get msgTypeId() { return 18; }
    static unapply(inst: CancelTasks): ConstructorParameters<typeof CancelTasks> { return [inst.path]; }

    constructor(readonly path: string) {
        super();
        Object.freeze(this);
    }
}

export class TableOp extends Message {
    static codecs: any[];
}
export class GroupAgg extends TableOp {
    static codec = combined(arrayCodec(int32, str), arrayCodec(int32, Pair.codec(str, str))).to(GroupAgg);
    static get msgTypeId() { return 0; }
    static unapply(inst: GroupAgg): ConstructorParameters<typeof GroupAgg> { return [inst.columns, inst.aggregations]; }

    constructor(readonly columns: string[], readonly aggregations: Pair<string, string>[]) {
        super();
        this.columns = columns;
        this.aggregations = aggregations;
        Object.freeze(this);
    }
}

export class QuantileBin extends TableOp {
    static codec = combined(str, int32, float64).to(QuantileBin);
    static get msgTypeId() { return 1; }
    static unapply(inst: QuantileBin): ConstructorParameters<typeof QuantileBin> { return [inst.column, inst.binCount, inst.err]; }
    constructor(readonly column: string, readonly binCount: number, readonly err: number) {
        super();
        Object.freeze(this);
    }
}

export class Select extends TableOp {
    static codec = combined(arrayCodec(int32, str)).to(Select);
    static get msgTypeId() { return 2; }
    static unapply(inst: Select): ConstructorParameters<typeof Select> { return [inst.columns]; }
    constructor(readonly columns: string[]) {
        super();
        Object.freeze(this);
    }
}

TableOp.codecs = [
    GroupAgg,
    QuantileBin,
    Select
];

TableOp.codec = discriminated(uint8, msgTypeId => TableOp.codecs[msgTypeId].codec, msg => (msg.constructor as typeof Message).msgTypeId);

export class ModifyStream extends Message {
    static codec = combined(shortStr, int32, arrayCodec(uint8, TableOp.codec), optional(StreamingDataRepr.codec)).to(ModifyStream);
    static get msgTypeId() { return 19; }
    static unapply(inst: ModifyStream): ConstructorParameters<typeof ModifyStream> {
        return [inst.path, inst.fromHandle, inst.ops, inst.newRepr];
    }
    constructor(readonly path: string, readonly fromHandle: number, readonly ops: TableOp[], readonly newRepr?: StreamingDataRepr) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        if (!(other instanceof ModifyStream) || other.path !== this.path || other.fromHandle !== this.fromHandle)
            return false;

        return isEqual(this.ops, other.ops);
    }
}

export class ReleaseHandle extends Message {
    static codec = combined(shortStr, uint8, int32).to(ReleaseHandle);
    static get msgTypeId() { return 20; }
    static unapply(inst: ReleaseHandle): ConstructorParameters<typeof ReleaseHandle> {
        return [inst.path, inst.handleType, inst.handleId];
    }
    constructor(readonly path: string, readonly handleType: number, readonly handleId: number) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof ReleaseHandle &&
            other.path === this.path &&
            other.handleType === this.handleType &&
            other.handleId === this.handleId;
    }
}

export class ClearOutput extends Message {
    static codec = combined(shortStr).to(ClearOutput);
    static get msgTypeId() { return 21; }

    static unapply(inst: ClearOutput): ConstructorParameters<typeof ClearOutput> {
        return [inst.path];
    }

    constructor(readonly path: string) {
        super();
        Object.freeze(this);
    }
}

export class SetCellOutput extends NotebookUpdate {
    static codec = combined(shortStr, uint32, uint32, int16, optional(Output.codec)).to(SetCellOutput);
    static get msgTypeId() { return 22; }
    static unapply(inst: SetCellOutput): ConstructorParameters<typeof SetCellOutput> {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.id, inst.output]
    }

    constructor(readonly path: string, readonly globalVersion: number, readonly localVersion: number, readonly id: number, readonly output?: Output) {
        super();
        Object.freeze(this);
    }
}

export class NotebookVersion extends Message {
    static codec = combined(shortStr, uint32).to(NotebookVersion);
    static get msgTypeId() { return 23; }

    static unapply(inst: NotebookVersion): ConstructorParameters<typeof NotebookVersion> {
        return [inst.path, inst.globalVersion];
    }

    constructor(readonly path: string, readonly globalVersion: number) {
        super();
        Object.freeze(this);
    }
}

export class RunningKernels extends Message {
    static codec = combined(arrayCodec(uint8, KernelStatus.codec)).to(RunningKernels);
    static get msgTypeId() { return 24; }

    static unapply(inst: RunningKernels): ConstructorParameters<typeof RunningKernels> {
        return [inst.kernelStatuses];
    }

    constructor(readonly kernelStatuses: KernelStatus[]) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof RunningKernels
    }
}

Message.codecs = [
    Error,           // 0
    LoadNotebook,    // 1
    NotebookCells,   // 2
    RunCell,         // 3
    CellResult,      // 4
    UpdateCell,      // 5
    InsertCell,      // 6
    CompletionsAt,   // 7
    ParametersAt,    // 8
    KernelStatus,    // 9
    UpdateConfig,    // 10
    SetCellLanguage, // 11
    StartKernel,     // 12
    ListNotebooks,   // 13
    CreateNotebook,  // 14
    DeleteCell,      // 15
    ServerHandshake, // 16
    HandleData,      // 17
    CancelTasks,     // 18
    ModifyStream,    // 19
    ReleaseHandle,   // 20
    ClearOutput,     // 21
    SetCellOutput,   // 22
    NotebookVersion, // 23
    RunningKernels,  // 24
];


Message.codec = discriminated(
    uint8,
    (msgTypeId) => Message.codecs[msgTypeId].codec,
    (msg) => (msg.constructor as typeof Message).msgTypeId
);


