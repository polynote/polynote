// Handle some modifications/customizations for monaco-vim
// The only thing this does for now is handle the stupid default mapping of `<CR>` to
// `j^` (jump to beginning of the line). Unfortunately the vim plugin eats all keypresses
// and doesn't propagate the event out back to Monaco, so the easiest way to get
// Shift+Enter to work in normal mode is just to remove that default mapping.

import { default as CodeMirror } from "monaco-vim/lib/cm/keymap_vim";
import {initVimMode} from "monaco-vim";

const vimSettings = CodeMirror.Vim;
vimSettings.unmap("<CR>", "normal");

export function createVim(editor, statusLine) {
    return initVimMode(editor, statusLine);
}

