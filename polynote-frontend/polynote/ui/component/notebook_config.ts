import {
    button,
    div,
    dropdown,
    DropdownElement,
    h2,
    h3,
    iconButton,
    para,
    span,
    TagElement,
    textbox
} from "../util/tags";
import {UIEvent, UIEventBase} from "../util/ui_event";
import {UIComponent, UIComponentContainer} from "./component"
import {IvyRepository, MavenRepository, NotebookConfig, PipRepository, RepositoryConfig} from "../../data/data";
import match from "../../util/match";

class NotebookConfigUI extends UIComponentContainer<UIComponent> {

    private controls: Controls = new Controls();

    readonly childContainer: TagElement<"div"> = div([], []);
    readonly container: TagElement<"div"> = div(['notebook-config'], [
        h2(['config'], ['Configuration & dependencies']).click(() => this.container.classList.toggle('open')),
        div(['content'], [
            this.childContainer,
            this.controls.container
        ])
    ]);

    constructor(private notebookConfig: NotebookConfig) {
        super();
        this.controls.addEventListener("SaveAndRestart", _ => this.dispatchEvent(new UpdatedConfig(this.notebookConfig)));
        this.add(new DependenciesUI(notebookConfig.dependencies || {})
                    .on(DependenciesChanged, evt => this.updateConfig({dependencies: evt.dependencies})));


    }

    private updateConfig(args: Partial<NotebookConfig>) {
        this.notebookConfig = this.notebookConfig.copy(args);
        this.dispatchEvent(new UpdatedConfig(this.notebookConfig));
    }

}

class UpdatedConfig extends UIEventBase<NotebookConfig> {
    constructor(readonly config: NotebookConfig) {
        super('UpdatedConfig');
    }
}

class Controls extends UIComponent {
    readonly container: TagElement<"div"> = div(['controls'], [
            button(['save'], {}, ['Save & Restart']).click(_ => this.dispatchEvent(new SaveAndRestart())),
            button(['cancel'], {}, ['Cancel']).click(_ => this.dispatchEvent(new Cancel()))
        ])
}

class SaveAndRestart extends UIEventBase<{}> {
    constructor() {
        super('SaveAndRestart');
    }
}

class Cancel extends UIEventBase<{}> {
    constructor() {
        super('Cancel');
    }
}

class DependenciesUI extends UIComponentContainer<DependencyUI> {
    readonly childContainer: TagElement<"div"> = div(['dependency-list'], []);

    readonly container: TagElement<"div"> = div(['notebook-dependencies', 'notebook-config-section'], [
        h3([], ['Dependencies']),
        para([], ['You can provide Scala / JVM dependencies using  Maven coordinates , e.g. ', span(['pre'], ['org.myorg:package-name_2.11:1.0.1']), ', or URLs like ', span(['pre'], ['s3://path/to/my.jar'])]),
        para([], ['You can also specify pip packages, e.g. ', span(['pre'], ['requests']), ', or with a version like ', span(['pre'], ['urllib3==1.25.3'])]),
        this.childContainer
    ]);

    constructor(private dependencies: Record<string, string[]>) {
        super();
        for (let lang of Object.keys(dependencies)) {
            for (let url of dependencies[lang]) {
                const depUI: DependencyUI = new DependencyUI(lang, url)
                    .on(DependencyChanged, evt => this.updateDependency(evt.from, evt.to))
                    .on(RemoveDependency, _ => this.removeDependency(depUI))
                    .on(AddDependency, _ => this.addDependency(depUI));
                this.add(depUI);
            }
        }
    }

    private updateDependency(from: [string, string], to: [string, string]) {
        const index = this.dependencies[from[0]].indexOf(from[1]);
        if (index != -1) {
            if (to[0] === from[0]) {
                this.dependencies[from[0]][index] = to[1];
            } else {
                this.dependencies[from[0]].splice(index, 1);
                this.dependencies[to[0]].push(to[1]);
            }
        }
        this.dispatchEvent(new DependenciesChanged(this.dependencies));
    }

    private removeDependency(ui: DependencyUI) {
        const index = this.dependencies[ui.type].indexOf(ui.url);
        if (index != -1) {
            this.dependencies[ui.type].splice(index, 1);
        }
        this.remove(ui);
        this.dispatchEvent(new DependenciesChanged(this.dependencies));
    }

    private addDependency(prev: DependencyUI) {
        this.dependencies[prev.type].push(prev.url);
        this.add(new DependencyUI(prev.type, prev.url));
        this.dispatchEvent(new DependenciesChanged(this.dependencies));
    }
}

class DependenciesChanged extends UIEventBase<{dependencies: Record<string, string[]>}> {
    constructor(readonly dependencies: Record<string, string[]>) {
        super('change',{dependencies});
    }
}

type UIElementsOf<T> = {
    [P in keyof T]: TagElement<infer X>
}

abstract class UIOf<T, Child extends UIComponent> extends UIComponentContainer<Child> {
    abstract readonly uiElements: UIElementsOf<T>;

    constructor(private _value: T) {
        super();
        this.init();
    }

    private init() {
        for (let key of Object.keys(this.uiElements)) {
            const el = this.uiElements[key as keyof T];

            this.childContainer.appendChild(el);
        }
    }
}

class DependencyUI extends UIComponent {
    private dropdown: DropdownElement =
        dropdown(['dependency-type'], {scala: 'scala/jvm', python: 'pip'}, this._type)
            .change(_ => this.setType(this.dropdown.getSelectedValue()));

    private urlText: TagElement<'input'> =
        textbox(['dependency'], 'Dependency coordinate, URL, pip package', this._url)
            .change(_ => this.setUrl(this.urlText.value));

    readonly container: TagElement<"div"> = div(['dependency-row', 'notebook-config-row'], [
        this.dropdown,
        this.urlText,
        iconButton(['remove'], 'Remove', '', 'Remove').click(_ => this.dispatchEvent(new RemoveDependency())),
        iconButton(['add'], 'Add', '', 'Add').click(_ => this.dispatchEvent(new AddDependency()))
    ]);

    constructor(private _type: string, private _url: string) {
        super();
    }

    private setType(type: string) {
        const oldType = this._type;
        this._type = type;
        if (type !== oldType) {
            this.dispatchEvent(new DependencyChanged([oldType, this._url], [type, this._url]));
        }
    }

    private setUrl(url: string) {
        const oldUrl = this._url;
        this._url = url;
        if (url != oldUrl) {
            this.dispatchEvent(new DependencyChanged([this.type, oldUrl], [this.type, url]));
        }
    }

    get type(): string { return this._type }
    get url(): string { return this._url }

}

class DependencyChanged extends UIEventBase<{from: [string, string], to: [string, string]}> {
    constructor(readonly from: [string, string], readonly to: [string, string]) {
        super('change', {from, to});
    }
}

class AddDependency extends UIEventBase<{}> {
    constructor() {
        super('AddDependency');
    }
}

class RemoveDependency extends UIEventBase<{}> {
    constructor() {
        super('RemoveDependency');
    }
}

class ResolversUI extends UIComponentContainer<ResolverUI> {
    readonly childContainer: TagElement<"div"> = div(['resolver-list'], []);
    readonly container: TagElement<"div"> =  div(['notebook-resolvers', 'notebook-config-section'], [
        h3([], ['Resolvers']),
        para([], ['Specify any custom Ivy, Maven, or Pip repositories here.']),
        this.childContainer
    ]);

    constructor(private repositories: RepositoryConfig[]) {
        super();

    }
}

class ResolverUI extends UIComponent {
    readonly container: TagElement<"div"> = div()

    constructor(private repository: RepositoryConfig) {
        match(repository)
            .when(IvyRepository => )
    }


}
