import {UIEventNameMap} from "./ui_events";
import {type} from "vega-lite/build/src/compile/legend/properties";

export class UIEvent<T> extends CustomEvent<T> {
    public propagationStopped = false;
    public originalTarget: UIEventTarget;

    static type: keyof UIEventNameMap;

    constructor(readonly eventType: keyof UIEventNameMap, detail?: T) {
        super(eventType, {detail: detail});
    }

    stopPropagation() {
        super.stopPropagation();
        this.propagationStopped = true;
    }

    copy() {
        const c = new UIEvent(this.eventType, this.detail);
        c.originalTarget = this.originalTarget;
        Object.setPrototypeOf(c, this.constructor.prototype);
        return c;
    }

    forward(el: EventTarget) {
        el.dispatchEvent(this.copy())
    }
}

interface UIEventListener<T> {
    (evt: UIEvent<T>): void;
}

interface CallbackEventListener<T> {
    (evt: CallbackEvent<T>): void;
}

// Represents a request adding a listener to an event on a parent instance.
export class EventRegistration<T> extends UIEvent<T> {
    constructor(event: CallbackEvent<T>) {
        super(EventRegistration.registrationId(event.eventType) as keyof UIEventNameMap, event.detail)
    }

    static registrationId(id: string) {
        return id + "EventRegistration";
    }
}

export class Request<T> extends UIEvent<T> {
    constructor(event: CallbackEvent<T>) {
        super(Request.requestId(event.eventType) as keyof UIEventNameMap, event.detail)
    }

    static requestId(id: string) {
        return id + "Request";
    }
}

interface HasCallback {
    callback: (...args: any[]) => void
}

export class CallbackEvent<T> extends UIEvent<T & HasCallback> {
    constructor(id: keyof UIEventNameMap, callback: (args: any[]) => void, detail?: T) {
        const det = Object.assign({callback: callback}, detail || {}) as T & HasCallback;
        super(id, det);
    }
}

type Unpack<T> = T extends UIEvent<infer P> ? P : never;

export class UIEventTarget extends EventTarget {
    private readonly listeners: Record<string, UIEventListener<any>[]>;
    constructor(private eventParent?: UIEventTarget) {
        super();
        this.listeners = {};
    }

    setEventParent(parent: UIEventTarget) {
        this.eventParent = parent;
        return this;
    }

    // Register your callback with someone upstream who knows what to do when they see your registration (fingers crossed!)
    registerEventListener<K extends keyof UIEventNameMap, T extends UIEventNameMap[K] = UIEventNameMap[K]>(type: K, callback: (...args: any[]) => void, options?: AddEventListenerOptions) {
        const registration = new EventRegistration(new CallbackEvent(type, callback, options));
        return this.dispatchEvent(registration);
    }

    // Listen for registration requests that you know how to handle
    handleEventListenerRegistration<K extends keyof UIEventNameMap, T extends UIEventNameMap[K] = UIEventNameMap[K]>(eventType: K, listener: CallbackEventListener<Unpack<T> extends never ? [] : Unpack<T>>, options?: boolean | AddEventListenerOptions) {
        const type = EventRegistration.registrationId(eventType);
        return this.addEventListener(type as keyof UIEventNameMap, listener, options);
    }

    // Send a request to be responded to by someone upstream
    request<K extends keyof UIEventNameMap>(type: K, callback: (...args: any[]) => void) {
        const request = new Request(new CallbackEvent(type, callback));
        return this.dispatchEvent(request);
    }

    // Respond to a request
    respond<K extends keyof UIEventNameMap>(type: K, response: CallbackEventListener<Unpack<UIEventNameMap[K]>>) {
        const requestType = Request.requestId(type);
        return this.addEventListener(requestType as keyof UIEventNameMap, response)
    }

    dispatchEvent(event: Event) {
        if (event instanceof UIEvent) {
            event.originalTarget = event.originalTarget || this;
        }
        const res = super.dispatchEvent(event);
        if (event instanceof UIEvent) {
            if(this.eventParent && !event.propagationStopped) {
                if (!this.eventParent.dispatchEvent) {
                    console.log('Event parent is not an event target!', this.eventParent);
                    return res;
                }
                this.eventParent.dispatchEvent(event.copy());
            }
        }
        return res;
    }

    addEventChild(child: UIEventTarget) {
        return child.setEventParent(this);
    }

    addEventListener<K extends keyof UIEventNameMap, T extends UIEventNameMap[K] = UIEventNameMap[K]>(type: K, listener: UIEventListener<Unpack<T>>, options?: boolean | AddEventListenerOptions) {
        super.addEventListener(type, listener, options);
        if (!this.listeners[type]) {
            this.listeners[type] = [];
        }
        this.listeners[type].push(listener);
        return listener;
    }

    removeEventListener<K extends keyof UIEventNameMap, T extends UIEventNameMap[K] = UIEventNameMap[K]>(type: K, listener: UIEventListener<Unpack<T>>, options?: boolean | AddEventListenerOptions) {
        super.removeEventListener(type, listener, options);
        const listenersOfType = this.listeners[type];
        if (listenersOfType) {
            const listenerIndex = listenersOfType.indexOf(listener);
            if (listenerIndex !== -1) {
                listenersOfType.splice(listenerIndex, 1);
            }
        }
        return listener;
    }

    addEventChildren(children: UIEventTarget[]) {
        for (const child of children) {
            this.addEventChild(child);
        }
    }

    removeAllListeners() {
        for (const listenerType in this.listeners) {
            if (this.listeners.hasOwnProperty(listenerType)) {
                const listenersOfType = this.listeners[listenerType];
                for (const listener of listenersOfType) {
                    this.removeEventListener(listenerType as keyof UIEventNameMap, listener);
                }
            }
        }
    }
}