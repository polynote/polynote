'use strict';

type ContentElement = (Node | string)
type Content = ContentElement | ContentElement[]

function appendContent(el: Node, content: Content) {
    if (!(content instanceof Array)) {
        content = [content];
    }

    for (let item of content) {
        if (typeof item === "string") {
            el.appendChild(document.createTextNode(item));
        } else if (item !== undefined) {
            el.appendChild(item);
        }
    }
}

export interface TagElement extends Omit<HTMLElement, "click"> { // since we are overwriting `click` with our own impl, we need to omit it from HTMLElement.
    attr(a: string, b: string): TagElement
    attrs(obj: Record<string, string | boolean>): TagElement
    withId(id: string): TagElement
    click(handler: EventListenerOrEventListenerObject): TagElement
    change(handler: EventListenerOrEventListenerObject): TagElement
    listener(name: string, handler: EventListenerOrEventListenerObject): TagElement
    withKey(key: string, value: string): TagElement
    disable(): TagElement
    addClass(cls: string): TagElement

    // allow arbitrary properties (needed for `attr`/`attrs`)
    [k: string]: any
}

export function tag(name: string, classes: string[] = [], attributes: Record<string, string | boolean> = {}, content: Content = []) {
    const el: TagElement = Object.assign(document.createElement(name), {
        attr(a: string, v: string) {
            el.setAttribute(a, v);
            return el;
        },
        attrs(obj: Record<string, string | boolean>) {
            for (const attr in obj) {
                if (obj.hasOwnProperty(attr)) {
                    const val = obj[attr];
                    if (typeof val === "boolean") {
                        el[attr] = val;
                    } else {
                        el.setAttribute(attr, val);
                    }
                }
            }
            return el;
        },
        withId(id: string) {
            el.id = id;
            return el;
        },
        click(handler: EventListenerOrEventListenerObject) {
            return el.listener('click', handler);
        },
        change(handler: EventListenerOrEventListenerObject) {
            return el.listener('change', handler);
        },
        listener(name: string, handler: EventListenerOrEventListenerObject) {
            el.addEventListener(name, handler);
            return el
        },
        withKey(key: string, value: string) {
            el[key] = value;
            return el
        },
        disable () {
            el.disabled = true;
            return el
        },
        addClass(cls: string) {
            el.classList.add(cls);
            return el;
        }
    });

    el.classList.add(...classes);
    el.attrs(attributes);
    appendContent(el, content);

    return el;
}

export function blockquote(classes: string[], content: Content) {
    return tag('blockquote', classes, {}, content);
}

export function para(classes: string[], content: Content) {
    return tag('p', classes, {}, content);
}

export function span(classes: string[], content: Content) {
    return tag('span', classes, {}, content);
}

export function div(classes: string[], content: Content) {
    return tag('div', classes, {}, content);
}

export function button(classes: string[], attributes: Record<string, string>, content: Content) {
    if (!("type" in attributes)) {
        attributes["type"] = "button"
    }
    return tag('button', classes, attributes, content);
}

export function iconButton(classes: string[], title: string, icon: string, alt: string) {
    classes.push('icon-button');
    return button(classes, {title: title}, [
        span(['icon', 'fas'], [icon]),
        span(['alt'], [alt])
    ]);
}

export function textbox(classes: string[], placeholder: string, value: string) {
    const input = tag('input', classes, {type: 'text', placeholder: placeholder}, []);
    if (value) {
        input.value = value;
    }
    return input;
}

export function dropdown(classes: string[], options: Record<string, string>) {
    let opts: TagElement[] = [];

    for (const value in options) {
        if (options.hasOwnProperty(value)) {
            opts.push(tag('option', [], {value: value}, [options[value]]));
        }
    }

    const select = tag('select', classes, {}, opts);
    select.setSelectedValue = (value: string) => {
        const index = opts.findIndex(opt => opt.value === value);
        if (index !== -1) {
            select.selectedIndex = index;
        }
    };
    select.getSelectedValue = () => select.options[select.selectedIndex].value;
    return select;
}

// create element that goes into a FakeSelect (but not the FakeSelect itself)
export function fakeSelectElem(classes: string[], buttons: TagElement[]) {
    classes.push("dropdown");
    return div(classes, [
        div(['marker', 'fas'], [""]),
    ].concat(buttons))
}

export function checkbox(classes: string[], label: string, value: boolean = false) {
    const attrs = {type:'checkbox', checked: value};
    return tag('label', classes, {}, [
        tag('input', [], attrs, []),
        span([], [label])
    ]);
}

export function h2(classes: string[], content: Content) {
    return tag('h2', classes, {}, content)
}

export function h3(classes: string[], content: Content) {
    return tag('h3', classes, {}, content);
}

export function h4(classes: string[], content: Content) {
    return tag('h4', classes, {}, content);
}

/**
 * - header: An array of strings representing the table header labels
 * - classes: An array of strings representing class names for the th/td elements of each column
 * - rows: An array of arrays, where each inner array is a row of content elements of each column
 *         Can also be an array of objects, where each object has keys specifying a class name from
 *         the classes array.
 * - rowHeading: If true, first element of each row will be a <th scope="row"> rather than <td>.
 * - addToTop: If true, addRow will add rows to the top of the body, otherwise to the bottom.
 */
interface TableContentSpec {
    header?: string[],
    classes: string[],
    rows?: (TagElement[] | Record<string, string>)[],
    rowHeading?: boolean,
    addToTop?: boolean
}

/**
 * Creates a table element with an addRow function which appends a row.
 */
type TableRow = Content[] | Record<string, Content>
export function table(classes: string[], contentSpec: TableContentSpec) {
    const colClass = (col: number) => contentSpec.classes ? contentSpec.classes[col] : '';
    const heading = contentSpec.header ? [tag('thead', [], {}, [
            tag('tr', [], {}, contentSpec.header.map((c, i) => tag('th', [colClass(i)], {}, [c])))
        ])] : [];

    function mkTR(row: TableRow) {
        // we need to repeat `row instanceof Array` here so TS an properly infer the type of row in the expression.
        const contentArrays: Content[] =
            row instanceof Array ? row : contentSpec.classes.map(cls => row[cls]).map(content => content instanceof Array ? content : [content]);


        const propCells: (TagElement[] | Record<string, TagElement>) = row instanceof Array ? [] : {};
        const cells = contentArrays.map((c, i) => {
            const cell = i === 0 && contentSpec.rowHeading ? tag('th', [colClass(i)], {scope: 'row'}, c) : tag('td', [colClass(i)], {}, c);
            if (propCells instanceof Array) {
                propCells[i] = cell;
            } else {
                propCells[contentSpec.classes[i]] = cell;
            }
            return cell;
        });

        const tr = tag('tr', [], {}, cells);

        tr.row = row;
        tr.propCells = propCells;
        tr.row.__el = tr;

        tr.updateValues = (props: Record<string, Content>) => {
            for (const prop in props) {
                if (props.hasOwnProperty(prop)) {
                    const value = props[prop];
                    tr.row[prop] = value;
                    const cell = tr.propCells[prop];
                    cell.innerHTML = "";
                    appendContent(cell, value);
                }
            }
            const nextSibling = tr.nextSibling;
            const parentNode = tr.parentNode;
            if (parentNode) {
                parentNode.removeChild(tr);
                parentNode.insertBefore(tr, nextSibling);   // re-trigger the highlight animation
            }
        };

        return tr;
    }

    const body = tag(
        'tbody', [], {},
        contentSpec.rows ? contentSpec.rows.map(mkTR) : []
    );

    const table = tag('table', classes, {}, [
        ...heading, body
    ]);

    table.addRow = (row: TableRow, whichBody: HTMLTableSectionElement) => {
        const tbody = whichBody === undefined ? body : whichBody;
        const rowEl = mkTR(row);
        if (contentSpec.addToTop)
            tbody.insertBefore(rowEl, tbody.firstChild);
        else
            tbody.appendChild(rowEl);
        return rowEl;
    };

    table.findRows = (props: Record<string, string>, tbody?: TagElement) => table.findRowsBy((row: TableRow) => {
        for (const prop in props) {
            if (props.hasOwnProperty(prop)) {
                if ((row as any/* ¯\_(ツ)_/¯ */)[prop] !== props[prop])
                    return false;
            }
        }
        return true;
    }, tbody);

    table.findRowsBy = (fn: (row: TableRow) => boolean, tbody?: TagElement) => {
        const [searchEl, selector] = tbody ? [tbody, 'tr'] : [table, 'tbody tr'];
        const matches: TagElement[] = [];
        [...searchEl.querySelectorAll(selector)].forEach((tr: TagElement) => {
            if (fn(tr.row)) {
                matches.push(tr);
            }
        });
        return matches;
    };

    table.addBody = (rows?: TableRow[]) => {
        const newBody = tag(
            'tbody', [], {},
            contentSpec.rows ? contentSpec.rows.map(mkTR) : []
        );

        table.appendChild(newBody);

        if (rows) {
            rows.forEach(row => table.addRow(row, newBody));
        }
        return newBody;
    };

    return table;
}

export function details(classes: string[], summary: Content, content: Content) {
    const el = tag('details', classes, {}, [
        tag('summary', [], {}, summary)
    ]);
    appendContent(el, content);
    return el;
}