package io.github.stopcran.kanji.core.kana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertFalse
import org.junit.Test

class KanaCatalogTest {
    @Test
    fun combinationsUnlockWhenComponentsAreSolid() {
        val solid = SrsState(phase = CardPhase.Review, stability = 7.0)
        fun open(script: KanaScript, states: Map<String, SrsState>) = KanaCatalog.unlocked(script, states).map { it.kana }
        assertFalse("ちゅ" in open(KanaScript.Hiragana, emptyMap()))
        assertFalse("ちゅ" in open(KanaScript.Hiragana, mapOf("ち" to solid)))
        assertFalse(
            "ちゅ" in open(
                KanaScript.Hiragana,
                mapOf("ち" to solid, "ゆ" to SrsState(phase = CardPhase.Review, stability = 6.99)),
            ),
        )
        assertTrue("ちゅ" in open(KanaScript.Hiragana, mapOf("ち" to solid, "ゆ" to solid)))
        assertTrue("チュ" in open(KanaScript.Katakana, mapOf("チ" to solid, "ユ" to solid)))
        assertTrue("ちゅ" in open(KanaScript.Hiragana, mapOf("ちゅ" to SrsState())))
        assertEquals(listOf("じ", "や"), KanaCatalog.components(KanaCatalog.card(KanaScript.Hiragana, "じゃ")!!))
    }

    @Test
    fun voicedKanaUnlockWhenTheirBaseIsSolid() {
        fun open(script: KanaScript, states: Map<String, SrsState>) = KanaCatalog.unlocked(script, states).map { it.kana }
        val solid = SrsState(phase = CardPhase.Review, stability = 3.0)
        val shaky = SrsState(phase = CardPhase.Review, stability = 2.99)
        assertFalse("が" in open(KanaScript.Hiragana, emptyMap()))
        assertFalse("が" in open(KanaScript.Hiragana, mapOf("か" to shaky)))
        assertFalse("が" in open(KanaScript.Hiragana, mapOf("か" to SrsState(phase = CardPhase.Learning, stability = 5.0))))
        assertTrue("が" in open(KanaScript.Hiragana, mapOf("か" to solid)))
        assertTrue("あ" in open(KanaScript.Hiragana, emptyMap()))
        assertTrue("が" in open(KanaScript.Hiragana, mapOf("が" to SrsState())))
        assertTrue("ぢ" in open(KanaScript.Hiragana, mapOf("ち" to solid)))
        assertTrue("づ" in open(KanaScript.Hiragana, mapOf("つ" to solid)))
        assertTrue("ヂ" in open(KanaScript.Katakana, mapOf("チ" to solid)))
        assertTrue("パ" in open(KanaScript.Katakana, mapOf("ハ" to solid)))
        assertTrue("ば" in open(KanaScript.Hiragana, mapOf("は" to solid)) && "ぱ" in open(KanaScript.Hiragana, mapOf("は" to solid)))
        assertFalse("ガ" in open(KanaScript.Katakana, mapOf("か" to solid)))
    }

    @Test
    fun everyVoicedKanaHasAnUnvoicedBasicBase() {
        KanaScript.entries.forEach { script ->
            val cards = KanaCatalog.all(script)
            val voiced = cards.filter { KanaCatalog.voicedBase(it) != null }
            assertEquals(25, voiced.size)
            voiced.forEach { card ->
                val base = KanaCatalog.card(script, KanaCatalog.voicedBase(card)!!)!!
                assertEquals(null, KanaCatalog.voicedBase(base))
                assertTrue(KanaCatalog.components(base).isEmpty())
            }
        }
    }

    @Test
    fun yoonOnVoicedKanaStillNeedsTheVoicedKanaToBeSolid() {
        val seven = SrsState(phase = CardPhase.Review, stability = 7.0)
        val open = KanaCatalog.unlocked(KanaScript.Hiragana, mapOf("か" to seven, "や" to seven)).map { it.kana }
        assertFalse("ぎゃ" in open)
        val voicedSolid = KanaCatalog.unlocked(KanaScript.Hiragana, mapOf("ぎ" to seven, "や" to seven)).map { it.kana }
        assertTrue("ぎゃ" in voicedSolid)
    }

    @Test
    fun eachScriptHasAllCoreAndCombinedKanaWithUniqueRomaji() {
        KanaScript.entries.forEach { script ->
            val cards = KanaCatalog.all(script)
            assertEquals(104, cards.size)
            assertEquals(104, cards.map { it.kana }.distinct().size)
            assertEquals(104, cards.map { it.romaji }.distinct().size)
            assertTrue(cards.all { it.script == script })
        }
    }

    @Test
    fun katakanaUsesScriptSpecificFormsForCombinations() {
        assertEquals("きゃ", KanaCatalog.card(KanaScript.Hiragana, "きゃ")?.kana)
        assertEquals("キャ", KanaCatalog.card(KanaScript.Katakana, "キャ")?.kana)
        assertEquals("kya", KanaCatalog.card(KanaScript.Katakana, "キャ")?.romaji)
    }

    @Test
    fun uncommonVoicedKanaHaveUnambiguousPromptsAndAcceptedTypingAliases() {
        val hiragana = KanaCatalog.all(KanaScript.Hiragana).associateBy { it.kana }
        assertEquals("di", hiragana.getValue("ぢ").romaji)
        assertEquals(listOf("ji"), hiragana.getValue("ぢ").alternateRomaji)
        assertEquals("du", hiragana.getValue("づ").romaji)
        assertEquals(listOf("zu"), hiragana.getValue("づ").alternateRomaji)
    }
}
