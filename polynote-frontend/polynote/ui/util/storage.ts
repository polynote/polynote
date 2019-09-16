'use strict';

type StorageListener = (oldValue: string | null, newValue: string | null) => void

export class Storage {
    constructor(readonly defaults: {[key: string]: any} = {
                    recentNotebooks: [],
                    notebookLocations: {}
                },
                public listeners: {[key: string]: StorageListener[]} = {}) {}

    set(name: string, value: any) {
        const oldValue = this.get(name);
        const newValue = JSON.stringify(value);
        window.localStorage.setItem(name, newValue);
        const listeners = this.listeners[name];
        if (listeners) {
            listeners.forEach(fn => fn(oldValue, newValue));
        }
    }

    get(name: string): any {
        const fromStorage = window.localStorage.getItem(name);
        if (fromStorage) {
            try {
                return JSON.parse(fromStorage);
            } catch(err) {

            }
        }

        return this.defaults[name];
    }

    update<T>(name: string, updateFn: (oldValue: T) => any) {
        this.set(name, updateFn(this.get(name)))
    }

    clear() {
        for (const [name, listeners] of Object.entries(this.listeners)) {
            listeners.forEach(fn => {
                this.clearStorageListener(name, fn);
                // so listeners can react to clearing the value
                fn(this.get(name), null);
            });
        }
        window.localStorage.clear();
    }

    show() {
        const onlyMyStuff = Object.entries(window.localStorage);
        return Object.assign({}, ...Array.from(onlyMyStuff, ([k, v]) => ({[k]: v}) ))
    }

    addStorageListener(name: string, fn: StorageListener) {
        this.listeners[name] = [...(this.listeners[name] || []), fn]
    }

    clearStorageListener(name: string, fn?: StorageListener) {
        if (fn) {
            this.listeners[name] = this.listeners[name].filter(x => x !== fn);
        } else {
            this.listeners[name] = []
        }
    }
}

export const storage = new Storage();

// Preferences are backed by Storage, but they can only store Preference values
export class Preferences {
    private readonly preferencesKey = "preferences";
    private preferences: Record<string, Preference> = {};

    constructor() {
        this.preferences = {};
        const rawPrefs: Record<string, Preference> = storage.get(this.preferencesKey) || {};
        for (const [key, json] of Object.entries(rawPrefs)) {
            if (json && json.value !== undefined && json.description !== undefined) {
                this.preferences[key] = new Preference(json.value, json.description);
            } else {
                throw new Error(`Unable to decode preference ${key} with value ${JSON.stringify(json)}`)
            }
        }

        // listen for storage clear and clear ourselves as well.
        window.addEventListener('storage', evt => {
            if (evt.key === null && evt.newValue === null) { // this means `clear()` was called, according to https://developer.mozilla.org/en-US/docs/Web/API/StorageEvent#Attributes
                console.log("cleared prefs");
                this.clear();
            }
        });

        this.sync()
    }

    sync() {
        storage.set(this.preferencesKey, this.preferences);
    }

    // register a preference (only if it doesn't already exist)
    register(name: string, initialValue: any, description?: string) {
        if (!this.get(name)) {
            this.preferences[name] = new Preference(initialValue, description || "An unknown description!");
            this.sync();
        }
        return name;
    }

    set(name: string, newValue: any, description?: string) {
        const pref = this.get(name);
        if (pref) {
            pref.value = newValue;
            if (description) pref.description = description;
            this.preferences[name] = pref;
            this.sync();
        } else {
            throw new Error("Attempt to set an unregistered preference! You must register a preference before you can set it!")
        }
    }

    get(name: string) {
        return this.preferences[name];
    }

    clear() {
        this.preferences = {};
        this.sync();
    }

    update(name: string, updateFn: (oldValue: Preference) => any) {
        this.set(name, updateFn(this.get(name)))
    }

    show() {
        return this.preferences;
    }

}

// Preferences are just values with a description which is used for display purposes
export class Preference {
    constructor(public value: any, public description: string) {}
}

export const preferences = new Preferences();
