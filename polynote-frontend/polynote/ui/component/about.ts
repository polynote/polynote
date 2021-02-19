import {FullScreenModal} from "../layout/modal";
import {button, div, dropdown, h2, h3, iconButton, loader, polynoteLogo, span, table, tag, TagElement} from "../tags";
import * as monaco from "monaco-editor";
import {
    Disposable,
    IDisposable, setProperty,
    StateView,
} from "../../state";
import {ServerMessageDispatcher,} from "../../messaging/dispatcher";
import {TabNav} from "../layout/tab_nav";
import {getHotkeys} from "../input/hotkeys";
import {ServerStateHandler} from "../../state/server_state";
import {
    clearStorage,
    LocalStorageHandler, NotebookScrollLocationsHandler, OpenNotebooksHandler,
    RecentNotebooksHandler,
    UserPreferencesHandler, ViewPrefsHandler
} from "../../state/preferences";
import {ClientBackup} from "../../state/client_backup";

export class About extends FullScreenModal implements IDisposable {
    private disposable: Disposable;
    private constructor(private serverMessageDispatcher: ServerMessageDispatcher) {
        super(
            div([], []),
            { windowClasses: ['about'] }
        );
    }

    private static inst: About;
    private static get(dispatcher: ServerMessageDispatcher) {
        if (!About.inst || About.inst.serverMessageDispatcher !== dispatcher) {
            About.inst = new About(dispatcher);
        }
        return About.inst
    }

    static show(dispatcher: ServerMessageDispatcher, section?: string) {
        About.get(dispatcher).show(section)
    }

    aboutMain() {
        const el = div(["about-display"], [
            div([], [
                polynoteLogo(),
                h2([], ["About this Polynote Server"])
            ])
        ]);

        const {serverVersion, serverCommit} = ServerStateHandler.state
        const info = [
            ["Server Version", serverVersion],
            ["Server Commit", serverCommit]
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
        return el;
    }

    hotkeys() {
        const el = div(["hotkeys-display"], [
            div([], [
                h2([], ["Press these buttons to do things"])
            ])
        ]);

        const tableEl = table([], {
            classes: ['key', 'desc'],
            rowHeading: false,
            addToTop: false
        });

        Object.entries(getHotkeys()).forEach(([key, desc]) => {
            tableEl.addRow({
                key,
                desc
            })
        })

        el.appendChild(tableEl);
        return el;
    }

    preferences() {
        let storageInfoEl, preferencesEl;
        const el = div(["preferences-storage"], [
            div([], [
                h2([], ["UI Preferences and Storage"]),
                span([], ["The Polynote UI keeps some information in your browser's Local Storage, including some preferences you can configure yourself."]),
                tag('br'),
                button(['clear', 'about-button'], {}, ['Clear All Preferences and Storage'])
                    .click(() => {
                        clearStorage();
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

        Object.entries(UserPreferencesHandler.state).forEach(([key, pref]) => {
            const value = pref.value;
            let valueEl;
            const options = Object.entries(pref.possibleValues).reduce<Record<string, string>>((acc, [k, v]) => {
                acc[k] = v.toString()
                return acc
            }, {})
            valueEl = dropdown([], options).change(evt => {
                const self = evt.currentTarget;
                if (! self || ! (self instanceof HTMLSelectElement)) {
                    throw new Error(`Unexpected Event target for event ${JSON.stringify(evt)}! Expected \`currentTarget\` to be an HTMLSelectElement but instead got ${JSON.stringify(self)}`)
                }
                const updatedValue = pref.possibleValues[self.options[self.selectedIndex].value];
                UserPreferencesHandler.updateField(key, () => setProperty("value", updatedValue))
            });
            valueEl.value = value.toString();
            prefsTable.addRow({
                key,
                val: valueEl ?? value.toString(),
                desc: pref.description
            })
        })

        preferencesEl.appendChild(prefsTable);

        const storageTable = table([], {
            classes: ['key', 'val', 'clear'],
            rowHeading: false,
            addToTop: false
        });

        const addStorageEl = <T extends object>(storageHandler: LocalStorageHandler<T>) => {
            const key = storageHandler.key;
            const handler = storageHandler.fork(this);
            const valueEl = div(['json'], []);

            const setValueEl = (value: any) => {
                monaco.editor.colorize(JSON.stringify(value), "json", {}).then(function(result) {
                    valueEl.innerHTML = result;
                });
            };
            setValueEl(handler.state);

            handler.addObserver(next => {
                setValueEl(next)
            })

            const clearEl = iconButton(["clear"], `Clear ${key}`, "trash-alt", "Clear")
                .click(() => storageHandler.clear())

            storageTable.addRow({
                key: key,
                val: valueEl,
                clear: clearEl
            })
        }

        addStorageEl(UserPreferencesHandler)
        addStorageEl(RecentNotebooksHandler)
        addStorageEl(NotebookScrollLocationsHandler)
        addStorageEl(OpenNotebooksHandler)
        addStorageEl(ViewPrefsHandler)

        storageInfoEl.appendChild(storageTable);

        return el;
    }

    stateInspector() {
        let stateEl: TagElement<"div", HTMLDivElement>;
        const el = div(["state-inspector"], [
            div([], [
                h2([], ["See the UI's current state"]),
                span([], ["Inspect the current state of the Polynote UI. Mostly useful for debugging purposes."]),
                tag('br'),
                h3([], ["State Inspector"]),
                stateEl = div(['state'], []),
            ])
        ]);

        const stateTable = table([], {
            classes: ['key', 'val'],
            rowHeading: false,
            addToTop: false
        });

        const showState = <T>(key: string, handler: StateView<T>) => {
            const valueEl = div(['json', 'loading'], []);

            const setValueEl = (value: any) => {
                monaco.editor.colorize(JSON.stringify(value, (k, v) => typeof v === 'bigint' ? v.toString : v, 1), "json", {}).then(function(result) {
                    valueEl.innerHTML = result;
                    valueEl.classList.remove('loading')
                });
            };
            setTimeout(() => {
                setValueEl(handler.state);
            }, 0)

            handler.addObserver(next => {
                setValueEl(next)
            }).disposeWith(this)

            stateTable.addRow({
                key,
                val: valueEl,
            })
        }

        showState("Server State", ServerStateHandler.get)
        Object.keys(ServerStateHandler.get.state.notebooks).forEach(path => {
            const maybeNbInfo = ServerStateHandler.getNotebook(path)
            if (maybeNbInfo) {
                showState(path, maybeNbInfo.handler)
            }
        })

        stateEl.appendChild(stateTable)

        return el;
    }

    openKernels() {
        let content = div([], ['Looks like no kernels are open now!']);
        const el = div(["open-kernels"], [
            div([], [
                h2([], ["Open Kernels"]),
                content
            ])
        ]);

        const onNotebookUpdate = () => {

            const tableEl = table(['kernels'], {
                header: ['path', 'status', 'actions'],
                classes: ['path', 'status', 'actions'],
                rowHeading: false,
                addToTop: false
            });

            ServerStateHandler.openNotebooks.forEach(([path, info]) => {
                const status = info.handler.state.kernel.status;
                const statusEl = span([], [
                    span(['status'], [status]),
                ]);
                const actions = div([], [
                    loader(),
                    iconButton(['start'], 'Start kernel', 'power-off', 'Start').click(() => {
                        info.info!.dispatcher.kernelCommand("start")
                    }),
                    iconButton(['kill'], 'Kill kernel', 'skull', 'Kill').click(() => {
                        info.info!.dispatcher.kernelCommand("kill")
                    }),
                    iconButton(['open'], 'Open notebook', 'external-link-alt', 'Open').click(() => {
                        ServerStateHandler.selectNotebook(path)
                        this.hide();
                    }),
                ]);

                const rowEl = tableEl.addRow({ path, status: statusEl, actions });
                rowEl.classList.add('kernel-status', status)
                info.handler.addPreObserver(prev => {
                    const prevStatus = prev.kernel.status
                    return state => {
                        const status = state.kernel.status;
                        rowEl.classList.replace(prevStatus, status)
                        statusEl.innerText = status;
                    }
                })

                // load the notebook if it hasn't been already
                if (!info.loaded) {
                    rowEl.classList.add('loading')
                    ServerStateHandler.loadNotebook(path, false).then(newInfo => {
                        info = newInfo; // update `info` for the button click callbacks
                        rowEl.classList.remove("loading");
                    })

                    this.onDispose.then(() => {
                        if (ServerStateHandler.state.currentNotebook !== path) {
                            ServerStateHandler.closeNotebook(path)
                        }
                    })
                }

                if (content.firstChild !== tableEl) {
                    content.firstChild?.replaceWith(tableEl);
                }
            })
        }
        this.serverMessageDispatcher.requestRunningKernels()
        onNotebookUpdate()
        ServerStateHandler.view("notebooks").addObserver(() => onNotebookUpdate()).disposeWith(this)

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
        this.disposable = new Disposable();
        const tabs = {
            'About': this.aboutMain.bind(this),
            'Hotkeys': this.hotkeys.bind(this),
            'Preferences': this.preferences.bind(this),
            'Open Kernels': this.openKernels.bind(this),
            'Client-side Backups': this.clientBackups.bind(this),
            'State Inspector': this.stateInspector.bind(this),
        };
        const tabnav = new TabNav(tabs);
        this.content.firstChild?.replaceWith(tabnav.el);
        if (section) tabnav.showItem(section);

        super.show();
    }

    hide() {
        this.dispose()
        super.hide()
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
