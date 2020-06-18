import {div, TagElement} from "../../util/tags";
import {UserPreferences} from "../state/storage";
import {editor} from "monaco-editor";
import IStandaloneCodeEditor = editor.IStandaloneCodeEditor;
import {createVim} from "../../util/vim";

export class VimStatus {
    private static inst: VimStatus;
    static get get() {
        if (! VimStatus.inst) {
            VimStatus.inst = new VimStatus()
        }
        return VimStatus.inst
    }

    public el: TagElement<"div">;
    private statusLine: TagElement<"div">;
    private vims: Record<string, { vim: any, statusLine: TagElement<"div"> }> = {}; // keys are editor ids, which should be unique.
    private currentVim?: string;
    private enabled: boolean = false;
    private constructor() {
        this.statusLine = div(["status"], []);
        this.el = div(["vim-status", "hide"], [this.statusLine])
        const stateHandler = (pref: UserPreferences["vim"]) => {
            if (!pref.value) {
                Object.keys(this.vims).forEach(key => {
                    this.deactivate(key)
                })
            }
            this.enabled = pref.value;
        }
        const vimState = UserPreferences.view("vim")
        stateHandler(vimState.getState())
        vimState.addObserver(state => stateHandler(state))
    }

    show() {
        this.el.classList.remove("hide")
    }

    hide() {
        this.el.classList.add("hide")
    }

    activate(editor: IStandaloneCodeEditor) {
        if (this.enabled) {
            this.show()
            const maybeVim = this.vims[editor.getId()]
            if (maybeVim) {
                console.log("already created vim")
                this.statusLine.replaceWith(maybeVim.statusLine)
                this.statusLine = maybeVim.statusLine;
                this.currentVim = editor.getId();
                return maybeVim.vim
            } else {
                console.log("creating vim")
                // this.currentVim?.vim.dispose()
                const statusLine = div(["status"], []);
                const vim = createVim(editor, statusLine);
                this.statusLine.replaceWith(statusLine);
                this.statusLine = statusLine;
                this.vims[editor.getId()] = {vim, statusLine}
                this.currentVim = editor.getId();
                return vim
            }
        }
    }

    deactivate(id: string) {
        console.log("deactivating vim!")
        this.hide()
        this.vims[id]?.vim.dispose()
        delete this.vims[id]
    }

}