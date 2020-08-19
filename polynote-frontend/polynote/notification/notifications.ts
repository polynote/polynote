import {UserPreferences, UserPreferencesHandler} from "../state/preferences";
import {FaviconHandler} from "./favicon_handler";

export class NotificationHandler {
    private static inst: NotificationHandler;
    static get get() {
        if (!NotificationHandler.inst) {
            NotificationHandler.inst = new NotificationHandler()
        }
        return NotificationHandler.inst;
    }

    private enabled: boolean = false;
    private constructor() {
        const handlePref = (pref: typeof UserPreferences["notifications"]) => {
            if (pref.value) {
                Notification.requestPermission().then((result) => {
                    console.log(`Requested notification permission and got: '${result}'`)
                });
            }
            this.enabled = pref.value;
        }
        handlePref(UserPreferencesHandler.state.notifications)
        UserPreferencesHandler.view("notifications").addObserver(pref => handlePref(pref))
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
        if (this.enabled && !document.hasFocus()) {
            return new Promise((resolve, reject) => {
                const n = new Notification(title, {body: body, icon: FaviconHandler.get.faviconUrl});
                n.addEventListener("click", (ev) => {
                    resolve()
                    n.close();
                });
            })
        } else return new Promise((resolve, reject) => {})
    }
}