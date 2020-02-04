import {preferences} from "./storage";

export const notificationPref = preferences.register("Notifications", false,
    "Whether to allow Polynote to send you browser notifications. " +
    "Toggle this to `true` for the first time for your browser to " +
    "request your permission.");

export function notificationsEnabled(): boolean {
    return preferences.get(notificationPref).value
}

preferences.addPreferenceListener(notificationPref, (oldValue, newValue) => {
    if (newValue.value === true) {
        Notification.requestPermission().then((result) => {
            console.log(`Requested notification permission and got ${result}`)
        });
    }
});
