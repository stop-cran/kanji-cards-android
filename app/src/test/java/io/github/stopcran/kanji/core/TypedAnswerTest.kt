package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.TypedAnswer
import io.github.stopcran.kanji.core.words.TypedVerdict
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQueues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TypedAnswerTest {
    private fun card(word: String, reading: String, title: String, type: String? = "wago") = WordCard(word, reading, title, type, emptyList())

    private val joke = card("冗談", "じょうだん", "joke; jest")
    private val look = card("見る", "みる", "to see")
    private val watch = card("観る", "みる", "to see")
    private val kana = card("ありがとう", "ありがとう", "thanks")
    private val other = card("談話", "だんわ", "conversation")
    private val all = listOf(joke, look, watch, kana, other)

    private fun verdict(input: String, target: WordCard) = TypedAnswer.grade(input, target, all).verdict

    @Test
    fun exactWrittenFormIsExactIgnoringSpacesAndWidth() {
        assertEquals(TypedVerdict.Exact, verdict("冗談", joke))
        assertEquals(TypedVerdict.Exact, verdict("  冗 談 ", joke))
        assertEquals(TypedVerdict.Exact, verdict("ありがとう", kana))
    }

    @Test
    fun kanaWordAcceptsKatakanaSpelling() {
        assertEquals(TypedVerdict.Exact, verdict("アリガトウ", kana))
    }

    @Test
    fun readingInKanaForKanjiWordIsKanaOnly() {
        assertEquals(TypedVerdict.KanaOnly, verdict("じょうだん", joke))
        assertEquals(TypedVerdict.KanaOnly, verdict("ジョウダン", joke))
        assertTrue(TypedAnswer.grade("じょうだん", joke, all).ok)
    }

    @Test
    fun synonymWithSameTitleAndTypeIsExactButReadingStaysKanaOnly() {
        assertEquals(TypedVerdict.Exact, verdict("観る", look))
        assertEquals(TypedVerdict.KanaOnly, verdict("みる", look))
        assertEquals(watch, TypedAnswer.grade("観る", look, all).matched)
    }

    @Test
    fun differentWordIsWrongAndNamed() {
        val r = TypedAnswer.grade("談話", joke, all)
        assertEquals(TypedVerdict.Wrong, r.verdict)
        assertEquals(other, r.matched)
        assertFalse(r.ok)
        assertEquals(TypedVerdict.Wrong, verdict("", joke))
        assertEquals(TypedVerdict.Wrong, verdict("xyz", joke))
    }

    @Test
    fun typingIsGatedByEnglishToJapaneseNotByForward() {
        val now = Instant.parse("2025-03-01T00:00:00Z")
        val known = SrsState(CardPhase.Review, 5.0, 5.0, now, now.minusSeconds(86_400), 3, 0)
        assertEquals(WordDirection.EnToJp, WordDirection.Type.gate)
        val locked = WordQueues.build(WordDirection.Type, listOf("a"), emptyMap(), mapOf("a" to SrsState()), now, 10, noise = 0.0)
        assertTrue(locked.isEmpty())
        val open = WordQueues.build(WordDirection.Type, listOf("a"), emptyMap(), mapOf("a" to known), now, 10, noise = 0.0)
        assertEquals(listOf("a"), open.map { it.kanji })
    }
}
