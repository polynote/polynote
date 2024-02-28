import {
    button,
    div,
    dropdown,
    DropdownElement,
    h2,
    h3,
    h4,
    helpIconButton,
    iconButton,
    para,
    span,
    tag,
    TagElement,
    textbox
} from "../../tags";
import {NotebookMessageDispatcher} from "../../../messaging/dispatcher";
import {Disposable, setProperty, setValue, StateHandler, StateView} from "../../../state";
import {
    IvyRepository,
    MavenRepository,
    NotebookConfig,
    PipRepository,
    RepositoryConfig,
    SparkPropertySet,
    WrappedResolver
} from "../../../data/data";
import {KernelStatusString} from "../../../data/messages";
import {NBConfig} from "../../../state/notebook_state";
import {joinQuotedArgs, parseQuotedArgs} from "../../../util/helpers";
import {ServerStateHandler} from "../../../state/server_state";
import {copyToClipboard} from "./cell";

export class NotebookConfigEl extends Disposable {
    readonly el: TagElement<"div">;
    private readonly stateHandler: StateHandler<NBConfig>;
    private pasteErrorMessage: TagElement<"p">

    constructor(dispatcher: NotebookMessageDispatcher, stateHandler: StateHandler<NBConfig>, kernelStateHandler: StateView<KernelStatusString>) {
        super()

        this.stateHandler = stateHandler;

        const configState = stateHandler.view("config");
        const dependencies = new Dependencies(configState.view("dependencies"), stateHandler);
        const exclusions = new Exclusions(configState.view("exclusions"), stateHandler);
        const resolvers = new Resolvers(configState.view("repositories"), stateHandler);
        const serverTemplatesHandler = ServerStateHandler.view("sparkTemplates").disposeWith(configState);
        const spark = new ScalaSparkConf(configState, serverTemplatesHandler, stateHandler);
        const kernel = new KernelConf(configState, stateHandler);

        const saveButton = button(['save'], {}, ['Save & Restart']).click(evt => {
            this.saveConfig(new NotebookConfig(dependencies.conf, exclusions.conf, resolvers.conf, spark.conf, spark.template, kernel.envVars, spark.scalaVersion, kernel.jvmArgs), true);
        })

        this.el = div(['notebook-config'], [
            h2(['config', 'help-text'], ['Configuration & dependencies']).click(() => stateHandler.updateField("open", open => setValue(!open))),
            helpIconButton([], "https://polynote.org/latest/docs/notebook-configuration/"),
            div(['content'], [
                dependencies.el,
                resolvers.el,
                exclusions.el,
                spark.el,
                kernel.el,
                div(['controls'], [
                    div([], [
                        saveButton,
                        button(['cancel'], {}, ['Cancel']).click(evt => {
                            stateHandler.updateField("open", () => setValue(false))
                        })
                    ]),
                    div([], [
                        button([], {}, ['Copy Configuration']).click(() => {
                            const conf = new NotebookConfig(dependencies.conf, exclusions.conf, resolvers.conf, spark.conf, spark.template, kernel.envVars, spark.scalaVersion, kernel.jvmArgs);
                            this.copyConfig(conf);
                        }),
                        button([], {}, ['Paste & Save Configuration']).click(() => this.pasteConfig()),
                        this.pasteErrorMessage = para(['hide', 'error-message'], ['Paste failed - your clipboard does not contain valid JSON'])
                    ])
                ])
            ])
        ]);

        kernelStateHandler.addObserver(state => {
            if (state === 'disconnected') {
                saveButton.disabled = true;
            } else {
                saveButton.disabled = false;
                if (state === 'dead') {
                    saveButton.textContent = "Save"
                } else {
                    saveButton.textContent = "Save & Restart"
                }
            }
        }).disposeWith(this)

        stateHandler.view("open").addObserver(open => {
            if (open) {
                this.el.classList.add("open")
            } else {
                this.pasteErrorMessage.classList.add('hide');
                this.el.classList.remove("open")
            }
        }).disposeWith(this)
    }

    private saveConfig(conf: NotebookConfig, closeConfigSection: boolean = false) {
        if (closeConfigSection)
            this.el.classList.remove("open");
        this.stateHandler.updateField("config", () => setValue(conf));
    }

    private copyConfig(conf: NotebookConfig) {
        copyToClipboard(JSON.stringify(conf));
    }

    private pasteConfig() {
        navigator.clipboard.readText().then(clipText => {
            let paste: Omit<NotebookConfig, "repositories"> & { repositories: [WrappedResolver] };
            let conf: NotebookConfig | undefined = undefined;

            try {
                paste = JSON.parse(clipText);
                let resolvers = Resolvers.parseWrappedResolvers(paste.repositories);
                conf = new NotebookConfig(paste.dependencies, paste.exclusions, resolvers, paste.sparkConfig, paste.sparkTemplate, paste.env, paste.scalaVersion, paste.jvmArgs);
                this.saveConfig(conf);
                this.pasteErrorMessage.classList.add('hide');
            } catch (e) {
                this.pasteErrorMessage.classList.remove('hide');
                console.error("Paste failed - the following clipboard value is not valid JSON:", paste!);
                console.error(e);
            }
        });
    }
}

type DepRow = HTMLDivElement & {data: { lang: string, dep: string, cache: boolean}}
const noCacheSentinel = "?nocache"
class Dependencies extends Disposable {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;

    constructor(dependenciesHandler: StateView<Record<string, string[]> | undefined>, stateHandler: StateHandler<NBConfig>) {
        super()

        this.el = div(['notebook-dependencies', 'notebook-config-section', 'open'], [
            h3([], ['Dependencies']).click(() => stateHandler.updateField('openDependencies', openDependencies => setValue(!openDependencies))),
            div(['notebook-config-section-content'], [
                para([], ['You can provide Scala / JVM dependencies using Maven coordinates, e.g. ', span(['pre'], ['org.myorg:package-name_2.11:1.0.1']), ', URLs like ', span(['pre'], ['s3://path/to/my.jar']), ', or absolute file paths by prepending ', span(['pre'], ['file:///']), ' to the path.']),
                para([], ['You can also specify pip packages, e.g. ', span(['pre'], ['requests']), ', or with a version like ', span(['pre'], ['urllib3==1.25.3'])]),
                this.container = div(['dependency-list'], [])
            ])
        ])

        const setDeps = (deps: Record<string, string[]> | undefined) => {
            this.container.innerHTML = "";

            if (deps && Object.keys(deps).length > 0) {
                Object.entries(deps).forEach(([lang, deps]) => {
                    if (deps.length === 0) {
                        this.addDep({lang, dep: "", cache: true}); // add an empty dependency for this language
                    }
                    deps.forEach(dep => {
                        if (dep.endsWith(noCacheSentinel)) {
                            this.addDep({lang, dep: dep.substr(0, dep.length - noCacheSentinel.length), cache: false})
                        } else {
                            this.addDep({lang, dep, cache: true})
                        }
                    })
                })
            } else {
                this.addDep()
            }
        }
        setDeps(dependenciesHandler.state)
        dependenciesHandler.addObserver(deps => setDeps(deps)).disposeWith(this)

        stateHandler.view('openDependencies').addObserver(open => {
            toggleConfigVisibility(open, this.el);
        }).disposeWith(this)
    }

    private defaultLang = "scala"; // TODO: make this configurable

    private addDep(item?: DepRow["data"]) {
        const data = item ?? {lang: this.defaultLang, dep: "", cache: true}

        const type = dropdown(['dependency-type'], {scala: 'scala/jvm', python: 'pip'}, data.lang).change(evt => {
            row.classList.remove(data.lang);
            data.lang = type.options[type.selectedIndex].value;
            row.classList.add(data.lang);

        });

        const input = textbox(['dependency'], 'Dependency coordinate, URL, pip package', data.dep).change(evt => {
            data.dep = input.value.trim()
        });

        const remove = iconButton(['remove'], 'Remove', 'minus-circle-red', 'Remove').click(evt => {
            this.container.removeChild(row);
            if (this.container.children.length === 0) this.addDep()
        });

        const cache = dropdown(['cache'], {cache: 'Cache', nocache: "Don't cache"}, data.cache ? 'cache' : 'nocache')
            .change(() => {
                data.cache = cache.options[cache.selectedIndex].value === "cache"
            })

        const detail = iconButton(['expand'], "Advanced Settings", 'triple-dots', "Advanced Settings").click(() => {
            row.classList.toggle("show-advanced")
        })

        const advanced = div(['advanced'], [
            div([], h3([], "Advanced Options")),
            div([], [
                para([], [
                    "Should Polynote use a cached version of this dependency, if available?",
                    " Applicable to URL or pip dependencies only."]),
                para([], ["Note that if any pip dependency bypasses the cache, the entire virtual environment will be recreated."]),
                cache
            ])
        ])

        const add = iconButton(['add'], 'Add', 'plus-circle', 'Add').click(evt => {
            this.addDep({...data})
        });

        const row = Object.assign(
            div(['dependency-row', 'notebook-config-row'], [type, input, detail, remove, add, advanced]),
            { data })
        this.container.appendChild(row)
    }

    get conf(): Record<string, string[]> {
        return Array.from(this.container.children).reduce<Record<string, string[]>>((acc, row: DepRow) => {
            if (row.data.dep) {
                if (row.data.cache) {
                    row.data.dep = row.data.dep.endsWith("?nocache") ? row.data.dep.substr(0, row.data.dep.length - "?nocache".length) : row.data.dep;
                } else {
                    row.data.dep = row.data.dep.endsWith("?nocache") ? row.data.dep : row.data.dep + "?nocache";
                }
                acc[row.data.lang] = [...(acc[row.data.lang] || []), row.data.dep]
            }
            return acc
        }, {})
    }
}

class Resolvers extends Disposable {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;

    constructor(resolversHandler: StateView<RepositoryConfig[] | undefined>, stateHandler: StateHandler<NBConfig>) {
        super()

        this.el = div(['notebook-resolvers', 'notebook-config-section', 'open'], [
            h3([], ['Resolvers']).click(() => stateHandler.updateField('openResolvers', openDependencies => setValue(!openDependencies))),,
            div(['notebook-config-section-content'], [
                para([], ['Specify any custom Ivy, Maven, or Pip repositories here.']),
                this.container = div(['resolver-list'], [])
            ])
        ])

        const setResolvers = (resolvers: RepositoryConfig[] | undefined) => {
            this.container.innerHTML = "";
            if (resolvers && resolvers.length > 0) {
                for (const resolver of resolvers) {
                    if (resolver instanceof IvyRepository) {
                        this.addRes({
                            type: "ivy",
                            url: resolver.url,
                            pattern: resolver.artifactPattern,
                            metadata: resolver.metadataPattern});
                    } else if (resolver instanceof MavenRepository) {
                        this.addRes({
                            type: "maven",
                            url: resolver.url})
                    } else if (resolver instanceof PipRepository) {
                        this.addRes({
                            type: "pip",
                            url: resolver.url})
                    } else {
                        throw new Error(`Unknown repository type! Don't know what to do with ${JSON.stringify(resolver)}`)
                    }
                }
            } else {
                this.addRes()// prepopulate a blank one
            }
        }

        setResolvers(resolversHandler.state)
        resolversHandler.addObserver(resolvers => setResolvers(resolvers)).disposeWith(this)

        stateHandler.view('openResolvers').addObserver(open => {
            toggleConfigVisibility(open, this.el);
        }).disposeWith(this)
    }

    private defaultRes = "ivy"; // TODO: make this configurable

    private addRes(item?: { type: string, url: string, metadata?: string, pattern?: string }) {
        const data = item ?? {type: this.defaultRes, url: ""}

        const type = dropdown(['resolver-type'], {ivy: 'Ivy', maven: 'Maven', pip: 'Pip'}, data.type).change(evt => {
            row.classList.remove(data.type);
            data.type = type.options[type.selectedIndex].value;
            row.classList.add(data.type);
        }) as DropdownElement

        const input = textbox(['resolver-url'], 'Resolver URL or pattern', data.url).change(() => {
            data.url = input.value.trim();
        })

        const pattern = textbox(['resolver-artifact-pattern', 'ivy'], 'Artifact pattern (blank for default)', data.pattern).change(() => {
            data.pattern = pattern.value.trim()
        })

        const metadata = textbox(['resolver-metadata-pattern', 'ivy'], 'Metadata pattern (blank for default)', data.metadata).change(() => {
            data.metadata = metadata.value.trim()
        })

        const remove = iconButton(['remove'], 'Remove', 'minus-circle-red', 'Remove').click(evt => {
            this.container.removeChild(row);
            if (this.container.children.length === 0) this.addRes()
        })

        const add = iconButton(['add'], 'Add', 'plus-circle', 'Add').click(evt => {
            this.addRes({...data})
        })

        const row = Object.assign(
            div(['resolver-row', 'notebook-config-row', data.type], [type, input, pattern, metadata, remove, add]),
            { data })

        this.container.appendChild(row)
    }

    get conf(): RepositoryConfig[] {
        return Array.from(this.container.children).flatMap((row: HTMLDivElement & {data: { type: string, url: string, metadata?: string, pattern?: string }}) => {
            const res = row.data;
            if (res.url) {
                let repo;
                switch (res.type) {
                    case "ivy":
                        repo = new IvyRepository(res.url, res.pattern, res.metadata);
                        break;
                    case "maven":
                        repo = new MavenRepository(res.url);
                        break;
                    case "pip":
                        repo = new PipRepository(res.url);
                        break;
                    default:
                        throw new Error(`Unknown repository type! Don't know what to do with ${res.type}`)
                }
                return [repo]
            } else { return [] }
        });
    }

    static parseWrappedResolvers(wrappedResolvers: WrappedResolver[]): RepositoryConfig[] {
        return wrappedResolvers.map((wrappedResolver: WrappedResolver) => {
            let resolver;
            if (wrappedResolver.type === "ivy") {
                resolver = wrappedResolver.resolver as IvyRepository;
                return new IvyRepository(resolver.url, resolver.artifactPattern, resolver.metadataPattern, resolver.changing);
            }
            else if (wrappedResolver.type === "maven") {
                resolver = wrappedResolver.resolver as MavenRepository;
                return new MavenRepository(resolver.url, resolver.changing);
            }
            else if (wrappedResolver.type == "pip") {
                resolver = wrappedResolver.resolver as PipRepository;
                return new PipRepository(resolver.url);
            }
            else {
                throw new Error(`Unknown repository type! Don't know what to do with ${JSON.stringify(wrappedResolver)}`)
            }
        });
    }
}

class Exclusions extends Disposable {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;

    constructor(exclusionsHandler: StateView<string[] | undefined>, stateHandler: StateHandler<NBConfig>) {
        super()

        this.el = div(['notebook-exclusions', 'notebook-config-section', 'open'], [
            h3([], ['Exclusions']).click(() => stateHandler.updateField('openExclusions', openDependencies => setValue(!openDependencies))),,
            div(['notebook-config-section-content'], [
                para([], ['[Scala only]: Specify organization:module coordinates for your exclusions, i.e. ', span(['pre'], ['org.myorg:package-name_2.11'])]),
                this.container = div(['exclusion-list'], [])
            ])
        ])

        const setExclusions = (exclusions: string[] | undefined) => {
            this.container.innerHTML = "";

            if (exclusions && exclusions.length > 0) {
                exclusions.forEach(excl => {
                    this.addExcl(excl)
                })
            } else {
                this.addExcl()
            }
        }
        setExclusions(exclusionsHandler.state)
        exclusionsHandler.addObserver(excl => setExclusions(excl)).disposeWith(this)

        stateHandler.view('openExclusions').addObserver(open => {
            toggleConfigVisibility(open, this.el);
        }).disposeWith(this)
    }

    private addExcl(item?: string) {
        const data = { exclusion: item ?? ""}

        const input = textbox(['exclusion'], 'Exclusion organization:name', data.exclusion).change(() => {
            data.exclusion = input.value.trim()
        })
        const remove = iconButton(['remove'], 'Remove', 'minus-circle-red', 'Remove').click(evt => {
            this.container.removeChild(row);
            if (this.container.children.length === 0) this.addExcl()
        })
        const add = iconButton(['add'], 'Add', 'plus-circle', 'Add').click(evt => {
            this.addExcl()
        })

        const row = Object.assign(
            div(['exclusion-row', 'notebook-config-row'], [input, remove, add]),
            {data})

        this.container.appendChild(row)
    }

    get conf(): string[] {
        return Array.from(this.container.children).flatMap((row: HTMLDivElement & {data: {exclusion: string}}) => {
            if (row.data.exclusion) return [row.data.exclusion]
            else return []
        })
    }
}

class ScalaSparkConf extends Disposable {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;
    private templateEl: DropdownElement;
    private scalaVersionInput: DropdownElement;
    private notebookSparkTemplates: SparkPropertySet[]; // keep our own state to handle templates that don't exist on the server

    constructor(configState: StateView<NotebookConfig>, private allTemplatesHandler: StateView<SparkPropertySet[]>, stateHandler: StateHandler<NBConfig>) {
        super()

        this.templateEl = dropdown([], Object.fromEntries([["", "None"]]), );
        this.container = div(['spark-config-list'], []);
        this.notebookSparkTemplates = [];

        const sparkConfHandler = configState.view("sparkConfig");
        const templateHandler = configState.view("sparkTemplate");
        const scalaVersionHandler = configState.view("scalaVersion");

        // TODO: this could come from the server
        const availableScalaVersions = [
            {key: "2.11", value: "2.11"},
            {key: "2.12", value: "2.12"},
            {key: "2.13", value: "2.13"}
        ];

        this.el = div(['notebook-spark-config', 'notebook-config-section', 'open'], [
            h3([], ['Scala and Spark configuration']).click(() => stateHandler.updateField('openSpark', openDependencies => setValue(!openDependencies))),,
            div(['notebook-config-section-content'], [
                para([], ['Set the Scala and Spark configuration for this notebook here. Please note that it is possible that your environment may override some of these settings at runtime :(']),
                div(['notebook-config-row'], [h4([], ['Spark template:']), this.templateEl, h4([], ['Spark properties:']), this.container]),
                h4([], 'Scala version:'),
                para([], ['If you have selected a Spark template, this will read from the associated versionConfigs key to display compatible Scala versions.']),
                this.scalaVersionInput = dropdown(['scala-version'], {}),
            ])
        ])

        const setConf = (conf: Record<string, string> | undefined) => {
            this.container.innerHTML = "";

            if (conf && Object.keys(conf).length > 0) {
                Object.entries(conf).forEach(([key, val]) => {
                    this.addConf({key, val})
                })
            } else {
                this.addConf()
            }
        }
        setConf(sparkConfHandler.state)
        sparkConfHandler.addObserver(conf => setConf(conf)).disposeWith(this)

        // populate the templates element.
        const updatedTemplates = (templates: SparkPropertySet[]) => {
            this.notebookSparkTemplates = this.notebookSparkTemplates.concat(templates);
            templates.forEach(tmpl => {
                this.templateEl.addValue(tmpl.name, tmpl.name)
            })
        }
        updatedTemplates(allTemplatesHandler.state)
        allTemplatesHandler.addObserver(templates => updatedTemplates(templates)).disposeWith(this)

        // watch for changes in the config's template
        const setTemplate = (template: SparkPropertySet | undefined) => {
            if (template && !this.notebookSparkTemplates.some(el => el.name === template.name)) {
                // if we don't recognize the template defined in the config, add it to this notebook only
                this.templateEl.addValue(template.name, template.name);
                this.notebookSparkTemplates.push(template);
            }
            this.templateEl.setSelectedValue(template?.name ?? "")
        }
        setTemplate(templateHandler.state)
        templateHandler.addObserver(template => {
            setTemplate(template);
            populateScalaVersions(template); // update displayed Scala versions
        }).disposeWith(this)

        stateHandler.view('openSpark').addObserver(open => {
            toggleConfigVisibility(open, this.el);
        }).disposeWith(this)

        // list the Scala versions coming from the version configurations associated with the selected Spark template
        const populateScalaVersions = (selectedTemplate: SparkPropertySet | undefined) => {
            this.scalaVersionInput.clearValues();
            const scalaVersions = (selectedTemplate?.versionConfigs) ?
              selectedTemplate?.versionConfigs.map(versionConfig => ({key: versionConfig.versionName, value: versionConfig.versionName})) :
              [{key: "Default", value: "Default"}, ...availableScalaVersions];
            scalaVersions.forEach(version => this.scalaVersionInput.addValue(version.key, version.value));
            this.scalaVersionInput.setSelectedValue(scalaVersionHandler.state || "");
        }

        // update displayed Scala versions when a different Spark template is selected
        this.templateEl.onSelect((newValue) => {
            const newSparkTemplate = this.notebookSparkTemplates.find(tmpl => tmpl.name === newValue);
            populateScalaVersions(newSparkTemplate);
        });

        scalaVersionHandler.addObserver(version => {
            this.scalaVersionInput.setSelectedValue(version || "")
        }).disposeWith(this);
    }

    private addConf(item?: {key: string, val: string}) {
        const data = item ?? {key: "", val: ""}

        const key = textbox(['spark-config-key'], 'key', data.key).change(() => {
            data.key = key.value.trim()
        })

        const val = textbox(['spark-config-val'], 'val', data.val).change(() => {
            data.val = val.value.trim()
        })

        const remove = iconButton(['remove'], 'Remove', 'minus-circle-red', 'Remove').click(evt => {
            this.container.removeChild(row);
            if (this.container.children.length === 0) this.addConf()
        })

        const add = iconButton(['add'], 'Add', 'plus-circle', 'Add').click(evt => {
            this.addConf()
        })

        const row = Object.assign(
            div(['exclusion-row', 'notebook-config-row'], [key, val, remove, add]),
            { data });
        this.container.appendChild(row)
    }

    get conf(): Record<string, string> {
        return Array.from(this.container.children).reduce<Record<string, string>>((acc, row: HTMLDivElement & {data: {key: string, val: string}}) => {
            if (row.data.key) acc[row.data.key] = row.data.val
            return acc
        }, {})
    }

    get template(): SparkPropertySet | undefined {
        const name = this.templateEl.options[this.templateEl.selectedIndex].value;
        return this.notebookSparkTemplates.find(tmpl => tmpl.name === name);
    }

    get scalaVersion(): string | undefined {
        return this.scalaVersionInput.getSelectedValue() || undefined;
    }

}

class KernelConf extends Disposable {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;
    private jvmArgsInput: TagElement<"input">;

    constructor(configState: StateView<NotebookConfig>, stateHandler: StateHandler<NBConfig>) {
        super();
        const envHandler = configState.view("env");
        const jvmArgsHandler = configState.view("jvmArgs");

        this.el = div(['notebook-env', 'notebook-config-section', 'open'], [
            h3([], ['Kernel configuration']).click(() => stateHandler.updateField('openKernel', openDependencies => setValue(!openDependencies))),,
            div(['notebook-config-section-content'], [
                para([], ['Please note this is only supported when kernels are launched as a subprocess (default).']),
                h4([], 'Environment variables:'),
                this.container = div(['env-list'], []),
                h4([], ['Additional JVM arguments:']),
                para([], ['Extra command-line arguments to the JVM, e.g. ', tag('code', [], {}, '"-Dmy.prop=a value" -Xmx200m')]),
                this.jvmArgsInput = textbox(['jvm-args'], "JVM Arguments", joinQuotedArgs(jvmArgsHandler.state)).attr("size", "64")
            ])
        ])

        const setEnv = (env: Record<string, string> | undefined) => {
            this.container.innerHTML = ""

            if (env && Object.keys(env).length > 0) {
                Object.entries(env).forEach(([key, val]) => {
                    this.addEnv({key, val})
                })
            } else {
                this.addEnv()
            }
        }
        setEnv(envHandler.state);
        envHandler.addObserver(env => setEnv(env));
        jvmArgsHandler.addObserver(strs => this.jvmArgsInput.value = joinQuotedArgs(strs) || "");

        stateHandler.view('openKernel').addObserver(open => {
            toggleConfigVisibility(open, this.el);
        }).disposeWith(this)
    }

    private addEnv(item?: {key: string, val: string}) {
        const data = item ?? {key: "", val: ""};

        const key = textbox(['env-key'], 'key', data.key).change(() => {
            data.key = key.value.trim()
        })

        const val = textbox(['env-val'], 'val', data.val).change(() => {
            data.val = val.value.trim()
        })

        const remove = iconButton(['remove'], 'Remove', 'minus-circle-red', 'Remove').click(evt => {
            this.container.removeChild(row);
            if (this.container.children.length === 0) this.addEnv()
        })

        const add = iconButton(['add'], 'Add', 'plus-circle', 'Add').click(evt => {
            this.addEnv()
        })

        const row = Object.assign(
            div(['exclusion-row', 'notebook-config-row'], [key, val, remove, add]),
            { data })
        this.container.appendChild(row)
    }

    get envVars() {
        return Array.from(this.container.children).reduce<Record<string, string>>((acc, row: HTMLDivElement & {data: {key: string, val: string}}) => {
            if (row.data.key) acc[row.data.key] = row.data.val
            return acc
        }, {})
    }

    get jvmArgs(): string[] | undefined {
        if (this.jvmArgsInput.value) {
            return parseQuotedArgs(this.jvmArgsInput.value);
        }
        return undefined;
    }
}

function toggleConfigVisibility(open: boolean, el: HTMLDivElement) {
    if (open) {
        el.classList.add('open')
    } else {
        el.classList.remove('open')
    }
}
