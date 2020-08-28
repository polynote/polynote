import { SocketSession } from './comms'
import { MainUI } from './ui/component/ui'
import { scala, vega } from './ui/monaco/languages'
import { themes } from './ui/monaco/themes'
import * as monaco from "monaco-editor";
import * as Tinycon from "tinycon";
import {MarkdownIt} from "./util/markdown-it";
import {Preferences, preferences} from "./ui/util/storage";

window.MarkdownIt = MarkdownIt;

// set up custom highlighters
monaco.languages.register({ id: 'scala' });
monaco.languages.setMonarchTokensProvider('scala', scala.definition);
monaco.languages.setLanguageConfiguration('scala', scala.config);

monaco.languages.register({id: 'vega'});
monaco.languages.setMonarchTokensProvider('vega', vega.definition);
monaco.languages.setLanguageConfiguration('vega', vega.config);

// use our theme
monaco.editor.defineTheme('polynote-light', themes.light);
monaco.editor.defineTheme('polynote-dark', themes.dark);

// open the global socket for control messages
SocketSession.global;

const mainUI = new MainUI();
const mainEl = document.getElementById('Main');
mainEl?.appendChild(mainUI.el);
const path = decodeURIComponent(window.location.pathname.replace(new URL(document.baseURI).pathname, ''));
const notebookBase = 'notebook/';
if (path.startsWith(notebookBase)) {
  mainUI.loadNotebook(path.substring(notebookBase.length));
} else {
  mainUI.showWelcome();
}

Tinycon.setOptions({
    background: '#308b24'
});

// Set up theme preference – default value comes from prefers-color-scheme media query
preferences.register(
    "Theme",
    window.matchMedia("(prefers-color-scheme: dark)").matches ? 'dark' : 'light',
    "The application color scheme (light or dark)",
    {'Light': 'light', 'Dark': 'dark'});

export function setTheme(theme: string) {
    if (theme) {
        const el = document.getElementById("polynote-color-theme");
        if (el) {
            el.setAttribute("href", `static/style/colors-${theme}.css`);
        }
        monaco.editor.setTheme(`polynote-${theme}`);
    }
}

// temporary – expose to the console so that we can test the different themes
(window as any).setTheme = setTheme;

// set the initial theme based on preferences
setTheme(preferences.get("Theme").value as string);

// change the theme when the preference is changed
preferences.addPreferenceListener("Theme", (oldValue, newValue) => {
    setTheme(newValue.value as string);
});
