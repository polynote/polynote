// we need to import this inside a `.ts` file. Docs seem to indicate you can rename the jest config to `jest.config.ts`
// but that wasn't working for me.
import '@testing-library/jest-dom'

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