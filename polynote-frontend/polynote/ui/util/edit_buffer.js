// BREAKOUT (utils?)
export class EditBuffer {

    constructor() {
        this.versions = [];
    }

    /**
     * Add edits corresponding to a version. The version should always increase.
     * @param version The version corresponding to the edits
     * @param edits   The edits
     */
    push(version, edits) {
        this.versions.push({version, edits});
    }

    /**
     * Discard edits with versions before the given version
     * @param until The earliest version to keep
     */
    discard(until) {
        while (this.versions.length > 0 && this.versions[0].version < until) {
            this.versions.shift();
        }
    }

    /**
     * Retrieve edits corresponding to a range of versions from the buffer
     *
     * @param from The start version, exclusive
     * @param to   The end version, inclusive
     * @returns {Array}
     */
    range(from, to) {
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