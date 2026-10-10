package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.Commitment
import io.github.stopcran.kanji.core.srs.DayLevel
import io.github.stopcran.kanji.core.srs.Pacing
import io.github.stopcran.kanji.core.srs.PacingNote
import org.junit.Assert.assertEquals
import org.junit.Test

class PacingTest {
    @Test
    fun capacityIsMedianOfActiveDaysWithAFloor() {
        assertEquals(40, Pacing.capacity(emptyList()))
        assertEquals(40, Pacing.capacity(listOf(0, 0, 10, 20)))
        assertEquals(90, Pacing.capacity(listOf(0, 60, 90, 120, 0)))
        assertEquals(100, Pacing.capacity(listOf(80, 120)))
    }

    @Test
    fun fullDayWithoutBacklogGivesTheBase() {
        val a = Pacing.allowance(10, DayLevel.Full, introducedToday = 3, due = 10, capacity = 40)
        assertEquals(10, a.total)
        assertEquals(7, a.remaining)
        assertEquals(PacingNote.None, a.note)
    }

    @Test
    fun lightDayHalvesAndRestDayStops() {
        assertEquals(5, Pacing.allowance(10, DayLevel.Light, 0, 0, 40).total)
        assertEquals(1, Pacing.allowance(1, DayLevel.Light, 0, 0, 40).total)
        val rest = Pacing.allowance(10, DayLevel.Off, 0, 0, 40)
        assertEquals(0, rest.remaining)
        assertEquals(PacingNote.RestDay, rest.note)
    }

    @Test
    fun backlogReducesThenPausesNewItems() {
        val reduced = Pacing.allowance(10, DayLevel.Full, 0, due = 60, capacity = 40)
        assertEquals(5, reduced.total)
        assertEquals(PacingNote.BacklogReduced, reduced.note)
        val paused = Pacing.allowance(10, DayLevel.Full, 0, due = 80, capacity = 40)
        assertEquals(0, paused.total)
        assertEquals(PacingNote.BacklogPaused, paused.note)
    }

    @Test
    fun itemsAlreadyStartedNeverGoNegative() {
        assertEquals(0, Pacing.allowance(10, DayLevel.Full, introducedToday = 12, due = 0, capacity = 40).remaining)
    }

    @Test
    fun weeklyGoalIsFiveDaysAtMostAndShrinksWithRestDays() {
        assertEquals(5, Pacing.weeklyGoal(DayLevel.parseWeek("FFFFFLL")))
        assertEquals(4, Pacing.weeklyGoal(DayLevel.parseWeek("FFFFOOO")))
        assertEquals(0, Pacing.weeklyGoal(DayLevel.parseWeek("OOOOOOO")))
    }

    @Test
    fun commitmentPresetsMapToTheBaseAmount() {
        assertEquals(Commitment.Steady, Commitment.of(10))
        assertEquals(null, Commitment.of(12))
        assertEquals(listOf(5, 10, 20), Commitment.entries.map { it.perDay })
    }

    @Test
    fun weekPlanParsesDefensively() {
        assertEquals(List(7) { DayLevel.Full }, DayLevel.parseWeek(null))
        assertEquals(DayLevel.Off, DayLevel.parseWeek("FFFFFFO")[6])
        assertEquals(DayLevel.Light, DayLevel.parseWeek("LxF")[0])
        assertEquals(DayLevel.Full, DayLevel.parseWeek("LxF")[1])
        assertEquals("FLOFFFF", DayLevel.encode(DayLevel.parseWeek("FLOFFFF")))
    }
}
