'use strict';

import {
    DataReader, DataWriter, Codec, combined, arrayCodec, discriminated, optional, mapCodec,
    str, shortStr, tinyStr, uint8, uint16, int32, uint32, bool
} from './codec.js'

import { Result, KernelErrorWithCause } from './result.js'

export class Message {
    static decode(data) {
        return Codec.decode(Message.codec, data);
    }

    static encode(msg) {
        return Codec.encode(Message.codec, msg);
    }

    encode() {
        return Message.encode(this);
    }
}

export class Error extends Message {
    static get msgTypeId() { return 0; }

    static unapply(inst) {
        return [inst.code, inst.error];
    }

    constructor(code, error) {
        super(code, error);
        this.code = code;
        this.error = error;
        Object.freeze(this);
    }
}

Error.codec = combined(uint16, KernelErrorWithCause.codec).to(Error);

export class LoadNotebook extends Message {
    static get msgTypeId() { return 1; }

    static unapply(inst) {
        return [inst.path];
    }

    constructor(path) {
        super(path);
        this.path = path;
        Object.freeze(this);
    }
}

LoadNotebook.codec = combined(shortStr).to(LoadNotebook);

export class CellMetadata {
    static unapply(inst) {
        return [inst.disableRun, inst.hideSource, inst.hideOutput];
    }

    constructor(disableRun, hideSource, hideOutput) {
        this.disableRun = disableRun;
        this.hideSource = hideSource;
        this.hideOutput = hideOutput;
        Object.freeze(this);
    }
}

CellMetadata.codec = combined(bool, bool, bool).to(CellMetadata);

export class NotebookCell {
    static unapply(inst) {
        return [inst.id, inst.language, inst.content, inst.results, inst.metadata];
    }

    constructor(id, language, content, results, metadata) {
        this.id = id;
        this.language = language;
        this.content = content || '';
        this.results = results || [];
        this.metadata = metadata || new CellMetadata(false, false, false);
        Object.freeze(this);
    }
}

NotebookCell.codec = combined(tinyStr, tinyStr, str, arrayCodec(uint16, Result.codec), CellMetadata.codec).to(NotebookCell);

export class RepositoryConfig {

}

export class IvyRepository extends RepositoryConfig {
    static unapply(inst) {
        return [inst.base, inst.artifactPattern, inst.metadataPattern, inst.changing];
    }

    static get msgTypeId() { return 0; }

    constructor(base, artifactPattern, metadataPattern, changing) {
        super();
        this.base = base;
        this.artifactPattern = artifactPattern;
        this.metadataPattern = metadataPattern;
        this.changing = changing;
        Object.freeze(this);
    }
}

IvyRepository.codec = combined(str, optional(str), optional(str), optional(bool)).to(IvyRepository);

export class MavenRepository extends RepositoryConfig {
    static unapply(inst) {
        return [inst.base, inst.changing];
    }

    static get msgTypeId() { return 1; }

    constructor(base, changing) {
        super();
        this.base = base;
        this.changing = changing;
    }
}

MavenRepository.codec = combined(str, optional(bool)).to(MavenRepository);

RepositoryConfig.codecs = [
    IvyRepository,  // 0
    MavenRepository // 1
];

RepositoryConfig.codec = discriminated(
    uint8,
    (msgTypeId) => RepositoryConfig.codecs[msgTypeId].codec,
    msg => msg.constructor.msgTypeId);

export class NotebookConfig {
    static unapply(inst) {
        return [inst.dependencies, inst.repositories];
    }

    constructor(dependencies, repositories) {
        this.dependencies = dependencies;
        this.repositories = repositories;
        Object.freeze(this);
    }

    static get default() {
        return new NotebookConfig([], []);
    }
}

NotebookConfig.codec = combined(
    optional(mapCodec(uint8, tinyStr, arrayCodec(uint8, tinyStr))),
    optional(arrayCodec(uint8, RepositoryConfig.codec))
).to(NotebookConfig);

export class NotebookCells extends Message {
    static get msgTypeId() { return 2; }

    static unapply(inst) {
        return [inst.path, inst.cells, inst.config];
    }

    constructor(path, cells, config) {
        super(path, cells);
        this.path = path;
        this.cells = cells;
        this.config = config;
        Object.freeze(this);
    }
}

NotebookCells.codec =
    combined(shortStr, arrayCodec(uint16, NotebookCell.codec), optional(NotebookConfig.codec)).to(NotebookCells);

export class RunCell extends Message {
    static get msgTypeId() { return 3; }

    static unapply(inst) {
        return [inst.notebook, inst.ids];
    }

    constructor(notebook, ids) {
        super(notebook, ids);
        this.notebook = notebook;
        this.ids = ids;
        Object.freeze(this);
    }
}

RunCell.codec = combined(shortStr, arrayCodec(uint16, tinyStr)).to(RunCell);

export class CellResult extends Message {
    static get msgTypeId() { return 4; }

    static unapply(inst) {
        return [inst.notebook, inst.id, inst.result]
    }

    constructor(notebook, id, result) {
        super(notebook, id, result);
        this.notebook = notebook;
        this.id = id;
        this.result = result;
        Object.freeze(this);
    }
}

CellResult.codec = combined(shortStr, tinyStr, Result.codec).to(CellResult);


export class ContentEdit {

    // TODO: starting to think overhead of scala.js would have been worth it to avoid duplicating all this logic...
    static rebase(a, b) {
        if (a instanceof Insert && b instanceof Insert) {
            if (a.pos < b.pos || (a.pos === b.pos && (a.content.length < b.content.length || a.content < b.content))) {
                return [[a], [new Insert(b.pos + a.content.length, b.content)]];
            } else {
                return [[new Insert(a.pos + b.content.length, a.content)], [b]];
            }
        } else if (a instanceof Insert) {
            if (a.pos <= b.pos) {
                return [[a], [new Delete(b.pos + a.content.length, b.length)]];
            } else if (a.pos < b.pos + b.length) {
                const beforeLength = a.pos - b.pos;
                return [[new Insert(b.pos, a.content)], [new Delete(b.pos, beforeLength), new Delete(b.pos + a.content.length, b.length - beforeLength)]];
            } else {
                return [[new Insert(a.pos - b.length, a.content)], [b]]
            }
        } else if (b instanceof Insert) {
            if (b.pos <= a.pos) {
                return [[new Delete(a.pos + b.content.length, a.length)], [b]];
            } else if (b.pos < a.pos + a.length) {
                const beforeLength = b.pos - a.pos;
                return [[new Delete(a.pos, beforeLength), new Delete(a.pos + b.content.length, a.length - beforeLength)], [new Insert(a.pos, b.content)]];
            } else {
                return [[a], [new Insert(b.pos - a.length, b.content)]];
            }
        } else {
            if (a.pos + a.length <= b.pos) {
                return [[a], [new Delete(b.pos - a.length, b.length)]];
            } else if (b.pos + b.length <= a.pos) {
                return [[new Delete(a.pos - b.length, a.length)], [b]];
            } else if (b.pos >= a.pos && b.pos + b.length <= a.pos + a.length) {
                return [[new Delete(a.pos, a.length - b.length)], []];
            } else if (a.pos >= b.pos && a.pos + a.length <= b.pos + b.length) {
                return [[], [new Delete(b.pos, b.length - a.length)]];
            } else if (b.pos > a.pos) {
                const overlap = a.pos + a.length - b.pos;
                return [[new Delete(a.pos, a.length - overlap)], [new Delete(a.pos, b.length - overlap)]];
            } else {
                const overlap = b.pos + b.length - b.pos;
                return [[new Delete(b.pos, a.length - overlap)], [new Delete(b.pos, b.length - overlap)]];
            }
        }
    }

    static rebaseAll(edit, edits) {
        const rebasedOther = [];
        let rebasedEdit = [edit];
        edits.forEach((b) => {
            let bs = [b];
            rebasedEdit = rebasedEdit.flatMap((a) => {
               if (bs.length === 0) return a;
               if (bs.length === 1) {
                   const rebased = ContentEdit.rebase(a, bs[0]);
                   bs = rebased[1];
                   return rebased[0];
               } else {
                   const rebased = ContentEdit.rebaseAll(a, bs);
                   bs = rebased[1];
                   return rebased[0];
               }
            });
            rebasedOther.push(...bs);
        });
        return [rebasedEdit, rebasedOther];
    }

    // port of ContentEdits#rebase, since there's no ContentEdits class here
    static rebaseEdits(edits1, edits2) {
        const result = [];
        let otherEdits = edits2;
        edits1.forEach((edit) => {
            const rebased = ContentEdit.rebaseAll(edit, otherEdits);
            result.push(...rebased[0]);
            otherEdits = rebased[1];
        });
        return result;
    }

    isEmpty() {
        return false;
    }

}

export class Insert extends ContentEdit {
    static get msgTypeId() { return 0; }

    static unapply(inst) {
        return [inst.pos, inst.content];
    }

    constructor(pos, content) {
        super(pos, content);
        this.pos = pos;
        this.content = content;
        Object.freeze(this);
    }

    isEmpty() {
        return this.content.length === 0;
    }
}

Insert.codec = combined(int32, str).to(Insert);

export class Delete extends ContentEdit {
    static get msgTypeId() { return 1; }

    static unapply(inst) {
        return [inst.pos, inst.length];
    }

    constructor(pos, length) {
        super(pos, length);
        this.pos = pos;
        this.length = length;
        Object.freeze(this)
    }

    isEmpty() {
        return this.length === 0;
    }
}

Delete.codec = combined(int32, int32).to(Delete);

ContentEdit.codecs = [Insert, Delete];

ContentEdit.codec = discriminated(
    uint8,
    msgTypeId => ContentEdit.codecs[msgTypeId].codec,
    msg => msg.constructor.msgTypeId
);

export class UpdateCell extends Message {
    static get msgTypeId() { return 5; }

    static unapply(inst) {
        return [inst.notebook, inst.globalVersion, inst.localVersion, inst.id, inst.edits];
    }

    constructor(notebook, globalVersion, localVersion, id, edits) {
        super(notebook, globalVersion, localVersion, id, edits);
        this.notebook = notebook;
        this.globalVersion = globalVersion;
        this.localVersion = localVersion;
        this.id = id;
        this.edits = edits;
        Object.freeze(this);
    }
}

UpdateCell.codec = combined(shortStr, uint32, uint32, tinyStr, arrayCodec(uint16, ContentEdit.codec)).to(UpdateCell);

export class InsertCell extends Message {
    static get msgTypeId() { return 6; }

    static unapply(inst) {
        return [inst.notebook, inst.globalVersion, inst.localVersion, inst.cell, inst.after];
    }

    constructor(notebook, globalVersion, localVersion, cell, after) {
        super(notebook, globalVersion, localVersion, cell, after);
        this.notebook = notebook;
        this.globalVersion = globalVersion;
        this.localVersion = localVersion;
        this.cell = cell;
        this.after = after;
        Object.freeze(this);
    }
}

InsertCell.codec = combined(shortStr, uint32, uint32, NotebookCell.codec, optional(tinyStr)).to(InsertCell);

export class ParamInfo {
    static unapply(inst) {
        return [inst.name, inst.type];
    }

    constructor(name, type) {
        this.name = name;
        this.type = type;
        Object.freeze(this);
    }
}

ParamInfo.codec = combined(tinyStr, shortStr).to(ParamInfo);

export class CompletionCandidate {
    static unapply(inst) {
        return [inst.name, inst.typeParams, inst.params, inst.type, inst.completionType];
    }

    constructor(name, typeParams, params, type, completionType) {
        this.name = name;
        this.typeParams = typeParams;
        this.params = params;
        this.type = type;
        this.completionType = completionType;
        Object.freeze(this);
    }
}

CompletionCandidate.codec =
    combined(tinyStr, arrayCodec(uint8, tinyStr), arrayCodec(uint8, arrayCodec(uint8, ParamInfo.codec)), shortStr, uint8).to(CompletionCandidate);

export class CompletionsAt extends Message {
    static get msgTypeId() { return 7; }

    static unapply(inst) {
        return [inst.notebook, inst.id, inst.pos, inst.completions];
    }

    constructor(notebook, id, pos, completions) {
        super(notebook, id, pos);
        this.notebook = notebook;
        this.id = id;
        this.pos = pos;
        this.completions = completions;
        Object.freeze(this);
    }
}

CompletionsAt.codec =
    combined(shortStr, tinyStr, int32, arrayCodec(uint16, CompletionCandidate.codec)).to(CompletionsAt);

export class ParameterHint {
    static unapply(inst) {
        return [inst.name, inst.typeName, inst.docString];
    }

    constructor(name, typeName, docString) {
        this.name = name;
        this.typeName = typeName;
        this.docString = docString;
        Object.freeze(this);
    }
}

ParameterHint.codec = combined(tinyStr, tinyStr, optional(shortStr)).to(ParameterHint);

export class ParameterHints {
    static unapply(inst) {
        return [inst.name, inst.docString, inst.parameters];
    }

    constructor(name, docString, parameters) {
        this.name = name;
        this.docString = docString;
        this.parameters = parameters;
        Object.freeze(this);
    }
}

ParameterHints.codec = combined(tinyStr, optional(shortStr), arrayCodec(uint8, ParameterHint.codec)).to(ParameterHints);

export class Signatures {
    static unapply(inst) {
        return [inst.hints, inst.activeSignature, inst.activeParameter];
    }

    constructor(hints, activeSignature, activeParameter) {
        this.hints = hints;
        this.activeSignature = activeSignature;
        this.activeParameter = activeParameter;
        Object.freeze(this);
    }
}

Signatures.codec = combined(arrayCodec(uint8, ParameterHints.codec), uint8, uint8).to(Signatures);

export class ParametersAt extends Message {
    static get msgTypeId() { return 8; }

    static unapply(inst) {
        return [inst.notebook, inst.id, inst.pos, inst.signatures];
    }

    constructor(notebook, id, pos, signatures) {
        super(notebook, id, pos, signatures);
        this.notebook = notebook;
        this.id = id;
        this.pos = pos;
        this.signatures = signatures;
        Object.freeze(this);
    }
}

ParametersAt.codec = combined(shortStr, tinyStr, int32, optional(Signatures.codec)).to(ParametersAt);

export class KernelStatusUpdate {
   constructor() {

   }
}

export class SymbolInfo {
    static unapply(inst) {
        return [inst.name, inst.typeName, inst.valueText, inst.availableViews];
    }

    constructor(name, typeName, valueText, availableViews) {
        this.name = name;
        this.typeName = typeName;
        this.valueText = valueText;
        this.availableViews = availableViews;
        Object.freeze(this);
    }
}

SymbolInfo.codec = combined(tinyStr, tinyStr, tinyStr, arrayCodec(uint8, tinyStr)).to(SymbolInfo);

export class UpdatedSymbols extends KernelStatusUpdate {
    static get msgTypeId() { return 0; }

    static unapply(inst) {
        return [inst.newOrUpdated, inst.removed];
    }

    constructor(newOrUpdated, removed) {
        super(newOrUpdated, removed);
        this.newOrUpdated = newOrUpdated;
        this.removed = removed;
        Object.freeze(this);
    }
}

UpdatedSymbols.codec = combined(arrayCodec(uint8, SymbolInfo.codec), arrayCodec(uint8, tinyStr)).to(UpdatedSymbols);

export const TaskStatus = Object.freeze({
    Complete: 0,
    Running: 1,
    Queued: 2,
    Error: 3
});

export class TaskInfo {
    static unapply(inst) {
        return [inst.id, inst.label, inst.detail, inst.status, inst.progress];
    }

    constructor(id, label, detail, status, progress) {
        this.id = id;
        this.label = label;
        this.detail = detail;
        this.status = status;
        this.progress = progress;
        Object.freeze(this);
    }
}

TaskInfo.codec = combined(tinyStr, tinyStr, tinyStr, uint8, uint8).to(TaskInfo);

export class UpdatedTasks extends KernelStatusUpdate {
    static get msgTypeId() { return 1; }

    static unapply(inst) {
        return [inst.tasks];
    }

    constructor(tasks) {
        super(tasks);
        this.tasks = tasks;
        Object.freeze(this);
    }
}

UpdatedTasks.codec = combined(arrayCodec(uint8, TaskInfo.codec)).to(UpdatedTasks);

export class KernelBusyState extends KernelStatusUpdate {
    static get msgTypeId() { return 2; }

    static unapply(inst) {
        return [inst.busy, inst.alive];
    }

    constructor(busy, alive) {
        super(busy, alive);
        this.busy = busy;
        this.alive = alive;
        Object.freeze(this);
    }
}

KernelBusyState.codec = combined(bool, bool).to(KernelBusyState);

export class KernelInfo extends KernelStatusUpdate {
    static get msgTypeId() { return 3; }

    static unapply(inst) {
        return [inst.content];
    }

    constructor(content) {
        super(content);
        this.content = content;
        Object.freeze(this);
    }
}

KernelInfo.codec = combined(mapCodec(uint8, shortStr, str)).to(KernelInfo);

KernelStatusUpdate.codecs = [
    UpdatedSymbols,   // 0
    UpdatedTasks,     // 1
    KernelBusyState,  // 2
    KernelInfo,       // 3
];

KernelStatusUpdate.codec = discriminated(
    uint8,
    msgTypeId => KernelStatusUpdate.codecs[msgTypeId].codec,
    msg => msg.constructor.msgTypeId
);

export class KernelStatus extends Message {
    static get msgTypeId() { return 9; }
    static unapply(inst) {
        return [inst.path, inst.update];
    }

    constructor(path, update) {
        super(path, update);
        this.path = path;
        this.update = update;
        Object.freeze(this);
    }
}

KernelStatus.codec = combined(shortStr, KernelStatusUpdate.codec).to(KernelStatus);

export class UpdateConfig extends Message {
    static get msgTypeId() { return 10; }
    static unapply(inst) {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.config];
    }

    constructor(path, globalVersion, localVersion, config) {
        super(path, globalVersion, localVersion, config);
        this.globalVersion = globalVersion;
        this.localVersion = localVersion;
        this.path = path;
        this.config = config;
        Object.freeze(this);
    }
}

UpdateConfig.codec = combined(shortStr, uint32, uint32, NotebookConfig.codec).to(UpdateConfig);

export class SetCellLanguage extends Message {
    static get msgTypeId() { return 11; }
    static unapply(inst) {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.id, inst.language];
    }

    constructor(path, globalVersion, localVersion, id, language) {
        super(path, globalVersion, localVersion, id, language);
        this.path = path;
        this.globalVersion = globalVersion;
        this.localVersion = localVersion;
        this.id = id;
        this.language = language;
        Object.freeze(this);
    }
}

SetCellLanguage.codec = combined(shortStr, uint32, uint32, tinyStr, tinyStr).to(SetCellLanguage);

export class StartKernel extends Message {
    static get msgTypeId() { return 12; }
    static unapply(inst) {
        return [inst.path, inst.level];
    }

    constructor(path, level) {
        super(path, level);
        this.path = path;
        this.level = level;
        Object.freeze(this);
    }

    static get NoRestart() { return 0; }
    static get WarmRestart() { return 1; }
    static get ColdRestart() { return 2; }
    static get Kill() { return 3; }
}

StartKernel.codec = combined(shortStr, uint8).to(StartKernel);

export class ListNotebooks extends Message {
    static get msgTypeId() { return 13; }
    static unapply(inst) {
        return [inst.notebooks];
    }

    constructor(notebooks) {
        super(notebooks);
        this.notebooks = notebooks;
        Object.freeze(this);
    }
}

ListNotebooks.codec = combined(arrayCodec(int32, shortStr)).to(ListNotebooks);

export class CreateNotebook extends Message {
    static get msgTypeId() { return 14; }
    static unapply(inst) {
        return [inst.path];
    }

    constructor(path) {
        super(path);
        this.path = path;
        Object.freeze(this);
    }
}

CreateNotebook.codec = combined(shortStr).to(CreateNotebook);

export class DeleteCell extends Message {
    static get msgTypeId() { return 15; }
    static unapply(inst) {
        return [inst.path, inst.globalVersion, inst.localVersion, inst.id];
    }

    constructor(path, globalVersion, localVersion, id) {
        super(path, globalVersion, localVersion, id);
        this.path = path;
        this.globalVersion = globalVersion;
        this.localVersion = localVersion;
        this.id = id;
        Object.freeze(this);
    }
}

DeleteCell.codec = combined(shortStr, uint32, uint32, tinyStr).to(DeleteCell);

export class ServerHandshake extends Message {
    static get msgTypeId() { return 16; }
    static unapply(inst) {
        return [inst.interpreters];
    }

    constructor(interpreters) {
        super(interpreters);
        this.interpreters = interpreters;
        Object.freeze(this);
    }
}

ServerHandshake.codec = combined(mapCodec(uint8, tinyStr, tinyStr)).to(ServerHandshake);

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
];


Message.codec = discriminated(
    uint8,
    (msgTypeId) => Message.codecs[msgTypeId].codec,
    (msg) => msg.constructor.msgTypeId
);


