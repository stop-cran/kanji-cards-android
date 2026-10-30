package io.github.stopcran.kanji.core.unlock

import java.time.Instant
import java.time.ZoneId

/** When Draw and the readings open for a kanji. */
enum class UnlockRule(val label: String, val hint: String) {
    NextDay("Next day", "Unlocks after you answer a kanji's meaning well on two different days"),
    SpacedSameDay("Spaced same day", "Unlocks after you answer a kanji's meaning well twice, with other cards and at least 10 minutes in between"),
    ;

    companion object {
        fun parse(name: String?) = entries.firstOrNull { it.name == name } ?: NextDay
    }
}

/** One persisted review answer, as loaded for the unlock rules (any mode of one source and stack). */
data class LogEvent(val id: Long, val atMs: Long, val kanji: String, val mode: String, val grade: Int)

/**
 * Which kanji have been recognised well enough to be drawn and read, from the persisted review log only (so it survives restarts).
 * Practice-only confirmations are never logged and therefore never count.
 */
object MeaningUnlock {
    const val MIN_GAP_MS = 10 * 60 * 1000L
    const val MIN_BETWEEN = 10
    private const val MEANING = "Quiz"
    private const val AGAIN = 1

    /** [events] are all of one (source, stack), in any order; they are sorted by (time, id) here. */
    fun learned(events: List<LogEvent>, rule: UnlockRule, zone: ZoneId = ZoneId.systemDefault()): Set<String> {
        val ordered = events.sortedWith(compareBy({ it.atMs }, { it.id }))
        val nextDay = nextDay(ordered, zone)
        if (rule == UnlockRule.NextDay) return nextDay
        return nextDay + spaced(ordered)
    }

    /** Two answers above Again on two different local dates; an Again does not take it back. */
    private fun nextDay(ordered: List<LogEvent>, zone: ZoneId): Set<String> =
        ordered.filter { it.mode == MEANING && it.grade > AGAIN }
            .groupBy { it.kanji }
            .filterValues { list -> list.map { Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() }.distinct().size >= 2 }
            .keys

    /** Two answers above Again after the latest Again, far enough apart in time and in intervening answers (both required, inclusive). */
    private fun spaced(ordered: List<LogEvent>): Set<String> {
        val meaning = HashMap<String, MutableList<Pair<Int, LogEvent>>>()
        ordered.forEachIndexed { i, e -> if (e.mode == MEANING) meaning.getOrPut(e.kanji) { mutableListOf() } += i to e }
        val out = HashSet<String>()
        for ((kanji, list) in meaning) {
            val sinceAgain = list.takeLastWhile { it.second.grade > AGAIN }
            if (sinceAgain.size < 2) continue
            val (firstIndex, first) = sinceAgain.first()
            val (lastIndex, last) = sinceAgain.last()
            if (last.atMs - first.atMs >= MIN_GAP_MS && lastIndex - firstIndex - 1 >= MIN_BETWEEN) out += kanji
        }
        return out
    }
}
