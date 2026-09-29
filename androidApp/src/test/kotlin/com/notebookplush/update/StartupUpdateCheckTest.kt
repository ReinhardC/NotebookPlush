package com.notebookplush.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupUpdateCheckTest {
    @Test fun waitsForBothStoresBeforeUsingTheSavedPreference() {
        val check = StartupUpdateCheck()
        assertFalse(check.decide(preferencesLoaded = false))
        assertFalse(check.decide(updaterReady = false))
        assertFalse(check.decide(enabled = false))
        assertFalse(check.decide())
    }

    @Test fun checksOnlyOnceAcrossRecompositionAndRotation() {
        val check = StartupUpdateCheck()
        assertTrue(check.decide())
        repeat(5) { assertFalse(check.decide()) }
        // Only a new updater lifetime represents another startup.
        assertTrue(StartupUpdateCheck().decide())
    }

    @Test fun waitsForAnExistingOperationWithoutLosingTheStartupCheck() {
        val check = StartupUpdateCheck()
        assertFalse(check.decide(busy = true))
        assertTrue(check.decide())
        assertFalse(check.decide())
    }

    @Test fun diagnosticBuildDoesNotCheckAutomatically() {
        val check = StartupUpdateCheck()
        assertFalse(check.decide(supported = false))
        assertFalse(check.decide())
    }

    @Test fun snoozedStartupWaitsForANewLaunchAfterTheWeek() {
        val now = 1_800_000_000_000L
        val reminder = UpdateReminder(now)
        val check = StartupUpdateCheck()
        assertFalse(check.shouldCheck(true, true, true, true, false, reminder, now + 1))
        assertFalse(check.shouldCheck(true, true, true, true, false, reminder, now + UpdateReminderDelayMillis))
        assertTrue(StartupUpdateCheck().shouldCheck(true, true, true, true, false,
            reminder, now + UpdateReminderDelayMillis))
        assertFalse(StartupUpdateCheck().shouldCheck(true, true, false, true, false,
            reminder, now + UpdateReminderDelayMillis))
    }

    @Test fun manualCheckPreventsASecondAutomaticPopup() {
        val check = StartupUpdateCheck()
        check.handledManually()
        assertFalse(check.decide())
    }

    private fun StartupUpdateCheck.decide(
        preferencesLoaded: Boolean = true,
        updaterReady: Boolean = true,
        enabled: Boolean = true,
        supported: Boolean = true,
        busy: Boolean = false,
    ) = shouldCheck(preferencesLoaded, updaterReady, enabled, supported, busy)
}
