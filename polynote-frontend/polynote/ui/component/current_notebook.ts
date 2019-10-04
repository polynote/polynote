import {UIEventTarget, UIEvent} from "../util/ui_event";
import {NotebookUI} from "./notebook";
import {Cell, CodeCell, TextCell} from "./cell";
import {NotebookCell} from "../../data/data";


/**
 * CurrentNotebookObserver keeps track of the currently selected notebook and provides a central point for other components to
 * coordinate to generate, and react to, modifications of the current notebook.
 *
 * Observers are UIEventTargets and are triggered with a UI Event dispatched on them. This is a bit of a shortcut for
 * now, in future we may want to be more generic and take a function.
 *
 * Using UIEventTarget allows the Observer to just insert itself as the parent of the notebook so it can catch all
 * dispatched events. It then broadcasts those events to all its subscribers.
 */
class CurrentNotebookObserver extends UIEventTarget {
    private _current: NotebookUI;
    // private observers: UIEventTarget[] = [];

    constructor() {
        super()
    }

    get current() {
        return this._current
    }

    set current(nb: NotebookUI) {
        nb.setEventParent(this); // so we can catch all events from this notebook.
        // this.dispatchEvent(new NotebookSelected(nb, this.current));
        this._current = nb

    }




    //
    // get currentCell() {
    //     return Cell.currentFocus // TODO: should be something like `this.current.selectedCell` instead...
    // }
    //
    //
    // subscribe(et: UIEventTarget) {
    //     this.observers.push(et);
    // }
    //
    // unsubscribe(et: UIEventTarget) {
    //     this.observers = this.observers.filter(sub => sub !== et)
    // }
    //
    // dispatchEvent(evt: Event): boolean {
    //     this.observers.forEach(obs => obs.dispatchEvent(evt)); // forward all the events we caught from the current nb
    //     return super.dispatchEvent(evt);
    // }
}

export const CurrentNotebook = new CurrentNotebookObserver();

export class NotebookSelected extends UIEvent<{current: NotebookUI, prev: NotebookUI}> {
    constructor(current: NotebookUI, prev: NotebookUI) {
        super('NotebookSelected', {current, prev})
    }
}