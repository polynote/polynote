import {BaseHandler, IDisposable, mkDisposable, ObjectStateHandler, setValue, StateHandler} from ".";
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
export class SocketStateHandler extends BaseHandler<SocketState> {

    private static inst: SocketStateHandler;

    static create(socket: SocketSession, initial: SocketState = {status: "disconnected", error: undefined}): SocketStateHandler {
        const socketKey = socket.url.href;
        Sockets.set(socketKey, socket);
        const baseHandler = new ObjectStateHandler<SocketState>(initial);
        const handler = new SocketStateHandler(baseHandler, socketKey);

        const setConnected = () => handler.updateField("status", () => setValue("connected"));
        socket.addEventListener('open', setConnected);

        const setDisconnected = () => handler.updateField("status", () => setValue("disconnected"));
        socket.addEventListener('close', setDisconnected);


        const handleError =  (evt: Event) => {
            console.error("got error event from socket: ", evt)
            const url = new URL(socket.url.toString());
            url.protocol = document.location.protocol;
            const req = new XMLHttpRequest();
            req.responseType = "arraybuffer";
            const updateError = (error: ConnectionError) => {
                handler.update(() => ({
                    error: error,
                    status: "disconnected"
                }));
            }
            req.addEventListener("readystatechange", evt => {
                if (req.readyState === XMLHttpRequest.DONE) {
                    if (req.response instanceof ArrayBuffer && req.response.byteLength > 0) {
                        let msg: Message;
                        try {
                            msg = Message.decode(req.response);
                        } catch (e) {
                            try {
                                const resp = new TextDecoder().decode(req.response)
                                msg = new messages.Error(0, new ServerErrorWithCause("Websocket Connection Error", resp, []))
                            } catch (_) {
                                if (e instanceof Error) {
                                    msg = new messages.Error(0, new ServerErrorWithCause("Websocket Connection Error", e.toString(), [],
                                        new ServerErrorWithCause(e.constructor.name, e.message || e.toString(), [])))
                                } else {
                                    msg = new messages.Error(0, new ServerErrorWithCause("Websocket Connection Error", e.toString(), []))
                                }
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
        };

        socket.addEventListener('error', handleError);

        handler.onDispose.then(() => {
            socket.removeEventListener('open', setConnected);
            socket.removeEventListener('close', setDisconnected);
            socket.removeEventListener('error', handleError);
        });

        return handler;
    }

    static get global() {
        if (!SocketStateHandler.inst) {
            SocketStateHandler.inst = SocketStateHandler.create(SocketSession.global)
        }
        return SocketStateHandler.inst;
    }

    private constructor(parent: StateHandler<SocketState>, readonly socketKey: string) {
        super(parent);
    }

    private get socket() {
        const socket = Sockets.get(this.socketKey);
        if (socket) return socket;
        else throw new Error(`Unable to find socket with key ${this.socketKey}`);
    }


    // delegates
    public addMessageListener(...args: Parameters<SocketSession["addMessageListener"]>): IDisposable {
        const listener = this.socket.addMessageListener(...args);
        return mkDisposable(listener, () => this.socket.removeMessageListener(listener));
    }

    public addInstanceListener(...args: Parameters<SocketSession["addInstanceListener"]>): IDisposable {
        const listener = this.socket.addInstanceListener(...args);
        return mkDisposable(listener, () => this.socket.removeMessageListener(listener));
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
        this.tryDispose();
        return this.socket.close(...args)
    }

    fork(disposeContext?: IDisposable): SocketStateHandler {
        const fork = new SocketStateHandler(this.parent.fork(disposeContext).disposeWith(this), this.socketKey).disposeWith(this);
        return disposeContext ? fork.disposeWith(disposeContext) : fork;
    }
}

/**
 * References to all sockets live here. We store sockets here in order to prevent the State from including Sockets
 * which are uncloneable.
 */
export const Sockets = new Map<string, SocketSession>();