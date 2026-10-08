package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.Romaji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomajiTest {
    @Test
    fun convertsHepburnAndKunrei() {
        assertEquals("やすむ", Romaji.toHiragana("yasumu"))
        assertEquals("きゅうしゅう", Romaji.toHiragana("kyuushuu"))
        assertEquals("ちょう", Romaji.toHiragana("chou"))
        assertEquals("じゃ", Romaji.toHiragana("ja"))
        assertEquals("つき", Romaji.toHiragana("tsuki"))
        assertEquals("がっこう", Romaji.toHiragana("gakkou"))
        assertEquals("きっちょう", Romaji.toHiragana("kitchou"))
        assertEquals("しんぶん", Romaji.toHiragana("shinbun"))
        assertEquals("ほんや", Romaji.toHiragana("hon'ya"))
        assertEquals("とうきょう", Romaji.toHiragana("toukyou"))
        assertEquals("とうきょう", Romaji.toHiragana("tōkyō"))
    }

    @Test
    fun rejectsNonRomaji() {
        assertNull(Romaji.toHiragana("rest"))
        assertNull(Romaji.toHiragana("休"))
        assertNull(Romaji.toHiragana(""))
    }
}