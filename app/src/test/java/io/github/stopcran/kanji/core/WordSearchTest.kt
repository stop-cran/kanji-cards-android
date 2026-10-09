package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.WordSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSearchTest {
    private fun s(q: String) = WordSearch.score("暑い", "あつい", "hot weather", q)

    @Test
    fun emptyQueryMatchesEverything() = assertTrue(s("  ") > 0)

    @Test
    fun matchesWrittenFormReadingInEitherScriptRomajiAndTitle() {
        assertTrue(s("暑") > 0)
        assertTrue(s("あつ") > 0)
        assertTrue(s("アツイ") > 0)
        assertTrue(s("atsui") > 0)
        assertTrue(s("weather") > 0)
        assertTrue(s("hot") > 0)
        assertEquals(0, s("cold"))
        assertEquals(0, s("さむい"))
    }

    @Test
    fun exactBeatsPartialAndKanaBeatsRomaji() {
        assertTrue(WordSearch.score("暑い", "あつい", "hot", "暑い") > WordSearch.score("暑い", "あつい", "hot", "暑"))
        assertTrue(s("あつい") > s("atsui"))
        assertTrue(s("あつい") > s("あつ"))
        assertTrue(s("hot") > s("wea"))
    }
}
