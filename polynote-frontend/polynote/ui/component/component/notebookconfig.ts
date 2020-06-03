import {
    button,
    div,
    dropdown,
    DropdownElement,
    h2,
    h3,
    h4,
    iconButton,
    para,
    span,
    TagElement,
    textbox
} from "../../util/tags";
import {NotebookMessageDispatcher, UpdateConfig} from "../messaging/dispatcher";
import {StateHandler} from "../state/state_handler";
import {
    IvyRepository,
    MavenRepository,
    NotebookConfig,
    PipRepository,
    RepositoryConfig,
    SparkPropertySet
} from "../../../data/data";
import {KernelStatus} from "../state/kernel_state";
import {ServerStateHandler} from "../state/server_state";

export class NotebookConfigComponent {
    readonly el: TagElement<"div">;

    constructor(dispatcher: NotebookMessageDispatcher, stateHandler: StateHandler<NotebookConfig>, kernelStateHandler: StateHandler<KernelStatus>) {

        const dependencies = new Dependencies(stateHandler.view("dependencies"))
        const exclusions = new Exclusions(stateHandler.view("exclusions"))
        const resolvers = new Resolvers(stateHandler.view("repositories"))
        const spark = new SparkConf(stateHandler.view("sparkConfig"), stateHandler.view("sparkTemplate"), ServerStateHandler.get.view("sparkTemplates"))
        const env = new EnvConf(stateHandler.view("env"))

        const saveButton = button(['save'], {}, ['Save & Restart']).click(evt => {
            const conf = new NotebookConfig(dependencies.conf, exclusions.conf, resolvers.conf, spark.conf, spark.template, env.conf);
            this.el.classList.remove("open");
            dispatcher.dispatch(new UpdateConfig(conf));
        })

        this.el = div(['notebook-config'], [
            h2(['config'], ['Configuration & dependencies']).click(() => this.el.classList.toggle('open')),
            div(['content'], [
                dependencies.el,
                resolvers.el,
                exclusions.el,
                spark.el,
                env.el,
                div(['controls'], [
                    saveButton,
                    button(['cancel'], {}, ['Cancel']).click(evt => {
                        this.el.classList.remove("open");
                    })
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
        })
    }
}

class Dependencies {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;

    constructor(dependenciesHandler: StateHandler<Record<string, string[]> | undefined>) {

        this.el = div(['notebook-dependencies', 'notebook-config-section'], [
            h3([], ['Dependencies']),
            para([], ['You can provide Scala / JVM dependencies using  Maven coordinates , e.g. ', span(['pre'], ['org.myorg:package-name_2.11:1.0.1']), ', or URLs like ', span(['pre'], ['s3://path/to/my.jar'])]),
            para([], ['You can also specify pip packages, e.g. ', span(['pre'], ['requests']), ', or with a version like ', span(['pre'], ['urllib3==1.25.3'])]),
            this.container = div(['dependency-list'], [])
        ])

        const setDeps = (deps: Record<string, string[]> | undefined) => {
            this.container.innerHTML = "";

            if (deps && Object.keys(deps).length > 0) {
                Object.entries(deps).forEach(([lang, deps]) => {
                    deps.forEach(dep => {
                        this.addDep({lang, dep})
                    })
                })
            } else {
                this.addDep()
            }
        }
        setDeps(dependenciesHandler.getState())
        dependenciesHandler.addObserver(deps => setDeps(deps))
    }

    private defaultLang = "scala"; // TODO: make this configurable

    private addDep(item?: {lang: string, dep: string}) {
        const data = item ?? {lang: this.defaultLang, dep: ""}

        const type = dropdown(['dependency-type'], {scala: 'scala/jvm', python: 'pip'}, data.lang).change(evt => {
            row.classList.remove(data.lang);
            data.lang = type.options[type.selectedIndex].value;
            row.classList.add(data.lang);

        });

        const input = textbox(['dependency'], 'Dependency coordinate, URL, pip package', data.dep).change(evt => {
            data.dep = input.value
        });

        const remove = iconButton(['remove'], 'Remove', 'minus-circle-red', 'Remove').click(evt => {
            this.container.removeChild(row);
            if (this.container.children.length === 0) this.addDep()
        });

        const add = iconButton(['add'], 'Add', 'plus-circle', 'Add').click(evt => {
            this.addDep({...data})
        });

        const row = Object.assign(
            div(['dependency-row', 'notebook-config-row'], [type, input, remove, add]),
            { data })
        this.container.appendChild(row)
    }

    get conf(): Record<string, string[]> {
        return Array.from(this.container.children).reduce<Record<string, string[]>>((acc, row: HTMLDivElement & {data: { lang: string, dep: string }}) => {
            if (row.data.dep) acc[row.data.lang] = [...(acc[row.data.lang] || []), row.data.dep]
            return acc
        }, {})
    }
}

class Resolvers {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;

    constructor(resolversHandler: StateHandler<RepositoryConfig[] | undefined>) {

        this.el = div(['notebook-resolvers', 'notebook-config-section'], [
            h3([], ['Resolvers']),
            para([], ['Specify any custom Ivy, Maven, or Pip repositories here.']),
            this.container = div(['resolver-list'], [])
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

        setResolvers(resolversHandler.getState())
        resolversHandler.addObserver(resolvers => setResolvers(resolvers))
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
            data.url = input.value;
        })

        const pattern = textbox(['resolver-artifact-pattern', 'ivy'], 'Artifact pattern (blank for default)', data.pattern).change(() => {
            data.pattern = pattern.value
        })

        const metadata = textbox(['resolver-metadata-pattern', 'ivy'], 'Metadata pattern (blank for default)', data.metadata).change(() => {
            data.metadata = metadata.value
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
}

class Exclusions {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;

    constructor(exclusionsHandler: StateHandler<string[] | undefined>) {
        this.el = div(['notebook-exclusions', 'notebook-config-section'], [
            h3([], ['Exclusions']),
            para([], ['[Scala only]: Specify organization:module coordinates for your exclusions, i.e. ', span(['pre'], ['org.myorg:package-name_2.11'])]),
            this.container = div(['exclusion-list'], [])
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
        setExclusions(exclusionsHandler.getState())
        exclusionsHandler.addObserver(excl => setExclusions(excl))
    }

    private addExcl(item?: string) {
        const data = { exclusion: item ?? ""}

        const input = textbox(['exclusion'], 'Exclusion organization:name', data.exclusion).change(() => {
            data.exclusion = input.value
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

class SparkConf {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;
    private templateEl: DropdownElement;

    constructor(confHandler: StateHandler<Record<string, string> | undefined>, templateHandler: StateHandler<SparkPropertySet | undefined>, private allTemplatesHandler: StateHandler<SparkPropertySet[]>) {
        this.templateEl = dropdown([], Object.fromEntries([["", "None"]]), );
        this.container = div(['spark-config-list'], []);

        this.el = div(['notebook-spark-config', 'notebook-config-section'], [
            h3([], ['Spark Config']),
            para([], ['Set Spark configuration for this notebook here. Please note that it is possible that your environment may override some of these settings at runtime :(']),
            div([], [h4([], ['Spark template:']), this.templateEl, h4([], ['Spark properties:']), this.container])
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
        setConf(confHandler.getState())
        confHandler.addObserver(conf => setConf(conf))

        // populate the templates element.
        const updatedTemplates = (templates: SparkPropertySet[]) => {
            templates.forEach(tmpl => {
                this.templateEl.addValue(tmpl.name, tmpl.name)
            })
        }
        updatedTemplates(allTemplatesHandler.getState())
        allTemplatesHandler.addObserver(templates => updatedTemplates(templates))

        // watch for changes in the config's template
        const setTemplate = (template: SparkPropertySet | undefined) => {
            this.templateEl.setSelectedValue(template?.name ?? "")
        }
        setTemplate(templateHandler.getState())
        templateHandler.addObserver(template => setTemplate(template))
    }

    private addConf(item?: {key: string, val: string}) {
        const data = item ?? {key: "", val: ""}

        const key = textbox(['spark-config-key'], 'key', data.key).change(() => {
            data.key = key.value
        })

        const val = textbox(['spark-config-val'], 'val', data.val).change(() => {
            data.val = val.value
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
        return this.allTemplatesHandler.getState().find(tmpl => tmpl.name === name)
    }

}

class EnvConf {
    readonly el: TagElement<"div">;
    private container: TagElement<"div">;

    constructor(envHandler: StateHandler<Record<string, string> | undefined>) {
        this.el = div(['notebook-env', 'notebook-config-section'], [
            h3([], ['Environment Variables']),
            para([], ['Set environment variables here. Please note this is only supported when kernels are launched as a subprocess (default).']),
            this.container = div(['env-list'], [])
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
        setEnv(envHandler.getState())
        envHandler.addObserver(env => setEnv(env))
    }

    private addEnv(item?: {key: string, val: string}) {
        const data = item ?? {key: "", val: ""};

        const key = textbox(['env-key'], 'key', data.key).change(() => {
            data.key = key.value
        })

        const val = textbox(['env-val'], 'val', data.val).change(() => {
            data.val = val.value
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

    get conf() {
        return Array.from(this.container.children).reduce<Record<string, string>>((acc, row: HTMLDivElement & {data: {key: string, val: string}}) => {
            if (row.data.key) acc[row.data.key] = row.data.val
            return acc
        }, {})
    }
}