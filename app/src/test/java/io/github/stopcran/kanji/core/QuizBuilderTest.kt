package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.quiz.QuizBuilder
import io.github.stopcran.kanji.core.quiz.QuizCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizBuilderTest {
    private fun card(k: String, title: String, tags: List<String> = emptyList(), d: List<String> = emptyList()) = QuizCard(k, title, tags, d)

    private val all = listOf(
        card("a", "sun", listOf("n5", "g1"), listOf("b")),
        card("b", "moon", listOf("n5")),
        card("c", "tree", listOf("n5", "g1")),
        card("d", "fire", listOf("n3")),
        card("e", "water", listOf("n3")),
        card("f", "sun", listOf("n5")),
    )

    @Test
    fun containsTargetOnceAndUniqueTitles() {
        repeat(50) { seed ->
            val o = QuizBuilder.options(all[0], all, Random(seed))
            assertEquals(4, o.size)
            assertEquals(1, o.count { it.kanji == "a" })
            assertEquals(o.size, o.map { it.title }.toSet().size)
        }
    }

    @Test
    fun usesConfiguredDistractorsFirst() {
        repeat(20) { seed -> assertTrue(QuizBuilder.options(all[0], all, Random(seed)).any { it.kanji == "b" }) }
    }

    @Test
    fun smallDeckGivesFewerOptions() {
        val o = QuizBuilder.options(all[0], all.take(2), Random(1))
        assertEquals(listOf("a", "b"), o.map { it.kanji }.sorted())
    }
}
