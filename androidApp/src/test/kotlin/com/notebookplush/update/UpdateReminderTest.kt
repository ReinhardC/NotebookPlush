package com.notebookplush.update

import org.junit.Assert.*
import org.junit.Test

class UpdateReminderTest {
    private val now = 1_800_000_000_000L

    @Test fun firstUseCanPromptImmediately() {
        assertTrue(UpdateReminder().mayPrompt(now))
    }

    @Test fun dismissalWaitsExactlySevenDays() {
        val reminder = UpdateReminder(now)
        assertFalse(reminder.mayPrompt(now))
        assertFalse(reminder.mayPrompt(now + UpdateReminderDelayMillis - 1))
        assertTrue(reminder.mayPrompt(now + UpdateReminderDelayMillis))
        assertTrue(reminder.mayPrompt(now + UpdateReminderDelayMillis + 1))
    }

    @Test fun restoredDismissalSurvivesRelaunchAndIsNotTiedToOneRelease() {
        // This is the timestamp round-trip used by the store. There is deliberately no version
        // key: a release published during the quiet week must not undo the reader's dismissal.
        val restored = UpdateReminder(UpdateReminder(now).dismissedAtMillis)
        assertFalse(restored.mayPrompt(now + UpdateReminderDelayMillis / 2))
        assertTrue(restored.mayPrompt(now + UpdateReminderDelayMillis))
    }

    @Test fun dismissingTheReminderStartsAnotherWeek() {
        val secondDismissal = now + UpdateReminderDelayMillis
        val reminder = UpdateReminder(secondDismissal)
        assertFalse(reminder.mayPrompt(secondDismissal + 1))
        assertTrue(reminder.mayPrompt(secondDismissal + UpdateReminderDelayMillis))
    }

    @Test fun clockMovingBackDoesNotPromptImmediately() {
        assertFalse(UpdateReminder(now).mayPrompt(now - 1))
    }
}
