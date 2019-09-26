import * as katex from "katex";

export const md = require('markdown-it');

export const mdk = (function() {
    // TODO: quick & dirty, adapt markdown-it-katex to work with this AMD thing that monaco makes me use.
    /*
      The following code is from markdown-it-katex (https://github.com/iktakahiro/markdown-it-katex/ which is a
      fork of https://github.com/waylonflinn/markdown-it-katex) under the MIT license. It has been modified to work
      without require.js/node.js, because these things just don't seem to work properly with monaco's loader. I
      also modified it to put contenteditable=false on the output element (if people try to dig in and edit that it
      will break terribly)

      Note that katex itself also wasn't working properly, and I just gave up and did it through a <script> tag in
      the document head. So whatever. The license of markdown-it-katex follows:

          The MIT License (MIT)

          Copyright (c) 2016 Waylon Flinn

          Permission is hereby granted, free of charge, to any person obtaining a copy
          of this software and associated documentation files (the "Software"), to deal
          in the Software without restriction, including without limitation the rights
          to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
          copies of the Software, and to permit persons to whom the Software is
          furnished to do so, subject to the following conditions:

          The above copyright notice and this permission notice shall be included in all
          copies or substantial portions of the Software.

          THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
          IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
          FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
          AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
          LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
          OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
          SOFTWARE.
    */

    function isValidDelim(state: any, pos: any) {
        var prevChar, nextChar,
            max = state.posMax,
            can_open = true,
            can_close = true;

        prevChar = pos > 0 ? state.src.charCodeAt(pos - 1) : -1;
        nextChar = pos + 1 <= max ? state.src.charCodeAt(pos + 1) : -1;

        // Check non-whitespace conditions for opening and closing, and
        // check that closing delimeter isn't followed by a number
        if (prevChar === 0x20/* " " */ || prevChar === 0x09/* \t */ ||
            (nextChar >= 0x30/* "0" */ && nextChar <= 0x39/* "9" */)) {
            can_close = false;
        }
        if (nextChar === 0x20/* " " */ || nextChar === 0x09/* \t */) {
            can_open = false;
        }

        return {
            can_open: can_open,
            can_close: can_close
        };
    }

    function math_inline(state: any, silent: any) {
        var start, match, token, res, pos, esc_count;

        if (state.src[state.pos] !== "$") { return false; }

        res = isValidDelim(state, state.pos);
        if (!res.can_open) {
            if (!silent) { state.pending += "$"; }
            state.pos += 1;
            return true;
        }

        // First check for and bypass all properly escaped delimieters
        // This loop will assume that the first leading backtick can not
        // be the first character in state.src, which is known since
        // we have found an opening delimieter already.
        start = state.pos + 1;
        match = start;
        while ( (match = state.src.indexOf("$", match)) !== -1) {
            // Found potential $, look for escapes, pos will point to
            // first non escape when complete
            pos = match - 1;
            while (state.src[pos] === "\\") { pos -= 1; }

            // Even number of escapes, potential closing delimiter found
            if ( ((match - pos) % 2) == 1 ) { break; }
            match += 1;
        }

        // No closing delimter found.  Consume $ and continue.
        if (match === -1) {
            if (!silent) { state.pending += "$"; }
            state.pos = start;
            return true;
        }

        // Check if we have empty content, ie: $$.  Do not parse.
        if (match - start === 0) {
            if (!silent) { state.pending += "$$"; }
            state.pos = start + 1;
            return true;
        }

        // Check for valid closing delimiter
        res = isValidDelim(state, match);
        if (!res.can_close) {
            if (!silent) { state.pending += "$"; }
            state.pos = start;
            return true;
        }

        if (!silent) {
            token         = state.push('math_inline', 'math', 0);
            token.markup  = "$";
            token.content = state.src.slice(start, match);
        }

        state.pos = match + 1;
        return true;
    }

    function math_block(state: any, start: any, end: any, silent: any){
        var firstLine, lastLine, next, lastPos, found = false, token,
            pos = state.bMarks[start] + state.tShift[start],
            max = state.eMarks[start];

        if(pos + 2 > max){ return false; }
        if(state.src.slice(pos,pos+2)!=='$$'){ return false; }

        pos += 2;
        firstLine = state.src.slice(pos,max);

        if(silent){ return true; }
        if(firstLine.trim().slice(-2)==='$$'){
            // Single line expression
            firstLine = firstLine.trim().slice(0, -2);
            found = true;
        }

        for(next = start; !found; ){

            next++;

            if(next >= end){ break; }

            pos = state.bMarks[next]+state.tShift[next];
            max = state.eMarks[next];

            if(pos < max && state.tShift[next] < state.blkIndent){
                // non-empty line with negative indent should stop the list:
                break;
            }

            if(state.src.slice(pos,max).trim().slice(-2)==='$$'){
                lastPos = state.src.slice(0,max).lastIndexOf('$$');
                lastLine = state.src.slice(pos,lastPos);
                found = true;
            }

        }

        state.line = next + 1;

        token = state.push('math_block', 'math', 0);
        token.block = true;
        token.content = (firstLine && firstLine.trim() ? firstLine + '\n' : '')
            + state.getLines(start + 1, next, state.tShift[start], true)
            + (lastLine && lastLine.trim() ? lastLine : '');
        token.map = [ start, state.line ];
        token.markup = '$$';
        return true;
    }

    function escapeHtml(unsafe: any) {
        return unsafe
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }

    return function math_plugin(md: any, options: any) {
        // Default options

        options = options || {};

        // set KaTeX as the renderer for markdown-it-simplemath
        var katexInline = function(latex: any){
            options.displayMode = false;
            try{
                // modified - go through a fake element so the result can have DOM modifications (emit contenteditable=false and data-tex-source)
                // this is probably a bit slower, but hopefully there aren't thousands of equations on the page!
                const fakeEl = document.createElement('div');
                katex.render(latex, fakeEl, options);
                fakeEl.children[0].setAttribute('contenteditable', 'false');
                fakeEl.children[0].setAttribute('data-tex-source', latex);
                return fakeEl.innerHTML;
            }
            catch(error){
                if(options.throwOnError){ console.log(error); }
                return `<span class='katex-error' contenteditable="false" title='${escapeHtml(error.toString())}' data-tex-source="${escapeHtml(latex)}">${escapeHtml(latex)}</span>`;
            }
        };

        var inlineRenderer = function(tokens: any, idx: any){
            return katexInline(tokens[idx].content);
        };

        var katexBlock = function(latex: any){
            options.displayMode = true;
            try{
                // modified - add contenteditable=false
                return `<p class='katex-block' contenteditable='false' data-tex-source="${escapeHtml(latex)}">` + katex.renderToString(latex, options) + "</p>";
            }
            catch(error){
                if(options.throwOnError){ console.log(error); }
                return `<p class='katex-block katex-error' contenteditable="false" title='${escapeHtml(error.toString())}' data-tex-source="${escapeHtml(latex)}">${escapeHtml(latex)}</p>`;
            }
        };

        var blockRenderer = function(tokens: any, idx: any){
            return  katexBlock(tokens[idx].content) + '\n';
        };

        md.inline.ruler.after('escape', 'math_inline', math_inline);
        md.block.ruler.after('blockquote', 'math_block', math_block, {
            alt: [ 'paragraph', 'reference', 'blockquote', 'list' ]
        });
        md.renderer.rules.math_inline = inlineRenderer;
        md.renderer.rules.math_block = blockRenderer;
    };
})();


declare global {
    interface Window {
        MarkdownIt: any;
    }
}

export const MarkdownIt = md({
    html: true,
    linkify: false,
});
MarkdownIt.use(mdk);
