// Handle some modifications/customizations for monaco-vim
// The only thing this does for now is handle the stupid default mapping of `<CR>` to
// `j^` (jump to beginning of the line). Unfortunately the vim plugin eats all keypresses
// and doesn't propagate the event out back to Monaco, so the easiest way to get
// Shift+Enter to work in normal mode is just to remove that default mapping.

// no reason to bother with types for this minor lib
// @ts-ignore
import { default as CodeMirror } from "monaco-vim/lib/cm/keymap_vim";
// @ts-ignore
import {initVimMode} from "monaco-vim";
import {editor} from "monaco-editor";

const vimSettings = CodeMirror.Vim;
vimSettings.unmap("<CR>", "normal");

export function createVim(editor: editor.IStandaloneCodeEditor, statusLine: HTMLElement) {
    return initVimMode(editor, statusLine);
}

