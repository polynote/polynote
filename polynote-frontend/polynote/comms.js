'use strict';

import { Message } from './messages.js'


function mkSocket() {
    this.socket = new WebSocket('ws://' + document.location.host + '/ws');
    this.socket.binaryType = 'arraybuffer';
    this.listeners = {
        message: this.receive.bind(this),
        open: this.opened.bind(this),
        close: this.close.bind(this),
        error: (event) => this.dispatchEvent(new CustomEvent('error', { detail: { cause: event }}))
    };

    this.socket.addEventListener('message', this.listeners.message);
    this.socket.addEventListener('open', this.listeners.open);
    this.socket.addEventListener('close', this.listeners.close);
    this.socket.addEventListener('error', this.listeners.error);
}

export class PolynoteMessageEvent extends CustomEvent {
    constructor(message) {
        super('message');
        this.message = message;
        Object.freeze(this);
    }
}

export class SocketSession extends EventTarget {

    constructor() {
        super();
        this.queue = [];
        this.messageListeners = [];
        SocketSession.current = this; // yeah...
        mkSocket.call(this);
    }

    opened(event) {
        while (this.queue.length) {
            this.send(this.queue.pop());
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

    send(msg) {
        if (msg instanceof Message) {
            if (this.isOpen) {
                const buf = msg.encode();
                if (buf instanceof ArrayBuffer) {
                    this.socket.send(buf);
                } else {
                    throw new Error(`Encoded message is not a buffer`);
                }
            } else {
                this.queue.unshift(msg);
            }
        } else {
            throw new Error(`Expected a message; got ${msg}`);
        }
    }

    receive(event) {
        if (event instanceof MessageEvent) {
            if (event.data instanceof ArrayBuffer) {
                const msg = Message.decode(event.data);
                this.dispatchEvent(new PolynoteMessageEvent(msg));

                for (var handler of this.messageListeners) {
                    if (msg instanceof handler[0]) {
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

    addMessageListener(msgType, fn, removeWhenFalse) {
        const handler = [msgType, fn, removeWhenFalse];
        this.messageListeners.push(handler);
        return handler;
    }

    removeMessageListener(handlerOrType, fn) {
        let handler;
        if (handlerOrType instanceof Array) {
            handler = handlerOrType;
        } else {
            handler = [handlerOrType, fn];
        }

        const index = this.messageListeners.indexOf(handler);
        if (index >= 0) {
            this.messageListeners.splice(index, 1);
        }
    }

    listenOnceFor(msgType, fn) {
        return this.addMessageListener(msgType, fn, true);
    }

    /**
     * Send a request and listen for the response. The message must properly implement the isResponse method.
     */
    request(msg) {
        return new Promise((resolve, reject) => {
            const listener = this.addEventListener('message', evt => {
               if (msg.isResponse(evt.message)) {
                   this.removeEventListener('message', listener);
                   resolve(evt.message);
               }
            });
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

    reconnect(onlyIfClosed) {
        if (!this.socket || this.isClosed || (!onlyIfClosed && (this.socket.readyState > WebSocket.CONNECTING))) {
            this.close();
            document.location.reload();
        }
    }

}