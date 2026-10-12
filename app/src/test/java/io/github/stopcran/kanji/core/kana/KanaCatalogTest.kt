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
