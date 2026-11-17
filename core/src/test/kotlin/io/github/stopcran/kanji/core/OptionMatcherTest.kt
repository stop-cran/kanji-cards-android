package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.voice.OptionMatcher
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQuizBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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

    @Test fun russianMeaningsAndNumberFormsMatchWithoutEnglishNormalization() {
        assertEquals(1, OptionMatcher.match(listOf("прекрасный день"), listOf("вода", "прекрасный день"), "ru"))
        assertEquals(1, OptionMatcher.match(listOf("2"), listOf("один", "две", "три"), "ru"))
        assertEquals(1, OptionMatcher.match(listOf("ёжи"), listOf("вода", "ежи"), "ru"))
        assertNull(OptionMatcher.match(listOf("water"), listOf("вода", "дерево"), "ru"))
    }

    @Test fun parentheticalQualifierIsNotASpokenMeaning() {
        val words = listOf("дверь", "позади", "старший брат", "все (уважительно, о людях)")
        assertNull(OptionMatcher.match(listOf("о людях"), words, "ru"))
        assertEquals(3, OptionMatcher.match(listOf("все"), words, "ru"))

        val kanji = listOf("дерево", "вода", "огонь", "толстый (например, в слове со значением «солнце»)")
        assertNull(OptionMatcher.match(listOf("солнце"), kanji, "ru"))
        assertEquals(3, OptionMatcher.match(listOf("толстый"), kanji, "ru"))
    }

    @Test fun meaningsOutsideQualifiersRemainVoiceVariants() {
        val title = "cooked rice (food, not raw; often served (warm, plain)); meal, dinner"
        assertEquals(listOf("cooked rice meal dinner", "cooked rice", "meal", "dinner"), OptionMatcher.variants(title))
        assertEquals(0, OptionMatcher.match(listOf("cooked"), listOf(title, "water")))
        assertEquals(0, OptionMatcher.match(listOf("meal"), listOf(title, "water")))
        assertEquals(0, OptionMatcher.match(listOf("dinner"), listOf(title, "water")))
        assertNull(OptionMatcher.match(listOf("plain"), listOf(title, "water")))
    }

    @Test fun unfinishedQualifierDoesNotLeakItsContents() {
        assertEquals(listOf("все каждый", "все", "каждый"), OptionMatcher.variants("все, каждый (уважительно, о людях", "ru"))
        assertNull(OptionMatcher.match(listOf("о людях"), listOf("все, каждый (уважительно, о людях", "вода"), "ru"))
    }

    @Test fun completeThickMeaningsBeatPartialFoil() {
        val thick = listOf("небо", "дерево", "толстый (в том числе о толщине книги); сердечный",
            "толстый (в обхвате); полный; великий (в том числе в слове со значением «солнце»)")
        assertEquals(3, OptionMatcher.match(listOf("толстый полный"), thick, "ru"))
        assertNull(OptionMatcher.match(listOf("толстый"), thick, "ru"))
    }

    @Test fun completeBusinessMeaningsBeatPartialFoil() {
        val business = listOf("сверху", "дело; случай", "разговор",
            "вести дело; деятельность; управлять (в том числе предприятием)")
        assertEquals(3, OptionMatcher.match(listOf("вести дело управлять"), business, "ru"))
        assertNull(OptionMatcher.match(listOf("управлять вести дело"), business, "ru"))
        assertNull(OptionMatcher.match(listOf("деятельность"), listOf(
            "сверху", "жизнь; деятельность", "разговор", business[3],
        ), "ru"))
        assertEquals(1, OptionMatcher.match(listOf("деятельность"), listOf(
            "сверху", "жизнь; деятельность", "разговор", "вести дело; осуществлять деятельность; управлять",
        ), "ru"))
    }

    @Test fun completeGroupMeaningsBeatPartialFoil() {
        val options = listOf("вверх; над", "группа; соединять", "говорить; разговаривать",
            "группа; объединение; скопление")
        assertEquals(3, OptionMatcher.match(listOf("группа объединение"), options, "ru"))
        assertNull(OptionMatcher.match(listOf("группа"), options, "ru"))
    }

    @Test fun combinedMeaningsRequireCompleteDistinctUnambiguousComponents() {
        val options = listOf("red; blue; green", "red; yellow", "water", "stone")
        assertEquals(0, OptionMatcher.match(listOf("red blue"), options))
        assertEquals(0, OptionMatcher.match(listOf("red green"), options))
        assertNull(OptionMatcher.match(listOf("blue red green"), options))
        assertEquals(0, OptionMatcher.match(listOf("blue red"), listOf("red; blue; green", "water")))
        assertNull(OptionMatcher.match(listOf("red yellow"), listOf("red; yellow; green", "red; yellow; blue")))
        assertEquals(1, OptionMatcher.match(listOf("red blue"), listOf("red; blue; green", "red blue")))
        assertNull(OptionMatcher.match(listOf("red water"), options))
        assertNull(OptionMatcher.match(listOf("red banana"), options))
        assertNull(OptionMatcher.match(listOf("red blue banana"), options))
        assertNull(OptionMatcher.match(listOf("banana"), options))
        assertNull(OptionMatcher.match(listOf("red red"), options))
        assertEquals(0, OptionMatcher.match(listOf("blue red"), listOf("red; blue; red; green", "red; yellow")))
        assertNull(OptionMatcher.match(listOf("red red"), listOf("red; blue; red; green", "red; yellow")))
        assertEquals(0, OptionMatcher.match(listOf("quarter", "red blue"), options))
    }

    @Test fun combinationsDoNotOverrideConflictingHypotheses() {
        val cases = listOf(
            Triple(
                listOf("небо", "дерево", "толстый (в том числе о толщине книги); сердечный",
                    "толстый (в обхвате); полный; великий (в том числе в слове со значением «солнце»)"),
                "сердечный", "толстый полный",
            ),
            Triple(
                listOf("вверх; над", "группа; соединять", "говорить; разговаривать",
                    "группа; объединение; скопление"),
                "соединять", "группа объединение",
            ),
            Triple(
                listOf("вверх; над", "дело; случай", "говорить; разговаривать",
                    "вести дело; деятельность; управлять (в том числе предприятием)"),
                "случай", "вести дело управлять",
            ),
        )
        for ((options, primary, alternative) in cases) {
            val foil = options.indexOfFirst { primary in it }
            assertEquals(foil, OptionMatcher.match(listOf(primary), options, "ru"))
            assertEquals(foil, OptionMatcher.match(listOf(primary, alternative), options, "ru"))
            assertEquals(foil, OptionMatcher.match(listOf(alternative, primary), options, "ru"))
            assertEquals(foil, OptionMatcher.match(listOf("$primary пожалуйста"), options, "ru"))
            assertNull(OptionMatcher.match(listOf("$primary пожалуйста", alternative), options, "ru"))
            assertNull(OptionMatcher.match(listOf(alternative, "$primary пожалуйста"), options, "ru"))
            assertEquals(3, OptionMatcher.match(listOf("неизвестно", alternative), options, "ru"))
        }
    }

    @Test fun combinationDoesNotOverrideAmbiguousEvidenceForOtherOptions() {
        val options = listOf("вверх; над", "дело; случай", "край; случай",
            "вести дело; деятельность; управлять (в том числе предприятием)")
        val ambiguous = "случай пожалуйста"
        val combination = "вести дело управлять"
        assertNull(OptionMatcher.match(listOf(ambiguous), options, "ru"))
        assertEquals(3, OptionMatcher.match(listOf(combination), options, "ru"))
        assertNull(OptionMatcher.match(listOf(ambiguous, combination), options, "ru"))
        assertNull(OptionMatcher.match(listOf(combination, ambiguous), options, "ru"))
        assertNull(OptionMatcher.match(listOf("случай", combination), options, "ru"))
        assertEquals(3, OptionMatcher.match(listOf("неизвестно", combination), options, "ru"))
    }

    @Test fun curatedWordDistractorsAppearAndRespectExclusionsAndLeaveASlotForScoring() {
        fun word(id: String, title: String, distractors: List<String> = emptyList(), exclusions: List<String> = emptyList()) =
            WordCard(id, id, title, "noun", emptyList(), quizExclusions = exclusions, quizDistractors = distractors)
        val words = listOf(word("target", "today", listOf("d1", "d2", "d3", "x1", "ghost"), listOf("x1")),
            word("d1", "this morning"), word("d2", "this year"), word("d3", "this month"), word("x1", "mood"),
            word("f1", "water"), word("f2", "tree"), word("f3", "sky"), word("f4", "stone"))
        val seen = hashSetOf<String>()
        repeat(40) { seed ->
            val shown = WordQuizBuilder.options(words[0], words, WordDirection.JpToEn, Random(seed)).map { it.word }
            assertEquals(4, shown.size)
            assertFalse("x1" in shown)
            assertTrue(shown.count { it in setOf("d1", "d2", "d3") } >= 2)
            seen += shown
        }
        assertTrue(seen.containsAll(listOf("d1", "d2", "d3")))
    }

    @Test fun curatedWordChoicesRemainTheClosedSetForVoicePicks() {
        fun word(id: String, title: String, exclusions: List<String> = emptyList()) =
            WordCard(id, id, title, "noun", emptyList(), quizExclusions = exclusions)
        val words = listOf(
            word("mood-a", "mood", listOf("mood-b")),
            word("mood-b", "feeling; mood"),
            word("tree", "tree"),
            word("water", "water"),
            word("mountain", "mountain"),
            word("sky", "sky"),
        )
        for (target in words.take(3)) {
            repeat(20) { seed ->
                val shown = WordQuizBuilder.options(target, words, WordDirection.JpToEn, Random(seed))
                assertEquals(4, shown.size)
                assertFalse(shown.map { it.word }.containsAll(listOf("mood-a", "mood-b")))
                val labels = shown.map { it.label }
                shown.forEachIndexed { index, option ->
                    assertEquals(index, OptionMatcher.match(listOf(option.label), labels))
                }
            }
        }
    }
}
