import * as Tinycon from "tinycon";

export class FaviconHandler {
    private static inst: FaviconHandler;
    static get get() {
        if (!FaviconHandler.inst) {
            FaviconHandler.inst = new FaviconHandler()
        }
        return FaviconHandler.inst;
    }

    private constructor() {
        Tinycon.setOptions({
            background: '#308b24'
        });
    }

    get faviconUrl(): string | undefined {
        return (document.getElementsByTagName('head')[0].querySelector("link[rel*='icon") as HTMLLinkElement)?.href;
    }

    private _bubble: number = 0;
    get bubble() {
        return this._bubble;
    }

    set bubble(num: number) {
        this._bubble = num;
        Tinycon.setBubble(this._bubble);
    }

    static inc(increment: number = 1) {
        FaviconHandler.get.inc(increment)
    }
    inc(increment: number = 1) {
        this.bubble += increment;
    }

    static dec(decrement: number = 1) {
        FaviconHandler.get.dec(decrement)
    }
    dec(decrement: number = 1) {
        this.bubble -= decrement;
        if (this.bubble < 0) this.reset()
    }

    private reset() {
        this.bubble = 0;
        Tinycon.reset();
    }
}