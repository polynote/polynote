import {div, TagElement} from "../tags";
import {ViewPreferences, ViewPrefsHandler} from "../../state/preferences";
import {Disposable} from "../../state/state_handler";

/**
 * Holds a classic three-pane display, where the left and right panes can be both resized and collapsed.
 */

export type Pane = { header: TagElement<"h2">, el: TagElement<"div">}
export class SplitView extends Disposable {
    readonly el: TagElement<"div">;
    constructor(leftPane: Pane, center: TagElement<"div">, rightPane: Pane) {
        super()

        const left = div(['grid-shell'], [
            div(['ui-panel'], [
                leftPane.header.click(evt => {
                    ViewPrefsHandler.update(s => {
                        return {
                            ...s,
                            leftPane: {
                                ...s.leftPane,
                                collapsed: !s.leftPane.collapsed
                            }
                        }
                    });
                    window.dispatchEvent(new CustomEvent('resize'));
                }),
                div(['ui-panel-content'], [leftPane.el])])]);

        const right = div(['grid-shell'], [
            div(['ui-panel'], [
                rightPane.header.click(evt => {
                    ViewPrefsHandler.update(s => {
                        return {
                            ...s,
                            rightPane: {
                                ...s.rightPane,
                                collapsed: !s.rightPane.collapsed
                            }
                        }
                    })
                    window.dispatchEvent(new CustomEvent('resize'))
                }),
                div(['ui-panel-content'], [rightPane.el])])]);

        const initialPrefs = ViewPrefsHandler.state;

        // left pane
        left.classList.add('left');
        left.style.gridArea = 'left';
        left.style.width = initialPrefs.leftPane.size;

        // left dragger
        const leftDragger = Object.assign(
            div(['drag-handle', 'left'], [
                div(['inner'], []).attr('draggable', 'true')
            ]), {
                initialX: 0,
                initialWidth: 0
            });
        leftDragger.style.gridArea = 'leftdrag';
        leftDragger.addEventListener('dragstart', (evt) => {
            leftDragger.initialX = evt.clientX;
            leftDragger.initialWidth = left.offsetWidth;
        });
        leftDragger.addEventListener('drag', (evt) => {
            evt.preventDefault();
            if (evt.clientX > 0) {
                left.style.width = (leftDragger.initialWidth + (evt.clientX - leftDragger.initialX)) + "px";
            }
        });
        leftDragger.addEventListener('dragend', () => {
            ViewPrefsHandler.update(s => {
                return {
                    ...s,
                    leftPane: {
                        ...s.leftPane,
                        size: left.style.width
                    }
                }
            });
        });

        // right pane
        right.classList.add('right');
        right.style.gridArea = 'right';
        right.style.width = initialPrefs.rightPane.size;

        // right dragger
        const rightDragger = Object.assign(
            div(['drag-handle', 'right'], [
                div(['inner'], []).attr('draggable', 'true')
            ]), {
                initialX: 0,
                initialWidth: 0
            });
        rightDragger.style.gridArea = 'rightdrag';
        rightDragger.addEventListener('dragstart', (evt) => {
            rightDragger.initialX = evt.clientX;
            rightDragger.initialWidth = right.offsetWidth;
        });
        rightDragger.addEventListener('drag', (evt) => {
            evt.preventDefault();
            if (evt.clientX > 0) {
                right.style.width = (rightDragger.initialWidth - (evt.clientX - rightDragger.initialX)) + "px";
            }
        });
        rightDragger.addEventListener('dragend', evt => {
            ViewPrefsHandler.update(s => {
                return {
                    ...s,
                    rightPane: {
                        ...s.rightPane,
                        size: right.style.width
                    }
                }
            });
        });

        const initialClasses = [];
        if (initialPrefs.leftPane.collapsed) {
            initialClasses.push('left-collapsed');
        }
        if (initialPrefs.rightPane.collapsed) {
            initialClasses.push('right-collapsed');
        }

        this.el = div(['split-view', ...initialClasses], [left, leftDragger, center, rightDragger, right]);

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
        collapseStatus(initialPrefs)
        ViewPrefsHandler.addObserver(collapseStatus, this)
    }
}