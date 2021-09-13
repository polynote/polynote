import {NotebookUpdate, UpdateCell} from "./messages";
import * as messages from "./messages";
import {ContentEdit} from "./content_edit";

interface Version {
    version: number,
    edit: NotebookUpdate
}

// An immutable holder of edits.
// The old EditBuffer used to be mutable, maybe this deserves a better name now that it is immutable.
export class EditBuffer {

    constructor(private _versions: Version[] = []) {}

    get versions(): Version[] {
        return [...this._versions]
    }

    get duplicate(): EditBuffer {
        const copies = this._versions.map(ver => ({version: ver.version, edit: ver.edit}))
        return new EditBuffer(copies)
    }

    /**
     * Add an edit corresponding to a version. The version should always increase.
     * @param version The version corresponding to the edits
     * @param edit    The edit
     */
    push(version: number, edit: NotebookUpdate) {
        this._versions.push({version, edit})
        return this;
    }

    /**
     * Discard edits with versions before the given version
     * @param until The earliest version to keep
     */
    discard(until: number) {
        while (this._versions.length > 0 && this._versions[0].version < until) {
            this._versions.shift();
        }
        return this;
    }

    /**
     * Retrieve edits corresponding to a range of versions from the buffer
     *
     * @param from The start version, exclusive
     * @param to   The end version, inclusive
     * @returns {Array}
     */
    private rawRange(from: number, to: number): Version[] {
        let i = 0;
        while (i < this._versions.length && this._versions[i].version <= from) {
            i++;
        }
        const versions = [];
        while (i < this._versions.length && this._versions[i].version <= to) {
            versions.push(this._versions[i]);
            i++;
        }
        return versions;
    }

    range(from: number, to: number): NotebookUpdate[] {
        return this.rawRange(from, to).map(ver => ver.edit)
    }

    /**
     * Rebase the given update from its localVersion through the target localVersion.
     *
     * At each buffered local version, the given update will be rebased onto that local version, and the local version
     * will also be rebased on to the given update. Then, the stored local version will be replaced with the "leftovers"
     * of that rebase. This is because any future update received from the server is already on top of the given update,
     * even if its localVersion hasn't been updated. Note that this logic only affects edits to cell content where the
     * same cell was edited by both the server update and a subsequent local version (of which the server was unaware).
     *
     * This is the same logic as the server's `SubscriberUpdateBuffer` (see `KernelPublisher.scala` in polynote-server),
     * except that the rebasing logic for equal updates are opposite: on the server side, equal updates cancel each other
     * out, while on the client side, equal updates are preserved. Equal updates can't be preserved on the server, because
     * that would result in duplicate edits affecting the final state – but equal edits must be preserved on the client,
     * because the client's edit has already affected the client's state. This has been experimentally verified to be
     * the only behavior under which concurrent editing reliably operates (see `KernelPublisherIntegrationTest` in
     * polynote-server, which simulates "keyboard-mashing" clients using the client-side logic replicated here)
     */
    rebaseThrough(update: NotebookUpdate, targetVersion: number): NotebookUpdate {
        if (update instanceof UpdateCell) {
            const versions = this.rawRange(update.localVersion, targetVersion);
            let rebased = update;
            let rebasedEdits = update.edits;
            for (let version of versions) {
                const nextUpdate = version.edit;
                if (nextUpdate instanceof UpdateCell && nextUpdate.id === update.id) {
                    const [sourceRebased, targetRebased] = ContentEdit.rebaseBoth(rebasedEdits, nextUpdate.edits)
                    rebasedEdits = sourceRebased;
                    version.edit = new UpdateCell(nextUpdate.globalVersion, nextUpdate.localVersion, nextUpdate.id, targetRebased, nextUpdate.metadata);
                }
            }
            return new UpdateCell(update.globalVersion, update.localVersion, update.id, rebasedEdits, update.metadata);
        } else {
            return messages.NotebookUpdate.rebase(update, this.range(update.localVersion, targetVersion))
        }
    }
}