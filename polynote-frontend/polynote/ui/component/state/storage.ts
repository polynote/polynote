import {storage} from "../../util/storage";
import {StateHandler} from "./state_handler";
import {diffArray} from "../../../util/helpers";
import * as deepEquals from 'fast-deep-equal/es6';

export type RecentNotebooks = {name: string, path: string}[];
export type OpenNotebooks = string[]; // paths
export type NotebookScrollLocations = Record<string, number>; // path -> scrollTop
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

export class LocalStorageHandler<T> extends StateHandler<T> {
    constructor(readonly key: string, private initial: T) {
        super(initial);

        // watch storage to detect when it was cleared
        const handleStorageChange = (next: T | null | undefined) => {
            console.log("detected storage change", next)
            if (next === null) { // cleared
                this.setState(initial)
            } else {
                if (next !== undefined) {
                    super.setState(next)
                } else {
                    super.setState(initial)
                }
            }
        }
        handleStorageChange(storage.get(this.key))
        storage.addStorageListener(this.key, (prev, next) => handleStorageChange(next))
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

    clear() {
        this.setState(this.initial)
    }
}

export function clearStorage() {
    storage.clear()
    location.reload();
}

export const RecentNotebooksHandler = new LocalStorageHandler<RecentNotebooks>("RecentNotebooks", []);
export const OpenNotebooksHandler = new LocalStorageHandler<OpenNotebooks>("OpenNotebooks", []);
export const NotebookScrollLocationsHandler = new LocalStorageHandler<NotebookScrollLocations>("NotebookScrollLocations", {});
export const ViewPrefsHandler = new LocalStorageHandler<ViewPreferences>("ViewPreferences", {
    leftPane: {
        size: '300px',
        collapsed: false,
    },
    rightPane: {
        size: '300px',
        collapsed: false,
    }
});

class UserPreferencesStorageHandler extends LocalStorageHandler<typeof UserPreferences> {
    constructor(initial: typeof UserPreferences) {

        const storageKey = "UserPreferences";
        const fromBrowser = storage.get(storageKey);
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