import {StateHandler, StateView} from "./state_handler";
import {SocketSession} from "../messaging/comms";
import * as messages from "../data/messages";
import {Message} from "../data/messages";
import {ServerErrorWithCause} from "../data/result";

export interface SocketState {
    status: "connected" | "disconnected",
    // EPHEMERAL: error gets cleared after broadcast (see SocketStateHandler).
    error?: ConnectionError
}

export enum ConnectionStatus {ONLINE, OFFLINE};

export class ConnectionError {
    constructor(readonly status: ConnectionStatus, readonly error: ServerErrorWithCause) {
        Object.freeze(this);
    }

    static unapply(inst: ConnectionError): ConstructorParameters<typeof ConnectionError> {
        return [inst.status, inst.error];
    }
}


/**
 * SocketStateHandler manages a Socket. It does not hold a reference to the socket, instead pushing it to the Sockets global map.
 */
export class SocketStateHandler extends StateHandler<SocketState> {

    private readonly socketKey: string;
    private static inst: SocketStateHandler;

    static get global() {
        if (!SocketStateHandler.inst) {
            SocketStateHandler.inst = new SocketStateHandler(SocketSession.global)
        }
        return SocketStateHandler.inst;
    }

    constructor(socket: SocketSession, initial: SocketState = {status: "disconnected", error: undefined}) {
        super(initial);

        this.socketKey = socket.url.href;
        Sockets.set(this.socketKey, socket);

        socket.addEventListener('open', evt => {
            this.updateState(s => {
                return { ...s, status: "connected" }
            })
        });

        socket.addEventListener('close', evt => {
            this.updateState(s => {
                return { ...s, status: "disconnected" }
            })
        });
        socket.addEventListener('error', evt => {
            console.error("got error event from socket: ", evt)
            const url = new URL(socket.url.toString());
            url.protocol = document.location.protocol;
            const req = new XMLHttpRequest();
            req.responseType = "arraybuffer";
            const updateError = (error: ConnectionError) => {
                this.updateState(s => {
                    return {
                        error: error,
                        status: "disconnected"
                    }
                })
            }
            req.addEventListener("readystatechange", evt => {
                if (req.readyState === XMLHttpRequest.DONE) {
                    if (req.response instanceof ArrayBuffer && req.response.byteLength > 0) {
                        let msg: Message;
                        try {
                            msg = Message.decode(req.response);
                        } catch (e) {
                            if (e instanceof Error) {
                                msg = new messages.Error(0, new ServerErrorWithCause(e.constructor.name, e.message || e.toString(), []))
                            } else {
                                msg = new messages.Error(0, new ServerErrorWithCause("Websocket Connection Error", e.toString(), []))
                            }
                        }
                        if (msg instanceof messages.Error) {
                            socket.close();
                            console.error("got error message", msg)
                            // since we got an error message, we know we were able to at least open the socket, so the
                            // connection is online.
                            updateError(new ConnectionError(ConnectionStatus.ONLINE, msg.error))
                        }
                    } else if (req.status === 0) {
                        console.error("An error occurred opening the websocket!")
                        // Assume that we are offline because we couldn't even open the websocket.
                        updateError(new ConnectionError(ConnectionStatus.OFFLINE,
                            new ServerErrorWithCause("Websocket Connection Error", "Error occurred connecting to websocket. \n" +
                                "Polynote has been disconnected from the server, so editing and execution functionality has been disabled.", [])))
                    }
                }
            });
            req.open("GET", url.toString());
            req.send(null);
        });
    }

    get socket() {
        const socket = Sockets.get(this.socketKey);
        if (socket) return socket;
        else throw new Error(`Unable to find socket with key ${this.socketKey}`);
    }


    // delegates
    public addMessageListener(...args: Parameters<SocketSession["addMessageListener"]>): ReturnType<SocketSession["addMessageListener"]> {
        return this.socket.addMessageListener(...args)
    }
    public removeMessageListener(...args: Parameters<SocketSession["removeMessageListener"]>): ReturnType<SocketSession["removeMessageListener"]> {
        return this.socket.removeMessageListener(...args)
    }
    public send(...args: Parameters<SocketSession["send"]>): ReturnType<SocketSession["send"]> {
        return this.socket.send(...args)
    }
    public reconnect(...args: Parameters<SocketSession["reconnect"]>): ReturnType<SocketSession["reconnect"]> {
        return this.socket.reconnect(...args)
    }
    public handleMessage(...args: Parameters<SocketSession["handleMessage"]>): ReturnType<SocketSession["handleMessage"]> {
        return this.socket.handleMessage(...args)
    }
    public close(...args: Parameters<SocketSession["close"]>): ReturnType<SocketSession["close"]> {
        this.clearObservers()
        return this.socket.close(...args)
    }
}

/**
 * References to all sockets live here. We store sockets here in order to prevent the State from including Sockets
 * which are uncloneable.
 */
export const Sockets = new Map<string, SocketSession>();