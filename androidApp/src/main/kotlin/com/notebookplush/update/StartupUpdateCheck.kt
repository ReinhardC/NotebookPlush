package com.notebookplush.update

/**
 * One decision per updater lifetime, after preferences and updater initialization. Rotation and returning
 * from the installer keep the updater, so neither may trigger another automatic request.
 * A disabled, snoozed or unsupported startup is consumed too; manual checks bypass it.
 */
internal class StartupUpdateCheck {
    private var decided = false

    fun handledManually() { decided = true }

    fun shouldCheck(
        preferencesLoaded: Boolean,
        updaterReady: Boolean,
        enabled: Boolean,
        supported: Boolean,
        busy: Boolean,
        reminder: UpdateReminder = UpdateReminder(),
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (decided || !preferencesLoaded || !updaterReady) return false
        if (!enabled || !supported || !reminder.mayPrompt(now)) {
            decided = true
            return false
        }
        if (busy) return false
        decided = true
        return true
    }
}
