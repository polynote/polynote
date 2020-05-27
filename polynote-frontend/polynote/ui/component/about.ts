"use strict";

import {AllowedElAttrs, button, div, dropdown, h2, h3, iconButton, polynoteLogo, span, table, tag} from "../util/tags";
import {FullScreenModal} from "./modal";
import {TabNav} from "./tab_nav";
import {getHotkeys} from "../util/hotkeys";
import {preferences, storage} from "../util/storage";
import * as monaco from "monaco-editor";
import {KernelCommand, LoadNotebook, RunningKernels, ServerVersion, UIMessageRequest} from "../util/ui_event";
import {KernelBusyState} from "../../data/messages";
import {ClientBackup} from "./client_backup";

export class About extends FullScreenModal {
    readonly storageUpdateListeners: string[];
    constructor() {
        super(
            div([], []),
            { windowClasses: ['about'] }
        );

        this.storageUpdateListeners = [];
    }

    aboutMain() {
        const el = div(["about-display"], [
            div([], [
                polynoteLogo(),
                h2([], ["About this Polynote Server"])
            ])
        ]);

        this.publish(new UIMessageRequest(ServerVersion, (version, commit) => {
            const info = [
                ["Server Version", version],
                ["Server Commit", commit]
            ];
            const tableEl = table(['server-info'], {
                classes: ['key', 'val'],
                rowHeading: false,
                addToTop: false
            });
            for (const [k, v] of info) {
                tableEl.addRow({
                    key: k.toString(),
                    val: v.toString()
                })
            }

            el.appendChild(tableEl);
        }));
        return el;
    }

    hotkeys() {
        const hotkeys = getHotkeys();
        const el = div(["hotkeys-display"], [
            div([], [
                h2([], ["Press these buttons to do things"])
            ])
        ]);

        for (const [context, kvs] of Object.entries(hotkeys)) {
            el.appendChild(h3([], [context]));
            const tableEl = table([], {
                classes: ['key', 'val'],
                rowHeading: false,
                addToTop: false
            });
            for (const [k, v] of Object.entries(kvs)) {
                tableEl.addRow({
                    key: k,
                    val: JSON.stringify(v)
                })
            }
            el.appendChild(tableEl);
        }

        return el;
    }

    preferences() {
        let storageInfoEl, preferencesEl;
        const el = div(["preferences-storage"], [
            div([], [
                h2([], ["UI Preferences and Storage"]),
                span([], ["The Polynote UI keeps some information in your browser's Local Storage, including some preferences you can configure yourself. "]),
                tag('br'),
                button(['clear', 'about-button'], {}, ['Clear All Preferences and Storage'])
                    .click(() => {
                        storage.clear();
                        location.reload();
                    }),
                tag('br'),
                h3([], ["Preferences"]),
                preferencesEl = div(['preferences'], []),
                h3([], ["Storage"]),
                span([], ["Here's everything Polynote is storing in local storage"]),
                storageInfoEl = div(['storage'], [])
            ])
        ]);

        const prefsTable = table([], {
            classes: ['key', 'val', 'desc'],
            rowHeading: false,
            addToTop: false
        });

        for (const [k, preference] of Object.entries(preferences.show())) {
            const value = preference.value;
            let valueEl;
            const prefOptions = preferences.getOptions(k);
            if (prefOptions) {
                const options = Object.entries(prefOptions).map(
                    ([label, optValue]) => {
                        const attributes: AllowedElAttrs<HTMLOptionElement> = {value: optValue.toString()};
                        if (optValue === value) {
                            attributes.selected = 'selected';
                        }
                        return tag("option", [], attributes, [label]).withKey("valueData", optValue)
                    }
                );

                valueEl = tag('select', [], {}, options).change(evt => {
                    const self = evt.currentTarget;
                    if (! self || ! (self instanceof HTMLSelectElement)) {
                        throw new Error(`Unexpected Event target for event ${JSON.stringify(evt)}! Expected \`currentTarget\` to be an HTMLSelectElement but instead got ${JSON.stringify(self)}`)
                    }
                    const option = self.options[self.selectedIndex];
                    let value: any = option.value;
                    if (typeof (option as any).valueData !== 'undefined') {
                        value = (option as any).valueData;
                    }
                    preferences.set(k, value);
                })
            }
            prefsTable.addRow({
                key: k,
                val: valueEl ?? value.toString(),
                desc: preference.description
            })
        }
        preferencesEl.appendChild(prefsTable);

        const storageTable = table([], {
            classes: ['key', 'val'],
            rowHeading: false,
            addToTop: false
        });

        for (const [k, v] of Object.entries(storage.show())) {
            const valueEl = div(['json'], []);

            const setValueEl = (value: any) => {
                monaco.editor.colorize(value, "json", {}).then(function(result) {
                    valueEl.innerHTML = result;
                });
            };
            setValueEl(v);

            storage.addStorageListener(k, (oldVal, newVal) => {
                if (newVal) setValueEl(JSON.stringify(newVal));
            });

            this.storageUpdateListeners.push(k);

            storageTable.addRow({
                key: k,
                val: valueEl
            })
        }
        storageInfoEl.appendChild(storageTable);

        return el;
    }

    runningKernels() {
        let content = div([], ['Looks like no kernels are running now!']);
        const el = div(["running-kernels"], [
            div([], [
                h2([], ["Running Kernels"]),
                content
            ])
        ]);

        const getKernelStatuses = () => {
            this.publish(new UIMessageRequest(RunningKernels, statuses => {
                const tableEl = table(['kernels'], {
                    header: ['path', 'status', 'actions'],
                    classes: ['path', 'status', 'actions'],
                    rowHeading: false,
                    addToTop: false
                });

                for (const path in statuses) {
                    const status = statuses[path];
                    if (status instanceof KernelBusyState) {
                        const state = (status.busy && 'busy') || (!status.alive && 'dead') || 'idle';
                        const statusEl = span([], [
                            span(['status'], [state]),
                        ]);
                        const actionsEl = div([], [
                            iconButton(['start'], 'Start kernel', 'power-off', 'Start').click(() => {
                                this.publish(new KernelCommand(path, 'start'));
                                getKernelStatuses();
                                setTimeout(() => getKernelStatuses(), 5000); // TODO: this should be event-driven!
                            }),
                            iconButton(['kill'], 'Kill kernel', 'skull', 'Kill').click(() => {
                                this.publish(new KernelCommand(path, 'kill'));
                                getKernelStatuses();
                                setTimeout(() => getKernelStatuses(), 5000);
                            }),
                            iconButton(['open'], 'Open notebook', 'external-link-alt', 'Open').click(() => {
                                this.publish(new LoadNotebook(path));
                                this.hide();
                            })
                        ]);

                        const rowEl = tableEl.addRow({
                            path: path,
                            status: statusEl,
                            actions: actionsEl
                        });
                        rowEl.classList.add('kernel-status', state)
                    }
                }

                if (Object.values(statuses).length > 0) content.replaceChild(tableEl, content.firstChild!);
            }));
        };
        getKernelStatuses();

        return el;
    }

    clientBackups() {
        let backupInfoEl;
        const el = div(["client-backups"], [
            div([], [
                h2([], ["Client-side Backups"]),
                span([], ["Polynote stores backups of your notebooks in your browser. These backups are intended to be " +
                "used as a last resort, in case something happened to the physical files on disk. This is not intended " +
                "to replace a proper version history feature which may be implemented in the future. Your browser may " +
                "chose to delete these backups at any time!"]),
                tag('br'),
                button(['about-button'], {}, ['Print Backups to JS Console'])
                    .click(() => {
                        ClientBackup.allBackups()
                            .then(backups => console.log("Here are all the currently stored backups", backups))
                            .catch(err => console.error("Error while fetching backups!", err));
                    }),
                button(['clear', 'about-button'], {}, ['Clear all backups'])
                    .click(() => {
                        if (confirm("Are you sure you want to clear all the backups? You can't undo this!")) {
                            ClientBackup.clearBackups()
                                .then(backups => console.log("Cleared backups. Here they are one last time.", backups))
                                .catch(err => console.error("Error while clearing backups!", err));
                        }
                    }),
                tag('br'),
                h3([], ["Backups"]),
                span([], ["Here are all the backups:"]),
                backupInfoEl = div(['storage'], [])
            ])
        ]);

        const backupsTable = table([], {
            classes: ['path', 'ts', 'backup'],
            rowHeading: false,
            addToTop: false
        });

        ClientBackup.allBackups()
            .then(backups => {
                for (const [k, v] of Object.entries(backups)) {

                    Object.entries(v.backups)
                        .sort(([ts1, _], [ts2, __]) => parseInt(ts2) - parseInt(ts1))
                        .forEach(([ts, backups]) => {
                            backups.sort((b1, b2) => b2.ts - b1.ts).forEach(backup => {
                                const valueEl = div(['json'], []);

                                const backupsJson = JSON.stringify(backup, (k, v) => typeof v === 'bigint' ? v.toString : v);

                                monaco.editor.colorize(backupsJson, "json", {}).then(function(result) {
                                    valueEl.innerHTML = result;
                                });

                                backupsTable.addRow({
                                    path: k,
                                    ts: new Date(backup.ts).toLocaleString(),
                                    backup: valueEl
                                })
                            })
                        })
                }
            });

        backupInfoEl.appendChild(backupsTable);

        return el;
    }


    show(section?: string) {
        const tabs = {
            'About': this.aboutMain.bind(this),
            'Hotkeys': this.hotkeys.bind(this),
            'Preferences': this.preferences.bind(this),
            'Running Kernels': this.runningKernels.bind(this),
            'Client-side Backups': this.clientBackups.bind(this),
        };
        const tabnav = new TabNav(tabs);
        this.content.replaceChild(tabnav.el, this.content.firstChild!);
        if (section) tabnav.showItem(section);

        super.show();
    }

    hide() {
        this.storageUpdateListeners.forEach(x => storage.clearStorageListener(x));
        super.hide()
    }
}
