"use strict";

export class UIEvent extends CustomEvent {
    constructor(type, detail) {
        super(type, {detail: detail});
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

// Represents a request adding a listener to an event on a parent instance.
export class EventRegistration extends UIEvent {
    constructor(event) {
        if (! event instanceof CallbackEvent) {
            throw Error("Must pass in a callback event, otherwise I won't know what to do when I get this event!")
        }
        super(EventRegistration.registrationId(event.type), event.detail)
    }

    static registrationId(id) {
        return id + "EventRegistration";
    }
}

export class Request extends UIEvent {
    constructor(event) {
        if (! event instanceof CallbackEvent) {
            throw Error("Must pass in a callback event, otherwise I won't know what to do when I get this event!")
        }
        super(Request.requestId(event.type), event.detail)
    }

    static requestId(id) {
        return id + "Request";
    }
}

export class CallbackEvent extends UIEvent {
    constructor(id, callback, detail={}) {
        const det = Object.assign({callback: callback}, detail);
        super(id, det);
    }
}

export class UIEventTarget extends EventTarget {
    constructor(eventParent) {
        super();
        this.eventParent = eventParent || null;
        this.listeners = {};
    }

    setEventParent(parent) {
        this.eventParent = parent;
        return this;
    }

    // Register your callback with someone upstream who knows what to do when they see your registration (fingers crossed!)
    registerEventListener(type, callback) {
        const registration = new EventRegistration(new CallbackEvent(type, callback));
        return this.dispatchEvent(registration);
    }

    // Listen for registration requests that you know how to handle
    handleEventListenerRegistration(eventType, listener, options) {
        const type = EventRegistration.registrationId(eventType);
        return this.addEventListener(type, listener, options);
    }

    // Send a request to be responded to by someone upstream
    request(type, callback) {
        const request = new Request(new CallbackEvent(type, callback));
        return this.dispatchEvent(request);
    }

    // Respond to a request
    respond(type, response) {
        const requestType = Request.requestId(type);
        return this.addEventListener(requestType, response)
    }

    dispatchEvent(event) {
        event.originalTarget = event.originalTarget || this;
        super.dispatchEvent(event);
        if(this.eventParent && !event.propagationStopped) {
            if (!this.eventParent.dispatchEvent) {
                console.log('Event parent is not an event target!', this.eventParent);
                return;
            }
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