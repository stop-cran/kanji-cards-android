package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.CardSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSearchTest {
    private val on = listOf("キュウ")
    private val kun = listOf("やす.む", "やす.まる")

    private fun s(q: String) = CardSearch.score("休", "rest", on, kun, q)

    @Test
    fun romajiFindsReadingsButRanksBelowMatchingKana() {
        assertTrue(s("yasumu") > 0)
        assertTrue(s("kyuu") > 0)
        assertTrue(s("kyu") > 0)
        assertTrue(s("yasumu") < s("やすむ"))
        assertTrue(s("kyuu") < s("キュウ"))
        assertTrue(s("kyuu") > s("きゅう") - 30 - 1)
        assertEquals(0, s("mizu"))
    }

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
        val exact = CardSearch.score("日", "sun", emptyList(), listOf("ひ", "-び"), "ひ")
        val partial = CardSearch.score("人", "person", emptyList(), listOf("ひと"), "ひ")
        assertTrue(exact > partial)
        assertTrue(partial > 0)
    }

    @Test
    fun katakanaPrefersOnyomiAndHiraganaPrefersKunyomi() {
        // 木 has kun き and on モク; 気 has on キ and kun -.
        val tree = { q: String -> CardSearch.score("木", "tree", listOf("モク", "ボク"), listOf("き", "こ-"), q) }
        val spirit = { q: String -> CardSearch.score("気", "spirit", listOf("キ", "ケ"), emptyList(), q) }
        assertTrue(spirit("キ") > tree("キ"))
        assertTrue(tree("き") > spirit("き"))
        assertTrue(tree("キ") > 0 && spirit("き") > 0)
    }

    @Test
    fun wholeWordTitleOutranksSubstring() {
        assertTrue(CardSearch.score("木", "tree", listOf("モク"), emptyList(), "tree") > CardSearch.score("木", "street", listOf("モク"), emptyList(), "tree"))
    }
}
