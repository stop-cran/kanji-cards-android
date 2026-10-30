package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.FontPolicy
import io.github.stopcran.kanji.core.srs.KanjiFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FontPolicyTest {
    @Test
    fun newAndYoungCardsGetOnlyPlainFont() {
        assertEquals(listOf(KanjiFont.Gothic), FontPolicy.allowed(0.0))
        assertEquals(listOf(KanjiFont.Gothic), FontPolicy.allowed(3.9))
    }

    @Test
    fun varietyGrowsWithStability() {
        assertEquals(2, FontPolicy.allowed(5.0).size)
        assertEquals(3, FontPolicy.allowed(20.0).size)
        assertEquals(KanjiFont.entries, FontPolicy.allowed(90.0))
    }

    @Test
    fun avoidsRepeatingPreviousFontWhenPossible() {
        val r = Random(1)
        repeat(50) { assertTrue(FontPolicy.pick(90.0, r, previous = KanjiFont.Mincho) != KanjiFont.Mincho) }
        assertEquals(KanjiFont.Gothic, FontPolicy.pick(0.0, r, previous = KanjiFont.Gothic))
    }
}
