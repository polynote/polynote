import {storage} from "../../util/storage";
import {StateHandler} from "./state_handler";

export type RecentNotebooks = {name: string, path: string}[];
export type NotebookLocations = Record<string, number>;
export interface ViewPreferences {
    leftPane: {
        size: string,
        collapsed: boolean
    },
    rightPane: {
        size: string,
        collapsed: boolean
    },
}


class LocalStorageHandler<T> extends StateHandler<T> {
    constructor(private key: string, private initial: T) {
        super(initial);
    }
    getState(): T {
        const recent = storage.get(this.key);
        if (recent) {
            return recent;
        } else {
            this.setState(this.initial);
            return this.initial;
        }
    }

    setState(s: T) {
        super.setState(s);
        storage.set(this.key, s)
    }
}

export const RecentNotebooksHandler = new LocalStorageHandler<RecentNotebooks>("RecentNotebooks", []);
export const NotebookLocationsHandler = new LocalStorageHandler<NotebookLocations>("NotebookLocations", {});
export const ViewSizesHandler = new LocalStorageHandler<ViewPreferences>("ViewPreferences", {
    leftPane: {
        size: '300px',
        collapsed: false,
    },
    rightPane: {
        size: '300px',
        collapsed: false,
    }
});
