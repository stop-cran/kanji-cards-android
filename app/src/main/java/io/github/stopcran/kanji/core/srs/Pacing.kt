package io.github.stopcran.kanji.core.srs

import kotlin.math.ceil
import kotlin.math.roundToInt

/** How many new items the learner wants on a given weekday: capacity is uneven across a week. */
enum class DayLevel(val code: Char, val factor: Double) {
    Off('O', 0.0), Light('L', 0.5), Full('F', 1.0);

    fun next(): DayLevel = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Seven codes, Monday first; anything malformed falls back to full days. */
        fun parseWeek(text: String?): List<DayLevel> =
            List(7) { i -> entries.firstOrNull { it.code == text?.getOrNull(i) } ?: Full }

        fun encode(week: List<DayLevel>): String = week.joinToString("") { it.code.toString() }
    }
}

enum class PacingNote { None, RestDay, LightDay, BacklogReduced, BacklogPaused }

/** [total] is today's allowance of new items across every mode; [remaining] is what is left after those already started today. */
data class NewAllowance(val total: Int, val remaining: Int, val note: PacingNote, val due: Int, val capacity: Int)

/**
 * One shared daily allowance of new items. It follows the learner's weekly rhythm (per-weekday level) and backs off while a review backlog
 * exceeds what they usually get through, so intake adapts to workload instead of being a constant. Reviews themselves are never capped.
 */
object Pacing {
    /** Reviews per day assumed when there is little history, and the floor for a measured capacity. */
    const val MIN_CAPACITY = 40

    /** Typical reviews on a day the learner studied: the median of the days with any, never below [MIN_CAPACITY]. */
    fun capacity(recentDailyReviews: List<Int>): Int {
        val active = recentDailyReviews.filter { it > 0 }.sorted()
        if (active.isEmpty()) return MIN_CAPACITY
        val median = if (active.size % 2 == 1) active[active.size / 2] else (active[active.size / 2 - 1] + active[active.size / 2]) / 2
        return maxOf(MIN_CAPACITY, median)
    }

    /** 1 up to a backlog of one day's capacity, falling linearly to 0 at twice that. */
    fun backlogFactor(due: Int, capacity: Int): Double = (2.0 - due.toDouble() / capacity).coerceIn(0.0, 1.0)

    fun allowance(base: Int, level: DayLevel, introducedToday: Int, due: Int, capacity: Int): NewAllowance {
        val backlog = backlogFactor(due, capacity)
        val scaled = base * level.factor * backlog
        val total = if (level == DayLevel.Light) ceil(scaled).toInt() else scaled.roundToInt()
        val note = when {
            level != DayLevel.Off && backlog == 0.0 && base > 0 -> PacingNote.BacklogPaused
            level == DayLevel.Off -> PacingNote.RestDay
            backlog < 1.0 && base > 0 -> PacingNote.BacklogReduced
            level == DayLevel.Light -> PacingNote.LightDay
            else -> PacingNote.None
        }
        return NewAllowance(total, maxOf(0, total - introducedToday), note, due, capacity)
    }
}
