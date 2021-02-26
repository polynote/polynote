'use strict';

import {EventTarget} from 'event-target-shim'
import {KeepAlive, Message} from "../data/messages";

type ListenerCallback = (...args: any[]) => (void | boolean)
export type MessageListener = [typeof Message, ListenerCallback, boolean?];

const mainEl = document.getElementById('Main');
const socketKey = mainEl?.getAttribute('data-ws-key');

const openSessions: Record<string, SocketSession> = {};

function wsUrl(url: URL) {
    url = new URL(url.href);
    if (!url.searchParams.get("key") && socketKey) {
        url.searchParams.append("key", socketKey)
    }
    url.protocol = url.protocol === "https:" || url.protocol == "wss:" ? 'wss:' : 'ws';
    return url;
}

function closeAll() {
    Object.entries(openSessions).forEach(([url, sess])=> {
        sess.close()
        delete openSessions[url]
    })
}

window.addEventListener("beforeunload", closeAll);

export class SocketSession extends EventTarget {
    private static inst: SocketSession;
    private pingIntervalId: number;

    static get global() {
        if (!SocketSession.inst) {
            SocketSession.inst = SocketSession.fromRelativeURL("ws")
        }
        return SocketSession.inst
    }

    static fromRelativeURL(relativeURL: string): SocketSession {
        const url = wsUrl(new URL(relativeURL, document.baseURI));
        if (openSessions[url.href]?.socket) {
            return openSessions[url.href];
        }
        const session = new SocketSession(url)
        openSessions[url.href] = session
        return session
    }

    private socket?: WebSocket;
    listeners: any;

    private constructor(
        readonly url: URL,
        public queue: Message[] = [],
        public messageListeners: MessageListener[] = [],
        private keepaliveInterval: number = 10 * 1000 // 10 seconds
    ) {
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

        // keepalive
        let latestPayload: number = 0; // Unsigned int. Can't be larger than 255.
        let receivedResponse: boolean = true;
        const ping = () => {
            if (receivedResponse) {
                latestPayload = (latestPayload + 1) % 256;
                this.send(new KeepAlive(latestPayload));
                receivedResponse = false;
            } else {
                console.error(this.url.href, "Did not receive response to latest ping!")
                this.dispatchEvent(new CustomEvent('error', {detail: {cause: `KeepAlive timed out after ${this.keepaliveInterval} ms`}}));
            }
        }
        this.addMessageListener(KeepAlive, payload => {
            if (payload !== latestPayload) {
                console.warn(this.url.href, "KeepAlive response didn't match! Expected", latestPayload, "received", payload)
            } else {
                receivedResponse = true;
            }
        })

        this.pingIntervalId = window.setInterval(() => {
            ping()
        }, this.keepaliveInterval)
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
            try {
                const buf = Message.encode(msg);
                this.socket.send(buf);
            } catch (err) {
                console.error("Error encoding message", err, msg);
                throw err;
            }
        } else {
            this.queue.unshift(msg);
        }
    }

    handleMessage(msg: Message) {
        for (const handler of this.messageListeners) {
            const msgType = handler[0];
            const listenerCB = handler[1];
            const removeWhenFalse = handler[2];

            if (msg instanceof msgType) { // check not redundant even though IntelliJ complains.
                const result = listenerCB(msg) as void | boolean;
                if (removeWhenFalse && (result === false || result === undefined)) {
                    this.removeMessageListener(handler);
                }
            }
        }
    }

    receive(event: Event) {
        if (event instanceof MessageEvent) {
            if (event.data instanceof ArrayBuffer) {
                const msg = Message.decode(event.data);
                this.handleMessage(msg)
            } else {
                //console.log(event.data);
            }
        }
    }

    addMessageListener<M extends Message, C extends (new (...args: any[]) => M) & typeof Message>(msgType: C, fn: (...args: ConstructorParameters<typeof msgType>) => void, removeWhenFalse: boolean = false) {
        const handler: MessageListener = [
            msgType,
            (inst: M) => fn.apply(null, msgType.unapply(inst)),
            removeWhenFalse];
        this.messageListeners.push(handler);
        return handler;
    }

    addInstanceListener<M extends Message, C extends (new (...args: any[]) => M) & typeof Message>(msgType: C, fn: (inst: M) => void, removeWhenFalse: boolean = false) {
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
            clearInterval(this.pingIntervalId)
            this.dispatchEvent(new CustomEvent('close'));
            delete openSessions[this.url.href];
        }
    }

    /**
     * Create a new connection, closing the existing one if needed.
     *
     * @param onlyIfClosed  Whether to force a reconnect if the connection is currently open.
     * @return              Whether a reconnect happened.
     */
    reconnect(onlyIfClosed: boolean): boolean {
        if (!this.socket || this.isClosed || (!onlyIfClosed && (this.socket.readyState > WebSocket.CONNECTING))) {
            this.close();
            this.mkSocket();
            return true
        }
        return false
    }

}

// for testing visibility
export const __testExports = {wsUrl, closeAll, openSessions}