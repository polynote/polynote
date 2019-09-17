import {UIEvent} from "./ui_event";
import {TabActivated, NoActiveTab, TabRemoved} from "../component/tab";
import {
    AdvanceCellEvent,
    BeforeCellRunEvent, CellExecutionFinished, CellExecutionStarted, CompletionRequest,
    ContentChangeEvent, DeleteCellEvent, InsertCellEvent, ParamHintRequest,
    RunCellEvent,
    SelectCellEvent, SetCellLanguageEvent
} from "../component/cell";
import {ExecutionStatus, TaskInfo} from "../../data/messages";

type TriggerItem = UIEvent<{item: string}>
export type ImportNotebook = UIEvent<{name: string, content: string}>
type ToggleNotebookListUI = UIEvent<{force?: boolean}>

type ViewAbout = UIEvent<{ section: string }>
type ServerVersion = UIEvent<{ version: number, commit: number }>
type StartKernel = UIEvent<{path: string}>
type KillKernel = UIEvent<{path: string}>
type LoadNotebook = UIEvent<{path: string}>
type CellResult = UIEvent<{once: boolean}>

type ToggleKernelUI = UIEvent<{force?: boolean}>

type UpdatedTask = UIEvent<{taskInfo: TaskInfo}>
type UpdatedExecutionStatus = UIEvent<{update: ExecutionStatus}>

export interface UIEventNameMap extends WindowEventMap {
    "TriggerItem": TriggerItem;
    "NewNotebook": UIEvent<undefined>;
    "ImportNotebook": ImportNotebook;
    "ToggleNotebookListUI": ToggleNotebookListUI;
    "TabActivated": TabActivated;
    "NoActiveTab": NoActiveTab;
    "TabRemoved": TabRemoved;
    "RunAll": UIEvent<undefined>;
    "RunToCursor": UIEvent<undefined>;
    "RunCurrentCell": UIEvent<undefined>;
    "CancelTasks": UIEvent<undefined>;
    "Undo": UIEvent<undefined>;
    "InsertAbove": UIEvent<undefined>;
    "InsertBelow": UIEvent<undefined>;
    "InsertCellBefore": InsertCellEvent;
    "InsertCellAfter": InsertCellEvent;
    "DeleteCell": DeleteCellEvent;
    "ViewAbout": ViewAbout;
    "DownloadNotebook": UIEvent<undefined>;
    "ClearOutput": UIEvent<undefined>;
    "ServerVersion": ServerVersion;
    "RunningKernels": UIEvent<undefined>;
    "StartKernel": StartKernel;
    "KillKernel": KillKernel;
    "LoadNotebook": LoadNotebook;
    "Connect": UIEvent<undefined>;
    "KernelStatus": UIEvent<undefined>;
    "SocketClosed": UIEvent<undefined>;
    "KernelError": UIEvent<undefined>;
    "CellResult": CellResult;
    "ToggleKernelUI": ToggleKernelUI;
    "CellsLoaded": UIEvent<undefined>;
    "SelectCell": SelectCellEvent;
    "RunCell": RunCellEvent;
    "BeforeCellRun": BeforeCellRunEvent;
    "ContentChange": ContentChangeEvent;
    "AdvanceCell": AdvanceCellEvent;
    "CellExecutionStarted": CellExecutionStarted;
    "CellExecutionFinished": CellExecutionFinished;
    "CompletionRequest": CompletionRequest;
    "ParamHintRequest": ParamHintRequest;
    "SetCellLanguage": SetCellLanguageEvent;
    "UpdatedTask": UpdatedTask;
    "UpdatedExecutionStatus": UpdatedExecutionStatus;
}