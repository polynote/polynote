'use strict';

import {loadIcon} from "./icons";
import {IDisposable, StateHandler, UpdatableState} from "../state";

type ContentElement = (Node | {el: Node} | string | undefined)
export type Content = ContentElement | ContentElement[];
export type AsyncContent = Content | Promise<ContentElement>

function appendContent(el: Node, content: AsyncContent) {
    if (content === undefined)
        return;

    if (content instanceof Promise) {
        content.then(c => appendContent(el, c));
        return;
    }

    if (!(content instanceof Array)) {
        content = [content];
    }

    for (const item of content) {
        if (item !== undefined) {
            if (typeof item === "string") {
                el.appendChild(document.createTextNode(item));
            } else if (item instanceof Node) {
                el.appendChild(item);
            } else if (item && item.el) {
                el.appendChild(item.el);
            }
        }
    }
}

export type AllowedElAttrs<T extends HTMLElement> = Partial<Record<keyof T, string | boolean>>

export type TagElement<K extends keyof HTMLElementTagNameMap, T extends HTMLElementTagNameMap[K] = HTMLElementTagNameMap[K]> = HTMLElementTagNameMap[K] & {
    attr(a: keyof T, b: string | boolean): TagElement<K, T>
    dataAttr(name: string, value: string): TagElement<K, T>
    attrs(obj: AllowedElAttrs<HTMLElementTagNameMap[K]>): TagElement<K, T>
    withId(id: string): TagElement<K, T>
    click(handler: EventListenerOrEventListenerObject): TagElement<K, T>
    mousedown(handler: EventListenerOrEventListenerObject): TagElement<K, T>
    change(handler: EventListenerOrEventListenerObject): TagElement<K, T>
    onValueChange<V = string>(fn: (newValue: V) => void): TagElement<K, T>
    listener(name: string, handler: EventListenerOrEventListenerObject): TagElement<K, T>
    withKey(key: string, value: any): TagElement<K, T>
    disable(): TagElement<K, T>
    addClass(cls: string): TagElement<K, T>
};

export function tag<T extends keyof HTMLElementTagNameMap>(
    name: T,
    classes: string[] = [],
    attributes?: AllowedElAttrs<HTMLElementTagNameMap[T]>,
    content: AsyncContent = [],
    eventDelegate?: TagElement<any>): TagElement<T> {

    let eventEl: TagElement<any>;
    if (eventDelegate) {
        eventEl = eventDelegate;
    }

    const el: TagElement<T> = Object.assign(document.createElement(name), {
        attr(a: keyof HTMLElementTagNameMap[T], v: string | boolean) {
            if (typeof v === "boolean") {
                return el.withKey(a.toString(), v);
            } else {
                el.setAttribute(a.toString(), v);
                return el;
            }
        },
        dataAttr(name: string, value: string) {
          el.setAttribute(name, value);
          return el;
        },
        attrs(obj: AllowedElAttrs<HTMLElementTagNameMap[T]>) {
            for (const a in obj) {
                if (obj.hasOwnProperty(a)) {
                    el.attr(a, obj[a]!);
                }
            }
            return el;
        },
        withId(id: string) {
            el.id = id;
            return el;
        },
        click(handler: EventListenerOrEventListenerObject) {
            eventEl.listener('click', handler);
            return el;
        },
        mousedown(handler: EventListenerOrEventListenerObject) {
            eventEl.listener('mousedown', handler);
            return el;
        },
        change(handler: EventListenerOrEventListenerObject) {
            eventEl.listener('change', handler);
            return el;
        },
        onValueChange<V = string>(fn: (newValue: V) => void) {
            if (eventEl instanceof HTMLInputElement && eventEl.type === 'checkbox') {
                (eventEl as any).change((evt: Event) => fn(eventEl.checked))
            } else if (eventEl instanceof HTMLInputElement || eventEl instanceof HTMLTextAreaElement) {
                (eventEl as any).change((evt: Event) => fn(eventEl.value))
            } else throw new Error("Element is not an input");
            return el;
        },
        listener(name: string, handler: EventListenerOrEventListenerObject) {
            eventEl.addEventListener(name, handler);
            return el;
        },
        withKey(key: string, value: any) {
            return Object.assign(el, {[key]: value})
        },
        disable () {
            return el.withKey('disabled', true)
        },
        addClass(cls: string) {
            el.classList.add(cls);
            return el;
        }
    });
    if (!eventEl) {
        eventEl = el;
    }
    el.classList.add(...classes);
    if (attributes) el.attrs(attributes);
    appendContent(el, content);

    return el;
}

export interface BindableTag<ValueType, K extends keyof HTMLElementTagNameMap, T extends HTMLElementTagNameMap[K] = HTMLElementTagNameMap[K]> {

    /**
     * Bind the value of this form field to a state handler. When the state handler is updated, the form field will be
     * updated with the new value. If value of the form field is changed by the user agent, the state handler will be
     * updated with the new value.
     */
    bind(state: UpdatableState<ValueType>): T & BindableTagElement<ValueType, K, T>

    /**
     * Like `bind`, but handles optional state values. If the state value is `undefined`, it does not propagate to the
     * form field; the state change is instead ignored.
     */
    bindPartial(state: UpdatableState<ValueType | undefined>): T & BindableTagElement<ValueType, K, T>

    /**
     * Like `bind`, but handles optional state values. If the value is `undefined`, the form field is updated with a
     * default value instead. If the form field is updated by the user agent to the default value, a default state value
     * can be specified.
     */
    bindWithDefault(state: UpdatableState<ValueType | undefined>, defaultValue: ValueType, defaultState?: ValueType): T & BindableTagElement<ValueType, K, T>
}

export type BindableTagElement<ValueType, K extends keyof HTMLElementTagNameMap, T extends HTMLElementTagNameMap[K] = HTMLElementTagNameMap[K]> =
    TagElement<K, T> & BindableTag<ValueType, K, T>

function mkBindable<ValueType, E extends TagElement<K, T>, K extends keyof HTMLElementTagNameMap, T extends HTMLElementTagNameMap[K] = HTMLElementTagNameMap[K]>(
        self: E,
        getValue: (el: E) => ValueType,
        update: (el: E, value: ValueType | null | undefined) => void,
        eventType: string = 'change'
    ): E & BindableTagElement<ValueType, K, T> {
    const result: E & BindableTagElement<ValueType, K, T> = Object.assign(self, {
        bind(state: UpdatableState<ValueType>): BindableTagElement<ValueType, K, T> {
            update(self, getValue(self));
            const listener = (evt: Event) => state.update(currentState => getValue(self));
            const observer = state.addObserver((newValue: ValueType) => {
                if (!self.isConnected) {
                    observer.dispose();
                    self.removeEventListener(eventType, listener);
                } else {
                    update(self, newValue);
                }
            });
            self.addEventListener(eventType, listener);
            state.onDispose.then(_ => self.removeEventListener(eventType, listener));
            return result;
        },
        bindPartial(state: UpdatableState<ValueType | undefined>): BindableTagElement<ValueType, K, T> {
            update(self, getValue(self));
            const listener = (evt: Event) => state.update(currentState => getValue(self));
            const observer = state.addObserver(
                (newValue) => {
                    if (!self.isConnected) {
                        observer.dispose();
                        self.removeEventListener(eventType, listener);
                    } else if (newValue !== undefined)
                        update(self, newValue)
                }
            );
            self.addEventListener(eventType, listener);
            state.onDispose.then(_ => self.removeEventListener(eventType, listener));
            return result;
        },
        bindWithDefault(state: UpdatableState<ValueType | undefined>, defaultValue: ValueType, defaultState?: ValueType): BindableTagElement<ValueType, K, T> {
            update(self, getValue(self));
            const listener = (evt: Event) => state.update(currentState => { const v = getValue(self); return v === defaultValue ? defaultState : v });
            const observer = state.addObserver(
                (newValue) => {
                    if (!self.isConnected) {
                        observer.dispose();
                        self.removeEventListener(eventType, listener);
                    } else {
                        update(self, newValue ?? defaultValue)
                    }
                }
            );
            self.addEventListener(eventType, listener);
            state.onDispose.then(_ => self.removeEventListener(eventType, listener));
            return result;
        }
    }) as unknown as E & BindableTagElement<ValueType, K, T>;
    return result;
}

function bindableTextInput<K extends 'input' | 'textarea', T extends HTMLElementTagNameMap[K] = HTMLElementTagNameMap[K]>(self: TagElement<K, T>): BindableTagElement<string, K, T> {
    return mkBindable(self, el => el.value, (el, value) => el.value = value || "", 'input') as unknown as BindableTagElement<string, K, T>;
}

function delegateBinding<ValueType, K extends keyof HTMLElementTagNameMap, K1 extends keyof HTMLElementTagNameMap, T extends HTMLElementTagNameMap[K] = HTMLElementTagNameMap[K], T1 extends HTMLElementTagNameMap[K1] = HTMLElementTagNameMap[K1]>(
    from: T1 & BindableTagElement<ValueType, K1, T1>,
    to: T & TagElement<K, T>
): T & BindableTagElement<ValueType, K, T> {
    const result: T & BindableTagElement<ValueType, K, T> = Object.assign(to, {
        bind(state: UpdatableState<ValueType>): T & BindableTagElement<ValueType, K, T> {
            from.bind(state);
            return result;
        },
        bindPartial(state: UpdatableState<ValueType | undefined>): T & BindableTagElement<ValueType, K, T> {
            from.bindPartial(state);
            return result;
        },
        bindWithDefault(state: UpdatableState<ValueType | undefined>, defaultValue: ValueType, defaultState?: ValueType): T & BindableTagElement<ValueType, K, T> {
            from.bindWithDefault(state, defaultValue, defaultState);
            return result;
        }
    })
    return result;
}

export function blockquote(classes: string[], content: Content) {
    return tag('blockquote', classes, undefined, content);
}

export function para(classes: string[], content: Content) {
    return tag('p', classes, undefined, content);
}

export function span(classes: string[], content: AsyncContent) {
    return tag('span', classes, undefined, content);
}

export function img(classes: string[], src: string, alt?: string) {
    return tag('img', classes, {src, alt}, []);
}

export function icon(classes: string[], iconName: string, alt?: string) {
    const icon = loadIcon(iconName).then(svgEl => {
        const el = svgEl.cloneNode(true) as SVGElement | HTMLImageElement;
        el.setAttribute('class', 'icon');
        if (alt) {
            el.setAttribute('alt', alt);
        }
        return el;
    });
    return span(classes, icon);
}

export interface LinkOptions {
    preventNavigate?: boolean,
    target?: string
}
export function a(classes: string[], href: string, content: Content, opts?: LinkOptions) {
    const a = tag("a", classes, {href: href, toString: href}, content);
    if (opts?.target) {
        a.target = opts?.target;
    }
    if (opts?.preventNavigate) {
        a.addEventListener("click", (evt: MouseEvent) => {
            evt.preventDefault();
            return false;
        });
    }
    return a;
}

// This is supposed to create an inline SVG so we can style it with CSS. I'm not sure why it doesn't work though...
// export function icon(classes: string[], iconName: string, alt: string) {
//     const use = document.createElement("use");
//     const svgLoc = `/style/icons/fa/${iconName}.svg`;
//     use.setAttribute('href', svgLoc);
//     const svg = document.createElement("svg");
//     svg.appendChild(use);
//     return span(classes, svg);
// }

export function div(classes: string[], content: Content) {
    return tag('div', classes, undefined, content);
}

export function button(classes: string[], attributes: Record<string, string>, content: Content): TagElement<"button"> {
    if (!("type" in attributes)) {
        attributes["type"] = "button"
    }
    return tag('button', classes, attributes, content);
}

export function iconButton(classes: string[], title: string, iconName: string, alt: string): TagElement<"button"> {
    classes.push('icon-button');
    return button(classes, {title: title}, icon([], iconName, alt));
}

export function helpIconButton(classes: string[], url: string): TagElement<"button"> {
    classes.push('help-icon');
    const b = iconButton(classes, "Learn More","circle-help", "Learn More");
    b.click(() => window.open(url));
    return b;
}

export interface InputType {
    text: string
    number: number
}

export function textbox(classes: string[], placeholder?: string, value: string = ""): BindableTagElement<string, 'input'> {
    const input = tag('input', classes, {type: "text", placeholder: placeholder}, []);
    if (value) {
        input.value = value;
    }
    input.addEventListener('keydown', (evt: KeyboardEvent) => {
        if (evt.key === 'Escape' || evt.key == 'Cancel') {
            input.dispatchEvent(new CustomEvent('Cancel', { detail: { key: evt.key, event: evt }}));
        } else if (evt.key === 'Enter' || evt.key === 'Accept') {
            input.dispatchEvent(new CustomEvent('Accept', { detail: { key: evt.key, event: evt}}));
        }
    });

    return bindableTextInput(input)
}

export function numberbox(classes: string[], placeholder?: string, value?: number): BindableTagElement<number, 'input'> {
    const input = tag('input', classes, {type: "text", placeholder: placeholder}, []);
    if (value !== undefined) {
        input.value = value.toString();
    }
    input.addEventListener('keydown', (evt: KeyboardEvent) => {
        if (evt.key === 'Escape' || evt.key == 'Cancel') {
            input.dispatchEvent(new CustomEvent('Cancel', { detail: { key: evt.key, event: evt }}));
        } else if (evt.key === 'Enter' || evt.key === 'Accept') {
            input.dispatchEvent(new CustomEvent('Accept', { detail: { key: evt.key, event: evt}}));
        }
    });

    return mkBindable(
        input,
        el => parseFloat(el.value),
        (el, value) => el.value = (value?.toString() ?? ""),
        'input'
    );
}


export function textarea(classes: string[], placeholder: string, value: string =""): BindableTagElement<string, "textarea"> {
    const text = tag('textarea', classes, {placeholder: placeholder}, []);
    if (value) {
        text.value = value;
    }

    const adjustHeight = () => {
        text.style.height = 'inherit'; // reset height first;
        const p = (s: string | null) => parseInt(s || '0');
        const s = window.getComputedStyle(text);
        const h = text.scrollHeight + p(s.paddingBottom) + p(s.paddingTop) + p(s.borderBottomWidth) + p(s.borderTopWidth);
        const ems = Math.floor(h / parseInt(s.fontSize));
        text.style.height = `${ems}em`;
    };

    text.addEventListener('input', () => adjustHeight());
    text.style.height = "3em"; // default height;

    return bindableTextInput(text);
}

export interface DropdownElement extends TagElement<"select"> {
    setSelectedValue(value: string): void
    getSelectedValue(): string
    addValue(key: string, val: string): void
    onSelect(fn: (newValue: string) => void): TagElement<"select">
    clearValues(): void
}

export function dropdown(classes: string[], options: Record<string, string>, value?: string): DropdownElement & BindableTagElement<string, 'select', DropdownElement> {
    let opts: TagElement<"option">[] = [];

    for (const value in options) {
        if (options.hasOwnProperty(value)) {
            opts.push(tag('option', [], {value: value}, [options[value]]));
        }
    }

    const select = tag('select', classes, {}, opts);
    const dropdown: DropdownElement =  Object.assign(select, {
        setSelectedValue(value: string) {
            const index = opts.findIndex(opt => opt.value === value);
            if (index !== -1) {
                select.selectedIndex = index;
            }
        },
        getSelectedValue() {
            return select.options[select.selectedIndex].value;
        },
        addValue(key: string, val: string) {
            const opt = tag('option', [], {value: key}, [val]);
            dropdown.add(opt);
            opts.push(opt)
        },
        onSelect(fn: (newValue: string) => void): TagElement<"select"> {
            return select.change(_ => fn(this.getSelectedValue()));
        },
        clearValues(): void {
            select.innerHTML = "";
            opts = [];
        }
    });

    if (value) dropdown.setSelectedValue(value);

    return mkBindable(dropdown, dropdown => dropdown.getSelectedValue(), (dropdown, value) => dropdown.setSelectedValue(value  || ""), 'change');

}

// create element that goes into a FakeSelect (but not the FakeSelect itself)
export function fakeSelectElem(classes: string[], buttons: TagElement<"button">[]) {
    classes.push("dropdown");
    return div(classes, [
        icon(['marker'], 'angle-down'),
    ].concat(buttons));
}

export function checkbox(classes: string[], label: string, value: boolean = false): BindableTagElement<boolean, 'label'> {
    const attrs = {type:'checkbox', checked: value};
    const cb = mkBindable(tag('input', [], attrs, []), cb => cb.checked, (cb, checked) => cb.checked = checked ?? false, 'input');
    const t = tag('label', classes, {}, [
        cb,
        span([], [label])
    ], cb);

    return delegateBinding(cb, t) as unknown as BindableTagElement<boolean, 'label'>;
}

export function radio(classes: string[], label: string, name: string, value: boolean = false) {
    const attrs = {type:'radio', checked: value, name: name};
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

export function label(classes: string[], label: string, input: TagElement<"input" | "select" | "textarea">, largeLabel: boolean = false): TagElement<"label"> {
    return tag('label', classes, {}, [
       largeLabel ? h4(['label-title'], label) : span(['label-title'], label),
       input
    ]);
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
    rows?: (TagElement<any>[] | Record<string, string>)[],
    rowHeading?: boolean,
    addToTop?: boolean
}

export interface TableRowElement extends TagElement<"tr"> {
    row: TableRow
    propCells: Record<string, TagElement<any>>
    updateValues(props: Record<string, Content>): void
}

export interface TableElement extends TagElement<"table"> {
    addRow(row: Content[] | TableRow, whichBody?: TagElement<"tbody">): TableRowElement
    findRows(props: Record<string, string>, tbody?: TagElement<"tbody">): TableRowElement[]
    findRowsBy(fn: (row: TableRow) => boolean, tbody?: TagElement<"tbody">): TableRowElement[]
    addBody(rows?: TableRow[]): TagElement<"tbody">
    clear(whichBody? : TagElement<"tbody">): TagElement<"tbody">
}

/**
 * Creates a table element with an addRow function which appends a row.
 */
type TableRow = Record<string, Content>
export function table(classes: string[], contentSpec: TableContentSpec): TableElement {
    const colClass = (col: number) => contentSpec.classes?.[col] ?? '';
    const heading = contentSpec.header ? [tag('thead', [], {}, [
            tag('tr', [], {}, contentSpec.header.map((c, i) => tag('th', [colClass(i)], {}, [c])))
        ])] : [];

    function mkTR(row: Content[] | TableRow): TableRowElement {
        const contentArrays: Content[] =
            row instanceof Array ? row : contentSpec.classes.map(cls => row[cls]).map(content => content instanceof Array ? content : [content]);

        const propCells: Record<string, TagElement<any>> = {};
        const cells = contentArrays.map((c, i) => {
            const cell = i === 0 && contentSpec.rowHeading ? tag('th', [colClass(i)], {scope: 'row'}, c) : tag('td', [colClass(i)], {}, c);
            propCells[contentSpec.classes[i]] = cell;
            return cell;
        });

        const tr = tag('tr', [], {}, cells) as TableRowElement;
        return Object.assign(tr, {
            row: {
                ...row,
                __el: tr
            },
            propCells: propCells,
            updateValues(props: Record<string, Content>) {
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
            }
        });
    }

    const body = tag(
        'tbody', [], {},
        contentSpec.rows?.map(mkTR) ?? []
    );

    const table = tag('table', classes, {}, [
        ...heading, body
    ]) as TableElement;

    return Object.assign(table, {
        addRow(row: Content[] | TableRow, whichBody?: TagElement<"tbody">) {
            const tbody = whichBody ?? body;
            const rowEl = mkTR(row);
            if (contentSpec.addToTop)
                tbody.insertBefore(rowEl, tbody.firstChild);
            else
                tbody.appendChild(rowEl);
            return rowEl;
        },
        findRows(props: Record<string, string>, tbody?: TagElement<"tbody">) {
            return table.findRowsBy((row: TableRow) => {
                for (const prop in props) {
                    if (props.hasOwnProperty(prop)) {
                        if (row[prop] !== props[prop])
                            return false;
                    }
                }
                return true;
            }, tbody);
        },
        findRowsBy(fn: (row: TableRow) => boolean, tbody?: TagElement<"tbody">) {
            const [searchEl, selector] = tbody ? [tbody, 'tr'] : [table, 'tbody tr'];
            const matches: TagElement<"tr">[] = [];
            [...searchEl.querySelectorAll(selector)].forEach((tr: TableRowElement) => {
                if (fn(tr.row)) {
                    matches.push(tr);
                }
            });
            return matches;
        },
        addBody(rows?: TableRow[]) {
            const newBody = tag(
                'tbody', [], {},
                contentSpec.rows?.map(mkTR) ?? []
            );

            table.appendChild(newBody);

            if (rows) {
                rows.forEach(row => table.addRow(row, newBody));
            }
            return newBody;
        },
        clear(whichBody? : TagElement<"tbody">) {
            if (whichBody) { // clear rows in this tbody element
                whichBody.innerHTML = "";
            }
            else { // do a clean reset of the table
                body.innerHTML = "";
                table.innerHTML = "";
                table.appendChild(body);
            }
            return whichBody ?? body; // return the cleared element
        }
    });
}

export function details(classes: string[], summary: Content, content: Content) {
    const el = tag('details', classes, {}, [
        tag('summary', [], {}, summary)
    ]);
    appendContent(el, content);
    return el;
}

export function polynoteLogo() {
    return div(['polynote-logo'], [span(['alt'], ["Polynote"])]);
}

export function loader(classes: string[] = []) {
    return div([...classes, 'loader'], [])
}