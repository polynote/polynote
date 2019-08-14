'use strict';

// build markdown from HTML
// TODO: this is not very robust (should use computed style of element rather than tag semantics?)
// TODO: this needs to handle tables and such.

// There are pre-existing implementations of this, but I don't want to bring in i.e. turndown - it has 97 (!!) dependencies.
// Another option could be to do this on the server, but I don't really want the server to know/care about HTML.
// This is working for now, but later maybe bite the bullet and bring in turndown instead
export const htmlToMarkdown = (function () {

    const listMarkers = Object.freeze({
        'ul': () => '* ',
        'ol': (index, indent) => (index + 1) + '. '
    });

    const elTypes = Object.freeze({
        'p': 'block',
        'h1': 'block',
        'h2': 'block',
        'h3': 'block',
        'h4': 'block',
        'h5': 'block',
        'h6': 'block',
        'blockquote': 'block',
        'ol': 'list',
        'ul': 'list',
    });

    return function (outerEl) {
        var currentListIndent = '';


        function convertInline(node) {
            let accum = "";

            function accumChildren() {
                for (var child of node.childNodes) {
                    accum += convertInline(child);
                }
            }

            if (node.nodeType === 1) {
                switch (node.nodeName.toLowerCase()) {
                    case 'b':
                    case 'strong':
                        accum += '**';
                        accumChildren();
                        accum += '**';
                        break;

                    case 'i':
                    case 'em':
                        accum += '*';
                        accumChildren();
                        accum += '*';
                        break;

                    case 'strike':
                    case 'del':
                        accum += '~~';
                        accumChildren();
                        accum += '~~';
                        break;

                    case 'a':
                        accum += `[`;
                        accumChildren();
                        accum += '](' + node.href + ')';
                        break;

                    case 'img':
                        accum += '![' + node.getAttribute('alt') + '](' + node.src + ')';
                        break;

                    case 'code':
                        accum += '`' + node.textContent + '`';
                        break;

                    case 'span':
                        if (node.hasAttribute('data-tex-source') && (node.classList.contains('katex-display') || node.classList.contains('katex-block'))) {
                            accum += '$$' + node.getAttribute('data-tex-source') + '$$\n';
                        }
                        else if (node.hasAttribute('data-tex-source')) {
                            // inline TeX source
                            accum += '$' + node.getAttribute('data-tex-source') + '$';
                        } else {
                            // in case some styles were set with CSS
                            const style = window.getComputedStyle(node);
                            const stack = [];
                            if (parseInt(style.fontWeight) >= 700) {
                                stack.push('**');
                            }
                            if (style.fontStyle === 'italic') {
                                stack.push('*');
                            }
                            stack.forEach(mod => accum += mod);
                            accumChildren();
                            stack.reverse().forEach(mod => accum += mod);
                        }
                        break;
                    default:
                        accum += node.outerHTML;
                }
            } else if (node.nodeType === 3) {
                accum = node.nodeValue;
            }

            return accum;
        }

        function convertList(items, marker) {
            const indent = currentListIndent;
            const indentRest = '  ' + indent;
            currentListIndent = '   ' + indent;

            function listItem(str, index) {
                const markerStr = marker(index, indent);
                const markerPadding = ''.padEnd(markerStr.length, ' ');
                const indentLine = '\n' + markerPadding;
                return [
                    indent, markerStr,
                    str.split(/\r?\n/g).join(indentLine)
                ].join('')
            }

            const accum = [...items].map((el, index) => {
                const nodeName = el.nodeName && el.nodeName.toLowerCase();
                if (nodeName && nodeName === 'li') {
                    return listItem(convertChildren(el), index);
                } else return convert(el);
            });

            currentListIndent = indent;

            return accum.join('\n');
        }

        function setex(str, marker) {
            return [str, ''.padEnd(str.length, marker)].join('\n');
        }

        function atx(str, level) {
            return [''.padEnd(level, '#'), ' ', str].join('');
        }

        function convertBlock(node) {
            let inside = '';
            if (node.hasAttribute('data-tex-source') && (node.classList.contains('katex-display') || node.classList.contains('katex-block'))) {
               inside = '$$' + node.getAttribute('data-tex-source') + '$$';
            } else if (node.hasAttribute('data-tex-source')) {
                // inline TeX source
                inside = '$' + node.getAttribute('data-tex-source') + '$';
            } else {
                inside = [...node.childNodes].map(convert).join('');
            }

            switch(node.nodeName.toLowerCase()) {
                case 'h1': return atx(inside, 1);
                case 'h2': return atx(inside, 2);
                case 'h3': return atx(inside, 3);
                case 'h4': return atx(inside, 4);
                case 'h5': return atx(inside, 5);
                case 'h6': return atx(inside, 6);
                case 'p': return `\n\n${inside}`;
                case 'blockquote': return '> ' + inside.split(/\r?\n/g).join('\n> ');
                case 'pre': return '    ' + inside.split(/\r?\n/g).join('\n    ');

                default:
                    return node.outerHTML;
            }
        }

        function convertChildren(node) {
            return [...node.childNodes].map(convert).join('');
        }

        function stripBreaks(str) {
            return str.replace(/^[\r\n]+/, '').replace(/[\r\n]+$/,'');
        }

        function convert(node) {
            const nodeName = node.nodeName && node.nodeName.toLowerCase();
            if (nodeName && elTypes[nodeName] === 'block') {
                return stripBreaks(convertBlock(node)) + '\n\n';
            } else if (nodeName && listMarkers[nodeName]) {
                return stripBreaks(convertList(node.children, listMarkers[nodeName])) + '\n\n';
            } else {
                return convertInline(node);
            }
        }

        return [...outerEl.childNodes].map(convert).join('');
    }
})();