package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Confusion
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ConfusionTest {
    private val now = Instant.parse("2026-10-11T12:00:00Z")

    private fun state(phase: CardPhase, due: Instant) =
        SrsState(phase, stability = 20.0, difficulty = 5.0, due = due, lastReview = now.minus(Duration.ofDays(3)), reps = 4, lapses = 1)

    @Test
    fun pullsALaterDueDateToTwoDaysAndKeepsTheScore() {
        val s = state(CardPhase.Review, now.plus(Duration.ofDays(30)))
        val n = Confusion.nudged(s, now)!!
        assertEquals(now.plus(Duration.ofDays(2)), n.due)
        assertEquals(s.copy(due = n.due), n)
    }

    @Test
    fun leavesCardsDueSoonerUnchanged() {
        assertNull(Confusion.nudged(state(CardPhase.Review, now.plus(Duration.ofDays(1))), now))
        assertNull(Confusion.nudged(state(CardPhase.Review, now.plus(Duration.ofDays(2))), now))
        assertNull(Confusion.nudged(state(CardPhase.Learning, now.plus(Duration.ofMinutes(10))), now))
    }

    @Test
    fun neverStartsANewCard() {
        assertNull(Confusion.nudged(null, now))
        assertNull(Confusion.nudged(SrsState(), now))
    }

    @Test
    fun repeatedNudgesDoNotCompound() {
        val once = Confusion.nudged(state(CardPhase.Review, now.plus(Duration.ofDays(30))), now)!!
        assertNull(Confusion.nudged(once, now.plus(Duration.ofHours(1))))
    }
}
