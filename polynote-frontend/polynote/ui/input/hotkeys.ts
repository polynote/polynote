import * as monaco from "monaco-editor";
import {KeyCode} from "monaco-editor";

// The following Monaco imports don't have proper types as they're directly using implementation code in a bit of a
// hacky way. So these @ts-ignore comments serve to further underscore the hackiness of the hotkey solution :(
// @ts-ignore
import {isMacintosh, OS} from 'monaco-editor/esm/vs/base/common/platform.js'
// @ts-ignore
// @ts-ignore
import {createSimpleKeybinding, KeyCodeUtils} from 'monaco-editor/esm/vs/base/common/keyCodes.js'
import {cellHotkeys} from "../component/notebook/cell";
import {ServerStateHandler} from "../../state/server_state";
import { HotkeyInfo } from "../../data/messages";

interface Keybinding {
    readonly ctrlKey: boolean;
    readonly shiftKey: boolean;
    readonly altKey: boolean;
    readonly metaKey: boolean;
    readonly keyCode: KeyCode;
}

export function getHotkeys() {
    const hotkeys: Record<string, string> = {};
    const keybindings: Record<number, HotkeyInfo> = Object.assign(ServerStateHandler.state.customKeybindings,cellHotkeys);

    Object.entries(keybindings).forEach(([code, keyInfo]) => {
        if (!keyInfo.hide) {
            const simpleKeybinding: Keybinding = createSimpleKeybinding(code, OS);
            const keyCombo = keybindingToString(simpleKeybinding);
            hotkeys[keyCombo] = keyInfo.description;
        }
    })

    return hotkeys;
}

export function keycodeToKeybinding(code: number) {
    return createSimpleKeybinding(code, OS);
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

export function keybindingFromString(keybindingStr: string, ): number {
    const keysStr = keybindingStr.toLowerCase().split("-");
    var keyMods = 0;
    if (keysStr.includes("ctrl")) keyMods += monaco.KeyMod.CtrlCmd;
    if (keysStr.includes("shift")) keyMods += monaco.KeyMod.Shift;
    if (keysStr.includes("option")) keyMods += monaco.KeyMod.Alt;
    if (keysStr.includes("alt")) keyMods += monaco.KeyMod.Alt;
    if (keysStr.includes("cmd")) keyMods += monaco.KeyMod.WinCtrl;
    if (keysStr.includes("meta")) keyMods += monaco.KeyMod.WinCtrl;
    const actualKeys = keysStr
    .map( key => KeyCodeUtils.fromString(key))
    .filter( keyCode => !createSimpleKeybinding(keyCode, OS).isModifierKey());
    if (actualKeys.length > 1) throw `Could not parse keybinding ${keybindingStr}: multiple key codes not supported`;
    if (actualKeys.length == 0) throw `Could not parse keybinding ${keybindingStr}: no key code found`;
    const keyCode = actualKeys[0];
    return keyCode + keyMods;
}