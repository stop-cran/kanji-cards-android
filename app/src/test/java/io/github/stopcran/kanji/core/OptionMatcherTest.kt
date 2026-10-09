package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.voice.OptionMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptionMatcherTest {
    private val options = listOf("water", "up, above", "cooked rice; meal", "to eat")

    @Test fun exactMatch() = assertEquals(0, OptionMatcher.match(listOf("water"), options))

    @Test fun ignoresCaseAndPunctuation() = assertEquals(0, OptionMatcher.match(listOf("Water!"), options))

    @Test fun matchesOneOfSeveralMeanings() {
        assertEquals(1, OptionMatcher.match(listOf("above"), options))
        assertEquals(2, OptionMatcher.match(listOf("meal"), options))
        assertEquals(2, OptionMatcher.match(listOf("cooked rice"), options))
    }

    @Test fun infinitiveMarkerIsOptional() {
        assertEquals(3, OptionMatcher.match(listOf("eat"), options))
        assertEquals(3, OptionMatcher.match(listOf("to eat"), options))
    }

    @Test fun usesAlternativeHypotheses() = assertEquals(0, OptionMatcher.match(listOf("quarter", "water"), options))

    @Test fun toleratesSmallRecognitionError() = assertEquals(0, OptionMatcher.match(listOf("waiter"), options))

    @Test fun digitsMatchNumberWords() = assertEquals(1, OptionMatcher.match(listOf("2"), listOf("one", "two", "three")))

    @Test fun noClearMatchGivesNull() {
        assertNull(OptionMatcher.match(listOf("banana"), options))
        assertNull(OptionMatcher.match(emptyList(), options))
        assertNull(OptionMatcher.match(listOf(""), options))
    }

    @Test fun ambiguousPhraseGivesNull() = assertNull(OptionMatcher.match(listOf("up"), listOf("up, above", "up, upward")))

    @Test fun exactBeatsPartialOverlap() = assertEquals(1, OptionMatcher.match(listOf("one"), listOf("one person; alone", "one")))
}
