/**
 * Mock SocketSession. In order to use this mock, add `jest.mock("../messaging/comms");` to the top of your test file.
 */
export class SocketSession {
    public url: { href: string };
    constructor(url: string) {
        this.url = {href: url}
    }

    static fromRelativeURL(url: string) {
        return new SocketSession(url)
    }
    public send = jest.fn();
    public addMessageListener = jest.fn();
    public addEventListener = jest.fn();
    public reconnect = jest.fn();
    public close = jest.fn();
}