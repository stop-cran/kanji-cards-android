package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.grammar.GrammarChoices
import io.github.stopcran.kanji.core.grammar.GrammarChoiceSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

internal class GrammarChoiceRandom(private val bucket: Int = 0, private val alternative: Int = 0) : Random() {
    override fun nextBits(bitCount: Int): Int = error("Unexpected random operation")
    override fun nextInt(until: Int): Int = if (until == 5) bucket else alternative.coerceAtMost(until - 1)
}

class GrammarChoicesTest {
    private val exercise = grammarFixture().exercises.first().copy(
        acceptedAnswers = listOf("a", "alternate", "another"),
        additionalCorrectOptions = listOf(
            GrammarOption("alternate", "alternate", "Fits differently."),
            GrammarOption("another", "another", "Another fitting framing."),
        ),
    )

    @Test
    fun exactlyOneOfFiveEligibleBucketsAddsOneCorrectChoiceAndKeepsEveryDistractor() {
        val questions = (0..4).map { GrammarChoices.build(exercise, GrammarChoiceRandom(it)) }
        assertEquals(listOf(2, 1, 1, 1, 1), questions.map { it.correctOptionIds.size })
        questions.forEach { question ->
            assertTrue(question.options.containsAll(exercise.options))
            assertEquals(question.correctOptionIds.size + 1, question.options.size)
            assertTrue(question.options.any { it.id !in question.correctOptionIds })
            assertTrue(question.options.filter { it.id in question.correctOptionIds }.all { it.label in exercise.acceptedAnswers })
        }
        for (bucket in 0..4) {
            val legacy = GrammarChoices.build(exercise.copy(additionalCorrectOptions = emptyList()), GrammarChoiceRandom(bucket))
            assertEquals(setOf(exercise.answer), legacy.correctOptionIds)
            assertEquals(exercise.options.toSet(), legacy.options.toSet())
        }
    }

    @Test
    fun authoredAlternativesRotateWithoutShowingThreeCorrectChoicesAndSnapshotRoundTrips() {
        for (index in 0..1) {
            val question = GrammarChoices.build(exercise, GrammarChoiceRandom(alternative = index))
            assertEquals(setOf(exercise.answer, exercise.additionalCorrectOptions[index].id), question.correctOptionIds)
            assertEquals(question, Json.decodeFromString(GrammarChoiceSnapshot.serializer(), GrammarChoices.encode(question)))
        }
    }
}
