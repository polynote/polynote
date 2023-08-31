'use strict';

import {
    arrayCodec,
    bool,
    bufferCodec,
    Codec,
    CodecContainer,
    combined,
    discriminated,
    either,
    float64,
    int16,
    int32,
    int64,
    mapCodec,
    optional,
    Pair,
    shortStr,
    str,
    tinyStr,
    uint16,
    uint32,
    uint8
} from './codec'

import {Output, PosRange, Result, ServerErrorWithCause} from './result'
import {StreamingDataRepr} from "./value_repr";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig, SparkPropertySet} from "./data";
import {ContentEdit} from "./content_edit";
import {Left, Right} from "./codec_types";
import {deepEquals} from "../util/helpers";
import {DoubleType, LongType, StructField, StructType} from "./data_type";

const cellID = int16

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
    static codec = combined(uint16, ServerErrorWithCause.codec).to(Error);
    static get msgTypeId() { return 0; }

    static unapply(inst: Error): ConstructorParameters<typeof Error> {
        return [inst.code, inst.error];
    }

    constructor(readonly code: number, readonly error: ServerErrorWithCause) {
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


// NOTE: maps to backend's `Notebook` message
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
    static codec = combined(arrayCodec(uint16, cellID)).to(RunCell);
    static get msgTypeId() { return 3; }

    static unapply(inst: RunCell): ConstructorParameters<typeof RunCell> {
        return [inst.ids];
    }

    constructor(readonly ids: number[]) {
        super();
        Object.freeze(this);
    }
}

export class CellResult extends Message {
    static codec = combined(cellID, Result.codec).to(CellResult);
    static get msgTypeId() { return 4; }

    static unapply(inst: CellResult): ConstructorParameters<typeof CellResult> {
        return [inst.id, inst.result]
    }

    constructor(readonly id: number, readonly result: Result) {
        super();
        Object.freeze(this);
    }
}


export class NotebookUpdate extends Message {
    readonly globalVersion: number;
    readonly localVersion: number;

    // any way to give this a better type? :(
    static unapply(inst: NotebookUpdate): any[] {
        return [inst];
    }

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
            return new InsertCell(a.globalVersion, a.localVersion, b.cell, a.after);
        } else if (a instanceof UpdateCell && b instanceof UpdateCell && a.id === b.id) {
            return new UpdateCell(a.globalVersion, a.localVersion, a.id, ContentEdit.rebaseEdits(a.edits, b.edits), a.metadata || b.metadata);
        } else {
            return a;
        }
    }

}

export class UpdateCell extends NotebookUpdate {
    static codec =
        combined(uint32, uint32, cellID, arrayCodec(uint16, ContentEdit.codec), optional(CellMetadata.codec)).to(UpdateCell);
    static get msgTypeId() { return 5; }

    static unapply(inst: UpdateCell): ConstructorParameters<typeof UpdateCell> {
        return [inst.globalVersion, inst.localVersion, inst.id, inst.edits, inst.metadata];
    }

    constructor(readonly globalVersion: number, readonly localVersion: number, readonly id: number,
                readonly edits: ContentEdit[], readonly metadata?: CellMetadata) {
        super();
        Object.freeze(this);
    }
}

export class InsertCell extends NotebookUpdate {
    static codec = combined(uint32, uint32, NotebookCell.codec, cellID).to(InsertCell);
    static get msgTypeId() { return 6; }

    static unapply(inst: InsertCell): ConstructorParameters<typeof InsertCell> {
        return [inst.globalVersion, inst.localVersion, inst.cell, inst.after];
    }

    constructor(readonly globalVersion: number, readonly localVersion: number,
                readonly cell: NotebookCell, readonly after: number) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof InsertCell &&
            other.after == this.after &&
            other.cell.language == this.cell.language &&
            other.cell.content == this.cell.content
    }
}

export class CreateComment extends NotebookUpdate {
    static codec = combined(uint32, uint32, cellID, CellComment.codec).to(CreateComment);
    static get msgTypeId() { return 29; }

    static unapply(inst: CreateComment): ConstructorParameters<typeof CreateComment> {
        return [inst.globalVersion, inst.localVersion, inst.cellId, inst.comment];
    }

    constructor(readonly globalVersion: number, readonly localVersion: number,
                readonly cellId: number, readonly comment: CellComment) {
        super();
        Object.freeze(this);
    }
}

export class UpdateComment extends NotebookUpdate {
    static codec = combined(uint32, uint32, cellID, tinyStr, PosRange.codec, shortStr).to(UpdateComment);
    static get msgTypeId() { return 30; }

    static unapply(inst: UpdateComment): ConstructorParameters<typeof UpdateComment> {
        return [inst.globalVersion, inst.localVersion, inst.cellId, inst.commentId, inst.range, inst.content];
    }

    constructor(readonly globalVersion: number, readonly localVersion: number, readonly cellId: number,
                readonly commentId: string, readonly range: PosRange, readonly content: string) {
        super();
        Object.freeze(this);
    }
}

export class DeleteComment extends NotebookUpdate {
    static codec = combined(uint32, uint32, cellID, tinyStr).to(DeleteComment);
    static get msgTypeId() { return 31; }

    static unapply(inst: DeleteComment): ConstructorParameters<typeof DeleteComment> {
        return [inst.globalVersion, inst.localVersion, inst.cellId, inst.commentId];
    }

    constructor(readonly globalVersion: number, readonly localVersion: number,
                readonly cellId: number, readonly commentId: string) {
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
        combined(tinyStr, arrayCodec(uint8, tinyStr), arrayCodec(uint8, arrayCodec(uint8, ParamInfo.codec)), shortStr, uint8, optional(shortStr)).to(CompletionCandidate);
    static unapply(inst: CompletionCandidate): ConstructorParameters<typeof CompletionCandidate> {
        return [inst.name, inst.typeParams, inst.params, inst.type, inst.completionType, inst.insertText];
    }

    constructor(readonly name: string, readonly typeParams: string[], readonly params: ParamInfo[][], readonly type: string, readonly completionType: number, readonly insertText?: string) {
        Object.freeze(this);
    }
}


export class CompletionsAt extends Message {
    static codec = combined(cellID, int32, arrayCodec(uint16, CompletionCandidate.codec)).to(CompletionsAt);

    static get msgTypeId() { return 7; }

    static unapply(inst: CompletionsAt): ConstructorParameters<typeof CompletionsAt> {
        return [inst.id, inst.pos, inst.completions];
    }

    constructor(readonly id: number, readonly pos: number, readonly completions: CompletionCandidate[]) {
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
    static codec = combined(cellID, int32, optional(Signatures.codec)).to(ParametersAt);
    static get msgTypeId() { return 8; }

    static unapply(inst: ParametersAt): ConstructorParameters<typeof ParametersAt> {
        return [inst.id, inst.pos, inst.signatures];
    }

    constructor(readonly id: number, readonly pos: number, readonly signatures?: Signatures) {
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

type TaskStatus = typeof TaskStatus[keyof typeof TaskStatus];

export class TaskInfo {
    static codec = combined(tinyStr, tinyStr, shortStr, uint8, uint8, optional(tinyStr)).to(TaskInfo);
    static unapply(inst: TaskInfo): ConstructorParameters<typeof TaskInfo> {
        return [inst.id, inst.label, inst.detail, inst.status, inst.progress, inst.parent];
    }

    constructor(readonly id: string, readonly label: string, readonly detail: string, readonly status: TaskStatus,
                readonly progress: number, readonly parent?: string) {
        Object.freeze(this);
    }
}

export class UpdatedTasks extends KernelStatusUpdate {
    static codec = combined(arrayCodec(uint16, TaskInfo.codec)).to(UpdatedTasks);
    static get msgTypeId() { return 1; }

    static unapply(inst: UpdatedTasks): ConstructorParameters<typeof UpdatedTasks> {
        return [inst.tasks];
    }

    constructor(readonly tasks: TaskInfo[]) {
        super();
        Object.freeze(this);
    }
}

export type KernelStatusString = 'busy' | 'idle' | 'dead' | 'disconnected';
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

    get asStatus(): KernelStatusString {
        if (this.busy) return 'busy'
        else if (this.alive) return 'idle'
        else return 'dead'
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
    static codec = combined(cellID, optional(PosRange.codec)).to(ExecutionStatus);
    static get msgTypeId() { return 4; }

    static unapply(inst: ExecutionStatus): ConstructorParameters<typeof ExecutionStatus> {
        return [inst.cellId, inst.pos];
    }

    constructor(readonly cellId: number, readonly pos?: PosRange) {
        super();
        Object.freeze(this);
    }
}

export class Presence {
    static codec = combined(int32, tinyStr, optional(shortStr)).to(Presence);
    static unapply(inst: Presence): ConstructorParameters<typeof Presence> { return [inst.id, inst.name, inst.avatar]; }
    constructor(readonly id: number, readonly name: string, readonly avatar?: string) { Object.freeze(this); }
}

export class PresenceUpdate extends KernelStatusUpdate {
    static codec = combined(arrayCodec(uint8, Presence.codec), arrayCodec(uint8, int32)).to(PresenceUpdate);
    static get msgTypeId() { return 5; }
    static unapply(inst: PresenceUpdate): ConstructorParameters<typeof PresenceUpdate> {
        return [inst.added, inst.removed];
    }

    constructor(readonly added: Presence[], readonly removed: number[]) {
        super();
        Object.freeze(this);
    }
}

export class PresenceSelection extends KernelStatusUpdate {
    static codec = combined(int32, cellID, PosRange.codec).to(PresenceSelection);
    static get msgTypeId() { return 6; }
    static unapply(inst: PresenceSelection): ConstructorParameters<typeof PresenceSelection> {
        return [inst.presenceId, inst.cellId, inst.range];
    }

    constructor(readonly presenceId: number, readonly cellId: number, readonly range: PosRange) {
        super();
        Object.freeze(this);
    }
}

export class KernelError extends KernelStatusUpdate {
    static codec = combined(ServerErrorWithCause.codec).to(KernelError);
    static get msgTypeId() { return 7; }
    static unapply(inst: KernelError): ConstructorParameters<typeof KernelError> { return [inst.err] }
    constructor(readonly err: ServerErrorWithCause) {
        super();
        Object.freeze(this);
    }
}

export class CellStatusUpdate extends KernelStatusUpdate {
    static codec = combined(cellID, uint8).to(CellStatusUpdate);
    static get msgTypeId() { return 8; }

    static unapply(inst: CellStatusUpdate): ConstructorParameters<typeof CellStatusUpdate> {
        return [inst.cellId, inst.status];
    }

    constructor(readonly cellId: number, readonly status: TaskStatus) {
        super();
        Object.freeze(this);
    }
}

KernelStatusUpdate.codecs = [
    UpdatedSymbols,    // 0
    UpdatedTasks,      // 1
    KernelBusyState,   // 2
    KernelInfo,        // 3
    ExecutionStatus,   // 4
    PresenceUpdate,    // 5
    PresenceSelection, // 6
    KernelError,       // 7
    CellStatusUpdate,  // 8
];

KernelStatusUpdate.codec = discriminated(
    uint8,
    msgTypeId => KernelStatusUpdate.codecs[msgTypeId].codec,
    msg => (msg.constructor as typeof Message).msgTypeId
);

export class KernelStatus extends Message {
    static codec = combined(KernelStatusUpdate.codec).to(KernelStatus);
    static get msgTypeId() { return 9; }
    static unapply(inst: KernelStatus): ConstructorParameters<typeof KernelStatus> {
        return [inst.update];
    }

    constructor(readonly update: KernelStatusUpdate) {
        super();
        Object.freeze(this);
    }
}

export class UpdateConfig extends NotebookUpdate {
    static codec = combined(uint32, uint32, NotebookConfig.codec).to(UpdateConfig);
    static get msgTypeId() { return 10; }
    static unapply(inst: UpdateConfig): ConstructorParameters<typeof UpdateConfig> {
        return [inst.globalVersion, inst.localVersion, inst.config];
    }

    constructor(readonly globalVersion: number, readonly localVersion: number, readonly config: NotebookConfig) {
        super();
        Object.freeze(this);
    }
}

export class SetCellLanguage extends NotebookUpdate {
    static codec = combined(uint32, uint32, cellID, tinyStr).to(SetCellLanguage);
    static get msgTypeId() { return 11; }
    static unapply(inst: SetCellLanguage): ConstructorParameters<typeof SetCellLanguage> {
        return [inst.globalVersion, inst.localVersion, inst.id, inst.language];
    }

    constructor(readonly globalVersion: number, readonly localVersion: number,
                readonly id: number, readonly language: string) {
        super();
        Object.freeze(this);
    }
}

export class StartKernel extends Message {
    static codec = combined(uint8).to(StartKernel);
    static get msgTypeId() { return 12; }
    static unapply(inst: StartKernel): ConstructorParameters<typeof StartKernel> {
        return [inst.level];
    }

    constructor(readonly level: number) {
        super();
        Object.freeze(this);
    }

    static get NoRestart() { return 0; }
    static get WarmRestart() { return 1; }
    static get ColdRestart() { return 2; }
    static get Kill() { return 3; }
}

export class FSNotebook {
    static codec = combined(shortStr, int64).to(FSNotebook);

    static unapply(inst: FSNotebook): ConstructorParameters<typeof FSNotebook> {
        return [inst.path, inst.lastSaved];
    }

    constructor(readonly path: string, readonly lastSaved: number) {
        Object.freeze(this);
    }
}

export class ListNotebooks extends Message {
    static codec = combined(arrayCodec(int32, FSNotebook.codec)).to(ListNotebooks);
    static get msgTypeId() { return 13; }
    static unapply(inst: ListNotebooks): ConstructorParameters<typeof ListNotebooks> {
        return [inst.notebooks];
    }

    constructor(readonly notebooks: FSNotebook[]) {
        super();
        Object.freeze(this);
    }
}

export class CreateNotebook extends Message {
    static codec = combined(shortStr, optional(str), optional(str)).to(CreateNotebook);
    static get msgTypeId() { return 14; }
    static unapply(inst: CreateNotebook): ConstructorParameters<typeof CreateNotebook> {
        return [inst.path, inst.content, inst.template];
    }

    constructor(readonly path: string, readonly content?: string, readonly template?: string) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof CreateNotebook && other.path === this.path;
    }
}

export class RenameNotebook extends Message {
    static codec = combined(shortStr, shortStr).to(RenameNotebook);
    static get msgTypeId() { return 25; }
    static unapply(inst: RenameNotebook): ConstructorParameters<typeof RenameNotebook> {
        return [inst.path, inst.newPath];
    }
    constructor(readonly path: string, readonly newPath: string) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return (other instanceof RenameNotebook) && other.path === this.path;
    }
}

export class CopyNotebook extends Message {
    static codec = combined(shortStr, shortStr).to(CopyNotebook);
    static get msgTypeId() { return 27; }
    static unapply(inst: CopyNotebook): ConstructorParameters<typeof RenameNotebook> {
        return [inst.path, inst.newPath];
    }
    constructor(readonly path: string, readonly newPath: string) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return (other instanceof CopyNotebook) && other.path === this.path;
    }
}

export class DeleteNotebook extends Message {
    static codec = combined(shortStr).to(DeleteNotebook);
    static get msgTypeId() { return 26; }
    static unapply(inst: DeleteNotebook): ConstructorParameters<typeof DeleteNotebook> {
        return [inst.path];
    }
    constructor(readonly path: string) {
        super();
        Object.freeze(this);
    }
}

export class DeleteCell extends NotebookUpdate {
    static codec = combined(uint32, uint32, cellID).to(DeleteCell);
    static get msgTypeId() { return 15; }
    static unapply(inst: DeleteCell): ConstructorParameters<typeof DeleteCell> {
        return [inst.globalVersion, inst.localVersion, inst.id];
    }

    constructor(readonly globalVersion: number, readonly localVersion: number, readonly id: number) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof DeleteCell &&
            other.id === this.id
    }
}

export class Identity {
    static codec = combined(tinyStr, optional(shortStr)).to(Identity);
    static unapply(inst: Identity): ConstructorParameters<typeof Identity> {
        return [inst.name, inst.avatar];
    }

    constructor(readonly name: string, readonly avatar: string | null) {
        Object.freeze(this);
    }
}

export class ServerHandshake extends Message {
    static codec = combined(mapCodec(uint8, tinyStr, tinyStr), tinyStr, tinyStr, optional(Identity.codec), arrayCodec(int32, SparkPropertySet.codec), arrayCodec(int32, shortStr), bool).to(ServerHandshake);
    static get msgTypeId() { return 16; }
    static unapply(inst: ServerHandshake): ConstructorParameters<typeof ServerHandshake> {
        return [inst.interpreters, inst.serverVersion, inst.serverCommit, inst.identity, inst.sparkTemplates, inst.notebookTemplates, inst.notifications];
    }

    constructor(readonly interpreters: Record<string, string>, readonly serverVersion: string, readonly serverCommit: string, readonly identity: Identity | null, readonly sparkTemplates: SparkPropertySet[], readonly notebookTemplates: string[], readonly notifications: boolean) {
        super();
        Object.freeze(this);
    }
}


export class HandleData extends Message {
    static codec = combined(uint8, int32, int32, either(Error.codec, arrayCodec(int32, bufferCodec))).to(HandleData);
    static get msgTypeId() { return 17; }
    static unapply(inst: HandleData): ConstructorParameters<typeof HandleData>{
        return [inst.handleType, inst.handle, inst.count, inst.data];
    }

    constructor(readonly handleType: number, readonly handle: number, readonly count: number,
                readonly data: Left<Error> | Right<ArrayBuffer[]>) {
        super();
        Object.freeze(this);
    }
}


export class CancelTasks extends Message {
    static codec = combined(shortStr, optional(tinyStr)).to(CancelTasks);
    static get msgTypeId() { return 18; }
    static unapply(inst: CancelTasks): ConstructorParameters<typeof CancelTasks> { return [inst.path, inst.taskId]; }

    constructor(readonly path: string, readonly taskId?: string) {
        super();
        this.taskId = taskId ?? undefined;
        Object.freeze(this);
    }
}

export abstract class TableOp extends Message {
    static codecs: any[];

    abstract streamCode(on: string): string
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

    streamCode(on: string): string {
        const aggSpecs = this.aggregations.map(pair => ({[pair.first]: pair.second}));
        return `${on}.aggregate(${JSON.stringify(this.columns)}, ${JSON.stringify(aggSpecs)})`
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

    streamCode(on: string): string {
        const args = [this.column, this.binCount, this.err].map(arg => JSON.stringify(arg)).join(', ');
        return `${on}.bin(${args})`
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

    streamCode(on: string): string {
        const args = this.columns.map(arg => JSON.stringify(arg)).join(', ');
        return `${on}.select(${args})`
    }
}

export class Sample extends TableOp {
    static codec = combined(float64).to(Sample);
    static get msgTypeId() { return 3; }
    static unapply(inst: Sample): ConstructorParameters<typeof Sample> { return [inst.sampleRate]; }
    constructor(readonly sampleRate: number) {
        super();
        Object.freeze(this);
    }

    streamCode(on: string): string {
        return `${on}.sample(${this.sampleRate})`
    }
}

export class SampleN extends TableOp {
    static codec = combined(int32).to(SampleN);
    static get msgTypeId() { return 4; }
    static unapply(inst: SampleN): ConstructorParameters<typeof Sample> { return [inst.n]; }
    constructor(readonly n: number) {
        super();
        Object.freeze(this);
    }

    streamCode(on: string): string {
        return `${on}.sampleN(${this.n})`
    }
}

export class Histogram extends TableOp {
    static codec = combined(str, int32).to(Histogram);
    static get msgTypeId() { return 5; }
    static unapply(inst: Histogram): ConstructorParameters<typeof Histogram> { return [inst.field, inst.binCount]; }

    static readonly dataType: StructType = new StructType([
        new StructField("start", DoubleType),
        new StructField("end", DoubleType),
        new StructField("count", LongType)
    ]);

    constructor(readonly field: string, readonly binCount: number) {
        super();
        Object.freeze(this);
    }

    streamCode(on: string): string {
        return `${on}.histogram(${JSON.stringify(this.field)}, ${this.binCount})`;
    }
}

TableOp.codecs = [
    GroupAgg,
    QuantileBin,
    Select,
    Sample,
    SampleN,
    Histogram
];

TableOp.codec = discriminated(uint8, msgTypeId => TableOp.codecs[msgTypeId].codec, msg => (msg.constructor as typeof Message).msgTypeId);

export class ModifyStream extends Message {
    static codec = combined(int32, arrayCodec(uint8, TableOp.codec), optional(StreamingDataRepr.codec)).to(ModifyStream);
    static get msgTypeId() { return 19; }
    static unapply(inst: ModifyStream): ConstructorParameters<typeof ModifyStream> {
        return [inst.fromHandle, inst.ops, inst.newRepr];
    }
    constructor(readonly fromHandle: number, readonly ops: TableOp[], readonly newRepr?: StreamingDataRepr) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        if (!(other instanceof ModifyStream) || other.fromHandle !== this.fromHandle)
            return false;

        return deepEquals(this.ops, other.ops);
    }
}

export class ReleaseHandle extends Message {
    static codec = combined(uint8, int32).to(ReleaseHandle);
    static get msgTypeId() { return 20; }
    static unapply(inst: ReleaseHandle): ConstructorParameters<typeof ReleaseHandle> {
        return [inst.handleType, inst.handleId];
    }
    constructor(readonly handleType: number, readonly handleId: number) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof ReleaseHandle &&
            other.handleType === this.handleType &&
            other.handleId === this.handleId;
    }
}

export class ClearOutput extends Message {
    static codec = combined().to(ClearOutput);
    static get msgTypeId() { return 21; }

    static unapply(inst: ClearOutput): ConstructorParameters<typeof ClearOutput> {
        return [];
    }

    constructor() {
        super();
        Object.freeze(this);
    }
}

export class SetCellOutput extends NotebookUpdate {
    static codec = combined(uint32, uint32, cellID, optional(Output.codec)).to(SetCellOutput);
    static get msgTypeId() { return 22; }
    static unapply(inst: SetCellOutput): ConstructorParameters<typeof SetCellOutput> {
        return [inst.globalVersion, inst.localVersion, inst.id, inst.output]
    }

    constructor(readonly globalVersion: number, readonly localVersion: number, readonly id: number, readonly output?: Output) {
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
    static codec = combined(arrayCodec(uint8, Pair.codec(shortStr, KernelBusyState.codec))).to(RunningKernels);
    static get msgTypeId() { return 24; }

    static unapply(inst: RunningKernels): ConstructorParameters<typeof RunningKernels> {
        return [inst.kernelStatuses];
    }

    constructor(readonly kernelStatuses: Pair<string, KernelBusyState>[]) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof RunningKernels
    }
}

export class CurrentSelection extends Message {
    static codec = combined(cellID, PosRange.codec).to(CurrentSelection);
    static get msgTypeId() { return 28; }
    static unapply(inst: CurrentSelection): ConstructorParameters<typeof CurrentSelection> {
        return [inst.cellID, inst.range];
    }

    constructor(readonly cellID: number, readonly range: PosRange) {
        super();
        Object.freeze(this);
    }
}

export class KeepAlive extends Message {
    static codec = combined(uint8).to(KeepAlive);
    static get msgTypeId() { return 32; }
    static unapply(inst: KeepAlive): ConstructorParameters<typeof KeepAlive> {
        return [inst.payload];
    }

    constructor(readonly payload: number) {
        super();
        Object.freeze(this);
    }
}

export class MoveCell extends NotebookUpdate {
    static codec = combined(uint32, uint32, cellID, cellID).to(MoveCell);
    static get msgTypeId() { return 33; }
    static unapply(inst: MoveCell): ConstructorParameters<typeof MoveCell> {
        return [inst.globalVersion, inst.localVersion, inst.cellId, inst.after];
    }
    constructor(readonly globalVersion: number, readonly localVersion: number, readonly cellId: number, readonly after: number) {
        super();
        Object.freeze(this);
    }
}

export class NotebookSearchResult {
    static codec = combined(shortStr, cellID, shortStr).to(NotebookSearchResult);

    static unapply(inst: NotebookSearchResult): ConstructorParameters<typeof NotebookSearchResult> {
        return [inst.path, inst.cellID, inst.cellContent];
    }

    constructor(readonly path: string, readonly cellID: number, readonly cellContent: string) {
        Object.freeze(this);
    }
}

export class SearchNotebooks extends Message {
    static codec = combined(shortStr, arrayCodec(int32, NotebookSearchResult.codec)).to(SearchNotebooks);
    static get msgTypeId() { return 34; }

    static unapply(inst: SearchNotebooks): ConstructorParameters<typeof SearchNotebooks> {
        return [inst.query, inst.results];
    }

    constructor(readonly query: string, readonly results: NotebookSearchResult[]) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof SearchNotebooks
    }
}

export class NotebookSaved extends Message {
    static codec = combined(shortStr, int64).to(NotebookSaved);
    static get msgTypeId() { return 35; }

    static unapply(inst: NotebookSaved): ConstructorParameters<typeof NotebookSaved> {
        return [inst.path, inst.timestamp];
    }

    constructor(readonly path: string, readonly timestamp: number) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return other instanceof NotebookSaved
    }
}

export class GoToDefinitionRequest extends Message {
    static codec = combined(either(str, cellID), int32, int32).to(GoToDefinitionRequest)
    static get msgTypeId() { return 36; }

    static unapply(inst: GoToDefinitionRequest): ConstructorParameters<typeof GoToDefinitionRequest> {
        return [inst.path, inst.pos, inst.reqId]
    }

    constructor(readonly path: Left<string> | Right<number>, readonly pos: number, readonly reqId: number) {
        super();
        Object.freeze(this);
    }

    isResponse(other: Message): boolean {
        return false;
    }
}

export class Location {
    static codec = combined(str, int32, int32).to(Location)

    static unapply(inst: Location): ConstructorParameters<typeof Location> {
        return [inst.uri, inst.line, inst.column];
    }
    constructor(readonly uri: string, readonly line: number, readonly column: number) {
        Object.freeze(this);
    }
}

export class GoToDefinitionResponse extends Message {
    static codec = combined(int32, arrayCodec(uint8, Location.codec)).to(GoToDefinitionResponse);
    static get msgTypeId() { return 37; }
    static unapply(inst: GoToDefinitionResponse): ConstructorParameters<typeof GoToDefinitionResponse> {
        return [inst.reqId, inst.location];
    }

    constructor(readonly reqId: number, readonly location: Location[]) {
        super();
        Object.freeze(this);
    }
}


Message.codecs = [
    Error,            // 0
    LoadNotebook,     // 1
    NotebookCells,    // 2
    RunCell,          // 3
    CellResult,       // 4
    UpdateCell,       // 5
    InsertCell,       // 6
    CompletionsAt,    // 7
    ParametersAt,     // 8
    KernelStatus,     // 9
    UpdateConfig,     // 10
    SetCellLanguage,  // 11
    StartKernel,      // 12
    ListNotebooks,    // 13
    CreateNotebook,   // 14
    DeleteCell,       // 15
    ServerHandshake,  // 16
    HandleData,       // 17
    CancelTasks,      // 18
    ModifyStream,     // 19
    ReleaseHandle,    // 20
    ClearOutput,      // 21
    SetCellOutput,    // 22
    NotebookVersion,  // 23
    RunningKernels,   // 24
    RenameNotebook,   // 25
    DeleteNotebook,   // 26
    CopyNotebook,     // 27
    CurrentSelection, // 28
    CreateComment,    // 29
    UpdateComment,    // 30
    DeleteComment,    // 31
    KeepAlive,        // 32
    MoveCell,         // 33
    SearchNotebooks,  // 34
    NotebookSaved,    // 35
    GoToDefinitionRequest,  // 36
    GoToDefinitionResponse, // 37
];


Message.codec = discriminated(
    uint8,
    (msgTypeId) => {
        const maybeMessage = Message.codecs[msgTypeId]
        if (maybeMessage) return maybeMessage.codec
        else throw new globalThis.Error(`Unable to find codec for id ${msgTypeId}`)
    },
    (msg) => (msg.constructor as typeof Message).msgTypeId
);


