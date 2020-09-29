'use strict';

type StorageListener = (oldValue: any | null, newValue: any | null) => void

class Storage {
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
            listeners.forEach(fn => fn(oldValue, value));
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
        this.listeners[name] = [...(this.listeners[name] || []), fn];
        return fn;
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
