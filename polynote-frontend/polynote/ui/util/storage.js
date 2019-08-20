'use strict';

export class Storage {
    constructor(defaults) {
        this.defaults = defaults || {
            recentNotebooks: [],
            notebookLocations: {}
        };
        this.listeners = {}; // String -> [functions[oldVal, newVal]]
    }

    set(name, value) {
        const oldValue = this.get(name);
        const newValue = JSON.stringify(value);
        window.localStorage.setItem(name, newValue);
        const listeners = this.listeners[name];
        if (listeners) {
            listeners.forEach(fn => fn(oldValue, newValue));
        }
    }

    get(name) {
        const fromStorage = window.localStorage.getItem(name);
        if (fromStorage) {
            try {
                return JSON.parse(fromStorage);
            } catch(err) {

            }
        }

        return this.defaults[name];
    }

    update(name, updateFn) {
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

    addStorageListener(name, fn) {
        this.listeners[name] = [...(this.listeners[name] || []), fn]
    }

    clearStorageListener(name, fn) {
        this.listeners[name] = this.listeners[name].filter(x => x !== fn);
    }
}

export const storage = new Storage();

// Preferences are backed by Storage, but they can only store Preference values
export class Preferences {
    constructor() {
        this.preferencesKey = "preferences";
        this.preferences = {};
        const rawPrefs = storage.get(this.preferencesKey) || {};
        for (const [key, json] of Object.entries(rawPrefs)) {
            if (json && json.value !== undefined && json.description !== undefined) {
                this.preferences[key] = new Preference(json.value, json.description);
            } else {
                // TODO: should this be an error?
                console.warn(`Unable to decode preference ${key} with value ${json}`)
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
    register(name, description, initialValue) {
        if (!this.get(name)) {
            this.preferences[name] = new Preference(initialValue, description || "An unknown description!");
            this.sync();
        }
        return name;
    }

    set(name, newValue, description) {
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

    get(name) {
        return this.preferences[name];
    }

    clear() {
        this.preferences = {};
        this.sync();
    }

    update(name, updateFn) {
        this.set(name, updateFn(this.get(name)))
    }

    show() {
        return this.preferences;
    }

}

// Preferences are just values with a description which is used for display purposes
export class Preference {
    constructor(value, description) {
        this.value = value;
        this.description = description
    }
}

export const preferences = new Preferences();
