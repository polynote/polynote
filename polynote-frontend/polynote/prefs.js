'use strict';

export class Prefs {
    constructor(defaults) {
        this.defaults = defaults || {
            recentNotebooks: []
        }
    }

    set(name, value) {
        window.localStorage.setItem(name, JSON.stringify(value));
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
}

export const prefs = new Prefs();