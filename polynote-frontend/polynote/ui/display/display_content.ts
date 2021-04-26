"use strict";

import * as monaco from "monaco-editor";
import * as katex from "katex";
import {Content, details, div, h4, span, tag, TagElement} from "../tags";
import {ArrayType, MapType, OptionalType, StructField, StructType} from "../../data/data_type";
import embed from "vega-embed";
import {DataRepr} from "../../data/value_repr";
import {DataReader} from "../../data/codec";

export function displayContent(contentType: string, content: string | DocumentFragment, contentTypeArgs?: Record<string, string>): Promise<TagElement<any>> {
    const [mimeType, args] = contentTypeArgs ? [contentType, contentTypeArgs] : parseContentType(contentType);

    let result;
    if (mimeType === "text/html" || mimeType === "image/svg" || mimeType === "image/svg+xml" || content instanceof DocumentFragment) {
        const node = div(['htmltext'], []);
        if (content instanceof DocumentFragment) {
            node.appendChild(content);
        } else {
            const frame = buildContainerFrame(content)
            node.appendChild(frame)
        }
        result = Promise.resolve(node);
    } else if (mimeType === "text/plain") {
        if (args.lang) {
            result = monaco.editor.colorize(content, args.lang, {}).then(html => {
                const node = (span(['plaintext', 'colorized'], []) as TagElement<"span", HTMLSpanElement & {"data-lang": string}>).attr('data-lang', args.lang);
                node.innerHTML = html;
                return node
            });
        } else {
            result = Promise.resolve(span(['plaintext'], [document.createTextNode(content)]));
        }

    } else if (mimeType === "application/x-latex" || mimeType === "application/latex") {
        const node = div([], []);
        katex.render(content, node, { displayMode: true, throwOnError: false });
        result = Promise.resolve(node);
    } else if (mimeType.startsWith("application/vnd.vegalite")) {
        const targetEl = div([], []);
        targetEl.style.display = 'block';
        const wrapperEl = div(['vega-result'], [targetEl]);
        const spec = JSON.parse(content);

        if (spec?.width === 'container' || spec?.height === 'container') {
            // must wait until the element is in the DOM before embedding, in case it's responsive sized.
            const onVisible = () => {
                embed(targetEl, spec);
                wrapperEl.removeEventListener('becameVisible', onVisible);
            }
            wrapperEl.addEventListener('becameVisible', onVisible);

            // must set the target element to have display: block, or it will be inline block and have no width
            targetEl.style.display = 'block';
            result = Promise.resolve(wrapperEl);
        } else {
            result = embed(targetEl, spec).then(_ => wrapperEl);
        }

    } else if (mimeType.startsWith("image/")) {
        const img = document.createElement('img');
        img.setAttribute('src', `data:${mimeType};base64,${content}`);
        result = Promise.resolve(img);
    } else {
        // what could it be? As a last resort we can just shove it in a data URL in an iframe and maybe the browser will deal with it?
        // we assume it's base64 encoded.
        const iframe = document.createElement('iframe');
        iframe.className = 'unknown-content';
        iframe.setAttribute("src", `data:${mimeType};base64,${content}`);
        result = Promise.resolve(iframe);
    }

    return result;
}

export function contentTypeName(contentType: string) {
    const [mime, args] = parseContentType(contentType);

    switch (mime) {
        case "text/plain": return "Text";
        case "text/html": return "HTML";
        case "image/svg": return "SVG";
        case "image/svg+xml": return "SVG";
        default:
            if (mime.startsWith("image/")) return "Image";
            return mime;
    }
}

export function parseContentType(contentType: string): [string, Record<string, string>] {
    const contentTypeParts = contentType.split(';').map(str => str.replace(/(^\s+|\s+$)/g, ""));
    const mimeType = contentTypeParts.shift()!;
    const args: Record<string, string> = {};
    contentTypeParts.forEach(part => {
        const [k, v] = part.split('=');
        args[k] = v;
    });

    return [mimeType, args];
}

export function truncate(string: any, len?: number) {
    len = len ?? 32;
    if (typeof string !== "string" && !(string instanceof String)) {
        string = string.toString();
    }
    if (string.length > len) {
        return string.substr(0, len - 1) + '…';
    }
    return string;
}

export type MIMEElement = TagElement<"div", HTMLDivElement & { rel?: string, "mime-type"?: string}>;
export function mimeEl(mimeType: string, args: Record<string, string>, content: Content): MIMEElement {
    const rel = args.rel || 'none';
    return (div(['output'], content) as MIMEElement).attr('rel', rel).attr('mime-type', mimeType);
}

export function displayData(data: any, fieldName?: string, expandObjects: boolean | number = false): TagElement<any> {
    const expandNext: boolean | number = typeof(expandObjects) === "number" ? (expandObjects > 0 ? expandObjects - 1 : false) : expandObjects;

    function shortDisplay(data: any) {
        if (data instanceof Array) {
            return span(['short-array'], ['Array(', data.length.toString(), ')']);
        } else if (typeof data === "number") {
            return span(['number'], [truncate(data.toString())]);
        } else if (typeof data === "boolean") {
            return span(['boolean'], [data.toString()]);
        } else if (typeof data === "object" && !(data instanceof String)) {
            return span(['short-object'], ['{…}']);
        } else {
            return span(['string'], [data.toString()]);
        }
    }

    if (data instanceof Array) {
        const summary = tag('summary', ['array-summary'], {}, ['Array(', data.length.toString(), ')']);
        if (fieldName) {
            summary.insertBefore(span(['field-name'], [fieldName]), summary.childNodes[0]);
        }
        const elems = tag('ul', ['array-elements'], {}, []);
        const details = tag('details', ['array-display'], {}, [summary, elems]);
        details.ontoggle = () => details.parentNode?.dispatchEvent(new CustomEvent('toggle', {bubbles: true}));  // the 'toggle' event doesn't bubble? Why?
        let count = 0;
        for (let elem of data) {
            if (count >= 99) {
                elems.appendChild(tag('li', ['more-elements'], {}, ['…', (data.length - count).toString(), ' more elements']));
                break;
            }
            count++;
            elems.appendChild(tag('li', [], {}, displayData(elem, undefined, expandNext)));
        }
        return details;
    } else if (data instanceof Map) {
        const summarySpan = span(['summary-content'], []);
        const summary = tag('summary', ['object-summary'], {}, [summarySpan]);
        if (fieldName) {
            summary.insertBefore(span(['field-name'], [fieldName]), summary.childNodes[0]);
        }
        summarySpan.appendChild(span([], ['…']));

        const fields = tag('ul', ['object-fields'], {}, []);
        const details = tag('details', ['object-display'], expandObjects ? { open: 'open' } : {}, [summary, fields]);
        details.ontoggle = () => details.parentNode?.dispatchEvent(new CustomEvent('toggle', {bubbles: true}));  // the 'toggle' event doesn't bubble? Why?
        for (let [key, val] of data) {
            fields.appendChild(tag('li', [], {}, [displayData(val, key, expandNext)]));
        }
        return details;
    } else if (data && typeof data === "object" && !(data instanceof String)) {
        const keys = Object.keys(data);
        const summarySpan = span(['summary-content'], []);
        const summary = tag('summary', ['object-summary'], {}, [summarySpan]);
        if (fieldName) {
            summary.insertBefore(span(['field-name'], [fieldName]), summary.childNodes[0]);
        }
        for (let key of keys) {
            if (summarySpan.textContent && summarySpan.textContent.length > 64) {
                summarySpan.addClass('truncated');
                break;
            }
            summarySpan.appendChild(shortDisplay(data[key]));
        }

        const fields = tag('ul', ['object-fields'], {}, []);
        const details = tag('details', ['object-display'], expandObjects ? { open: 'open' } : {}, [summary, fields]);
        details.ontoggle = () => details.parentNode?.dispatchEvent(new CustomEvent('toggle', {bubbles: true}));  // the 'toggle' event doesn't bubble? Why?
        for (let key of keys) {
            fields.appendChild(tag('li', [], {}, [displayData(data[key], key, expandNext)]));
        }
        return details;
    } else {
        let result;
        if (data !== null && data !== undefined) {
            switch (typeof data) {
                case "number": result = span(['number', 'mtk6'], [truncate(data.toString())]); break;
                case "boolean": result = span(['boolean', 'mtk6'], [data.toString()]); break;
                default: result = span(['string'], [data.toString()]);
            }
        } else if (data == null) {
            result = span(['null'], ['null'])
        } else {
            result = span(['undefined'], ['undefined'])
        }
        if (fieldName) {
            return span(['object-field'], [span(['field-name'], [fieldName]), result]);
        }
        return result;
    }
}

function colorizeResultType(typeName: string, language: string = "scala"): Promise<TagElement<"span">> {
    return monaco.editor.colorize(typeName, language, {}).then(typeHTML => {
        const resultType = span(['result-type'], []).attr("data-lang" as any, language);
        resultType.innerHTML = typeHTML;

        // why do they put <br> elements in there?
        [...resultType.getElementsByTagName('br')].forEach(br => br.parentNode?.removeChild(br));
        return resultType
    })
}

export function prettyDisplayString(resultName: string, typeName: string, valueText: string): Promise<[string, TagElement<"div">]> {
    return colorizeResultType(typeName, "scala").then(resultType => {
        const el = div(['string-content'], [
            span(['result-name-and-type'], [span(['result-name'], [resultName]), ': ', resultType, ' = ']),
            valueText
        ]);
        return ["text/html", el];
    })
}

export function prettyDisplayData(resultName: string, typeName: string, dataRepr: DataRepr): Promise<[string, TagElement<"div">]> {
    return colorizeResultType(typeName, "scala").then(resultType => {
        const el = div([], [
            span(['result-name-and-type'], [span(['result-name'], [resultName]), ': ', resultType, ' = ']),
            displayData(dataRepr.dataType.decodeBuffer(new DataReader(dataRepr.data)), undefined, 1)
        ]);
        return ["text/html", el];
    })
}

export function prettyDuration(milliseconds: number) {
    function quotRem(dividend: number, divisor: number) {
        const quotient = Math.floor(dividend / divisor);
        const remainder = dividend % divisor;
        return [quotient, remainder];
    }

    const [durationDays, leftOverHrs] = quotRem(milliseconds, 1000 * 60 * 60 * 24);
    const [durationHrs, leftOverMin] = quotRem(leftOverHrs, 1000 * 60 * 60);
    const [durationMin, leftOverSec] = quotRem(leftOverMin, 1000 * 60);
    const [durationSec, durationMs] = quotRem(leftOverSec, 1000);

    const duration = [];
    if (durationDays) {
        duration.push(`${durationDays}d`);
        duration.push(`${durationHrs}h`);
        duration.push(`${durationMin}m`);
        duration.push(`${durationSec}s`);
    } else if (durationHrs) {
        duration.push(`${durationHrs}h`);
        duration.push(`${durationMin}m`);
        duration.push(`${durationSec}s`);
    } else if (durationMin) {
        duration.push(`${durationMin}m`);
        duration.push(`${durationSec}s`);
    } else if (durationSec) {
        duration.push(`${durationSec}s`);
    } else if (durationMs) {
        duration.push(`${durationMs}ms`);
    }

    return duration.join(":")
}

export function displaySchema(structType: StructType): HTMLElement {

    function displayField(field: StructField): HTMLElement {
        if (field.dataType instanceof ArrayType) {
            const nameAndType = [
                span(['field-name'], [field.name]), ': ',
                span(['field-type'], [span(['bracket'], ['[']), field.dataType.element.typeName(), span(['bracket'], [']'])])];

            let description: Content = nameAndType;
            if (field.dataType.element instanceof StructType) {
                description = [details([], nameAndType, [displayStruct(field.dataType.element)])]
            } else if (field.dataType.element instanceof MapType) {
                description = [details([], nameAndType, [displayMap(field.dataType.element)])]
            }

            return tag("li", ['object-field', 'array-field'], {}, description);
        }

        if (field.dataType instanceof StructType) {
            return tag("li", ['object-field', 'struct-field'], {}, [
                details([], [span(['field-name'], [field.name]), ': ', span(['field-type'], ['struct'])], [
                    displayStruct(field.dataType)
                ])
            ]);
        }

        if (field.dataType instanceof MapType) {
            return tag("li", ['object-field', 'struct-field'], {}, [
                details([], [span(['field-name'], [field.name]), ': ', span(['field-type'], ['map'])], [
                    displayMap(field.dataType)
                ])
            ]);
        }

        if (field.dataType instanceof OptionalType) {
            return displayField(new StructField(field.name + "?", field.dataType.element));
        }

        const typeName = field.dataType.typeName();
        const attrs = {"data-fieldType": typeName} as any;
        return tag("li", ['object-field'], attrs, [
            span(['field-name'], [field.name]), ': ', span(['field-type'], [typeName])
        ]);
    }

    function displayStruct(typ: StructType): HTMLElement {
        return tag("ul", ["object-fields"], {}, typ.fields.map(displayField));
    }

    function displayMap(typ: MapType): HTMLElement {
        return tag("ul", ["object-fields", "map-type"], {}, [displayField(new StructField("key", typ.keyType)), displayField(new StructField("value", typ.valueType))]);
    }

    return div(['schema-display'], [
        details(
            ['object-display'],
            [span(['object-summary', 'schema-summary'], [span(['summary-content', 'object-field-summary'], [truncate(structType.fields.map(f => f.name).join(", "), 64)])])],
            [tag("ul", ['object-fields'], {}, structType.fields.map(displayField))]).attr('open', 'open')
    ]);
}

/**
 * Build an iframe to hold HTML output. Not secured.
 *
 * @param content HTML string to place into the iframe.
 */
function buildContainerFrame(content: string): HTMLIFrameElement {
    const doc = document.implementation.createHTMLDocument("Polynote output container");
    const head = doc.documentElement.appendChild(doc.createElement('head'));
    [...document.head.getElementsByTagName('link')].forEach((stylesheet: HTMLLinkElement) => {
        const link = doc.importNode(stylesheet, true);
        link.href = new URL(link.href, document.location.href).href;
        head.appendChild(link);
    });
    const style = head.appendChild(doc.createElement('style'));
    style.setAttribute('type', 'text/css');
    style.innerText = `
        html, body { margin: 0; padding: 0 }
    `;
    // apparently Monaco adds a bazillion style elements to the page...
    [...document.head.getElementsByTagName("style")].forEach(style => {
        const s = doc.importNode(style, true)
        head.appendChild(s)
    })
    const body = doc.documentElement.appendChild(doc.createElement('body'));
    const container = body.appendChild(doc.createElement('div'));
    container.classList.add('htmltext');
    container.id = 'polynote-sandbox-container';
    container.innerHTML = content;

    const iframe = document.createElement('iframe');
    iframe.style.border = '0';
    iframe.style.width = '100%';
    iframe.onload = evt => {
        const sandboxContainer = iframe.contentDocument?.getElementById('polynote-sandbox-container');
        if (sandboxContainer) {
            iframe.style.height = sandboxContainer.scrollHeight + "px";
            iframe.style.width = sandboxContainer.scrollWidth + "px";
        } else {
            iframe.style.height = "300px";
            iframe.style.width = "100%";
        }
    }
    iframe.srcdoc = doc.documentElement.outerHTML;

    return iframe;
}