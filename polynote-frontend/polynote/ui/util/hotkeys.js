
import {Cell, CodeCell} from "../component/cell";
import {OS, isMacintosh} from 'monaco-editor/esm/vs/base/common/platform.js'
import {createSimpleKeybinding} from 'monaco-editor/esm/vs/base/common/keyCodes.js'
import {KeyCodeUtils} from 'monaco-editor/esm/vs/base/common/keyCodes.js'

// Return an object of hotkeys with the following structure:
// {
//     source: {
//          key combination: description
//     }
// }
export function getHotkeys() {
    const hotkeys = {};

    const cellHotkeys = {};
    for (const [keycode, action] of Cell.keyMap) {
        const desc = action.desc;
        if (desc) {
            const simpleKeybinding = createSimpleKeybinding(keycode, OS);
            const keyCombo = keybindingToString(simpleKeybinding);
            cellHotkeys[keyCombo] = desc;
        }
    }
    hotkeys['Cells'] = cellHotkeys;

    const codeCellHotkeys = {};
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

function keybindingToString(simpleKeybinding) {
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
     * @param fun                   A function of type (Position, Range, Selection, Cell) -> Boolean. The return type indicates whether to stop propa
     * @param desc                  A human-readable description for this action (like "Run All Cells")
     * @param preventDefault        Whether to call preventDefault on the event
     * @param ignoreWhenSuggesting  Whether to ignore this action if the suggestion widget is currently open
     */
    constructor(fun, desc, preventDefault = false, ignoreWhenSuggesting = true) {
        this.fun = fun;
        this.desc = desc;
        this.preventDefault = preventDefault;
        this.ignoreWhenSuggesting = ignoreWhenSuggesting;
    }

    withPreventDefault(pd) {
        this.preventDefault = pd;
        return this;
    }

    withIgnoreWhenSuggesting(i) {
        this.ignoreWhenSuggesting = i;
        return this;
    }

    withDesc(desc) {
        this.desc = desc;
        return this;
    }

    // Create a new KeyAction that executes `otherAction` and then this action.
    // The new action takes the most restrictive configuration of `preventDefault` and `ignoreWhenSuggesting`
    runAfter(firstAction) {
        const fun = (position, range, selection, cell) => {
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