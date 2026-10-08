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
}
