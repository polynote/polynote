"use strict";

import { arrayCodec, bool, combined, discriminated, int16, mapCodec, optional, str, tinyStr, uint16, uint8 } from "./codec";
import {ExecutionInfo, Result} from "./result";

export class CellMetadata {
    static unapply(inst) {
        return [inst.disableRun, inst.hideSource, inst.hideOutput, inst.executionInfo];
    }

    constructor(disableRun, hideSource, hideOutput, executionInfo) {
        this.disableRun = disableRun || false;
        this.hideSource = hideSource || false;
        this.hideOutput = hideOutput || false;
        this.executionInfo = executionInfo || null;
        Object.freeze(this);
    }

    copy(props) {
        const disableRun = typeof props.disableRun !== 'undefined' ? props.disableRun : this.disableRun;
        const hideSource = typeof props.hideSource !== 'undefined' ? props.hideSource : this.hideSource;
        const hideOutput = typeof props.hideOutput !== 'undefined' ? props.hideOutput : this.hideOutput;
        const executionInfo = typeof props.executionInfo !== 'undefined' ? props.executionInfo : this.executionInfo;
        return new CellMetadata(disableRun, hideSource, hideOutput, executionInfo);
    }
}

CellMetadata.codec = combined(bool, bool, bool, optional(ExecutionInfo.codec)).to(CellMetadata);

export class NotebookCell {
    static unapply(inst) {
        return [inst.id, inst.language, inst.content, inst.results, inst.metadata];
    }

    constructor(id, language, content, results, metadata) {
        this.id = id;
        this.language = language;
        this.content = content || '';
        this.results = results || [];
        this.metadata = metadata || new CellMetadata(false, false, false, null);
        Object.freeze(this);
    }
}

NotebookCell.codec = combined(int16, tinyStr, str, arrayCodec(int16, Result.codec), CellMetadata.codec).to(NotebookCell);

export class RepositoryConfig {
}

export class IvyRepository extends RepositoryConfig {
    static unapply(inst) {
        return [inst.base, inst.artifactPattern, inst.metadataPattern, inst.changing];
    }

    static get msgTypeId() {
        return 0;
    }

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

    static get msgTypeId() {
        return 1;
    }

    constructor(base, changing) {
        super();
        this.base = base;
        this.changing = changing;
    }
}

MavenRepository.codec = combined(str, optional(bool)).to(MavenRepository);

export class PipRepository extends RepositoryConfig {
    static unapply(inst) {
        return [inst.url];
    }

    static get msgTypeId() {
        return 2;
    }

    constructor(url) {
        super();
        this.url = url;
    }
}

PipRepository.codec = combined(str).to(PipRepository);

RepositoryConfig.codecs = [
    IvyRepository,   // 0
    MavenRepository, // 1
    PipRepository    // 2
];

RepositoryConfig.codec = discriminated(
    uint8,
    (msgTypeId) => RepositoryConfig.codecs[msgTypeId].codec,
    msg => msg.constructor.msgTypeId);

export class NotebookConfig {
    static unapply(inst) {
        return [inst.dependencies, inst.exclusions, inst.repositories, inst.sparkConfig];
    }

    constructor(dependencies, exclusions, repositories, sparkConfig) {
        this.dependencies = dependencies;
        this.exclusions = exclusions;
        this.repositories = repositories;
        this.sparkConfig = sparkConfig;
        Object.freeze(this);
    }

    static get default() {
        return new NotebookConfig([], [], [], {});
    }
}

NotebookConfig.codec = combined(
    optional(mapCodec(uint8, tinyStr, arrayCodec(uint8, tinyStr))),
    optional(arrayCodec(uint8, tinyStr)),
    optional(arrayCodec(uint8, RepositoryConfig.codec)),
    optional(mapCodec(uint16, str, str)),
).to(NotebookConfig);