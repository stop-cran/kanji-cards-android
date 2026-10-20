package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.WordScene
import io.github.stopcran.kanji.core.words.PictureQuestions
import io.github.stopcran.kanji.core.words.PictureScene
import io.github.stopcran.kanji.core.words.WordCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PictureQuestionsTest {
    private fun word(id: String, exclusions: List<String> = emptyList()) =
        WordCard(id, id, "meaning-$id", "wago", emptyList(), quizExclusions = exclusions)

    private fun scene(word: String, id: String = "primary", hash: String = "$word-$id", eligible: Boolean = true) =
        PictureScene(word, WordScene(id, id, "images/$word--$id.webp", "Scene $word $id", eligible), hash)

    @Test
    fun variantsKeepExclusionsAndExactImageAmbiguitiesOutOfBothDirections() {
        val target = word("a", listOf("b"))
        val cards = listOf(target, word("b"), word("c", listOf("a")), word("d"), word("e", listOf("d")), word("f"), word("g"))
        val scenes = cards.map { scene(it.word) } +
            listOf(scene("a", "alternate"), scene("a", "diagram", eligible = false), scene("f", "duplicate", "a-alternate"))
        val seen = mutableSetOf<String>()
        repeat(40) { seed ->
            val question = PictureQuestions.build(target, cards, scenes, Random(seed))!!
            seen += question.target.scene.id
            val ids = question.options.map { it.word }
            assertEquals(3, ids.size)
            assertEquals(1, ids.count { it == "a" })
            assertTrue(ids.none { it in setOf("b", "c", "f") })
            assertFalse("d" in ids && "e" in ids)
            assertTrue(question.scenes.values.all { it.scene.quizEligible })
            assertEquals(ids.size, question.scenes.values.map { it.imageHash }.toSet().size)
            val retry = PictureQuestions.build(target, cards, scenes, Random(seed + 100), question.target.scene.id)!!
            assertEquals(question.target, retry.target)
        }
        assertEquals(setOf("primary", "alternate"), seen)
    }

    @Test
    fun unusableCuratedChoicesDoNotConsumeTitlesOrPreventDistinctAlternateImages() {
        val target = word("a").copy(quizDistractors = listOf("missing", "b"))
        val missing = word("missing").copy(title = "shared")
        val replacement = word("c").copy(title = "shared")
        val cards = listOf(target, missing, word("b"), replacement, word("d"))
        val scenes = listOf(scene("a"), scene("b", hash = "same"), scene("c"), scene("d", hash = "same"), scene("d", "distinct"))
        repeat(20) { seed ->
            val question = PictureQuestions.build(target, cards, scenes, Random(seed))!!
            assertTrue(question.options.any { it.word == "c" })
            assertTrue(question.options.none { it.word == "missing" })
            assertEquals(question.options.size, question.scenes.values.map { it.imageHash }.toSet().size)
        }
    }

    @Test
    fun noQuestionForArticleOnlyMissingLockedScenesOrSoleAmbiguousAnswer() {
        val target = word("a")
        val cards = listOf(target, word("b"))
        assertNull(PictureQuestions.build(target, cards, listOf(scene("a", eligible = false), scene("b")), Random(1)))
        assertNull(PictureQuestions.build(target, cards, listOf(scene("a"), scene("b", hash = "a-primary")), Random(1)))
        assertNull(PictureQuestions.build(target.copy(quizExclusions = listOf("b")), cards, listOf(scene("a"), scene("b")), Random(1)))
        assertNull(PictureQuestions.build(target, cards, listOf(scene("a", "other"), scene("b")), Random(1), "previous"))
    }
}
