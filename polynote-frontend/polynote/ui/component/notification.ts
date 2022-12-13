import {a, div, iconButton, para} from "../tags";
import {DismissedNotificationsHandler} from "../../state/preferences";

export class Notification {
    readonly el: HTMLElement;

    constructor(version: string, releaseNotes: string) {
        this.el = div(['snackbar'], [
            iconButton(['notification-close'], 'Close', 'times-circle', 'Close').click(evt => {
                DismissedNotificationsHandler.update((ignoredReleases) => {
                    const newIgnoredReleases: string[] = Object.assign([], ignoredReleases);
                    newIgnoredReleases.push(version);
                    return newIgnoredReleases;
                });
                this.el.remove();
            }),
            para([], [`Great news! Version ${version} of Polynote is now available.`]),
            a([], releaseNotes, ["View the release notes here."], { target: "_blank" })
        ])
    }
}