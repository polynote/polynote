import {
    Backup,
    Backups,
    BACKUPS_PER_DAY,
    BACKUPS_PER_NOTEBOOK,
    clean,
    cleanCells,
    cleanConfig,
    ClientBackup,
    todayTs,
    typedUpdate
} from "./client_backup";
import {NotebookCells, UpdateConfig} from "../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../data/data";
import {PosRange} from "../data/result";
import * as MockDate from "mockdate";

const nowTime = new Date()

beforeEach(done => {
    MockDate.set(nowTime)
    ClientBackup.clearBackups().then(() => done())
})

const sampleConfig = NotebookConfig.default;
const sampleComment = new CellComment("sampleCommentUUID", new PosRange(1, 2), "me", undefined, Date.now(), "this is my comment")
const sampleCells = [
    new NotebookCell(0, "scala", "val x = 1", [], undefined, {[sampleComment.uuid]: sampleComment}),
    new NotebookCell(1, "python", "dir(x)", [], new CellMetadata().copy({hideOutput: true})),
]
const sampleNotebook = new NotebookCells("somePath", sampleCells, sampleConfig)

describe("ClientBackup", () => {
    it("stores backups for a notebook", done => {
        const expectedBackups = new Backups(sampleNotebook.path, {
            [todayTs()]: [new Backup(sampleNotebook.path, cleanCells(sampleNotebook.cells), cleanConfig(sampleNotebook.config!))]
        })
        ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, sampleNotebook.config)
            .then(backup => {
                expect(backup).toEqual(expectedBackups)
            })
            .then(() => {
                return ClientBackup.getBackups(sampleNotebook.path)
            }).then(backup => {
                expect(backup).toEqual(expectedBackups)
            })
            .then(done)
    })
    it("stores multiple backups, but only if the notebook has changed", done => {
        const changedNbConfig = new NotebookConfig({scala: ["someScalaDep"]})
        const expectedBackups = new Backups(sampleNotebook.path, {
            [todayTs()]: [
                new Backup(sampleNotebook.path, cleanCells(sampleNotebook.cells), cleanConfig(sampleNotebook.config!)),
                new Backup(sampleNotebook.path, cleanCells(sampleNotebook.cells), cleanConfig(changedNbConfig))
            ]
        })
        // first, demonstrate that adding the same notebook over and over doesn't change the backups
        ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, sampleNotebook.config)
            .then(() => {
                return ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, sampleNotebook.config)
            }).then(() => {
                return ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, sampleNotebook.config)
            }).then(() => {
                return ClientBackup.getBackups(sampleNotebook.path)
            }).then(backups => {
                expect(Object.values(backups.backups)).toHaveLength(1)

                // next, add changed notebook
                return ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, changedNbConfig)
            })
            .then(() => {
                return ClientBackup.getBackups(sampleNotebook.path)
            }).then(backups => {
                expect(backups).toEqual(expectedBackups) // backups should now have the new nb we added

                // again, if we repeat the addition, we expect nothing to happen
                return ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, changedNbConfig)
            }).then(() => {
                return ClientBackup.getBackups(sampleNotebook.path)
            }).then(backups => {
                expect(backups).toEqual(expectedBackups) // backups haven't changed.
            })
            .then(done)
    })

    it("can provide a list of all backups", done => {
        const today = todayTs()
        MockDate.set(today)
        const tomorrowDate = new Date()
        tomorrowDate.setDate(tomorrowDate.getDate() + 1)
        const tomorrow = todayTs(tomorrowDate)
        const changedNbConfig1 = new NotebookConfig({scala: ["someScalaDep1"]})
        const changedNbConfig2 = new NotebookConfig({scala: ["someScalaDep2"]})
        const changedNbConfig3 = new NotebookConfig({scala: ["someScalaDep3"]})
        const expectedBackups = clean({
            [sampleNotebook.path]: new Backups(sampleNotebook.path, {
                [today]: [
                    new Backup(sampleNotebook.path, cleanCells(sampleNotebook.cells), cleanConfig(sampleNotebook.config!), today),
                    new Backup(sampleNotebook.path, cleanCells(sampleNotebook.cells), cleanConfig(changedNbConfig1), today)
                ],
                [tomorrow]: [
                    new Backup(sampleNotebook.path, cleanCells(sampleNotebook.cells), cleanConfig(changedNbConfig2), tomorrow),
                    new Backup(sampleNotebook.path, cleanCells(sampleNotebook.cells), cleanConfig(changedNbConfig3), tomorrow)
                ]
            })
        })
        // add today's notebooks
        ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, sampleNotebook.config).then(() => {
            return ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, changedNbConfig1)
        }).then(() => {
            // tick date and add tomorrow's
            MockDate.set(tomorrow)
            return ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, changedNbConfig2).then(() => {
                return ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, changedNbConfig3)
            })
        }).then(() => {
            return ClientBackup.allBackups()
        }).then(backups => {
            expect(backups).toEqual(expectedBackups)
        }).then(done)
    })
    it("collects notebook updates for existing backups", done => {
        const update = new UpdateConfig(0, 0, sampleNotebook.config!)
        const expected = new Backups(sampleNotebook.path, {
            [todayTs()]: [
                new Backup(sampleNotebook.path, cleanCells(sampleNotebook.cells), cleanConfig(sampleNotebook.config!), Date.now(), [{ts: Date.now(), update: typedUpdate(update)}])
            ]
        })
        ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, sampleNotebook.config).then(() => {
            return ClientBackup.updateNb(sampleNotebook.path, update)
        }).then(() => {
            return ClientBackup.getBackups(sampleNotebook.path)
        }).then(backup => {
            expect(backup).toEqual(expected)
        }).then(done)
    })

    it("has daily and lifetime limits to the number of backups it stores", done => {
        // quiet console.warn for this test
        jest.spyOn(console, 'warn').mockImplementation(() => {});

        const limitNotebooks = [...Array(BACKUPS_PER_NOTEBOOK).keys()].map(i => {
            return {
                ...sampleNotebook,
                config: new NotebookConfig({scala: ["dep" + i]})
            }
        })

        // first, add all the limitNotebooks "today" and note that we only keep the last BACKUPS_PER_DAY
        const tryAll = limitNotebooks.reduce<Promise<Backups> | undefined>((promiseChain, nextNotebook) => {
            if (promiseChain === undefined) {
                return ClientBackup.addNb(nextNotebook.path, nextNotebook.cells, nextNotebook.config)
            } else return promiseChain.then(() => ClientBackup.addNb(nextNotebook.path, nextNotebook.cells, nextNotebook.config))
        }, undefined)
        tryAll!.then(() => {
            return ClientBackup.getBackups(sampleNotebook.path)
        }).then(backups => {
            const allBackups = Object.values(backups.backups)
            expect(allBackups.flat().length).toEqual(BACKUPS_PER_DAY)
            const lastNotebooks = limitNotebooks.map(nb => new Backup(nb.path, cleanCells(nb.cells), cleanConfig(nb.config!)).toI()).slice(-BACKUPS_PER_DAY)
            expect(backups.backups[todayTs()]).toEqual(lastNotebooks)
        }).then(() => {
            // next, let's add notebooks to different days and show that we only keep the last BACKUPS_PER_NOTEBOOK
            return limitNotebooks.reduce<Promise<Backups> | undefined>((promiseChain, nextNotebook, idx) => {
                if (promiseChain === undefined) {
                    return ClientBackup.addNb(nextNotebook.path, nextNotebook.cells, nextNotebook.config)
                } else return promiseChain.then(() => {
                    if (idx % BACKUPS_PER_DAY === 0) {
                        const date = new Date()
                        date.setDate(date.getDate() + 1)
                        MockDate.set(date)
                    }
                    return ClientBackup.addNb(nextNotebook.path, nextNotebook.cells, nextNotebook.config)
                })
            }, undefined)!
                .then(() => {
                    return ClientBackup.getBackups(sampleNotebook.path)
                }).then(backups => {
                    const allBackups = Object.values(backups.backups).flat()
                    expect(allBackups.length).toEqual(BACKUPS_PER_NOTEBOOK)
                })
        }).then(() => {
            // next, show that the number should stay the same even if we add another notebook
            return ClientBackup.addNb(sampleNotebook.path, sampleNotebook.cells, new NotebookConfig({python: ["foo"]}))
                .then(() => {
                    return ClientBackup.getBackups(sampleNotebook.path)
                }).then(backups => {
                    const allBackups = Object.values(backups.backups).flat()
                    expect(allBackups.length).toEqual(BACKUPS_PER_NOTEBOOK)
                })
        }).then(() => {
            // finally, show that the number does change if we add another backup from a different notebook!
            return ClientBackup.addNb("otherPath", sampleNotebook.cells, new NotebookConfig({python: ["foo"]}))
                .then(() => {
                    return ClientBackup.allBackups()
                }).then(backups => {
                    const allBackups = Object.values(backups).flatMap(b => Object.values(b.backups)).flat()
                    expect(allBackups.length).toEqual(BACKUPS_PER_NOTEBOOK + 1)
                })
        }).then(done)
    })
})