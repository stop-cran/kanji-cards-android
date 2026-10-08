package io.github.stopcran.kanji.core.srs

import java.time.Instant

enum class StudyMode { Quiz, Draw }

data class QueueItem(val kanji: String, val state: SrsState, val isNew: Boolean)

/** Due cards first (most overdue first), then new cards in content order, capped by the remaining daily new-card budget. */
object QueueBuilder {
    fun build(
        allKanji: List<String>,
        states: Map<String, SrsState>,
        now: Instant,
        newCardsRemainingToday: Int,
        limit: Int = Int.MAX_VALUE,
    ): List<QueueItem> {
        val due = allKanji.mapNotNull { k ->
            val s = states[k] ?: return@mapNotNull null
            if (s.phase != CardPhase.New && !s.due.isAfter(now)) QueueItem(k, s, false) else null
        }.sortedBy { it.state.due }
        val fresh = allKanji.filter { states[it] == null || states[it]!!.phase == CardPhase.New }
            .take(maxOf(0, newCardsRemainingToday))
            .map { QueueItem(it, states[it] ?: SrsState(), true) }
        return (due + fresh).take(limit)
    }
}
