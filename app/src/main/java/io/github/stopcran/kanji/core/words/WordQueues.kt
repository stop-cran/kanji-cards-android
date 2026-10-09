package io.github.stopcran.kanji.core.words

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.QueueItem
import io.github.stopcran.kanji.core.srs.SrsState
import java.time.Instant
import kotlin.random.Random

object WordQueues {
    /**
     * Queue for one direction. English to Japanese only includes words unlocked by the forward direction (or already started),
     * and the other direction's memory slightly defers urgency (see [WordBlend]).
     */
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
    ): List<QueueItem> {
        val eligible = when (direction) {
            WordDirection.JpToEn -> words
            WordDirection.EnToJp -> words.filter { w -> WordGate.reverseUnlocked(other[w]) || own[w]?.phase.let { it != null && it != CardPhase.New } }
        }
        return if (extra) QueueBuilder.extra(eligible, own, now, limit, noise, rnd)
        else QueueBuilder.build(eligible, own, now, newBudget, limit, noise, rnd) { w -> WordBlend.urgencyFactor(other[w], now) }
    }
}
