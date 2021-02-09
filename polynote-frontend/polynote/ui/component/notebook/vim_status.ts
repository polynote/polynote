import {div, TagElement} from "../../tags";
import {Disposable} from "../../../state";
import {UserPreferences, UserPreferencesHandler} from "../../../state/preferences"
import {editor} from "monaco-editor";
import {createVim} from "../../input/monaco/vim";
import IStandaloneCodeEditor = editor.IStandaloneCodeEditor;

export class VimStatus extends Disposable {
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
    private currentVimId?: string;
    private enabled: boolean = false;
    private constructor() {
        super()
        this.statusLine = div(["status"], []);
        this.el = div(["vim-status", "hide"], [this.statusLine])
        const stateHandler = (pref: typeof UserPreferences["vim"]) => {
            if (!pref.value) {
                Object.keys(this.vims).forEach(key => {
                    this.deactivate(key)
                })
            }
            this.enabled = pref.value;
        }
        const vimState = UserPreferencesHandler.view("vim")
        stateHandler(vimState.state)
        vimState.addObserver(state => stateHandler(state)).disposeWith(this)
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
                this.statusLine.replaceWith(maybeVim.statusLine)
                this.statusLine = maybeVim.statusLine;
                this.currentVimId = editor.getId();
                return maybeVim.vim
            } else {
                // this.currentVim?.vim.dispose()
                const statusLine = div(["status"], []);
                const vim = createVim(editor, statusLine);
                this.statusLine.replaceWith(statusLine);
                this.statusLine = statusLine;
                this.vims[editor.getId()] = {vim, statusLine}
                this.currentVimId = editor.getId();
                return vim
            }
        }
    }

    deactivate(id: string) {
        this.hide()
        this.vims[id]?.vim.dispose()
        delete this.vims[id]
    }

    static get currentVim() {
        const inst = VimStatus.get;
        return inst.currentVimId ? inst.vims[inst.currentVimId] : undefined
    }

    static get currentlyActive() {
        return document.activeElement ? VimStatus.currentVim?.statusLine.contains(document.activeElement) : undefined
    }
}