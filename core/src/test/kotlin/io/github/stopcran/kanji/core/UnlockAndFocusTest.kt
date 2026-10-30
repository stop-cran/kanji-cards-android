package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.focus.FocusBatch
import io.github.stopcran.kanji.core.focus.Requirements
import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.unlock.LogEvent
import io.github.stopcran.kanji.core.unlock.MeaningUnlock
import io.github.stopcran.kanji.core.unlock.UnlockRule
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockAndFocusTest {
    private val utc = ZoneOffset.UTC
    private val day = 24 * 3600_000L
    private val min = 60_000L
    private var nextId = 0L

    private fun q(kanji: String, at: Long, grade: Int = 3, mode: String = "Quiz") = LogEvent(nextId++, at, kanji, mode, grade)

    /** [n] answers of other kanji spread one minute apart, starting at [from]. */
    private fun filler(from: Long, n: Int) = (0 until n).map { q("x$it", from + it * min / 100) }

    @Test fun nextDayNeedsTwoDays() {
        val same = listOf(q("a", 1000), q("a", 2000))
        assertTrue(MeaningUnlock.learned(same, UnlockRule.NextDay, utc).isEmpty())
        val two = listOf(q("a", 1000), q("a", day + 1000))
        assertEquals(setOf("a"), MeaningUnlock.learned(two, UnlockRule.NextDay, utc))
    }

    @Test fun spacedNeedsGapAndIntervening() {
        val t0 = 10 * min
        val ok = listOf(q("a", t0)) + filler(t0 + 1, 10) + q("a", t0 + 10 * min)
        assertEquals(setOf("a"), MeaningUnlock.learned(ok, UnlockRule.SpacedSameDay, utc))
        assertTrue(MeaningUnlock.learned(ok, UnlockRule.NextDay, utc).isEmpty())
        val tooFew = listOf(q("a", t0)) + filler(t0 + 1, 9) + q("a", t0 + 10 * min)
        assertTrue(MeaningUnlock.learned(tooFew, UnlockRule.SpacedSameDay, utc).isEmpty())
        val tooSoon = listOf(q("a", t0)) + filler(t0 + 1, 10) + q("a", t0 + 10 * min - 1)
        assertTrue(MeaningUnlock.learned(tooSoon, UnlockRule.SpacedSameDay, utc).isEmpty())
    }

    @Test fun againResetsSpacedSuccesses() {
        val t0 = 10 * min
        val events = listOf(q("a", t0)) + filler(t0 + 1, 10) + q("a", t0 + 5 * min, grade = 1) + q("a", t0 + 11 * min)
        assertTrue(MeaningUnlock.learned(events, UnlockRule.SpacedSameDay, utc).isEmpty())
    }

    @Test fun otherModesOnlyCountAsIntervening() {
        val t0 = 10 * min
        val between = (0 until 10).map { q("a", t0 + 1 + it, mode = "Draw") }
        val events = listOf(q("a", t0)) + between + q("a", t0 + 10 * min)
        assertEquals(setOf("a"), MeaningUnlock.learned(events, UnlockRule.SpacedSameDay, utc))
    }

    private fun srs(phase: CardPhase) = SrsState(phase = phase)

    @Test fun focusCountsKanjiUntilEveryRequiredCardIsReview() {
        val reqs = mapOf("a" to Requirements("a", true, mapOf(ReadingKind.On to listOf("on1"))))
        val review = srs(CardPhase.Review)
        val allReview = mapOf("a" to review)
        val readings = mapOf(ReadingKind.On to mapOf(ReadingId.of("a", "on1") to review))
        assertEquals(emptySet<String>(), FocusBatch.inLearning(setOf("a"), reqs, allReview, allReview, readings))
        assertEquals(setOf("a"), FocusBatch.inLearning(setOf("a"), reqs, allReview, emptyMap(), readings))
        assertEquals(setOf("a"), FocusBatch.inLearning(setOf("a"), reqs, allReview, allReview, emptyMap()))
        assertEquals(setOf("a"), FocusBatch.inLearning(setOf("a"), reqs, mapOf("a" to srs(CardPhase.Learning)), allReview, readings))
    }

    @Test fun undrawableKanjiNeedsNoDrawing() {
        val reqs = mapOf("a" to Requirements("a", false, emptyMap()))
        assertEquals(emptySet<String>(), FocusBatch.inLearning(setOf("a"), reqs, mapOf("a" to srs(CardPhase.Review)), emptyMap(), emptyMap()))
    }

    @Test fun capacityNeverNegative() {
        assertEquals(3, FocusBatch.capacity(10, 7))
        assertEquals(0, FocusBatch.capacity(10, 12))
    }
}
