import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import {languages} from "monaco-editor/esm/vs/editor/editor.api";
import IMonarchLanguage = languages.IMonarchLanguage;
import LanguageConfiguration = languages.LanguageConfiguration;

interface LanguageDefinition {
  config: LanguageConfiguration, definition: IMonarchLanguage
}

export const scala: LanguageDefinition = {
  config: {
    surroundingPairs: [
      {open:"{",close:"}"},
      {open:"(",close:")"},
      {open:"[",close:"]"},
    ],
    autoClosingPairs: [
      {open:"{",close:"}"},
      {open:"(",close:")"},
      {open:"[",close:"]"},
      {open: '"""', close: '"""'},
      {open: '"', close: '"'},
    ],
    brackets: [
      ["{","}"],
      ["(", ")"],
      ["[", "]"],
      ['"""', '"""'],
    ],
    comments: {
      blockComment: ["/*", "*/"],
      lineComment: "//"
    }
  },
  definition: {
    // Set defaultToken to invalid to see what you do not tokenize yet
    // defaultToken: 'invalid',

    // @ts-ignore  no idea why TS doesn't like `keywords` in particular...
    keywords: [
      // From https://www.scala-lang.org/files/archive/spec/2.11/01-lexical-syntax.html
      'abstract', 'case', 'catch', 'class', 'def',
      'do', 'else', 'extends', 'false', 'final',
      'finally', 'for', 'forSome', 'if', 'implicit',
      'import', 'lazy', 'macro', 'match', 'new',
      'null', 'object', 'override', 'package', 'private',
      'protected', 'return', 'sealed', 'super', 'this',
      'throw', 'trait', 'try', 'true', 'type',
      'val', 'var', 'while', 'with', 'yield'
    ],

    typeKeywords: [
      'boolean', 'double', 'byte', 'int', 'short', 'char', 'void', 'long', 'float'
    ],

    operators: [
      '_', ':', '=', '=>', '<-', '<:', '<%', '>:', '#', '@',

      // Copied from java.ts, to be validated
      '=', '>', '<', '!', '~', '?', ':',
      '==', '<=', '>=', '!=', '&&', '||', '++', '--',
      '+', '-', '*', '/', '&', '|', '^', '%', '<<',
      '>>', '>>>', '+=', '-=', '*=', '/=', '&=', '|=',
      '^=', '%=', '<<=', '>>=', '>>>=',
    ],

    brackets: [
      { open: '(', close: ')', token: 'delimiter.parenthesis'},
      { open: '{', close: '}', token: 'delimiter.curly'},
      { open: '[', close: ']', token: 'delimiter.square'}
    ],

    // we include these common regular expressions
    symbols:  /[=><!~?:&|+\-*\/\^%]+/,

    // C# style strings
    escapes: /\\(?:[abfnrtv\\"']|x[0-9A-Fa-f]{1,4}|u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})/,

    // The main tokenizer for our languages
    tokenizer: {
      root: [
        // identifiers and keywords
        [/[a-z_$][\w$]*/, { cases: { '@typeKeywords': 'keyword',
            '@keywords': 'keyword',
            '@default': 'identifier' } }],
        [/[A-Z][\w\$]*/, 'type.identifier' ],  // to show class names nicely

        // whitespace
        { include: '@whitespace' },

        // delimiters and operators
        [/[{}()\[\]]/, '@brackets'],
        [/@symbols/, { cases: { '@operators': 'operator',
            '@default'  : '' } } ],

        // @ annotations.
        // As an example, we emit a debugging log message on these tokens.
        // Note: message are supressed during the first load -- change some lines to see them.
        [/@\s*[a-zA-Z_\$][\w\$]*/, { token: 'annotation', log: 'annotation token: $0' }],

        // number literals
        [/\d*\.\d+([eE][\-+]?\d+)?[fF]?/, 'number.float'],
        [/0[xX][0-9a-fA-F]+[lL]?/, 'number.hex'],
        [/\d+[lL]?/, 'number'],

        // delimiter: after number because of .\d floats
        [/[;,.]/, 'delimiter'],

        // triple-quoted string literal
        [/"""/, {token: 'string.quote', bracket: '@open', next: '@triplestring'}],

        // string literal
        [/"([^"\\]|\\.)*$/, 'string.invalid' ],  // non-teminated string
        [/"/,  { token: 'string.quote', bracket: '@open', next: '@string' } ],

        // symbol literal
        [/'([a-z]\w*)/, 'string.symbol'],

        // character literal
        [/'@escapes'/, 'string.char'],
        [/'[^']'/, 'string.char'],

      ],

      comment: [
        [/[^\/*]+/, 'comment' ],
        [/\/\*/,    'comment', '@push' ],    // nested comment
        [/\*\//,    'comment', '@pop'  ],
        [/[\/*]/,   'comment' ]
      ],

      string: [
        [/[^\\"]+/,  'string'],
        [/@escapes/, 'string.escape'],
        [/\\./,      'string.escape.invalid'],
        [/"/,        { token: 'string.quote', bracket: '@close', next: '@pop' } ]
      ],

      triplestring: [
        [/"""/, {token: 'string.quote', bracket: '@close', next: '@pop'}],
        [/([^"]|"[^"]|""[^"])*/, 'string']
      ],

      whitespace: [
        [/[ \t\r\n]+/, 'white'],
        [/\/\*/,       'comment', '@comment' ],
        [/\/\/.*$/,    'comment'],
      ],
    },
  }
};


// This could be improved quite a bit... recognize context like object key/value, etc
export const vega: LanguageDefinition = {
  config: {
    surroundingPairs: [
      {open:"{",close:"}"},
      {open:"(",close:")"},
      {open:"[",close:"]"},
    ],
    autoClosingPairs: [
      {open:"{",close:"}"},
      {open:"(",close:")"},
      {open:"[",close:"]"},
      {open:"`",close:"`"},
      {open: '"', close: '"'},
    ],
    brackets: [
      ["{","}"],
      ["(", ")"],
      ["[", "]"],
      ['`', '`'],
    ],
    comments: {
      blockComment: ["/*", "*/"],
      lineComment: "//"
    }
  },
  definition: {
    // @ts-ignore  no idea why TS doesn't like `keywords` in particular...
    keywords: [
      'autosize', 'axis', 'layer', 'mark', 'x', 'y', 'y2', 'field', 'encoding', 'color', 'type', 'title', 'opacity',
      'style', 'orient', 'size', 'width', 'height', '$schema', 'data', 'name', 'values'
    ],
    operators: [
      '_', ':', '=', '=>', '+', '-', '/', '*', '%', '?', '>', '<', '<=', '>=', '&', '&&', '|', '||', '==', '==='
    ],

    brackets: [
      { open: '(', close: ')', token: 'delimiter.parenthesis'},
      { open: '{', close: '}', token: 'delimiter.curly'},
      { open: '[', close: ']', token: 'delimiter.square'}
    ],

    symbols:  /[=><!~?:&|+\-*\/\^%]+/,

    // C# style strings
    escapes: /\\(?:[abfnrtv\\"']|x[0-9A-Fa-f]{1,4}|u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8})/,

    tokenizer: {
      root: [
        // identifiers and keywords
        [/[a-zA-Z_$][\w$]*/, {
          cases: {
            '@keywords': 'keyword',
            '@default': 'identifier'
          }
        }],

        // whitespace
        { include: '@whitespace' },

        // delimiters and operators
        [/[{}()\[\]]/, '@brackets'],
        [/@symbols/, { cases: { '@operators': 'operator',
            '@default'  : '' } } ],

        // number literals
        [/\d*\.\d+([eE][\-+]?\d+)?/, 'number.float'],
        [/0[xX][0-9a-fA-F]?/, 'number.hex'],
        [/\d+?/, 'number'],

        // delimiter: after number because of .\d floats
        [/[;,.]/, 'delimiter'],

        // string literal
        [/"([^"\\]|\\.)*$/, 'string.invalid' ],  // non-teminated string
        [/"/,  { token: 'string.quote', bracket: '@open', next: '@string' } ],

        // single-quoted string literal
        [/'([^'\\]|\\.)*$/, 'string.invalid' ],  // non-teminated string
        [/'/,  { token: 'string.quote', bracket: '@open', next: '@sqstring' } ],

      ],

      comment: [
        [/[^\/*]+/, 'comment' ],
        [/\/\*/,    'comment', '@push' ],    // nested comment
        [/\*\//,    'comment', '@pop'  ],
        [/[\/*]/,   'comment' ]
      ],

      string: [
        [/[^\\"]+/,  'string'],
        [/@escapes/, 'string.escape'],
        [/\\./,      'string.escape.invalid'],
        [/"/,        { token: 'string.quote', bracket: '@close', next: '@pop' } ]
      ],

      sqstring: [
        [/[^\\']+/,  'string'],
        [/@escapes/, 'string.escape'],
        [/\\./,      'string.escape.invalid'],
        [/'/,        { token: 'string.quote', bracket: '@close', next: '@pop' } ]
      ],

      whitespace: [
        [/[ \t\r\n]+/, 'white'],
        [/\/\*/,       'comment', '@comment' ],
        [/\/\/.*$/,    'comment'],
      ],
    }
  }
};
