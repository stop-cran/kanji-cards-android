package io.github.stopcran.kanji.core.words

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.QueueItem
import io.github.stopcran.kanji.core.srs.SrsState
import java.time.Instant
import kotlin.random.Random

object WordQueues {
    fun eligible(
        direction: WordDirection, words: List<String>, own: Map<String, SrsState>, other: Map<String, SrsState>,
    ): List<String> = if (!direction.gated) words else words.filter { w ->
        WordGate.reverseUnlocked(other[w]) || own[w]?.phase.let { it != null && it != CardPhase.New }
    }

    /**
     * Queue for one direction. Gated directions only include words unlocked by their [WordDirection.gate] direction (or already
     * started); [other] is that gate direction's state (the caller passes the states of `direction.gate`). The other direction's
     * memory slightly defers urgency (see [WordBlend]).
     */
    @Suppress("LongParameterList")
    fun build(
        direction: WordDirection,
        words: List<String>,
        own: Map<String, SrsState>,
        other: Map<String, SrsState>,
        now: Instant,
        newBudget: Int,
        extra: Boolean = false,
        limit: Int = if (extra) 10 else Int.MAX_VALUE,
        noise: Double = QueueBuilder.DEFAULT_NOISE,
        rnd: Random = Random.Default,
        preferred: List<String> = emptyList(),
    ): List<QueueItem> {
        require(limit >= 0) { "Queue limit cannot be negative" }
        val eligible = eligible(direction, words, own, other)
        val selected = preferred.distinct().filter { it in eligible }
        val priority = when {
            selected.isEmpty() -> emptyList()
            extra -> QueueBuilder.extra(selected, own, now, limit, noise = 0.0, newCardsLimit = newBudget)
            else -> QueueBuilder.build(selected, own, now, newBudget, limit, noise = 0.0)
        }
        val remaining = eligible.filterNot { it in selected }
        val remainingLimit = (limit - priority.size).coerceAtLeast(0)
        val remainingBudget = (newBudget - priority.count { it.isNew }).coerceAtLeast(0)
        val ordinary = if (extra) QueueBuilder.extra(remaining, own, now, remainingLimit, noise, rnd)
        else QueueBuilder.build(remaining, own, now, remainingBudget, remainingLimit, noise, rnd) { w ->
            if (direction == WordDirection.Reading || direction == WordDirection.Type) 1.0 else WordBlend.urgencyFactor(other[w], now)
        }
        return priority + ordinary
    }
}
