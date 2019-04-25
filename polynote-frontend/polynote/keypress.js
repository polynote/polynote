
// inspired by SimpleKeyBinding: https://github.com/Microsoft/vscode/blob/master/src/vs/base/common/keyCodes.ts#L443
export class KeyPress {
    // TODO: windows support needs to swap ctrl and meta
    constructor(key, ctrl, shift, alt, meta) {
        this.key = key;
        this.ctrl = ctrl;
        this.shift = shift;
        this.alt = alt;
        this.meta = meta; // meta == monaco.KeyMod.CtrlCmd
    }

    // we need to do this annoying hash stuff ourselves because js object equality is not a thing... sigh
    hashCode() {
        const ctrl = this.ctrl ? '1' : '0';
        const shift = this.shift ? '1' : '0';
        const alt = this.alt ? '1' : '0';
        const meta = this.meta ? '1' : '0';
        return `${ctrl}${shift}${alt}${meta}${this.key}`;
    }

    get h() {
        return this.hashCode();
    }

    static fromHash(hash) {
        const [ctrl, shift, alt, meta, ...key] = hash.split('');
        return new KeyPress(key.join(''), ctrl === "1", shift === "1", alt === "1", meta === "1")
    }

    matches(keyPressEvent) {
        return (this.key === keyPressEvent.code) || (this.key === keyPressEvent.key) &&
            this.ctrl === keyPressEvent.ctrlKey &&
            this.shift === keyPressEvent.shiftKey &&
            this.alt === keyPressEvent.altKey &&
            this.meta === keyPressEvent.metaKey

    }

    static fromEvent(keyPressEvent) {
        return new KeyPress(
            keyPressEvent.code || keyPressEvent.key,
            keyPressEvent.ctrlKey,
            keyPressEvent.shiftKey,
            keyPressEvent.altKey,
            keyPressEvent.metaKey)
    }

    static of(key, modifiers) {
        modifiers = modifiers || {};
        return new KeyPress(key, modifiers.ctrl || false, modifiers.shift || false, modifiers.alt || false, modifiers.meta || false)
    }
}