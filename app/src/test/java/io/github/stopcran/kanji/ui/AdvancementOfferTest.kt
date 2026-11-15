package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.WordEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AdvancementOfferTest {
    private val now = Instant.parse("2026-01-10T00:00:00Z")
    private val solid = SrsState(CardPhase.Review, 30.0, 5.0, now, now.minusSeconds(86400), 3)
    private val kanji = (1..20).map {
        KanjiEntity("s", "k$it", "t", 5, "jlpt-n5", null, null, null, "オン", "くん", "", "", null, it)
    }
    private val words = (1..20).map { WordEntity("s", "w$it", "w$it", "m", null, "", "", "", 5) }

    private fun states(ids: List<String>) = ids.associateWith { solid }

    private fun offer(
        stack: io.github.stopcran.kanji.core.words.WordStack,
        kanjiCards: List<KanjiEntity> = kanji,
        readingsReady: Boolean = true,
    ): Boolean {
        val on = if (readingsReady) kanjiCards.associate { card -> ReadingId.of(card.kanji, "おん") to solid } else emptyMap()
        val kun = if (readingsReady) kanjiCards.associate { card -> ReadingId.of(card.kanji, "くん") to solid } else emptyMap()
        val set = StateSet(
            states(kanjiCards.map { it.kanji }), emptyMap(), on, kun,
            states(words.map { it.word }), states(words.map { it.word }), states(words.map { it.word }),
        )
        return shouldOfferN4(kanjiCards, words, emptyMap(), stack, set, n4Unlocked = false, dismissedMs = 0, now = now, nowMs = now.toEpochMilli())
    }

    @Test
    fun offerIsMadeOnlyFromTheN5WordStack() {
        assertTrue(offer(WordStacks.n5))
        assertFalse(offer(WordStacks.onomatopoeia))
    }

    @Test
    fun offerRequiresEvidenceForEachAvailableKanjiReadingSkill() {
        assertTrue(offer(WordStacks.n5))
        assertFalse(offer(WordStacks.n5, readingsReady = false))
    }

    @Test
    fun offerUsesUniqueLevelTagsWhenEntityLevelIsMissing() {
        assertTrue(offer(WordStacks.n5, kanji.map { it.copy(jlpt = null) }))
    }
}
