'use strict';

import {Message, NotebookUpdate} from './data/messages'
import {EventTarget} from 'event-target-shim'
import {remove} from "vega-lite/build/src/compositemark";
import {Extractable} from "./util/match";

export class PolynoteMessageEvent<T extends Message> extends CustomEvent<any> {
    constructor(readonly message: T) {
        super('message');
        Object.freeze(this);
    }
}

type ListenerCallback = (...args: any[]) => void
export type MessageListener = [typeof Message, ListenerCallback, boolean?];

const mainEl = document.getElementById('Main');
const socketKey = mainEl?.getAttribute('data-ws-key');

const openSessions: Record<string, SocketSession> = {};

function wsUrl(url: URL) {
    url = new URL(url.href);
    if (!url.searchParams.get("key") && socketKey) {
        url.searchParams.append("key", socketKey)
    }
    url.protocol = url.protocol === "https:" || url.protocol == "wss" ? 'wss:' : 'ws';
    return url;
}

function closeAll() {
    for (const url in Object.keys(openSessions)) {
        const sess = openSessions[url];
        sess.close();
        delete openSessions[url];
    }
}

window.addEventListener("beforeunload", closeAll);

export class SocketSession extends EventTarget {
    private static inst: SocketSession;

    static get global() {
        if (!SocketSession.inst) {
            SocketSession.inst = SocketSession.fromRelativeURL("ws")
        }
        return SocketSession.inst
    }

    static fromRelativeURL(relativeURL: string): SocketSession {
        const url = wsUrl(new URL(relativeURL, document.baseURI));
        if (openSessions[url.href]) {
            return openSessions[url.href];
        }
        return new SocketSession(url)
    }

    private socket?: WebSocket;
    listeners: any;

    private constructor(readonly url: URL, public queue: Message[] = [], public messageListeners: MessageListener[] = []) {
        super();
        this.mkSocket();
    }

    mkSocket() {
        this.socket = new WebSocket(this.url.href);
        this.socket.binaryType = 'arraybuffer';
        this.listeners = {
            message: this.receive.bind(this),
            open: this.opened.bind(this),
            close: this.close.bind(this),
            error: (event: Event) => this.onError(event)
        };

        this.socket.addEventListener('message', this.listeners.message);
        this.socket.addEventListener('open', this.listeners.open);
        this.socket.addEventListener('close', this.listeners.close);
        this.socket.addEventListener('error', this.listeners.error);
    }

    onError(event: Event) {
        if (this.socket) {
            this.close();
            this.dispatchEvent(new CustomEvent('error', {detail: {cause: event}}));
        }
    }


    opened(event: Event) {
        while (this.queue.length) {
            this.send(this.queue.pop()!);
        }
        this.dispatchEvent(new CustomEvent('open'));
    }

    get isOpen(): boolean {
        return this.socket?.readyState === WebSocket.OPEN;
    }

    get isConnecting() {
        return this.socket?.readyState === WebSocket.CONNECTING;
    }

    get isClosed() {
        return !this.socket || this.socket.readyState >= WebSocket.CLOSING;
    }

    send(msg: Message) {
        if (this.socket && this.isOpen) {
            const buf = Message.encode(msg);
            this.socket.send(buf);
        } else {
            this.queue.unshift(msg);
        }
    }

    receive(event: Event) {
        if (event instanceof MessageEvent) {
            if (event.data instanceof ArrayBuffer) {
                const msg = Message.decode(event.data);
                this.dispatchEvent(new PolynoteMessageEvent(msg)); // this is how `request` works.

                for (const handler of this.messageListeners) {
                    const msgType = handler[0];
                    const listenerCB = handler[1];
                    const removeWhenFalse = handler[2];

                    if (msg instanceof msgType) { // check not redundant even though IntelliJ complains.
                        const result = listenerCB.apply(null, msgType.unapply(msg));
                        if (removeWhenFalse && (result === false || result === undefined)) {
                            this.removeMessageListener(handler);
                        }
                    }
                }
            } else {
                //console.log(event.data);
            }
        }
    }

    addMessageListener<M extends Message, C extends (new (...args: any[]) => M) & typeof Message>(msgType: C, fn: (...args: ConstructorParameters<typeof msgType>) => void, removeWhenFalse: boolean = false) {
        const handler: MessageListener = [msgType, fn, removeWhenFalse];
        this.messageListeners.push(handler);
        return handler;
    }

    removeMessageListener(handler: MessageListener) {
        const index = this.messageListeners.indexOf(handler);
        if (index >= 0) {
            this.messageListeners.splice(index, 1);
        }
    }

    listenOnceFor<M extends Message, C extends (new (...args: any[]) => M) & typeof Message>(msgType: C, fn: (...args: ConstructorParameters<typeof msgType>) => void) {
        return this.addMessageListener(msgType, fn, true);
    }

    /**
     * Send a request and listen for the response. The message must properly implement the isResponse method.
     */
    request<T extends Message>(msg: T) {
        return new Promise<T>((resolve, reject) => {
            this.addEventListener('message', (evt: PolynoteMessageEvent<T>) => {
               if (msg.isResponse(evt.message)) {
                   resolve(evt.message);
                   return true; // so it gets removed.
               } else return false;
            }, /*removeWhenFalse*/ true);
            this.send(msg);
        });
    }

    close() {
        if (this.socket) {
            if (this.socket.readyState < WebSocket.CLOSING) {
                this.socket.close();
            }
            for (const l in this.listeners) {
                if (this.listeners.hasOwnProperty(l)) {
                    this.socket.removeEventListener(l, this.listeners[l]);
                }
            }
            this.listeners = {};
            this.socket = undefined;
            this.dispatchEvent(new CustomEvent('close'));
        }
    }

    reconnect(onlyIfClosed: boolean) {
        if (!this.socket || this.isClosed || (!onlyIfClosed && (this.socket.readyState > WebSocket.CONNECTING))) {
            this.close();
            this.mkSocket();
        }
    }

}