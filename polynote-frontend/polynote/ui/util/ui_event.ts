import {Extractable} from "../../util/match";
import {KernelStatus} from "../../data/messages";
import {Cell} from "../component/cell";
import {LazyDataRepr, StreamingDataRepr, UpdatingDataRepr} from "../../data/value_repr";

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

type UIMessageListenerCallback<T extends any[]> = (...args: T) => boolean | void
type UIMessageListener = [typeof UIMessage, UIMessageListenerCallback<any>, boolean?]

// PubSub for UIMessages that also publishes events to its parent (if present)
export class UIMessageTarget {
    private listeners: UIMessageListener[] = [];

    constructor(private parent?: UIMessageTarget) {}

    setParent(parent: UIMessageTarget) {
        this.parent = parent;
        return this;
    }

    subscribe<T extends typeof UIMessage>(msgType: T, fn: UIMessageListenerCallback<ConstructorParameters<T>>, removeWhenFalse = false) {
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
        if (this.parent instanceof UIMessageTarget) {
            this.parent.publish(event.copy())
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
    constructor(readonly version: number, readonly commit: number) { super() }

    static unapply(inst: ServerVersion): ConstructorParameters<typeof ServerVersion> {
        return [inst.version, inst.commit]
    }
}

export class RunningKernels extends UIMessage {
    constructor(readonly statuses: KernelStatus[]) { super() }

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

export class SelectCell extends UIMessage {
    constructor(readonly cell: Cell) { super() }

    static unapply(inst: SelectCell): ConstructorParameters<typeof SelectCell> {
        return [inst.cell]
    }
}

export class TriggerItem extends UIMessage {
    constructor(readonly item: string) { super() }

    static unapply(inst: TriggerItem): ConstructorParameters<typeof TriggerItem> {
        return [inst.item]
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
    constructor(readonly name?: string, readonly content?: string) { super(); }
    static unapply(inst: ImportNotebook): ConstructorParameters<typeof ImportNotebook> {
        return [inst.name, inst.content]
    }
}

export class CreateNotebook extends UIMessage {
    constructor() { super() }
    static unapply(inst: CreateNotebook): ConstructorParameters<typeof CreateNotebook> {
        return [];
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

