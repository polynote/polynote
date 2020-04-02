import {StateHandler} from "./state_handler";
import {KernelReport, PosRange, Result, ServerErrorWithCause} from "../../../data/result";
import {CompletionCandidate, Presence, Signatures} from "../../../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../../../data/data";
import {KernelState} from "./kernel_state";
import {EditBuffer} from "../../../data/edit_buffer";
import {ContentEdit} from "../../../data/content_edit";

export interface CellState {
    id: number,
    language: string,
    content: string,
    results: Result[],
    metadata: CellMetadata,
    comments: Record<string, CellComment>,
    pendingEdits: ContentEdit[],
}

type CompletionOrHint =
    { cell: number, pos: number; completions: CompletionCandidate[] } | {cell: number, pos: number, signatures?: Signatures};

export interface NotebookState {
    // basic states
    path: string,
    cells: CellState[],
    config: NotebookConfig,
    errors: ServerErrorWithCause[],
    kernel: KernelState, // TODO move kernel state to this file
    // version
    globalVersion: number,
    localVersion: number,
    editBuffer: EditBuffer,
    // ephemeral states
    // there can only be one completion/hint at a time, right?
    activeCompletionHint?: CompletionOrHint,
    executionHighlight?: {id: number, pos: PosRange},
    activePresence: Record<number, { presence: Presence, selection?: { cell: number, range: PosRange}}>
}

export class NotebookStateHandler extends StateHandler<NotebookState> {
    constructor(state: NotebookState) {
        super(state);
    }
}