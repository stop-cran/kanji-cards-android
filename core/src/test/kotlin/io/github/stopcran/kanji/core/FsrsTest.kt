package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FsrsTest {
    private val fsrs = Fsrs()
    private val t0 = Instant.parse("2026-01-01T10:00:00Z")

    @Test
    fun zeroStabilityHasZeroRetrievability() {
        val s = SrsState(phase = CardPhase.Learning, stability = 0.0, lastReview = t0)
        assertEquals(0.0, fsrs.retrievability(s, t0), 0.0)
    }

    @Test
    fun firstReviewIntervalsMatchInitialStability() {
        assertEquals(3L, fsrs.intervalDays(fsrs.review(SrsState(), Grade.Good, t0).stability))
        assertEquals(16L, fsrs.intervalDays(fsrs.review(SrsState(), Grade.Easy, t0).stability))
        assertEquals(1L, fsrs.intervalDays(fsrs.review(SrsState(), Grade.Hard, t0).stability))
    }

    @Test
    fun againKeepsCardInLearningWithShortStep() {
        val s = fsrs.review(SrsState(), Grade.Again, t0)
        assertEquals(CardPhase.Learning, s.phase)
        assertEquals(t0.plus(Duration.ofMinutes(10)), s.due)
        assertEquals(0, s.lapses)
    }

    @Test
    fun successiveGoodReviewsGrowIntervals() {
        var s = fsrs.review(SrsState(), Grade.Good, t0)
        var now = s.due
        var previous = Duration.between(t0, s.due)
        repeat(5) {
            s = fsrs.review(s, Grade.Good, now)
            val interval = Duration.between(now, s.due)
            assertTrue(interval > previous)
            previous = interval
            now = s.due
        }
        assertTrue(previous.toDays() > 60)
    }

    @Test
    fun lapseShrinksStabilityAndCountsLapse() {
        var s = fsrs.review(SrsState(), Grade.Good, t0)
        s = fsrs.review(s, Grade.Good, s.due)
        val before = s.stability
        val lapsed = fsrs.review(s, Grade.Again, s.due.plus(Duration.ofDays(5)))
        assertEquals(1, lapsed.lapses)
        assertTrue(lapsed.stability < before)
    }

    @Test
    fun retrievabilityIsNinetyPercentAtScheduledInterval() {
        val s = fsrs.review(SrsState(), Grade.Good, t0)
        assertEquals(0.9, fsrs.retrievability(s, s.due), 0.02)
    }

    @Test
    fun difficultyStaysInRange() {
        var s = fsrs.review(SrsState(), Grade.Good, t0)
        repeat(30) { s = fsrs.review(s, Grade.Again, s.due) }
        assertTrue(s.difficulty in 1.0..10.0)
        repeat(30) { s = fsrs.review(s, Grade.Easy, s.due) }
        assertTrue(s.difficulty in 1.0..10.0)
    }

    @Test
    fun matchesPyFsrsReferenceSequence() {
        // Generated with py-fsrs 5.1.0 (FSRS-5 defaults, no fuzz, no learning steps), reviewing each card exactly when due.
        val expected = listOf(
            Triple(Grade.Good, 3.173, 5.2824 to 3L),
            Triple(Grade.Good, 10.7389, 5.273 to 11L),
            Triple(Grade.Hard, 16.2576, 6.0271 to 16L),
            Triple(Grade.Good, 45.0303, 6.0142 to 45L),
        )
        var s = SrsState()
        var now = t0
        for ((grade, stability, dd) in expected) {
            s = fsrs.review(s, grade, now)
            assertEquals(stability, s.stability, 1e-3)
            assertEquals(dd.first, s.difficulty, 1e-3)
            assertEquals(dd.second, Duration.between(now, s.due).toDays())
            now = s.due
        }
    }

    @Test
    fun guessableFirstSuccessIsCappedToADayButOtherGradesAreNot() {
        val good = fsrs.review(SrsState(), Grade.Good, t0, firstSuccessCapDays = 1.0)
        assertEquals(Duration.ofDays(1), Duration.between(t0, good.due))
        assertEquals(1.0, good.stability, 0.0)
        val again = fsrs.review(SrsState(), Grade.Again, t0, firstSuccessCapDays = 1.0)
        assertEquals(Duration.ofMinutes(10), Duration.between(t0, again.due))
        val second = fsrs.review(good, Grade.Good, good.due, firstSuccessCapDays = 1.0)
        assertTrue(Duration.between(good.due, second.due).toDays() > 1)
    }

    @Test
    fun fuzzIsDeterministicBoundedAndSkipsShortIntervals() {
        assertEquals(1L, fsrs.fuzzed(1, 42))
        assertEquals(2L, fsrs.fuzzed(2, 42))
        val seeds = (1L..500L).map { Fsrs.seed("水", "Quiz", it.toInt()) }
        assertEquals(fsrs.fuzzed(30, seeds[0]), fsrs.fuzzed(30, seeds[0]))
        val spread = seeds.map { fsrs.fuzzed(100, it) }
        assertTrue(spread.all { it in 95L..105L })
        assertTrue(spread.toSet().size > 5)
        assertTrue(seeds.map { fsrs.fuzzed(5, it) }.all { it in 4L..6L })
        assertEquals(30L, fsrs.fuzzed(30, null))
    }

    @Test
    fun lapseStabilityNeverExceedsReferenceCap() {
        val w = Fsrs.DEFAULT_WEIGHTS
        var s = fsrs.review(SrsState(), Grade.Good, t0)
        repeat(3) { s = fsrs.review(s, Grade.Good, s.due) }
        val before = s.stability
        val lapsed = fsrs.review(s, Grade.Again, s.due)
        assertTrue(lapsed.stability <= before / Math.exp(w[17] * w[18]) + 1e-9)
        assertEquals(1, lapsed.lapses)
    }
}