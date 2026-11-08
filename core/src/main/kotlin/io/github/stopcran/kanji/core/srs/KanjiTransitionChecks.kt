package io.github.stopcran.kanji.core.srs

/** Explicit look-alike pairs, independent of the more general multiple-choice distractor lists. */
object KanjiTransitionChecks {
    const val MAX_PER_UTC_DAY = 2
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val MIN_DELAY_MS = 2 * DAY_MS

    private val pairs = listOf(
        "自" to "目", "自" to "白", "体" to "休", "止" to "上",
        "太" to "犬", "太" to "大", "末" to "未", "末" to "本", "未" to "本",
    )

    fun related(kanji: String): Set<String> = pairs.flatMap { (a, b) ->
        when (kanji) {
            a -> listOf(b)
            b -> listOf(a)
            else -> emptyList()
        }
    }.toSet()

    /** The first spaced slot before the natural due date, with at most two reservations per UTC day. */
    fun nextDue(nowMs: Long, naturalDueMs: Long, reservations: Collection<Long>): Long? {
        if (nowMs > Long.MAX_VALUE - MIN_DELAY_MS) return null
        val occupied = reservations.groupingBy { Math.floorDiv(it, DAY_MS) }.eachCount()
        var due = nowMs + MIN_DELAY_MS
        while (due < naturalDueMs) {
            if (occupied.getOrDefault(Math.floorDiv(due, DAY_MS), 0) < MAX_PER_UTC_DAY) return due
            if (due > Long.MAX_VALUE - DAY_MS) return null
            due += DAY_MS
        }
        return null
    }
}
