import {UIEvent, UIEventBase, UIEventTarget} from "../util/ui_event";
import {button, Content} from "../util/tags";

export abstract class UIComponent extends UIEventTarget {

    abstract container: HTMLElement;

    constructor() {
        super();
    }

    on<T extends UIEventBase<U>, U, Self extends UIComponent>(this: Self, type: ((new (...any: any) => T) & { type: string }), listener: ((evt: T) => void)): Self {
        this.addEventListener(type.type, evt => {
           if (evt instanceof type) {
               listener(evt as T);
           }
        });
        return this;
    }

}

export abstract class UIComponentContainer<Child extends UIComponent> extends UIComponent {

    abstract childContainer: HTMLElement;
    readonly children: Child[] = [];

    constructor() {
        super();
    }

    add<C extends Child>(child: C): C {
        this.childContainer.appendChild(child.container);
        child.setEventParent(this);
        this.children.push(child);
        return child;
    }

    replace<N extends Child, O extends Child>(newChild: N, oldChild: O): N {
        this.childContainer.replaceChild(newChild.container, oldChild.container);
        const childIndex = this.children.indexOf(oldChild);
        if (childIndex != -1) {
            this.children.splice(childIndex, 1, newChild)
        }
        return newChild;
    }

    remove<C extends Child>(child: C): C {
        this.childContainer.removeChild(child.container);
        child.setEventParent(undefined);
        const childIndex = this.children.indexOf(child);
        if (childIndex !== -1) {
            this.children.splice(childIndex, 1);
        }
        return child;
    }
}