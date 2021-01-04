import {clear, get, keys, set} from 'idb-keyval';
import {NotebookUpdate} from "../data/messages";
import {CellComment, NotebookCell, NotebookConfig} from "../data/data";
import {deepEquals, isObject} from "../util/helpers";

export class ClientBackup {
    static addNb(path: string, cells: NotebookCell[], config?: NotebookConfig): Promise<Backups> {
        const cleanedConfig = config && cleanConfig(config)
        const backup = new Backup(path, cleanCells(cells), cleanedConfig);
        return get(path)
            .then((backupsObj?: IBackups) => {
                const backups: Backups = backupsObj ? Backups.fromI(backupsObj) : new Backups(path);
                if (backups.latestBackup()?.equals(backup)) {
                    return backups
                } else {
                    backups.addBackup(backup);

                    return set(path, backups.toI())
                        .then(() => {
                            return backups
                        })
                        .catch(err => {
                            console.error("Error while setting backups", err);
                            throw err;
                        })
                }
            })
    }

    static getBackups(path: string): Promise<IBackups> {
        return get(path)
            .then((iBackups: IBackups) => {
                if (iBackups) {
                    return iBackups
                }
                throw new Error(`No Backup found for ${path}`)
            })
    }

    static allBackups(): Promise<Record<string, IBackups>> {
        return keys()
            .then(ks => {
                const backups: Record<string, IBackups> = {};
                return Promise.all(ks.map((k: string) =>
                    ClientBackup.getBackups(k)
                        .then(iBackup => {
                            backups[k] = iBackup;
                        })
                )).then(() => backups)
            })
    }

    static updateNb(path: string, upd: NotebookUpdate): Promise<Backups> {
        return get(path)
            .then((iBackups?: IBackups) => {
                if (iBackups) {
                    return ClientBackup.addBackup(iBackups, upd)
                        .then(backups => {
                            return set(path, backups.toI())
                                .then(() => {
                                    return backups
                                })
                                .catch(err => {
                                    console.error("Error while updating backups", err);
                                    throw err;
                                })
                        })
                } else {
                    throw new Error(`Unable to find a current backup for notebook at ${path}`)
                }
            })
    }

    private static addBackup(iBackups: IBackups, upd: NotebookUpdate): Promise<Backups> {
        return new Promise(resolve => {
            const backups = Backups.fromI(iBackups);
            backups.addUpdate(upd);
            resolve(backups)
        })
    }

    static clearBackups() {
        return this.allBackups().then(backups => clear().then(() => backups))
    }
}

export function todayTs(date: Date = new Date()) {
    return date.setHours(0,0,0,0);
}


/**
 * These limits are used to determine how many backups to store.
 *
 * When the limits are reached:
 *     If the daily limit is reached, the oldest backup saved that day is replaced by the newest one.
 *     If the total limit is reached, the oldest day of backups is removed.
 */
export const BACKUPS_PER_NOTEBOOK = 100;
export const BACKUPS_PER_DAY = 10;

/**
 * Stores a set of backups for a particular notebook.
 *
 * Note that objects stored in the IndexedDB are "Structured Clones" of the objects put in there. This means they can't
 * have functions, etc. and lose their prototypes, which breaks equality. So, we have [[IBackups]] and [[IBackup]] which
 * represent the resulting cloned object we get when fetching from the DB.
 *
 * See https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Structured_clone_algorithm for more about structured cloning.
 */
interface IBackups {
    readonly path: string,
    readonly backups: Record<number, IBackup[]>
}

export class Backups {

    constructor(readonly path: string, readonly backups: Record<number, Backup[]> = {}) {}

    addBackup(backup: Backup) {
        if (Object.values(this.backups).length > BACKUPS_PER_NOTEBOOK) {
            const oldestDay = Math.min(...Object.keys(this.backups).map(i => parseInt(i)));
            const oldestBackups = this.backups[oldestDay];
            console.warn("Reached total backup limit of", BACKUPS_PER_NOTEBOOK);
            console.warn("Deleting backups from", new Date(oldestDay).toDateString(), oldestBackups);
            delete this.backups[oldestDay];
        }

        const todayBackups = this.backups[todayTs()];

        if (todayBackups === undefined) {
            this.backups[todayTs()] = [backup];
        } else if (this.backups[todayTs()].length < BACKUPS_PER_DAY) {
            this.backups[todayTs()].push(backup)
        } else {
            const removed = this.backups[todayTs()].shift();
            this.backups[todayTs()].push(backup);
            console.warn("Reached backup limit of", BACKUPS_PER_DAY, "backups per day.");
            console.warn("Removed an old backup from earlier today", removed);
        }
    }

    latestBackup() {
        // short-circuit if there are backups from today
        const todayBackups = this.backups[todayTs()];
        if (todayBackups) {
            return todayBackups[todayBackups.length - 1];
        } else {
            const newestDay = Math.max(...Object.keys(this.backups).map(i => parseInt(i)));
            const newestBackups = this.backups[newestDay];
            if (newestBackups) {
                return newestBackups[newestBackups.length - 1];
            } else {
                return undefined
            }
        }
    }

    addUpdate(upd: NotebookUpdate) {
        const latest = this.latestBackup();
        if (latest) {
            const typedUpdate = Object.assign({type: upd.constructor.name}, upd);
            const latestUpdate = latest.updates[latest.updates.length - 1]?.update;
            if (! deepEquals(latestUpdate, typedUpdate)) {
                latest.addUpdate(typedUpdate);
            } else {
                console.warn("got duplicate update: ", latestUpdate, typedUpdate, deepEquals(latestUpdate, typedUpdate))
            }
        } else {
            throw new Error(`No backups found for ${this.path}!`)
        }
    }

    static fromI(iBackups: IBackups): Backups {
        const backups: Record<number, Backup[]> = {};
        Object.entries(iBackups.backups).forEach(([ts, iBackups]) => {
            backups[parseInt(ts)] = iBackups.map(Backup.fromI);
        });
        return new Backups(iBackups.path, backups)
    }

    toI(): IBackups {
        return {
            path: this.path,
            backups: this.backups
        }
    }
}

/**
 * Stores a particular backup of a notebook and edits to it from a given session.
 * Notebook outputs are not saved; only the content of the notebook is saved.
 */
// these helper types are needed because the objects saved in the DB are structured clones. See note earlier in this file for more info.
type Cleaned<T> = Pick<T, any>
type CleanedCell = Omit<NotebookCell, 'results' | 'metadata' | 'comments'> & {comments: Cleaned<CellComment>};
type CleanedConfig = Cleaned<NotebookConfig>;

export function cleanCells(cells: NotebookCell[]): CleanedCell[] {
    return cells.map(cell => {
        const cleaned: Record<string, Pick<CellComment, any>> = {};
        Object.entries(cell.comments).forEach(([id, comment]) => {
            cleaned[id] = clean(comment) // We need to remove prototypes and functions from CellComment before storing it in the database.
        })
        return {id: cell.id, content: cell.content, language: cell.language, comments: cleaned}
    })
}

export function cleanConfig(config: NotebookConfig): CleanedConfig {
    return clean(config)
}

export function clean<T>(obj: T) {
    function go(obj: any): any {
        if (isObject(obj)) {
            const cleaned = {} as any;
            Object.entries(obj).forEach(([k, v]) => {
                cleaned[k] = go(v)
            })
            return cleaned
        } else if (Array.isArray(obj)) {
            return obj.map(item => go(item))
        } else {
            return obj
        }
    }

    return go(obj)
}

type TypedUpdate = NotebookUpdate & {type: string}
export function typedUpdate(update: NotebookUpdate): TypedUpdate {
    return Object.assign({type: update.constructor.name}, update);
}

interface IBackup {
    readonly path: string,
    readonly cells: CleanedCell[],
    readonly config?: CleanedConfig,
    readonly ts: number,
    readonly updates: {ts: number, update: TypedUpdate}[],
}
export class Backup {

    constructor(readonly path: string,
                readonly cells: CleanedCell[],
                readonly config?: CleanedConfig,
                readonly ts: number = Date.now(),
                readonly updates: {ts: number, update: TypedUpdate}[] = []) {
    }

    addUpdate(update: NotebookUpdate) {
        const ts = Date.now();
        this.updates.push({ts, update: typedUpdate(update)});
    }

    static fromI(iBackup: IBackup): Backup {
        return new Backup(iBackup.path, iBackup.cells, iBackup.config, iBackup.ts, iBackup.updates)
    }

    toI(): IBackup {
        return {
            path: this.path,
            cells: this.cells,
            config: this.config,
            ts: this.ts,
            updates: this.updates
        }
    }

    equals(backup: Backup) {
        return this.path === backup.path
            && deepEquals(this.cells, backup.cells)
            && deepEquals(this.config, backup.config)
            && deepEquals(this.updates, backup.updates)
    }
}

