
import {Cell, CodeCell} from "../component/cell";
import {IPosition, IRange, ISelection, KeyCode} from "monaco-editor";

// The following Monaco imports don't have proper types as they're directly using implementation code in a bit of a
// hacky way. So these @ts-ignore comments serve to further underscore the hackiness of the hotkey solution :(
// @ts-ignore
import {OS, isMacintosh} from 'monaco-editor/esm/vs/base/common/platform.js'
// @ts-ignore
import {createSimpleKeybinding} from 'monaco-editor/esm/vs/base/common/keyCodes.js'
// @ts-ignore
import {KeyCodeUtils} from 'monaco-editor/esm/vs/base/common/keyCodes.js'

export interface Hotkeys {
    [keycode: string]: KeyAction
}

interface Keybinding {
    readonly ctrlKey: boolean;
    readonly shiftKey: boolean;
    readonly altKey: boolean;
    readonly metaKey: boolean;
    readonly keyCode: KeyCode;
}

export function getHotkeys() {
    const hotkeys: Record<string, Record<string, string>> = {};

    const cellHotkeys: Record<string, string> = {};
    for (const [keycode, action] of Cell.keyMap) {
        const desc = action.desc;
        if (desc) {
            const simpleKeybinding: Keybinding = createSimpleKeybinding(keycode, OS);
            const keyCombo = keybindingToString(simpleKeybinding);
            cellHotkeys[keyCombo] = desc;
        }
    }
    hotkeys['Cells'] = cellHotkeys;

    const codeCellHotkeys: Record<string, string> = {};
    for (const [keycode, action] of CodeCell.keyMapOverrides) {
        const desc = action.desc;
        if (desc) {
            const simpleKeybinding = createSimpleKeybinding(keycode, OS);
            const keyCombo = keybindingToString(simpleKeybinding);
            codeCellHotkeys[keyCombo] = desc;
        }
    }
    hotkeys['Code Cells'] = codeCellHotkeys;

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

export class KeyAction {
    /**
     * An action to be taken runAfter a KeyPress
     *
     * @param fun                   The actual action.
     * @param desc                  A human-readable description for this action (like "Run All Cells")
     * @param preventDefault        Whether to call preventDefault on the event
     * @param ignoreWhenSuggesting  Whether to ignore this action if the suggestion widget is currently open
     */
    constructor(readonly fun: (pos: IPosition, range: IRange, selection: string, cell: Cell) => void,
                public desc: string = "",
                public preventDefault: boolean = false,
                public ignoreWhenSuggesting: boolean = true) {}

    withPreventDefault(pd: boolean) {
        this.preventDefault = pd;
        return this;
    }

    withIgnoreWhenSuggesting(ignore: boolean) {
        this.ignoreWhenSuggesting = ignore;
        return this;
    }

    withDesc(desc: string) {
        this.desc = desc;
        return this;
    }

    // Create a new KeyAction that executes `otherAction` and then this action.
    // The new action takes the most restrictive configuration of `preventDefault` and `ignoreWhenSuggesting`
    runAfter(firstAction: KeyAction) {
        const fun = (position: IPosition, range: IRange, selection: string, cell: Cell) => {
            firstAction.fun(position, range, selection, cell); // position, range, selection, cell might be mutated by this function!
            this.fun(position, range, selection, cell);
        };
        // preventDefault defaults to false, so if either action has explicitly set it to true we want it to take precedence.
        const preventDefault = this.preventDefault || firstAction.preventDefault;

        // ignoreWhenSuggesting defaults to true, so if either action has explicitly set it to false we want it to take precedence.
        const ignoreWhenSuggesting = this.ignoreWhenSuggesting && firstAction.ignoreWhenSuggesting;

        // `firstAction.name` takes precedence if defined
        const desc = firstAction.desc || this.desc;

        return new KeyAction(fun, desc, preventDefault, ignoreWhenSuggesting)
    }
}