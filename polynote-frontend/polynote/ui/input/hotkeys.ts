import {KeyMod} from "monaco-editor";

// The following Monaco imports don't have proper types as they're directly using implementation code in a bit of a
// hacky way. So these @ts-ignore comments serve to further underscore the hackiness of the hotkey solution :(
// @ts-ignore
import {isMacintosh, OS} from 'monaco-editor/esm/vs/base/common/platform.js'
// @ts-ignore
import {KeyCodeUtils} from 'monaco-editor/esm/vs/base/common/keyCodes.js'
import {cellHotkeys, hotKeyCodeToString} from "../component/notebook/cell";
import * as monaco from "monaco-editor";

export function getHotkeys() {
    const hotkeys: Record<string, string> = {};

    Object.entries(cellHotkeys).forEach(([hotkeyCode, keyInfo]) => {
        if (!keyInfo.hide) {
            const keyCombo = hotkeyCodeToString(hotkeyCode);
            hotkeys[keyCombo] = keyInfo.description;
        }
    })

    return hotkeys;
}

function hotkeyCodeToString(hotkeyCode: string): string {
    const keys: string[] = [];

    hotKeyCodeToString[hotkeyCode].forEach((code: KeyMod) => {
        if (code === monaco.KeyMod.WinCtrl)  keys.push("Ctrl");
        else if (code === monaco.KeyMod.Shift) keys.push("Shift");
        else if (code === monaco.KeyMod.Alt) {
            if (isMacintosh) keys.push("Option")
            else keys.push("Alt")
        } else if (code === monaco.KeyMod.CtrlCmd) {
            if (isMacintosh) keys.push("Cmd")
            else keys.push("Meta")
        }
        else keys.push(KeyCodeUtils.toString(code));
    })

    return keys.join("+")
}
