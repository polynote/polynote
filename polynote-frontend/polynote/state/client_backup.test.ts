import {Backup, Backups, cleanCells, cleanConfig, ClientBackup, todayTs} from "./client_backup";
import {NotebookCells} from "../data/messages";
import {CellComment, CellMetadata, NotebookCell, NotebookConfig} from "../data/data";
import {PosRange} from "../data/result";
import * as MockDate from "mockdate";
import * as deepEquals from 'fast-deep-equal/es6';

const nowTime = Date.now()
MockDate.set(nowTime)

beforeEach(done => {
    ClientBackup.clearBackups().then(() => done())
})

const sampleConfig = NotebookConfig.default;
const sampleComment = new CellComment("sampleCommentUUID", new PosRange(1, 2), "me", undefined, nowTime, "this is my comment")
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
})