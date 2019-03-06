"use strict";

export class UIEvent extends CustomEvent {
    constructor(id, detail) {
        super(id, {detail: detail});
        this.propagationStopped = false;
    }

    stopPropagation() {
        super.stopPropagation();
        this.propagationStopped = true;
    }

    copy() {
        const c = new UIEvent(this.type, this.detail);
        c.originalTarget = this.originalTarget;
        Object.setPrototypeOf(c, this.constructor.prototype);
        return c;
    }

    forward(el) {
        el.dispatchEvent(this.copy())
    }
}

export class UIEventTarget extends EventTarget {
    constructor() {
        super();
        this.eventParent = null;
        this.listeners = {};
    }

    setEventParent(parent) {
        this.eventParent = parent;
        return this;
    }

    dispatchEvent(event) {
        event.originalTarget = event.originalTarget || this;
        super.dispatchEvent(event);
        if(this.eventParent && !event.propagationStopped) {
            this.eventParent.dispatchEvent(event.copy());
        }
    }

    addEventChild(child) {
        return child.setEventParent(this);
    }

    addEventListener(type, listener, options) {
        super.addEventListener(type, listener, options);
        if (!this.listeners[type]) {
            this.listeners[type] = [];
        }
        this.listeners[type].push(listener);
        return listener;
    }

    removeEventListener(type, listener, options) {
        super.removeEventListener(type, listener, options);
        const listenersOfType = this.listeners[type];
        if (listenersOfType) {
            const listenerIndex = this.listeners.indexOf(listener);
            if (listenerIndex !== -1) {
                listenersOfType.splice(listenerIndex, 1);
            }
        }
        return listener;
    }

    addEventChildren(children) {
        for (const child of children) {
            this.addEventChild(child);
        }
    }

    removeAllListeners() {
        for (const listenerType in this.listeners) {
            if (this.listeners.hasOwnProperty(listenerType)) {
                const listenersOfType = this.listeners[listenerType];
                for (const listener of listenersOfType) {
                    this.removeEventListener(listenerType, listener);
                }
            }
        }
    }
}