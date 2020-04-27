import {div, TagElement} from "../../util/tags";
import {ServerMessageDispatcher} from "../messaging/dispatcher";
import {ViewPrefsHandler} from "../state/storage";
import {storage} from "../../util/storage";

/**
 * Holds a classic three-pane display, where the left and right panes can be both resized and collapsed.
 */

export type Pane = { header: TagElement<"h2">, el: TagElement<"div">}
export class SplitViewComponent {
    readonly el: TagElement<"div">;
    constructor(leftPane: Pane, center: TagElement<"div">, rightPane: Pane) {
        // todo: left, right make divs, uipanel, handle clicks etc.
        const left = div(['grid-shell'], [
            div(['ui-panel'], [
                leftPane.header.click(evt => {
                    ViewPrefsHandler.updateState(s => {
                        s.leftPane.collapsed = !s.leftPane.collapsed;
                        return s
                    })
                }),
                div(['ui-panel-content'], [leftPane.el])])]);

        const right = div(['grid-shell'], [
            div(['ui-panel'], [
                rightPane.header.click(evt => {
                    ViewPrefsHandler.updateState(s => {
                        s.rightPane.collapsed = !s.rightPane.collapsed;
                        return s
                    })
                }),
                div(['ui-panel-content'], [rightPane.el])])]);

        const initialPrefs = ViewPrefsHandler.getState();

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
            if (evt.clientX) {
                left.style.width = (leftDragger.initialWidth + (evt.clientX - leftDragger.initialX)) + "px";
            }
        });
        leftDragger.addEventListener('dragend', () => {
            ViewPrefsHandler.updateState(s => {
                s.leftPane.size = left.style.width;
                return s
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
            if (evt.clientX) {
                right.style.width = (rightDragger.initialWidth - (evt.clientX - rightDragger.initialX)) + "px";
            }
        });
        rightDragger.addEventListener('dragend', evt => {
            ViewPrefsHandler.updateState(s => {
                s.rightPane.size = right.style.width;
                return s
            });
        });

        this.el = div(['split-view'], [left, leftDragger, center, rightDragger, right]);

        ViewPrefsHandler.addObserver((_, prefs) => {
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
        })
    }
}