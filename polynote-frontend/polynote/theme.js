import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'

export const theme = {
  base: 'vs',
  inherit: true,
  rules: [
    { token: 'keyword', foreground: '000088', fontStyle: 'bold' },
    { token: 'string', foreground: '008800', fontStyle: 'bold'},
    { token: 'number', foreground: '0000FF' }
  ]
};



