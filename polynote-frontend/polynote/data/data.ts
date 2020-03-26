import {
    arrayCodec, bool, Codec, CodecContainer, combined, discriminated, either, int16, int64, mapCodec, optional, shortStr,
    str, tinyStr, uint16, uint8
} from "./codec";
import {ExecutionInfo, PosRange, Result} from "./result";

export class CellMetadata {
    static codec = combined(bool, bool, bool, optional(ExecutionInfo.codec)).to(CellMetadata);
    static unapply(inst: CellMetadata): ConstructorParameters<typeof CellMetadata> {
        return [inst.disableRun, inst.hideSource, inst.hideOutput, inst.executionInfo];
    }

    constructor(readonly disableRun: boolean = false, readonly hideSource: boolean = false, readonly hideOutput: boolean = false, readonly executionInfo?: ExecutionInfo) {}

    copy(metadata: Partial<CellMetadata>) {
        const disableRun = metadata.disableRun ?? this.disableRun;
        const hideSource = metadata.hideSource ?? this.hideSource;
        const hideOutput = metadata.hideOutput ?? this.hideOutput;
        const executionInfo = metadata.executionInfo ?? this.executionInfo;
        return new CellMetadata(disableRun, hideSource, hideOutput, executionInfo);
    }
}

// called CellComment to differentiate it from the DOM Node which is globally available without import -- is there any way to make those imports explicit???
export class CellComment {
    static codec = combined(tinyStr, PosRange.codec, tinyStr, optional(str), int64, shortStr).to(CellComment);
    static unapply(inst: CellComment): ConstructorParameters<typeof CellComment> {
        return [inst.uuid, inst.range, inst.author, inst.authorAvatarUrl, inst.createdAt, inst.content];
    }

    constructor(readonly uuid: string, readonly range: PosRange, readonly author: string, readonly authorAvatarUrl: string | undefined, readonly createdAt: number, readonly content: string) {
        Object.freeze(this);
    }
}

export class NotebookCell {
    static codec = combined(int16, tinyStr, str, arrayCodec(int16, Result.codec), CellMetadata.codec, mapCodec(int16, tinyStr, CellComment.codec)).to(NotebookCell);
    static unapply(inst: NotebookCell): ConstructorParameters<typeof NotebookCell> {
        return [inst.id, inst.language, inst.content, inst.results, inst.metadata, inst.comments];
    }

    constructor(readonly id: number,
                readonly language: string,
                readonly content: string = '',
                readonly results: Result[] = [],
                readonly metadata: CellMetadata = new CellMetadata(false, false, false),
                readonly comments: Record<string, CellComment> = {}) {}
}

export abstract class RepositoryConfig extends CodecContainer {
    static codec: Codec<RepositoryConfig>;
    static codecs: typeof RepositoryConfig[];
    static msgTypeId: number;

    abstract url: string
}

export class IvyRepository extends RepositoryConfig {
    static codec = combined(str, optional(str), optional(str), optional(bool)).to(IvyRepository);
    static unapply(inst: IvyRepository): ConstructorParameters<typeof IvyRepository>{
        return [inst.url, inst.artifactPattern, inst.metadataPattern, inst.changing];
    }

    static get msgTypeId() {
        return 0;
    }

    constructor(readonly url: string, readonly artifactPattern?: string, readonly metadataPattern?: string, readonly changing?: boolean) {
        super();
    }
}

export class MavenRepository extends RepositoryConfig {
    static codec = combined(str, optional(bool)).to(MavenRepository);
    static unapply(inst: MavenRepository): ConstructorParameters<typeof MavenRepository> {
        return [inst.url, inst.changing];
    }

    static get msgTypeId() {
        return 1;
    }

    constructor(readonly url: string, readonly changing?: boolean) {
        super();
    }
}

export class PipRepository extends RepositoryConfig {
    static codec = combined(str).to(PipRepository);
    static unapply(inst: PipRepository): ConstructorParameters<typeof PipRepository> {
        return [inst.url];
    }

    static get msgTypeId() {
        return 2;
    }

    constructor(readonly url: string) {
        super();
    }
}

RepositoryConfig.codecs = [
    IvyRepository,   // 0
    MavenRepository, // 1
    PipRepository    // 2
];

RepositoryConfig.codec = discriminated(
    uint8,
    (msgTypeId) => RepositoryConfig.codecs[msgTypeId].codec,
    msg => (msg.constructor as typeof RepositoryConfig).msgTypeId);

export class SparkPropertySet {
    static codec = combined(str, mapCodec(uint16, str as Codec<string>, str), optional(str), optional(str)).to(SparkPropertySet);
    static unapply(inst: SparkPropertySet): ConstructorParameters<typeof SparkPropertySet> {
        return [inst.name, inst.properties, inst.sparkSubmitArgs, inst.distClasspathFilter];
    }

    constructor(readonly name: string, readonly properties: Record<string, string>, readonly sparkSubmitArgs?: string, readonly distClasspathFilter?: string) {
        Object.freeze(this);
    }
}

export class NotebookConfig {
    static codec = combined(
        optional(mapCodec(uint8, tinyStr, arrayCodec(uint8, tinyStr))),
        optional(arrayCodec(uint8, tinyStr)),
        optional(arrayCodec(uint8, RepositoryConfig.codec)),
        optional(mapCodec(uint16, str as Codec<string>, str)),
        optional(SparkPropertySet.codec),
        optional(mapCodec(uint16, str as Codec<string>, str)),
    ).to(NotebookConfig);
    static unapply(inst: NotebookConfig): ConstructorParameters<typeof NotebookConfig> {
        return [inst.dependencies, inst.exclusions, inst.repositories, inst.sparkConfig, inst.sparkTemplate, inst.env];
    }

    constructor(readonly dependencies?: Record<string, string[]>, readonly exclusions?: string[],
                readonly repositories?: RepositoryConfig[], readonly sparkConfig?: Record<string, string>,
                readonly sparkTemplate?: SparkPropertySet,
                readonly env?: Record<string, string>) {
        Object.freeze(this);
    }

    static get default() {
        return new NotebookConfig({}, [], [], {}, undefined, {});
    }
}

