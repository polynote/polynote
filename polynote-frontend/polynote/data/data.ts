import {
    arrayCodec, bool, Codec, CodecContainer, combined, discriminated, int16, mapCodec, optional, str, tinyStr,
    uint16, uint8
} from "./codec";
import {ExecutionInfo, Result} from "./result";
import {keys} from "vega-lite/build/src/util";

export abstract class Copyable {
    copy<T, C extends (new (...args: any) => T) & {unapply: (inst: T) => ConstructorParameters<C>}>(
        this: T,
        args: Partial<T>
    ): T {
        const myConstructor: C = (this as any as {constructor: C}).constructor;
        const theirArgs = myConstructor.unapply(args as T);
        const myArgs: ConstructorParameters<C> = myConstructor.unapply(this);

        const mergedArgs: any[] = [];
        for (let i = 0; i < myArgs.length; i++) {
            mergedArgs[i] = theirArgs[i];
            if (mergedArgs[i] === undefined) {
                mergedArgs[i] = myArgs[i];
            }
        }

        return new myConstructor(...mergedArgs);
    }
}

export class CellMetadata extends Copyable {
    static codec = combined(bool, bool, bool, optional(ExecutionInfo.codec)).to(CellMetadata);
    static unapply(inst: CellMetadata): ConstructorParameters<typeof CellMetadata> {
        return [inst.disableRun, inst.hideSource, inst.hideOutput, inst.executionInfo];
    }

    constructor(readonly disableRun: boolean = false, readonly hideSource: boolean = false, readonly hideOutput: boolean = false, readonly executionInfo?: ExecutionInfo) {
        super();
    }
}

export class NotebookCell extends Copyable {
    static codec = combined(int16, tinyStr, str, arrayCodec(int16, Result.codec), CellMetadata.codec).to(NotebookCell);
    static unapply(inst: NotebookCell): ConstructorParameters<typeof NotebookCell> {
        return [inst.id, inst.language, inst.content, inst.results, inst.metadata];
    }

    constructor(readonly id: number,
                readonly language: string,
                readonly content: string = '',
                readonly results: Result[] = [],
                readonly metadata: CellMetadata = new CellMetadata(false, false, false)) {
        super();
    }
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

export class NotebookConfig extends Copyable {
    static codec = combined(
        optional(mapCodec(uint8, tinyStr, arrayCodec(uint8, tinyStr))),
        optional(arrayCodec(uint8, tinyStr)),
        optional(arrayCodec(uint8, RepositoryConfig.codec)),
        optional(mapCodec(uint16, str, str)),
    ).to(NotebookConfig);
    static unapply(inst: NotebookConfig): ConstructorParameters<typeof NotebookConfig> {
        return [inst.dependencies, inst.exclusions, inst.repositories, inst.sparkConfig];
    }

    constructor(readonly dependencies?: Record<string, string[]>, readonly exclusions?: string[],
                readonly repositories?: RepositoryConfig[], readonly sparkConfig?: Record<string, string>) {
        super();
        Object.freeze(this);
    }

    static get default() {
        return new NotebookConfig({}, [], [], {});
    }
}

