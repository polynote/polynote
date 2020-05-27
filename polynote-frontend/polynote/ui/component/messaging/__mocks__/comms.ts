import {Message} from "../../../../data/messages";
import {MessageListener} from "../comms";

/**
 * Mock SocketSession. In order to use this mock, add `jest.mock("../messaging/comms");` to the top of your test file.
 */
export class SocketSession {
    public url: { href: string };
    constructor(url: string) {
        this.url = {href: url}
    }


    private static inst: SocketSession;
    static get global() {
        if (!SocketSession.inst) {
            SocketSession.inst = SocketSession.fromRelativeURL("ws")
        }
        return SocketSession.inst
    }

    static fromRelativeURL(url: string) {
        return new SocketSession(url)
    }
    // public send = jest.fn();
    // public addMessageListener = jest.fn();

    public send = jest.fn((msg: Message) => {
        for (const handler of this.messageListeners) {
            const msgType = handler[0];
            const listenerCB = handler[1];
            const removeWhenFalse = handler[2];

            if (msg instanceof msgType) { // check not redundant even though IntelliJ complains.
                listenerCB.apply(null, msgType.unapply(msg));
            }
        }
    })

    public messageListeners: MessageListener[] = [];
    public addMessageListener = jest.fn(<M extends Message, C extends (new (...args: any[]) => M) & typeof Message>(msgType: C, fn: (...args: ConstructorParameters<typeof msgType>) => void, removeWhenFalse: boolean = false) => {
        const handler: MessageListener = [msgType, fn, removeWhenFalse];
        this.messageListeners.push(handler);
        return handler;
    });

    public addEventListener = jest.fn();
    public reconnect = jest.fn();
    public close = jest.fn();
}