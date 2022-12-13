import {storage} from "./storage";
import {BaseHandler, IDisposable, ObjectStateHandler, setValue, StateHandler} from ".";
import {deepEquals, diffArray} from "../util/helpers";

export type RecentNotebooks = {name: string, path: string}[];
export type OpenNotebooks = string[]; // paths
export type NotebookScrollLocations = Record<string, number>; // path -> scrollTop
export type DismissedNotifications = string[];
export interface NotebookListPrefs {
    sortColumn: "name" | "date",
    descending: boolean,
    dateWidth: number
};
export interface ViewPreferences {
    leftPane: {
        size: string,
        collapsed: boolean
    },
    rightPane: {
        size: string,
        collapsed: boolean
    },
};
export type StickyLeftBarPreferences = {notebooks: boolean, summary: boolean};
export interface LeftBarPreferences {
    stickyLeftBar: StickyLeftBarPreferences
};

export class LocalStorageHandler<T extends object> extends BaseHandler<T> {
    private static defaultHandler<T extends object>(key: string, defaultState: T): StateHandler<T> {
        return new ObjectStateHandler<T>(storage.get(key) ?? defaultState);
    }

    constructor(readonly key: string, private defaultState: T, handler?: StateHandler<T>) {
        super(handler || LocalStorageHandler.defaultHandler(key, defaultState));

        const fromStorage = {};
        // watch storage to detect when it was cleared
        const handleStorageChange = (next: T | null | undefined) => {
            if (next !== undefined && next !== null) {
                this.update(() => setValue(next), fromStorage)
            } else {
                this.update(() => setValue(defaultState), fromStorage)
            }
        }
        handleStorageChange(storage.get(key));

        storage.addStorageListener(this.key, (prev, next) => handleStorageChange(next))

        this.filterSource(src => src !== fromStorage).addObserver(value => {
            storage.set(this.key, value)
        })
    }

    clear() {
        this.update(() => setValue(this.defaultState));
    }

    fork(disposeContext?: IDisposable): LocalStorageHandler<T> {
        const fork = new LocalStorageHandler<T>(this.key, this.defaultState, this.parent).disposeWith(this);
        return disposeContext ? fork.disposeWith(disposeContext) : fork;
    }
}

export function clearStorage() {
    storage.clear()
    location.reload();
}

export const RecentNotebooksHandler = new LocalStorageHandler<RecentNotebooks>("RecentNotebooks", storage.get("recentNotebooks") ?? []);
export const OpenNotebooksHandler = new LocalStorageHandler<OpenNotebooks>("OpenNotebooks", []);
export const NotebookScrollLocationsHandler = new LocalStorageHandler<NotebookScrollLocations>("NotebookScrollLocations", {});
export const DismissedNotificationsHandler = new LocalStorageHandler<DismissedNotifications>("DismissedNotifications", []);
export const NotebookListPrefsHandler = new LocalStorageHandler<NotebookListPrefs>("NotebookList", {
    sortColumn: "name",
    descending: false,
    dateWidth: 108
});
export const ViewPrefsHandler = new LocalStorageHandler<ViewPreferences>("ViewPreferences", {
    leftPane: {
        size: '300px',
        collapsed: false,
    },
    rightPane: {
        size: '300px',
        collapsed: false,
    },
});
export const LeftBarPrefsHandler = new LocalStorageHandler<LeftBarPreferences>("LeftBarPreferences", {
    stickyLeftBar: {
        notebooks: true,
        summary: false,
    }
})

class UserPreferencesStorageHandler extends LocalStorageHandler<typeof UserPreferences> {
    constructor(initial: typeof UserPreferences) {

        const storageKey = "UserPreferences";
        const fromBrowser = storage.get(storageKey) ?? { // try the previous storage format.
            vim: storage.get("preferences")?.["VIM"] ?? {},
            markdown: storage.get("preferences")?.["Markdown"] ?? {},
            theme: storage.get("preferences")?.["Theme"] ?? {},
            notifications: storage.get("preferences")?.["Notifications"] ?? {},
        };
        // In order to handle schema changes over time, we compare the locally stored values with `initial`.
        // The schema in `initial` is the currently expected schema, so we need to handle added and removed keys.
        const [added, removed] = diffArray(Object.keys(initial), Object.keys(fromBrowser))

        added.forEach((key: keyof typeof UserPreferences) => {
            fromBrowser[key] = initial[key]
        });
        removed.forEach((key: keyof typeof UserPreferences) => {
            delete fromBrowser[key];
        });

        // Next, we check the schema of the preferences themselves
        (Object.keys(fromBrowser) as (keyof typeof initial)[]).forEach(k => {
            const [added, removed] = diffArray(Object.keys(initial[k]), Object.keys(fromBrowser[k]));

            added.forEach((key: keyof typeof initial[typeof k]) => {
                fromBrowser[k][key] = initial[k][key];
            })

            removed.forEach(key => {
                delete fromBrowser[k][key]
            })

            // check whether the current value is allowed
            if (! Object.values(initial[k].possibleValues).includes(fromBrowser[k].value)) {
                fromBrowser[k] = initial[k]
            }

        })

        super(storageKey, fromBrowser);
        this.clear()
    }

    protected compare(s1: any, s2: any): boolean {
        return deepEquals(s1, s2)
    }
}

type UserPreference<T extends Record<string, any>> = {name: string, value: T[keyof T], description: string, possibleValues: T}
export const UserPreferences: {[k: string]: UserPreference<Record<string, any>>} = {
    vim: {name: "VIM", value: false, description: "Whether VIM input mode is enabled for Code cells", possibleValues: {true: true, false: false}},
    markdown: {name: "Markdown", value: false, description: "Whether raw markdown or rich text editing mode is enabled.", possibleValues: {true: true, false: false}},
    notifications: {
        name: "Notifications",
        value: false,
        description: "Whether to allow Polynote to send you browser notifications. " +
            "Toggling this to `true` for the first time will prompt your browser to request your permission.",
        possibleValues: {true: true, false: false}},
    theme: {
        name: "Theme",
        value: window.matchMedia("(prefers-color-scheme: dark)").matches ? 'Dark' : 'Light',
        description: "The application color scheme (Light or Dark)",
        possibleValues: {Light: "Light", Dark: "Dark"}}
}
export const UserPreferencesHandler = new UserPreferencesStorageHandler(UserPreferences)