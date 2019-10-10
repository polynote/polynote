import {UIEvent} from "./ui_event";
import {TabActivated, NoActiveTab, TabRemoved} from "../component/tab";
import {SelectCellEvent} from "../component/cell";
import {KernelStatus, RunningKernels} from "../../data/messages";
import {ReprDataRequest} from "../component/table_view";

type TriggerItem = UIEvent<{item: string}>
export type ImportNotebook = UIEvent<{name: string, content: string}>
type ToggleNotebookListUI = UIEvent<{force?: boolean}>

type ViewAbout = UIEvent<{ section: string }>
type ServerVersion = UIEvent<{ version: number, commit: number }>
type RunningKernelsEvt = UIEvent<ConstructorParameters<typeof RunningKernels>>
type KernelStatusEvt = UIEvent<ConstructorParameters<typeof KernelStatus>>
type StartKernel = UIEvent<{path: string}>
type KillKernel = UIEvent<{path: string}>
type LoadNotebook = UIEvent<{path: string}>

type ToggleKernelUI = UIEvent<{force?: boolean}>

export interface UIEventNameMap extends WindowEventMap {
    "TriggerItem": TriggerItem;
    "NewNotebook": UIEvent<[]>;
    "ImportNotebook": ImportNotebook;
    "ToggleNotebookListUI": ToggleNotebookListUI;
    "TabActivated": TabActivated;
    "NoActiveTab": NoActiveTab;
    "TabRemoved": TabRemoved;
    "CancelTasks": UIEvent<[]>;
    "ViewAbout": ViewAbout;
    "DownloadNotebook": UIEvent<[]>;
    "ClearOutput": UIEvent<[]>;
    "ServerVersion": ServerVersion;
    "RunningKernels": RunningKernelsEvt;
    "StartKernel": StartKernel;
    "KillKernel": KillKernel;
    "LoadNotebook": LoadNotebook;
    "KernelStatus": KernelStatusEvt;
    "ToggleKernelUI": ToggleKernelUI;
    "CellsLoaded": UIEvent<[]>;
    "SelectCell": SelectCellEvent;
    "ReprDataRequest": ReprDataRequest;
}