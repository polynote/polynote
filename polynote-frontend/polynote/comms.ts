'use strict';

import { Message } from './data/messages'
import {EventTarget} from 'event-target-shim'

export class PolynoteMessageEvent<T extends Message> extends CustomEvent<any> {
    constructor(readonly message: T) {
        super('message');
        Object.freeze(this);
    }
}

type ListenerCallback = (...args: any[]) => void
export type MessageListener = [typeof Message, ListenerCallback, boolean?];

const mainEl = document.getElementById('Main');
const socketKey = mainEl ? mainEl.getAttribute('data-ws-key') : null;
window.addEventListener("beforeunload", evt => {
    const sess = SocketSession.tryGet;
    if (sess && sess.isOpen) {
        sess.close();
    }
});

export class SocketSession extends EventTarget {
    private static inst: SocketSession;

    static get tryGet(): SocketSession | null {
        if (SocketSession.inst)
            return SocketSession.inst;
        return null;
    }

    static get get() {
        if (!SocketSession.inst) {
            SocketSession.inst = new SocketSession()
        }
        return SocketSession.inst
    }

    socket: WebSocket;
    listeners: any;

    private constructor(public queue: Message[] = [], public messageListeners: MessageListener[] = []) {
        super();
        this.mkSocket();
    }

    mkSocket() {
        const wsUrl = new URL(`ws?key=${socketKey}`, document.baseURI);
        wsUrl.protocol = wsUrl.protocol === "https:" ? 'wss:' : 'ws';
        this.socket = new WebSocket(wsUrl.href);
        this.socket.binaryType = 'arraybuffer';
        this.listeners = {
            message: this.receive.bind(this),
            open: this.opened.bind(this),
            close: this.close.bind(this),
            error: (event: Event) => this.dispatchEvent(new CustomEvent('error', { detail: { cause: event }}))
        };

        this.socket.addEventListener('message', this.listeners.message);
        this.socket.addEventListener('open', this.listeners.open);
        this.socket.addEventListener('close', this.listeners.close);
        this.socket.addEventListener('error', this.listeners.error);
    }


    opened(event: Event) {
        while (this.queue.length) {
            this.send(this.queue.pop()!);
        }
        this.dispatchEvent(new CustomEvent('open'));
    }

    get isOpen() {
        return this.socket && this.socket.readyState === WebSocket.OPEN;
    }

    get isConnecting() {
        return this.socket && this.socket.readyState === WebSocket.CONNECTING;
    }

    get isClosed() {
        return !this.socket || this.socket.readyState >= WebSocket.CLOSING;
    }

    send(msg: Message) {
        if (this.isOpen) {
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

    addMessageListener(msgType: typeof Message, fn: ListenerCallback, removeWhenFalse: boolean = false) {
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

    listenOnceFor(msgType: typeof Message, fn: ListenerCallback) {
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
        if (this.socket.readyState < WebSocket.CLOSING) {
            this.socket.close();
        }
        for (const l in this.listeners) {
            if (this.listeners.hasOwnProperty(l)) {
                this.socket.removeEventListener(l, this.listeners[l]);
            }
        }
        this.listeners = {};
        this.dispatchEvent(new CustomEvent('close'));
    }

    reconnect(onlyIfClosed: boolean) {
        if (!this.socket || this.isClosed || (!onlyIfClosed && (this.socket.readyState > WebSocket.CONNECTING))) {
            this.close();
            this.mkSocket();
        }
    }

}