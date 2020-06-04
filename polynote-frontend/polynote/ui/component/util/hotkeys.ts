
import {KeyCode} from "monaco-editor";

// The following Monaco imports don't have proper types as they're directly using implementation code in a bit of a
// hacky way. So these @ts-ignore comments serve to further underscore the hackiness of the hotkey solution :(
// @ts-ignore
import {OS, isMacintosh} from 'monaco-editor/esm/vs/base/common/platform.js'
// @ts-ignore
import {createSimpleKeybinding} from 'monaco-editor/esm/vs/base/common/keyCodes.js'
// @ts-ignore
import {KeyCodeUtils} from 'monaco-editor/esm/vs/base/common/keyCodes.js'
import {cellHotkeys} from "../component/cell";

interface Keybinding {
    readonly ctrlKey: boolean;
    readonly shiftKey: boolean;
    readonly altKey: boolean;
    readonly metaKey: boolean;
    readonly keyCode: KeyCode;
}

export function getHotkeys() {
    const hotkeys: Record<string, string> = {};

    Object.entries(cellHotkeys).forEach(([code, [key, desc]]) => {
        const simpleKeybinding: Keybinding = createSimpleKeybinding(code, OS);
        const keyCombo = keybindingToString(simpleKeybinding);
        hotkeys[keyCombo] = desc;
    })

    return hotkeys;
}

function keybindingToString(simpleKeybinding: Keybinding): string {
    let keys = [];
    if (simpleKeybinding.ctrlKey) {
        keys.push("Ctrl")
    }
    if (simpleKeybinding.shiftKey) {
        keys.push("Shift")
    }
    if (simpleKeybinding.altKey) {
        if (isMacintosh) {
            keys.push("Option")
        } else {
            keys.push("Alt")
        }
    }
    if (simpleKeybinding.metaKey) {
        if (isMacintosh) {
            keys.push("Cmd")
        } else {
            keys.push("Meta")
        }
    }
    const actualKey = KeyCodeUtils.toString(simpleKeybinding.keyCode);
    if (actualKey) {
        keys.push(actualKey)
    }
    return keys.join("+")
}
