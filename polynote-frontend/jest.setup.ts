// we need to import this inside a `.ts` file. Docs seem to indicate you can rename the jest config to `jest.config.ts`
// but that wasn't working for me.
import '@testing-library/jest-dom'
import 'jest-canvas-mock';


// Mock functions that are not implemented in JSDOM (see: https://jestjs.io/docs/en/manual-mocks#mocking-methods-which-are-not-implemented-in-jsdom )
Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: jest.fn().mockImplementation(query => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: jest.fn(), // deprecated
        removeListener: jest.fn(), // deprecated
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
        dispatchEvent: jest.fn(),
    })),
});

// We need to mock these APIs because they break jest and it's too much of a pain to figure out how to get it to load these files properly.
// It's fine because we aren't writing tests based on this anyways.
jest.mock('monaco-editor/esm/vs/base/common/platform.js', () => ({
    OS: 2,
    isMacintosh: true
}))

jest.mock('monaco-editor/esm/vs/base/common/keyCodes.js', () => ({
    KeyCodeUtils: {
        toString(num: number) {
            return "mocked!"
        }
    },
    createSimpleKeybinding() {
        return {}
    }
}))

jest.mock('monaco-editor/esm/vs/base/common/keyCodes.js', () => ({
    KeyCodeUtils: {
        toString(num: number) {
            return "mocked!"
        }
    }
}))

jest.mock('monaco-editor/esm/vs/base/browser/keyboardEvent.js', () => ({
    StandardKeyboardEvent: {
        constructor(evt: Event) {},
        _asKeybinding() { return 0 }
    }
}))

jest.mock("monaco-vim/lib/cm/keymap_vim", () => ({
    default: {
        Vim: {
            unmap(str1: string, str2: string) {}
        }
    }
}))

// We have to mock the creation of iconButtons here because we are inspecting the entire notebookList, and sometimes
// the iconButtons will fail to load and return null, which causes the querySelector to crash

jest.mock("./polynote/ui/tags", () => {
    const original = jest.requireActual("./polynote/ui/tags");
    return {
        ...original,
        iconButton: jest.fn().mockReturnValue(document.createElement("img")),
        icon: jest.fn().mockReturnValue(document.createElement("img")),
        helpIconButton: jest.fn().mockReturnValue(document.createElement("img"))
    }
});

jest.mock("./polynote/ui/icons", () => {
    return {
        loadIcon: jest.fn().mockReturnValue(Promise.resolve(document.createElement("img")))
    }
})

// Make these available to jest.
import {TextEncoder, TextDecoder} from "util";
// @ts-ignore
global.TextEncoder = TextEncoder
// @ts-ignore
global.TextDecoder = TextDecoder

// mock the `Main` element
import {div} from "./polynote/ui/tags";
const main = div([], [])
main.id = "Main"
export const superSecretKey = "PolynoteRocks!";
main.setAttribute('data-ws-key', superSecretKey)
document.body.appendChild(main)
