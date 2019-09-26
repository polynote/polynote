import {storage} from "../util/storage";
import {div, TagElement} from "../util/tags";

interface Pane {
    el: TagElement<"div">
}

export class SplitView {
    readonly el: TagElement<"div">;
    constructor(readonly id: string, readonly left: Pane, readonly center: Pane, readonly right: Pane) {
        let children = [];

        if (left) {
            const prefId = `${id}.leftSize`;
            left.el.classList.add("left");
            left.el.style.gridArea = 'left';
            left.el.style.width = storage.get(prefId) || '300px';

            let leftDragger = Object.assign(
                div(['drag-handle', 'left'], [
                    div(['inner'], []).attr('draggable', 'true')
                ]), {
                  initialX: 0,
                  initialWidth: 0
                });
            leftDragger.style.gridArea = 'leftdrag';

            children.push(left.el);
            children.push(leftDragger);

            leftDragger.addEventListener('dragstart', (evt) => {
                leftDragger.initialX = evt.clientX;
                leftDragger.initialWidth = left.el.offsetWidth;
            });

            leftDragger.addEventListener('drag', (evt) => {
                evt.preventDefault();
                if (evt.clientX) {
                    left.el.style.width = (leftDragger.initialWidth + (evt.clientX - leftDragger.initialX)) + "px";
                }
            });

            leftDragger.addEventListener('dragend', (evt) => {
                storage.set(prefId, left.el.style.width);
                window.dispatchEvent(new CustomEvent('resize', {}));
            });
        }

        if (center) {
            children.push(center.el);
        }

        if (right) {
            const prefId = `${id}.rightSize`;
            right.el.classList.add("right");
            right.el.style.gridArea = 'right';
            right.el.style.width = storage.get(prefId) || '300px';

            let rightDragger = Object.assign(
                div(['drag-handle', 'right'], [
                    div(['inner'], []).attr('draggable', 'true')
                ]), {
                    initialX: 0,
                    initialWidth: 0
                });

            rightDragger.style.gridArea = 'rightdrag';

            children.push(rightDragger);
            children.push(right.el);

            rightDragger.addEventListener('dragstart', (evt) => {
                rightDragger.initialX = evt.clientX;
                rightDragger.initialWidth = right.el.offsetWidth;
            });

            rightDragger.addEventListener('drag', (evt) => {
                evt.preventDefault();
                if (evt.clientX) {
                    right.el.style.width = (rightDragger.initialWidth - (evt.clientX - rightDragger.initialX)) + "px";
                }
            });

            rightDragger.addEventListener('dragend', evt => {
                storage.set(prefId, right.el.style.width);
                window.dispatchEvent(new CustomEvent('resize', {}));
            });
        }

        this.el = div(['split-view', id], children);
    }

    // TODO: remove event dispatch on window, replace toggles with add, remove
    collapse(side: 'left' | 'right', force: boolean = false) {
        if (side === 'left') {
            this.el.classList.toggle('left-collapsed', force || undefined) // undefined because we want it to toggle normally if we aren't forcing it.
            window.dispatchEvent(new CustomEvent('resize', {}));
        } else if (side === 'right') {
            this.el.classList.toggle('right-collapsed', force || undefined)
            window.dispatchEvent(new CustomEvent('resize', {}));
        } else {
            throw `Supported values are 'right' and 'left', got ${side}`
        }
    }
}