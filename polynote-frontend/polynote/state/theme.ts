import {Disposable} from ".";
import * as monaco from "monaco-editor";
import {UserPreferences, UserPreferencesHandler} from "./preferences";

export class ThemeHandler extends Disposable {
    constructor() {
        super()
        const handlePref = (pref: typeof UserPreferences["theme"]) => {
            if (pref.value !== undefined && pref.value !== null) {
                const el = document.getElementById("polynote-color-theme");
                if (el) {
                    el.setAttribute("href", `static/style/colors-${pref.value.toLowerCase()}.css`);
                }
                monaco.editor.setTheme(`polynote-${pref.value.toLowerCase()}`);
            }
        }
        handlePref(UserPreferencesHandler.state.theme)
        UserPreferencesHandler.observeKey("theme", pref => handlePref(pref)).disposeWith(this)
    }
}
