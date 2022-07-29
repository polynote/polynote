import {Disposable} from ".";
import {UserPreferences, UserPreferencesHandler} from "./preferences";

export class MarkdownEditorHandler extends Disposable {
    constructor() {
        super()
        const handlePref = (pref: typeof UserPreferences["markdownEditor"]) => {
            if (pref.value !== undefined && pref.value !== null) {
                const el = document.getElementById("text-toolbar");
                if (!el) return;
                if (pref.value) {
                    el.classList.remove("text");
                    el.classList.add("text-markdown");
                } else {
                    el.classList.remove("text-markdown");
                    el.classList.add("text");
                }
            }
        }
        handlePref(UserPreferencesHandler.state.markdownEditor)
        UserPreferencesHandler.observeKey("markdownEditor", pref => handlePref(pref)).disposeWith(this)
    }
}
