import {NotebookUpdate} from "../../../data/messages";

interface Version {
    version: number,
    edits: NotebookUpdate[]
}

// An immutable holder of edits.
// The old EditBuffer used to be mutable, maybe this deserves a better name now that it is immutable.
export class EditBuffer {

    constructor(readonly versions: Version[] = []) {}

    /**
     * Add edits corresponding to a version. The version should always increase.
     * @param version The version corresponding to the edits
     * @param edits   The edits
     */
    push(version: number, edits: NotebookUpdate[] | NotebookUpdate) {
        if (! (edits instanceof Array)) {
            edits = [edits]
        }
        return new EditBuffer([...this.versions, {version, edits}]);
    }

    /**
     * Discard edits with versions before the given version
     * @param until The earliest version to keep
     */
    discard(until: number) {
        const versions = [...this.versions];
        while (versions.length > 0 && versions[0].version < until) {
            versions.shift();
        }
        return new EditBuffer(versions)
    }

    /**
     * Retrieve edits corresponding to a range of versions from the buffer
     *
     * @param from The start version, exclusive
     * @param to   The end version, inclusive
     * @returns {Array}
     */
    range(from: number, to: number): NotebookUpdate[] {
        let i = 0;
        while (i < this.versions.length && this.versions[i].version <= from) {
            i++;
        }
        const edits = [];
        while (i < this.versions.length && this.versions[i].version <= to) {
            edits.push(...this.versions[i].edits);
            i++;
        }
        return edits;
    }
}