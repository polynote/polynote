import {UserPreferences, UserPreferencesHandler} from "./preferences";
import * as monaco from "monaco-editor";
import {Disposable} from "./state_handler";

export class ThemeHandler extends Disposable {
    constructor() {
        super()
        const handlePref = (pref: typeof UserPreferences["theme"]) => {
            console.log("Setting theme to ", pref.value)
            if (pref.value !== undefined && pref.value !== null) {
                const el = document.getElementById("polynote-color-theme");
                if (el) {
                    el.setAttribute("href", `static/style/colors-${pref.value.toLowerCase()}.css`);
                }
                monaco.editor.setTheme(`polynote-${pref.value.toLowerCase()}`);
            }
        }
        handlePref(UserPreferencesHandler.state.theme)
        UserPreferencesHandler.view("theme").addObserver(pref => handlePref(pref), this)
    }
}
