package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.ReminderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPolicyTest {
    private val day = 24L * 3600 * 1000
    private val today = 100 * day
    private val now = today + 19 * 3600 * 1000

    @Test
    fun remindsWhenDueAndNotStudiedToday() {
        val d = ReminderPolicy.decide(now, today, today - day, 0, 0, 5)
        assertTrue(d.remind)
        assertEquals(1, d.ignored)
    }

    @Test
    fun silentWhenNothingDueOrAlreadyStudiedToday() {
        assertFalse(ReminderPolicy.decide(now, today, null, 0, 0, 0).remind)
        assertFalse(ReminderPolicy.decide(now, today, today + 1000, 0, 0, 5).remind)
    }

    @Test
    fun backsOffWhileIgnoredAndStopsAfterLastGap() {
        val studied = today - 200 * day
        // 1 ignored: next reminder only after 2 days.
        assertFalse(ReminderPolicy.decide(now, today, studied, now - day, 1, 5).remind)
        assertTrue(ReminderPolicy.decide(now, today, studied, now - 2 * day, 1, 5).remind)
        // 3 ignored: 7 days.
        assertFalse(ReminderPolicy.decide(now, today, studied, now - 5 * day, 3, 5).remind)
        assertTrue(ReminderPolicy.decide(now, today, studied, now - 7 * day, 3, 5).remind)
        // 5 ignored: never again.
        assertFalse(ReminderPolicy.decide(now, today, studied, now - 100 * day, 5, 5).remind)
    }

    @Test
    fun studyingResetsTheBackoff() {
        val lastReminder = now - 10 * day
        val d = ReminderPolicy.decide(now, today, now - 9 * day, lastReminder, 5, 5)
        assertTrue(d.remind)
        assertEquals(1, d.ignored)
    }
}
