/**
 * The Dispatcher is used to handle actions initiated by the UI.
 * It knows whether an Action should be translated to a message and then sent on the socket, or if it should be
 * handled by something else.
 */
import {SocketSession} from "../../../comms";
import match from "../../../util/match";
import * as messages from "../../../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../../../data/data";
import {Output, PosRange} from "../../../data/result";
import {UIMessage} from "../../util/ui_event";
import {StateHandler} from "../state/state_handler";
import {Message} from "../../../data/messages";
import {NotebookState, NotebookStateHandler} from "../state/notebook_state";
import {ContentEdit} from "../../../data/content_edit";


class MessageDispatcher<S> {
    constructor(protected socket: SocketSession, protected state: StateHandler<S>) {}
}

export class NotebookMessageDispatcher extends MessageDispatcher<NotebookState>{
    constructor(socket: SocketSession, state: NotebookStateHandler) {
        super(socket, state);
        // when the socket is opened, send a KernelStatus message to request the current status from the server.
        socket.addEventListener('open', evt => socket.send(new messages.KernelStatus(new messages.KernelBusyState(false, false))));
    }

    dispatch(action: UIAction) {
        this.state.updateState(state => {
            match(action)
                .when(Reconnect, (onlyIfClosed: boolean) => {
                    this.socket.reconnect(onlyIfClosed)
                })
                .when(KernelCommand, (command: string) => {
                    if (command === "start") {
                        this.socket.send(new messages.StartKernel(messages.StartKernel.NoRestart));
                    } else if (command === "kill") {
                        if (confirm("Kill running kernel? State will be lost.")) {
                            this.socket.send(new messages.StartKernel(messages.StartKernel.Kill));
                        }
                    }
                })
                .when(CreateComment, (cellId, comment) => {
                    this.socket.send(new messages.CreateComment(state.globalVersion, state.localVersion, cellId, comment))
                })
                .when(UpdateComment, (cellId, commentId, range, content) => {
                    this.socket.send(new messages.UpdateComment(state.globalVersion, state.localVersion, cellId, commentId, range, content))
                })
                .when(DeleteComment, (cellId, commentId) => {
                    this.socket.send(new messages.DeleteComment(state.globalVersion, state.localVersion, cellId, commentId))
                })
                .when(SetCurrentSelection, (cellId, range) => {
                    this.socket.send(new messages.CurrentSelection(cellId, range))
                })
                .when(SetCellOutput, (cellId, output) => {
                    this.socket.send(new messages.SetCellOutput(state.globalVersion, state.localVersion, cellId, output))
                })
                .when(SetCellLanguage, (cellId, language) => {
                    this.socket.send(new messages.SetCellLanguage(state.globalVersion, state.localVersion, cellId, language))
                })
                .when(CreateCell, (cellId, cell, prev) => {
                    const update = new messages.InsertCell(state.globalVersion, ++state.localVersion, cell, prev);
                    this.socket.send(update);
                    state.editBuffer.push(state.localVersion, update);
                })
                .when(UpdateCell, (cellId, edits, metadata) => {
                    const update = new messages.UpdateCell(state.globalVersion, ++state.localVersion, cellId, edits, metadata);
                    this.socket.send(update);
                    state.editBuffer.push(state.localVersion, update);
                })
                .when(DeleteCell, (cellId) => {
                    const update = new messages.DeleteCell(state.globalVersion, ++state.localVersion, cellId);
                    this.socket.send(update);
                    state.editBuffer.push(state.localVersion, update);
                })
                .when(UpdateConfig, conf => {
                    const update = new messages.UpdateConfig(state.globalVersion, ++state.localVersion, conf);
                    this.socket.send(update);
                    state.editBuffer.push(state.localVersion, update);
                })
                .when(CreateNotebook, (path, content) => {
                    // TODO: how to open the newly-created notebook?
                    this.socket.send(new messages.CreateNotebook(path, content))
                })
                .when(RenameNotebook, (oldPath, newPath) => {
                    this.socket.send(new messages.RenameNotebook(oldPath, newPath))
                })
                .when(CopyNotebook, (oldPath, newPath) => {
                    this.socket.send(new messages.CopyNotebook(oldPath, newPath))
                })
                .when(RequestCompletions, (cellId, offset) => {
                    this.socket.send(new messages.CompletionsAt(cellId, offset, []))
                })
                .when(RequestParameters, (cellId, offset) => {
                    this.socket.send(new messages.ParametersAt(cellId, offset))
                })
                .when(RequestNotebookVersion, version => {
                    this.socket.send(new messages.NotebookVersion(state.path, version))
                })
                .when(RequestCellRun, cells => {
                    this.socket.send(new messages.RunCell(cells))
                })
                .when(RequestCancelTasks, path => {
                    this.socket.send(new messages.CancelTasks(path))
                })
                .when(RequestClearOutput, () => {
                    this.socket.send(new messages.ClearOutput())
                })
                .when(RequestNotebooksList, () => {
                    this.socket.send(new messages.ListNotebooks([]))
                })


            // TODO: add more actions! basically UIEvents that anything subscribes to should be here I thinkhttps://krebsonsecurity.com/2020/04/war-dialing-tool-exposes-zooms-password-problems/.


            return state
        });
    }
}

export class UIAction {
    static unapply(inst: UIAction): any[] {return []}

    constructor(...args: any[]) {}
}

export class KernelCommand extends UIAction {
    constructor(readonly command: "start" | "kill") { super() }

    static unapply(inst: KernelCommand): ConstructorParameters<typeof KernelCommand> {
        return [inst.command]
    }
}

export class Reconnect extends UIAction {
    constructor(readonly onlyIfClosed: boolean) { super() }

    static unapply(inst: Reconnect): ConstructorParameters<typeof Reconnect> {
        return [inst.onlyIfClosed]
    }
}


export class CreateComment extends UIAction {
    constructor(readonly cellId: number, readonly comment: CellComment) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CreateComment): ConstructorParameters<typeof CreateComment> {
        return [inst.cellId, inst.comment];
    }
}

export class UpdateComment extends UIAction {
    constructor(readonly cellId: number, readonly commentId: string, readonly range: PosRange, readonly content: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: UpdateComment): ConstructorParameters<typeof UpdateComment> {
        return [inst.cellId, inst.commentId, inst.range, inst.content];
    }
}

export class DeleteComment extends UIAction {
    constructor(readonly cellId: number, readonly commentId: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeleteComment): ConstructorParameters<typeof DeleteComment> {
        return [inst.cellId, inst.commentId];
    }
}

export class CreateCell extends UIAction {
    constructor(readonly cellId: number, readonly cell: NotebookCell, readonly prev: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CreateCell): ConstructorParameters<typeof CreateCell> {
        return [inst.cellId, inst.cell, inst.prev];
    }
}

export class UpdateCell extends UIAction {
    constructor(readonly cellId: number, readonly edits: ContentEdit[], readonly metadata?: CellMetadata) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: UpdateCell): ConstructorParameters<typeof UpdateCell> {
        return [inst.cellId, inst.edits, inst.metadata];
    }
}

export class DeleteCell extends UIAction {
    constructor(readonly cellId: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeleteCell): ConstructorParameters<typeof DeleteCell> {
        return [inst.cellId];
    }
}

export class UpdateConfig extends UIAction {
    constructor(readonly config: NotebookConfig) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: UpdateConfig): ConstructorParameters<typeof UpdateConfig> {
        return [inst.config];
    }
}

export class CreateNotebook extends UIAction {
    constructor(readonly path: string, readonly content?: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CreateNotebook): ConstructorParameters<typeof CreateNotebook> {
        return [inst.path, inst.content];
    }
}

export class RenameNotebook extends UIAction {
    constructor(readonly oldPath: string, readonly newPath: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RenameNotebook): ConstructorParameters<typeof RenameNotebook> {
        return [inst.oldPath, inst.newPath];
    }
}

export class CopyNotebook extends UIAction {
    constructor(readonly oldPath: string, readonly newPath: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: CopyNotebook): ConstructorParameters<typeof CopyNotebook> {
        return [inst.oldPath, inst.newPath];
    }
}

export class DeleteNotebook extends UIAction {
    constructor(readonly path: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: DeleteNotebook): ConstructorParameters<typeof DeleteNotebook> {
        return [inst.path];
    }
}

export class SetCurrentSelection extends UIAction {
    constructor(readonly cellId: number, readonly range: PosRange) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetCurrentSelection): ConstructorParameters<typeof SetCurrentSelection> {
        return [inst.cellId, inst.range];
    }
}

export class SetCellOutput extends UIAction {
    constructor(readonly cellId: number, readonly output: Output) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetCellOutput): ConstructorParameters<typeof SetCellOutput> {
        return [inst.cellId, inst.output];
    }
}

export class SetCellLanguage extends UIAction {
    constructor(readonly cellId: number, readonly language: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: SetCellLanguage): ConstructorParameters<typeof SetCellLanguage> {
        return [inst.cellId, inst.language];
    }
}

export class RequestCompletions extends UIAction {
    constructor(readonly cellId: number, readonly offset: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestCompletions): ConstructorParameters<typeof RequestCompletions> {
        return [inst.cellId, inst.offset];
    }
}

export class RequestParameters extends UIAction {
    constructor(readonly cellId: number, readonly offset: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestParameters): ConstructorParameters<typeof RequestParameters> {
        return [inst.cellId, inst.offset];
    }
}

export class RequestNotebookVersion extends UIAction {
    constructor(readonly version: number) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestNotebookVersion): ConstructorParameters<typeof RequestNotebookVersion> {
        return [inst.version];
    }
}

export class RequestCellRun extends UIAction {
    constructor(readonly cells: number[]) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestCellRun): ConstructorParameters<typeof RequestCellRun> {
        return [inst.cells];
    }
}

export class RequestCancelTasks extends UIAction {
    constructor(readonly path: string) {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestCancelTasks): ConstructorParameters<typeof RequestCancelTasks> {
        return [inst.path];
    }
}

export class RequestClearOutput extends UIAction {
    constructor() {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestClearOutput): ConstructorParameters<typeof RequestClearOutput> {
        return [];
    }
}

export class RequestNotebooksList extends UIAction {
    constructor() {
        super();
        Object.freeze(this);
    }

    static unapply(inst: RequestNotebooksList): ConstructorParameters<typeof RequestNotebooksList> {
        return [];
    }
}
