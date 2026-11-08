package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.KanjiTransitionChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class KanjiTransitionChecksTest {
    @Test
    fun relationshipsAreExplicitAndSymmetric() {
        assertTrue("休" in KanjiTransitionChecks.related("体"))
        assertTrue("体" in KanjiTransitionChecks.related("休"))
        assertTrue("白" in KanjiTransitionChecks.related("自"))
        assertTrue(KanjiTransitionChecks.related("月").isEmpty())
    }

    @Test
    fun checksAreSpacedAndNeverDisplaceAnEarlierNaturalReview() {
        val now = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli()
        val day = KanjiTransitionChecks.DAY_MS
        val first = now + 2 * day
        assertEquals(first, KanjiTransitionChecks.nextDue(now, now + 20 * day, emptyList()))
        assertEquals(first, KanjiTransitionChecks.nextDue(now, now + 20 * day, listOf(first)))
        assertEquals(first + day, KanjiTransitionChecks.nextDue(now, now + 20 * day, listOf(first, first + 1000)))
        assertNull(KanjiTransitionChecks.nextDue(now, first, emptyList()))
        assertNull(KanjiTransitionChecks.nextDue(now, first + day, listOf(first, first + 1000)))
        assertNull(KanjiTransitionChecks.nextDue(Long.MAX_VALUE - 1, Long.MAX_VALUE, emptyList()))
    }
}
