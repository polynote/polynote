// BREAKOUT (nb_config.js)
import {UIEvent, UIEventTarget} from "../util/ui_event";
import {button, div, dropdown, h2, h3, iconButton, para, span, textbox} from "../util/tags";
import * as messages from "../../data/messages";

export class NotebookConfigUI extends UIEventTarget {
    constructor() {
        super();
        this.el = div(['notebook-config'], [
            h2(['config'], ['Configuration & dependencies']).click(evt => this.el.classList.toggle('open')),
            div(['content'], [
                div(['notebook-dependencies', 'notebook-config-section'], [
                    h3([], ['Dependencies']),
                    para([], ['You can provide Scala / JVM dependencies using  Maven coordinates , e.g. ', span(['pre'], ['org.myorg:package-name_2.11:1.0.1']), ', or URLs like ', span(['pre'], ['s3://path/to/my.jar'])]),
                    para([], ['You can also specify pip packages, e.g. ', span(['pre'], ['requests']), ', or with a version like ', span(['pre'], ['urllib3==1.25.3'])]),
                    this.dependencyContainer = div(['dependency-list'], [
                        this.dependencyRowTemplate = div(['dependency-row', 'notebook-config-row'], [
                            dropdown(['dependency-type'], {scala: 'scala/jvm', python: 'pip'}).change(evt => {
                                const self = evt.currentTarget;
                                const row = self.parentNode;
                                const value = self.options[self.selectedIndex].value;
                                row.className = 'dependency-row';
                                row.classList.add('notebook-config-row');
                                row.classList.add(value);
                            }),
                            textbox(['dependency'], 'Dependency coordinate, URL, pip package'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                const row = evt.currentTarget.parentNode;
                                this.addDependency(this.mkDependency(row));
                                this.dependencyRowTemplate.querySelector('.dependency').value = '';
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['notebook-resolvers', 'notebook-config-section'], [
                    h3([], ['Resolvers']),
                    para([], ['Specify any custom Ivy, Maven, or Pip repositories here.']),
                    this.resolverContainer = div(['resolver-list'], [
                        this.resolverRowTemplate = div(['resolver-row', 'notebook-config-row', 'ivy'], [
                            dropdown(['resolver-type'], {ivy: 'Ivy', maven: 'Maven', pip: 'Pip'}).change(evt => {
                                const self = evt.currentTarget;
                                const row = self.parentNode;
                                const value = self.options[self.selectedIndex].value;
                                row.className = 'resolver-row';
                                row.classList.add('notebook-config-row');
                                row.classList.add(value);
                            }),
                            textbox(['resolver-url'], 'Resolver URL or pattern'),
                            textbox(['resolver-artifact-pattern', 'ivy'], 'Artifact pattern (blank for default)'),
                            textbox(['resolver-metadata-pattern', 'ivy'], 'Metadata pattern (blank for default)'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                const row = evt.currentTarget.parentNode;
                                this.addResolver(this.mkResolver(row));
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['notebook-exclusions', 'notebook-config-section'], [
                    h3([], ['Exclusions']),
                    para([], ['[Scala only]: Specify organization:module coordinates for your exclusions, i.e. ', span(['pre'], ['org.myorg:package-name_2.11'])]),
                    this.exclusionContainer = div(['exclusion-list'], [
                        this.exclusionRowTemplate = div(['exclusion-row', 'notebook-config-row'], [
                            textbox(['exclusion'], 'Exclusion organization:name'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                this.addExclusion(evt.currentTarget.parentNode.querySelector('.exclusion').value);
                                this.exclusionRowTemplate.querySelector('.exclusion').value = '';
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['notebook-spark-config', 'notebook-config-section'], [
                    h3([], ['Spark Config']),
                    para([], ['Set Spark configuration for this notebook here. Please note that it is possible that your environment may override some of these settings at runtime :(']),
                    this.sparkConfigContainer = div(['spark-config-list'], [
                        this.sparkConfigRowTemplate = div(['spark-config-row', 'notebook-config-row'], [
                            textbox(['spark-config-key'], 'key'),
                            textbox(['spark-config-val'], 'value'),
                            iconButton(['add'], 'Add', '', 'Add').click(evt => {
                                this.addSparkConfig([
                                    evt.currentTarget.parentNode.querySelector('.spark-config-key').value.trim(),
                                    evt.currentTarget.parentNode.querySelector('.spark-config-val').value.trim()
                                ]);
                                this.sparkConfigRowTemplate.querySelector('.spark-config-key').value = '';
                                this.sparkConfigRowTemplate.querySelector('.spark-config-val').value = '';
                            }),
                            iconButton(['remove'], 'Remove', '', 'Remove')
                        ])
                    ])
                ]),
                div(['controls'], [
                    button(['save'], {}, ['Save & Restart']).click(evt => {
                        this.lastConfig = this.config;
                        this.el.classList.remove("open");
                        this.dispatchEvent(new UIEvent('UpdatedConfig', {config: this.config}));
                    }),
                    button(['cancel'], {}, ['Cancel']).click(evt => {
                        if (this.lastConfig) {
                            this.setConfig(this.lastConfig);
                        }
                        this.el.classList.remove("open");
                    })
                ])
            ])
        ]);
    }

    mkDependency(row) {
        const typeSelect = row.querySelector('.dependency-type');
        const type = typeSelect.options[typeSelect.selectedIndex].value;
        const dep = row.querySelector('.dependency').value;
        return [type, dep];
    }

    mkResolver(row) {
        const typeSelect = row.querySelector('.resolver-type');
        const type = typeSelect.options[typeSelect.selectedIndex].value;
        if (type === 'ivy') {
            return new messages.IvyRepository(
                row.querySelector('.resolver-url').value,
                row.querySelector('.resolver-artifact-pattern').value || null,
                row.querySelector('.resolver-metadata-pattern').value || null,
                null
            );
        } else if (type === 'maven') {
            return new messages.MavenRepository(
                row.querySelector('.resolver-url').value,
                null
            );
        } else if (type === 'pip') {
            return new messages.PipRepository(
                row.querySelector('.resolver-url').value
            );
        }
    }

    addDependency(dep) {
        const [type, value] = dep;
        const row = this.dependencyRowTemplate.cloneNode(true);
        row.querySelector('.dependency').value = value;

        const typeSelect = row.querySelector('.dependency-type');
        let idx = -1;
        [...typeSelect].forEach((option, i) => {
            if (option.value === type) {
                idx = i
            }
        });

        row.querySelector('.dependency-type').selectedIndex = idx;

        row.querySelector('.remove').addEventListener('click', evt => {
            row.innerHTML = '';
            row.parentNode.removeChild(row);
        });
        this.dependencyContainer.insertBefore(row, this.dependencyRowTemplate);
    }

    addExclusion(value) {
        const row = this.exclusionRowTemplate.cloneNode(true);
        row.querySelector('.exclusion').value = value;
        row.querySelector('.remove').addEventListener('click', evt => {
            row.innerHTML = '';
            row.parentNode.removeChild(row);
        });
        this.exclusionContainer.insertBefore(row, this.exclusionRowTemplate);
    }

    addResolver(value) {
        const row = this.resolverRowTemplate.cloneNode(true);
        row.querySelector('.resolver-url').value = value.base;

        const type = value.constructor.msgTypeId;

        if (value instanceof messages.IvyRepository) {
            row.querySelector('.resolver-artifact-pattern').value = value.artifactPattern || '';
            row.querySelector('.resolver-metadata-pattern').value = value.metadataPattern || '';
        }

        const typeSelect = row.querySelector('.resolver-type');
        typeSelect.selectedIndex = type;

        row.querySelector('.remove').addEventListener('click', evt => {
            row.innerHTML = '';
            row.parentNode.removeChild(row);
        });

        this.resolverContainer.insertBefore(row, this.resolverRowTemplate);
    }

    addSparkConfig(value) {
        const row = this.sparkConfigRowTemplate.cloneNode(true);
        row.querySelector('.spark-config-key').value = value[0] || '';
        row.querySelector('.spark-config-val').value = value[1] || '';
        row.querySelector('.remove').addEventListener('click', evt => {
            row.innerHTML = '';
            row.parentNode.removeChild(row);
        });
        this.sparkConfigContainer.insertBefore(row, this.sparkConfigRowTemplate);
    }

    clearConfig() {
        const containers = new Map([
            [this.dependencyContainer, this.dependencyRowTemplate],
            [this.exclusionContainer, this.exclusionRowTemplate],
            [this.resolverContainer, this.resolverRowTemplate],
            [this.sparkConfigContainer, this.sparkConfigRowTemplate]
        ]);

        for (const [container, template] of containers) {
            while (container.childNodes.length > 0) {
                container.removeChild(container.childNodes[0]);
            }
            container.appendChild(template);
            [...container.querySelectorAll('input')].forEach(input => input.value = '');
        }
    }

    setConfig(config) {
        this.lastConfig = config;
        this.clearConfig();

        if (config.dependencies) {
            for (const [lang, deps] of Object.entries(config.dependencies)) {
                for (const dep of deps) {
                    this.addDependency([lang, dep]);
                }
            }
        }

        if (config.exclusions) {
            for (const excl of config.exclusions) {
                this.addExclusion(excl);
            }
        }

        if (config.repositories) {
            for (const repository of config.repositories) {
                this.addResolver(repository);
            }
        }

        if (config.sparkConfig) {
            for (const entry of Object.entries(config.sparkConfig)) {
                this.addSparkConfig(entry);
            }
        }
    }

    get config() {
        const deps = {};
        const depInputs = this.dependencyContainer.querySelectorAll('.dependency-row');
        depInputs.forEach(row => {
            const [type, dep] = this.mkDependency(row);
            if (type && dep) {
                deps[type] = [...(deps[type] || []), dep];
            }
        });

        const exclusions = [];
        const exclusionInputs = this.exclusionContainer.querySelectorAll('.exclusion-row input');
        exclusionInputs.forEach(input => {
            if (input.value) exclusions.push(input.value);
        });

        const repos = [];
        const repoRows = this.resolverContainer.querySelectorAll('.resolver-row');
        repoRows.forEach(row => {
            const repository = this.mkResolver(row);
            if (repository.base) {
                repos.push(repository);
            }
        });

        const sparkConfig = {};
        const sparkConfigRows = this.sparkConfigContainer.querySelectorAll('.spark-config-row');
        sparkConfigRows.forEach(row => {
            const k = row.querySelector('.spark-config-key').value.trim();
            const v = row.querySelector('.spark-config-val').value.trim();
            if (k) sparkConfig[k] = v;
        });

        return new messages.NotebookConfig(
            deps,
            exclusions,
            repos,
            sparkConfig
        );
    }

}