import {UIMessageTarget, UIMessage} from "../util/ui_event";
import {NotebookUI} from "./notebook";


/**
 * CurrentNotebook keeps track of the currently selected notebook and provides a central point for other components to
 * coordinate to generate, and react to, modifications of the current notebook.
 */
export class CurrentNotebook extends UIMessageTarget {
    private static inst: NotebookUI;

    static get get() {
        return this.inst
    }

    static set(nb: NotebookUI) {
        this.inst = nb
    }
}
