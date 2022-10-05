import {div, h2, iconButton, TagElement} from "../tags";
import {
    Disposable,
    IDisposable,
    mkDisposable,
    setProperty,
    StateHandler,
} from "../../state";
import {
    LeftBarPreferences,
    LeftBarPrefsHandler,
    ViewPreferences,
    ViewPrefsHandler
} from "../../state/preferences";
import {safeForEach} from "../../util/helpers";
import {SearchModal} from "../component/search";
import {Modal} from "./modal";

export interface LeftMenuSections {
    files: boolean,
    summary: boolean,
}

/**
 * Holds a classic three-pane display, where the left and right panes can be both resized and collapsed.
 */

export type Pane = { header: TagElement<"h2">, el: TagElement<"div">}
export type LeftPaneContents = Record<string, (Pane | Modal)>

class Dragger extends Disposable {
    readonly el: TagElement<'div'>;
    private initialX: number = 0;
    private initialWidth: number = 0;
    dragging: boolean = false;
    
    constructor(private side: 'left' | 'right', private state: StateHandler<{size: string}>, private panel: TagElement<'div'>, private parent: SplitView) {
        super();
        this.disposeWith(parent);
        
        const el = this.el = div(['drag-handle', side], [
            div(['inner'], [])
        ]);


        el.style.gridArea = 'leftdrag';

        el.addEventListener('mousedown', evt => {
            this.initialX = evt.clientX;
            this.dragging = true;
            this.initialWidth = panel.offsetWidth;
            parent.triggerStartResize();

            const onRelease: () => void = () => {
                window.removeEventListener('mousemove', onMove);
                window.removeEventListener('mouseup', onRelease);
                window.removeEventListener('blur', onRelease);
                state.update(() => setProperty("size", panel.style.width));
                this.dragging = false;
                parent.triggerEndResize();
            };

            const onMove: (evt: MouseEvent) => void = (evt) => {
                evt.preventDefault();
                if (evt.clientX > 0) {
                    currentWidth = updateWidth(evt.clientX);
                    if (dragTimeout === 0) {
                        dragTimeout = window.setTimeout(() => {
                            dragTimeout = 0;
                            window.requestAnimationFrame(() => {
                                panel.style.width = currentWidth + "px";
                            })
                        }, 10);
                    }
                }
            };

            window.addEventListener('mousemove', onMove);
            window.addEventListener('mouseup', onRelease);
            window.addEventListener('blur', onRelease);
        })

        let currentWidth = 0;
        let dragTimeout = 0;

        const updateWidth: (mouseX: number) => number =
            side === 'left' ? (mouseX => (this.initialWidth + (mouseX - this.initialX)))
                            : (mouseX => (this.initialWidth - (mouseX - this.initialX)))

        el.style.gridArea = `${side}drag`;
    }

}

export class SplitView extends Disposable {
    readonly el: TagElement<"div">;
    private _centerWidth: number = 0;
    get centerWidth(): number { return this._centerWidth; }

    private resizeObservers: (((width: number) => void) & IDisposable)[] = [];
    private startResizeObservers: (((width: number) => void) & IDisposable)[] = [];
    private endResizeObservers: (((width: number) => void) & IDisposable)[] = [];
    private centerResizeObserver: ResizeObserver;
    private leftDragger: Dragger;
    private rightDragger: Dragger;

    private readonly leftView: StateHandler<ViewPreferences["leftPane"]>;
    private readonly stickyLeftMenu: StateHandler<LeftBarPreferences["stickyLeftMenu"]>;

    constructor(leftPaneContents: LeftPaneContents, private center: TagElement<"div">, rightPane: Pane) {
        super()

        const rightView = ViewPrefsHandler.lens("rightPane").disposeWith(this);
        this.leftView = ViewPrefsHandler.lens("leftPane").disposeWith(this);
        this.stickyLeftMenu = LeftBarPrefsHandler.lens("stickyLeftMenu").disposeWith(this);

        const resizeObserver = this.centerResizeObserver = new ResizeObserver(([entry]) => this.triggerResize(entry.contentRect.width));
        resizeObserver.observe(center);

        this.onDispose.then(() => {
            resizeObserver.disconnect();
            this.resizeObservers.forEach(obs => obs.dispose());
            this.resizeObservers = [];
            this.startResizeObservers.forEach(obs => obs.dispose());
            this.startResizeObservers = [];
            this.endResizeObservers.forEach(obs => obs.dispose());
            this.endResizeObservers = [];
        })

        const nbList = leftPaneContents["nbList"] as Pane;
        const tableOfContents = leftPaneContents["tableOfContents"] as Pane;
        const searchModal = leftPaneContents["search"] as SearchModal;

        const filesIcon = iconButton(['file-system'], 'View Files', 'folder', '[View Files]');
        const summaryIcon = iconButton(['list-ul'], 'Table of Contents', 'list-ul', '[Table of Contents]');
        const searchIcon = iconButton(['search'], 'Search Files', 'search', '[Search Files]');

        const notebooksBundle = div(["notebooks-bundle"], [
                h2([], ["Notebooks"]),
                filesIcon,
            ]).click(() => this.toggleSection("files", this.stickyLeftMenu, this.leftView));
        const summaryBundle = div(["summary-bundle"], [
                h2([], ["Summary"]),
                summaryIcon,
            ]).click(() => this.toggleSection("summary", this.stickyLeftMenu, this.leftView));
        const searchBundle = div(["search-bundle"], [
                h2([], ["Search"]),
                searchIcon
            ]).click(() => searchModal.showUI())

        const left = div(['grid-shell'], [
            div(['sticky-left-bar'], [
                notebooksBundle,
                summaryBundle,
                searchBundle,
            ]),
            // Default ui-panel to the notebook list
            div(['ui-panel'], [
                nbList.header,
                div(['ui-panel-content', 'left'], [nbList.el])])]);

        // Attach header listeners for the left bar
        nbList.header.click(() => this.togglePanel(this.leftView, true));
        tableOfContents.header.click(() => this.togglePanel(this.leftView, true));

        const right = div(['grid-shell'], [
            div(['ui-panel'], [
                rightPane.header.click(() => this.togglePanel(rightView, false)),
                div(['ui-panel-content', 'right'], [rightPane.el])])]);

        const intialViewPrefs = ViewPrefsHandler.state;
        const initialLeftBarPrefs = LeftBarPrefsHandler.state;

        // left pane
        left.classList.add('left');
        left.style.gridArea = 'left';
        left.style.width = intialViewPrefs.leftPane.size;

        let dragTimeout = 0;
        let leftX = 0;
        let rightX = 0;

        // left dragger
        const leftDragger = this.leftDragger = new Dragger('left', this.leftView, left, this);

        // right pane
        right.classList.add('right');
        right.style.gridArea = 'right';
        right.style.width = intialViewPrefs.rightPane.size;

        // right dragger
        const rightDragger = this.rightDragger = new Dragger('right', rightView, right, this);

        this.el = div(['split-view'], [left, leftDragger, center, rightDragger, right]);

        const collapseStatus = (prefs: ViewPreferences) => {
            if (prefs.leftPane.collapsed) {
                this.el.classList.add('left-collapsed');
            } else {
                this.el.classList.remove('left-collapsed');
            }
            if (prefs.rightPane.collapsed) {
                this.el.classList.add('right-collapsed');
            } else {
                this.el.classList.remove('right-collapsed');
            }
        }
        collapseStatus(intialViewPrefs);
        ViewPrefsHandler.addObserver(collapseStatus).disposeWith(this);

        const leftBarStatus = (leftBarPrefs: LeftBarPreferences) => {
            if (leftBarPrefs.stickyLeftMenu.files) {
                notebooksBundle.classList.add('active');
                this.setLeftPane(nbList.header, nbList.el);
            } else {
                notebooksBundle.classList.remove('active');
            }
            if (leftBarPrefs.stickyLeftMenu.summary) {
                summaryBundle.classList.add('active');
                this.setLeftPane(tableOfContents.header, tableOfContents.el);
            } else {
                summaryBundle.classList.remove('active');
            }
        }
        leftBarStatus(initialLeftBarPrefs);
        LeftBarPrefsHandler.addObserver(leftBarStatus).disposeWith(this);
    }

    private setLeftPane(header: TagElement<"h2">, el: TagElement<"div">): void {
        const oldEl = this.el.querySelector('.ui-panel');
        if (oldEl !== null) {
            oldEl.innerHTML = ""; // we can't use replaceWith and have to modify the innerHTML because of the CSS grid
            oldEl.appendChild(header);
            oldEl.appendChild(div(['ui-panel-content', 'left'], [el]));
        }
    }

    private togglePanel(state: StateHandler<{ collapsed: boolean }>, canToggleSection: boolean): void {
        this.triggerStartResize(this.centerWidth);
        state.updateAsync(state => setProperty("collapsed", !state.collapsed)).then(() => {
            window.dispatchEvent(new CustomEvent('resize'));

            // If the panel was collapsed by something other than the left bar, update the left bar's state
            if (canToggleSection && state.state.collapsed) {
                this.toggleSection("none", this.stickyLeftMenu, this.leftView);
            }
        })
    }

    onResize(fn: (width: number) => void): IDisposable {
        const disposable = mkDisposable(fn, () => SplitView.removeObserver(this.resizeObservers, fn));
        this.resizeObservers.push(disposable);
        return disposable;
    }

    onStartResize(fn: (width: number) => void): IDisposable {
        const disposable = mkDisposable(fn, () => SplitView.removeObserver(this.startResizeObservers, fn));
        this.startResizeObservers.push(disposable);
        return disposable;
    }

    onEndResize(fn: (width: number) => void): IDisposable {
        const disposable = mkDisposable(fn, () => SplitView.removeObserver(this.endResizeObservers, fn));
        this.endResizeObservers.push(disposable);
        return disposable;
    }

    private static removeObserver(observers: ((width: number) => void)[], obs: (width: number) => void): void {
        const idx = observers.indexOf(obs);
        if (idx >= 0) {
            observers.splice(idx, 1);
        }
    }

    triggerResize(width: number, notify: boolean = true): void {
        if (this._centerWidth === width)
            return;

        this._centerWidth = width;
        if (notify) {
            safeForEach(this.resizeObservers, obs => obs(width));
        }

        if (!this.leftDragger.dragging && !this.rightDragger.dragging) {
            this.triggerEndResize(width);
        }
    }

    triggerStartResize(w?: number): void {
        const width = w ?? (this.centerWidth || this.center.clientWidth);
        safeForEach(this.startResizeObservers, obs => obs(width));
    }

    triggerEndResize(w?: number) {
        const width = w ?? (this.centerWidth || this.center.clientWidth);
        this._centerWidth = width;
        safeForEach(this.endResizeObservers, obs => obs(width));
    }

    private toggleSection(section: string, state: StateHandler<{ files: boolean, summary: boolean }>, leftPanelState: StateHandler<{ collapsed: boolean }>) {
        const newSections = LeftBarPrefsHandler.state.stickyLeftMenu;

        // If the left bar selection should change the panel, update it accordingly
        if (section !== "none" && !newSections[<keyof LeftMenuSections> section] && leftPanelState.state.collapsed || newSections[<keyof LeftMenuSections> section] && !leftPanelState.state.collapsed)
            this.togglePanel(this.leftView, false);

        state.updateAsync(state => setProperty("files", section === 'files' ? !state.files : false))
        state.updateAsync(state => setProperty("summary", section === 'summary' ? !state.summary : false))
    }
}