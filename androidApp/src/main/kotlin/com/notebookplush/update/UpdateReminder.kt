package com.notebookplush.update

// A dismissal quiets all automatic update prompts for seven days, including newer releases.
internal const val UpdateReminderDelayMillis = 7L * 24 * 60 * 60 * 1000

internal data class UpdateReminder(val dismissedAtMillis: Long = 0) {
    fun mayPrompt(now: Long): Boolean = dismissedAtMillis <= 0 ||
        (now >= dismissedAtMillis && now - dismissedAtMillis >= UpdateReminderDelayMillis)
}
