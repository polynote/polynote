'use strict';

import { Message } from './data/messages'

export class PolynoteMessageEvent<T extends Message> extends CustomEvent<any> {
    constructor(readonly message: T) {
        super('message');
        Object.freeze(this);
    }
}

type ListenerCallback = (...args: any[]) => void
export type MessageListener = [typeof Message, ListenerCallback, boolean?];

export class SocketSession extends EventTarget {
    private static inst: SocketSession;

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
        const schema = location.protocol === 'https:' ? 'wss://' : 'ws://';
        this.socket = new WebSocket(schema + document.location.host + '/ws');
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
                    if (msg instanceof handler[0]) { // check not redundant even though IntelliJ complains.
                        const result = handler[1].apply(null, handler[0].unapply(msg));
                        if (handler[2] && result === false) {
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