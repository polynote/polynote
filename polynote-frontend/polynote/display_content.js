"use strict";

import * as monaco from "monaco-editor";
import {div, span} from "./tags";

export function displayContent(contentType, content) {
    const [mimeType, args] = parseContentType(contentType);

    if (mimeType === "text/plain") {
        if (args.lang) {
            return monaco.editor.colorize(content, args.lang, {}).then(html => {
                const node = span(['plaintext', 'colorized'], []).attr('data-lang', args.lang);
                node.innerHTML = html;
                return node
            });
        }
        return Promise.resolve(span(['plaintext'], [document.createTextNode(content)]));
    }

    if (mimeType === "text/html" || mimeType === "image/svg" || mimeType === "image/svg+xml") {
        const node = div(['htmltext'], []);
        node.innerHTML = content;
        return Promise.resolve(node);
    }

    if (mimeType.startsWith("image/")) {
        const img = document.createElement('img');
        img.setAttribute('src', `data:${mimeType};base64,${content}`);
        return Promise.resolve(img);
    }

    // what could it be? As a last resort we can just shove it in a data URL in an iframe and maybe the browser will deal with it?
    // we assume it's base64 encoded.
    const iframe = document.createElement('iframe');
    iframe.className = 'unknown-content';
    iframe.setAttribute("src", `data:${mimeType};base64,${content}`);
    return Promise.resolve(iframe);
}

export function contentTypeName(contentType) {
    const [mime, args] = parseContentType(contentType);

    switch (mime) {
        case "text/plain": return "Text";
        case "text/html": return "HTML";
        case "image/svg": return "SVG";
        default:
            if (mime.startsWith("image/")) return "Image";
            return mime;
    }
}

export function parseContentType(contentType) {
    const contentTypeParts = contentType.split(';').map(str => str.replace(/(^\s+|\s+$)/g, ""));
    const mimeType = contentTypeParts.shift();
    const args = {};
    contentTypeParts.forEach(part => {
        const [k, v] = part.split('=');
        args[k] = v;
    });

    return [mimeType, args];
}

export function displayData(data) {
    // TODO: can we somehow replicate what the chrome console does? Like give you browseable data structures?
    if (typeof data === "object" && !(data instanceof String)) {
        return document.createTextNode(JSON.stringify(data));
    } else {
        return document.createTextNode(data.toString());
    }
}