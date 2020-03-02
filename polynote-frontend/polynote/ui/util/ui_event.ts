import {Extractable} from "../../util/match";
import {KernelBusyState, KernelStatus, Message} from "../../data/messages";
import {Cell} from "../component/cell";
import {LazyDataRepr, StreamingDataRepr, UpdatingDataRepr} from "../../data/value_repr";
import {CellComment} from "../../data/data";
import {editor} from "monaco-editor";
import IStandaloneCodeEditor = editor.IStandaloneCodeEditor;
import {PosRange} from "../../data/result";

export class UIMessage {
    static unapply(inst: UIMessage): any[] {return []}

    copy(): this {
        const constructor = this.constructor as typeof UIMessage;
        return new constructor(...(constructor as typeof UIMessage).unapply(this)) as this
    }

    forward(el: UIMessageTarget) {
        el.publish(this.copy())
    }

    constructor(...args: any[]) {}
}

type UIMessageListenerCallback<T extends new (...args: any) => any> = (...args: ConstructorParameters<T>) => boolean | void
export type UIMessageListener = [typeof UIMessage, UIMessageListenerCallback<any>, boolean?]

export class UIMessageTarget {
    private listeners: UIMessageListener[] = [];

    /**
     * PubSub for UIMessages. Bubbles published messages up the chain.
     *
     * @param uiParent  All UIMessageTargets must have a parent. If this is the root node, uiParent should point to itself.
     *                  Since we can't really have a constructor parameter that points to itself, instantiators are responsible
     *                  for calling `makeRoot` on root nodes.
     */
    constructor(private uiParent?: UIMessageTarget) {}

    setParent(parent: UIMessageTarget) {
        this.uiParent = parent;
        return this;
    }

    makeRoot() {
        this.uiParent = this;
        return this;
    }

    subscribe<T extends typeof UIMessage>(msgType: T, fn: UIMessageListenerCallback<T>, removeWhenFalse = false) {
        const handler: UIMessageListener = [msgType, fn, removeWhenFalse];
        this.listeners.push(handler);
        return handler
    }

    unsubscribe(handler: UIMessageListener){
        this.listeners = this.listeners.filter(l => l !== handler);
    }

    unsubscribeAll() {
        this.listeners = [];
    }

    publish(event: UIMessage): void {
        for (const handler of this.listeners) {
            const [msgType, fn, removeWhenFalse] = handler;
            if (event instanceof msgType) {
                const result = fn.apply(null, msgType.unapply(event));
                if (removeWhenFalse && result === false){
                    this.unsubscribe(handler);
                }
            }
        }
        if (this.uiParent instanceof UIMessageTarget) {
            if (this.uiParent !== this) {
                this.uiParent.publish(event.copy())
            }
        } else {
            // this is for catching unintentional breaks in the chain.
            console.warn("Called publish() on parent-less instance! This might be an accidental break in the chain. Otherwise, consider calling makeRoot() when initializing this UIMessageTarget")
        }
    }
}

export class UIMessageRequest<T extends typeof UIMessage> extends UIMessage {
    constructor(readonly message: T, readonly cb: (...args: ConstructorParameters<T>) => void){ super() }
    static unapply<T extends typeof UIMessage>(inst: UIMessageRequest<T>): ConstructorParameters<typeof UIMessageRequest> {
        return [inst.message, inst.cb]
    }
}


export class ServerVersion extends UIMessage {
    constructor(readonly version: string, readonly commit: string) { super() }

    static unapply(inst: ServerVersion): ConstructorParameters<typeof ServerVersion> {
        return [inst.version, inst.commit]
    }
}

export class RunningKernels extends UIMessage {
    constructor(readonly statuses: Record<string, KernelBusyState>) { super() }

    static unapply(inst: RunningKernels): ConstructorParameters<typeof RunningKernels> {
        return [inst.statuses]
    }
}

export class KernelCommand extends UIMessage {
    constructor(readonly path: string, readonly command: "start" | "kill") { super() }

    static unapply(inst: KernelCommand): ConstructorParameters<typeof KernelCommand> {
        return [inst.path, inst.command]
    }
}

export class LoadNotebook extends UIMessage {
    constructor(readonly path: string) { super() }

    static unapply(inst: LoadNotebook): ConstructorParameters<typeof LoadNotebook> {
        return [inst.path]
    }
}

export class CellSelected extends UIMessage {
    constructor(readonly cell: Cell) { super() }

    static unapply(inst: CellSelected): ConstructorParameters<typeof CellSelected> {
        return [inst.cell]
    }
}

export class FocusCell extends UIMessage {
    constructor(readonly path: string, readonly cellId: number) {
        super();
    }

    static unapply(inst: FocusCell): ConstructorParameters<typeof FocusCell> {
        return [inst.path, inst.cellId]
    }
}

export class UIToggle extends UIMessage {
    constructor(readonly which: string, readonly force?: boolean) { super() }

    static unapply(inst:UIToggle): ConstructorParameters<typeof UIToggle> {
        return [inst.which, inst.force]
    }
}

export class ReprDataRequest extends UIMessage {
    constructor(
        readonly handleType: typeof LazyDataRepr.handleTypeId | typeof StreamingDataRepr.handleTypeId | typeof UpdatingDataRepr.handleTypeId,
        readonly handleId: number,
        readonly count: number,
        readonly onComplete: (data: ArrayBuffer[]) => void,
        readonly onFail: (err?: any) => void) { super () }

    static unapply(inst: ReprDataRequest): ConstructorParameters<typeof ReprDataRequest> {
        return [inst.handleType, inst.handleId, inst.count, inst.onComplete, inst.onFail];
    }
}

export class CellsLoaded extends UIMessage {
    constructor() { super() }
    static unapply(inst: CellsLoaded): ConstructorParameters<typeof CellsLoaded> {
        return [];
    }
}

export class ImportNotebook extends UIMessage {
    constructor(readonly name: string, readonly content: string) { super(); }
    static unapply(inst: ImportNotebook): ConstructorParameters<typeof ImportNotebook> {
        return [inst.name, inst.content]
    }
}

export class CreateNotebook extends UIMessage {
    constructor(readonly path?: string) { super() }
    static unapply(inst: CreateNotebook): ConstructorParameters<typeof CreateNotebook> {
        return [inst.path];
    }
}

export class RenameNotebook extends UIMessage {
    constructor(readonly path: string) { super() }
    static unapply(inst: RenameNotebook): ConstructorParameters<typeof RenameNotebook> {
        return [inst.path];
    }
}

export class CopyNotebook extends UIMessage {
    constructor(readonly path: string) { super() }
    static unapply(inst: CopyNotebook): ConstructorParameters<typeof CopyNotebook> {
        return [inst.path];
    }
}

export class DeleteNotebook extends UIMessage {
    constructor(readonly path: string) { super() }
    static unapply(inst: DeleteNotebook): ConstructorParameters<typeof DeleteNotebook> {
        return [inst.path];
    }
}

export class NoActiveTab extends UIMessage {
    constructor() { super() }
    static unapply(inst: NoActiveTab): ConstructorParameters<typeof NoActiveTab> {
        return [];
    }
}

export class TabRemoved extends UIMessage {
    constructor(readonly tabName: string) { super() }
    static unapply(inst: TabRemoved): ConstructorParameters<typeof TabRemoved> {
        return [inst.tabName]
    }
}

export class TabActivated extends UIMessage {
    constructor(readonly tabName: string, readonly tabType: string) { super() }
    static unapply(inst: TabActivated): ConstructorParameters<typeof TabActivated> {
        return [inst.tabName, inst.tabType]
    }
}

export class TabRenamed extends UIMessage {
    constructor(readonly tabName: string, readonly newName: string, readonly tabType: string, readonly isCurrent: boolean) { super() }
    static unapply(inst: TabRenamed): ConstructorParameters<typeof TabRenamed> {
        return [inst.tabName, inst.newName, inst.tabType, inst.isCurrent]
    }
}

export class DownloadNotebook extends UIMessage {
    constructor(readonly path: string) { super() }
    static unapply(inst: DownloadNotebook): ConstructorParameters<typeof DownloadNotebook> {
        return [inst.path];
    }
}

export class ClearOutput extends UIMessage {
    constructor(readonly path: string) { super() }
    static unapply(inst: ClearOutput): ConstructorParameters<typeof ClearOutput> {
        return [inst.path];
    }
}

export class CancelTasks extends UIMessage {
    constructor(readonly path: string) { super() }
    static unapply(inst: CancelTasks): ConstructorParameters<typeof CancelTasks> {
        return [inst.path];
    }
}

export class ViewAbout extends UIMessage {
    constructor(readonly section: string) { super() }
    static unapply(inst: ViewAbout): ConstructorParameters<typeof ViewAbout> {
        return [inst.section]
    }
}

export class ModalClosed extends UIMessage {
    constructor() { super() }
    static unapply(inst: ModalClosed): ConstructorParameters<typeof ModalClosed> {
        return [];
    }
}

export class CurrentIdentity extends UIMessage {
    constructor(readonly name?: string, readonly avatar?: string) { super() }

    static unapply(inst: CurrentIdentity): ConstructorParameters<typeof CurrentIdentity> {
        return [inst.name, inst.avatar]
    }
}

export class CreateComment extends UIMessage {
    constructor(readonly cellId: number, readonly comment: CellComment) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CreateComment): ConstructorParameters<typeof CreateComment> {
        return [inst.cellId, inst.comment];
    }
}

export class UpdateComment extends UIMessage {
    constructor(readonly cellId: number, readonly commentId: string, readonly range: PosRange, readonly content: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: UpdateComment): ConstructorParameters<typeof UpdateComment> {
        return [inst.cellId, inst.commentId, inst.range, inst.content];
    }
}

export class DeleteComment extends UIMessage {
    constructor(readonly cellId: number, readonly commentId: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeleteComment): ConstructorParameters<typeof DeleteComment> {
        return [inst.cellId, inst.commentId];
    }
}
