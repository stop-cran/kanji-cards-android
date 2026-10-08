package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.CardSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSearchTest {
    private val rest = Triple("休", "rest", listOf("キュウ", "やす.む", "やす.まる"))

    private fun s(q: String) = CardSearch.score(rest.first, rest.second, rest.third, q)

    @Test
    fun emptyQueryMatchesEverything() = assertTrue(s("  ") > 0)

    @Test
    fun matchesKanjiTitleAndReadings() {
        assertTrue(s("休") > 0)
        assertTrue(s("rest") > 0)
        assertTrue(s("RES") > 0)
        assertTrue(s("やす") > 0)
        assertTrue(s("やすむ") > 0)
        assertTrue(s("きゅう") > 0)
        assertTrue(s("キュウ") > 0)
        assertEquals(0, s("みず"))
    }

    @Test
    fun exactReadingOutranksPartial() {
        val exact = CardSearch.score("日", "sun", listOf("ひ", "-び"), "ひ")
        val partial = CardSearch.score("人", "person", listOf("ひと"), "ひ")
        assertTrue(exact > partial)
        assertTrue(partial > 0)
    }

    @Test
    fun wholeWordTitleOutranksSubstring() {
        assertTrue(CardSearch.score("木", "tree", listOf("モク"), "tree") > CardSearch.score("木", "street", listOf("モク"), "tree"))
    }
}
