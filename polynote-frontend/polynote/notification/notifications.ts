import {Disposable, setProperty} from "../state";
import {FaviconHandler} from "./favicon_handler";
import {UserPreferences, UserPreferencesHandler} from "../state/preferences";

class NotificationStorageHandler extends Disposable {
    private enabled?: boolean;
    constructor() {
        super()
        UserPreferencesHandler.observeKey("notifications", pref => this.handlePref(pref)).disposeWith(this)
    }

    handlePref(pref: typeof UserPreferences["notifications"]) {
        if (pref.value) {
            Notification.requestPermission().then((result) => {
                if (result === 'denied') {
                    // user changed their mind, so update the preference accordingly
                    UserPreferencesHandler.updateField("notifications", () => setProperty("value", false))
                }
            });
        }
        this.enabled = pref.value ?? this.enabled;
    }

    /**
     * If notifications are enabled, creates one with the given parameters, using the current favicon as the icon.
     * Notifications are only shown when the window is out of focus.
     *
     * Returns a promise that resolves if the notification is clicked. If notifications are not enabled, creates a promise that never resolves :\
     *
     * @param title     Title of the notification
     * @param body      Body text for the notification.
     */
    notify(title: string, body: string) {
        if (this.enabled === undefined) {
            this.handlePref(UserPreferencesHandler.state.notifications)
        }
        if (this.enabled && !document.hasFocus()) {
            return new Promise<void>((resolve, reject) => {
                const n = new Notification(title, {body: body, icon: FaviconHandler.get.faviconUrl});
                n.addEventListener("click", (ev) => {
                    resolve()
                    n.close();
                });
            })
        } else return new Promise((resolve, reject) => {})
    }
}

export const NotificationHandler = new NotificationStorageHandler()