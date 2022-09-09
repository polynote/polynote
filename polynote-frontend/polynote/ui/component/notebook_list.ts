import {Disposable, IDisposable} from "../../state";
import {div, iconButton, label, para, table, TagElement, textbox} from "../tags";
import {ServerMessageDispatcher} from "../../messaging/dispatcher";
import {ServerStateHandler} from "../../state/server_state";
import {Modal} from "../layout/modal";
import {BranchEl, NotebookList} from "./notebooklist";

export class NotebookListModal extends Modal implements IDisposable {
    private serverMessageDispatcher: ServerMessageDispatcher;
    private disposable: Disposable;

    private dragEnter: EventTarget | null;
    private tree: BranchEl;

    constructor(serverMessageDispatcher: ServerMessageDispatcher) {
        const nbList = new NotebookList(serverMessageDispatcher);
        const wrapper = div(['create-notebook-modal'], [nbList.el]);
        super(wrapper, { title: "Notebooks", windowClasses: ['search'] });

        this.disposable = new Disposable();
        this.serverMessageDispatcher = serverMessageDispatcher;
    }

    show() {
        super.show();
    }

    showUI() {
        this.container.style.display = "flex";
    }

    hide() {
        this.container.style.display = "none";
    }

    // implement IDisposable
    dispose() {
        return this.disposable.dispose()
    }

    get onDispose() {
        return this.disposable.onDispose
    }

    get isDisposed() {
        return this.disposable.isDisposed
    }

    tryDispose() {
        return this.disposable.tryDispose()
    }

    disposeWith(that: IDisposable): this {
        this.disposable.disposeWith(that);
        return this;
    }
}