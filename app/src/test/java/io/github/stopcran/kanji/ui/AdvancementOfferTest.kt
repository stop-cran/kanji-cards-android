package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.WordEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AdvancementOfferTest {
    private val now = Instant.parse("2026-01-10T00:00:00Z")
    private val solid = SrsState(CardPhase.Review, 30.0, 5.0, now, now.minusSeconds(86400), 3)
    private val kanji = (1..20).map { KanjiEntity("s", "k$it", "t", 5, "", null, null, null, "", "", "", "", null, it) }
    private val words = (1..20).map { WordEntity("s", "w$it", "w$it", "m", null, "", "", "", 5) }

    private fun states(ids: List<String>) = ids.associateWith { solid }

    private fun offer(stack: io.github.stopcran.kanji.core.words.WordStack): Boolean {
        val set = StateSet(
            states(kanji.map { it.kanji }), emptyMap(), emptyMap(), emptyMap(),
            states(words.map { it.word }), states(words.map { it.word }), states(words.map { it.word }),
        )
        return shouldOfferN4(kanji, words, emptyMap(), stack, set, n4Unlocked = false, dismissedMs = 0, now = now, nowMs = now.toEpochMilli())
    }

    @Test
    fun offerIsMadeOnlyFromTheN5WordStack() {
        assertTrue(offer(WordStacks.n5))
        assertFalse(offer(WordStacks.onomatopoeia))
    }
}
