import {editor} from "monaco-editor";
import IStandaloneThemeData = editor.IStandaloneThemeData;

export const theme: IStandaloneThemeData = {
  base: 'vs',
  inherit: true,
  rules: [
    { token: 'keyword', foreground: '000088', fontStyle: 'bold' },
    { token: 'string', foreground: '008800', fontStyle: 'bold'},
    { token: 'number', foreground: '0000FF' }
  ],
  colors: {
    'editor.lineHighlightBackground': '#FFFAE3',
    'editor.lineHighlightBorder': '#00000000',
    'editorOverviewRuler.border': "#00000000"
  }
};



