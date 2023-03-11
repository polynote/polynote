import {div, h2, TagElement} from "../tags";
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
import {LeftPaneHandler} from "../component/leftpane";

export interface LeftMenuSections {
    files: boolean,
    summary: boolean,
}

/**
 * Holds a classic three-pane display, where the left and right panes can be both resized and collapsed.
 */

export type Pane = { header: TagElement<"h2">, el: TagElement<"div"> };

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

        const updateWidth = (mouseX: number): number => {
            // Use different minimums for each side since the left side also has to factor in space for the sticky sidebar
            if (side === 'left') {
                const leftWidth = this.initialWidth + (mouseX - this.initialX);
                return Math.max(leftWidth, 128);
            } else {
                const rightWidth = this.initialWidth - (mouseX - this.initialX);
                return Math.max(rightWidth, 64);
            }
        }

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

    constructor(leftPaneContents: LeftPaneHandler, private center: TagElement<"div">, rightPane: Pane) {
        super()

        const rightView = ViewPrefsHandler.lens("rightPane").disposeWith(this);
        this.leftView = ViewPrefsHandler.lens("leftPane").disposeWith(this);

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

        const left = div(['grid-shell'], [leftPaneContents.leftBar, leftPaneContents.leftPane]);

        const right = div(['grid-shell'], [
            div(['ui-panel'], [
                rightPane.header.click(() => this.togglePanel(rightView)),
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
    }

    private togglePanel(state: StateHandler<{ collapsed: boolean }>): void {
        this.triggerStartResize(this.centerWidth);
        state.updateAsync(state => setProperty("collapsed", !state.collapsed)).then(() => {
            window.dispatchEvent(new CustomEvent('resize'));
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
}