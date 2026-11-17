package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.readingtext.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ReadingQuizTest {
    @Test fun threeOfFourAtTheCapturedHighestTierUnlocksOnlyTheFollowingQuizTier() {
        val record = content()
        val plan = ReadingQuiz.capture(record.content, record.hash, 2, ReadingBookMode.OpenBook, Random(1))
        assertEquals(listOf(1, 2, 2, 2, 2), plan.questions.map { it.question.tier })
        val result = ReadingQuiz.finish(plan, answers(plan, highestCorrect = 3, lowerCorrect = false))
        assertTrue(result.passed)
        assertEquals(3, result.unlockedTier)
        assertEquals(3, result.highestCorrect)
        assertEquals(4, result.highestCount)
        assertEquals(2, plan.highestTier)
        val failed = ReadingQuiz.finish(plan, answers(plan, highestCorrect = 2, lowerCorrect = true))
        assertFalse(failed.passed)
        assertEquals(2, failed.unlockedTier)
    }

    @Test fun bothModesPreserveQuestionPicturesAndCanAdvanceSharedProgressWithoutADayGate() {
        val record = content()
        val first = ReadingQuiz.capture(record.content, record.hash, 1, ReadingBookMode.OpenBook, Random(1))
        val afterFirst = ReadingQuiz.finish(first, answers(first, 4))
        assertEquals(ReadingBookMode.OpenBook, afterFirst.mode)
        val second = ReadingQuiz.capture(record.content, record.hash, afterFirst.unlockedTier, ReadingBookMode.ClosedBook, Random(2))
        val afterSecond = ReadingQuiz.finish(second, answers(second, 4))
        assertEquals(3, afterSecond.unlockedTier)
        assertEquals(ReadingBookMode.ClosedBook, afterSecond.mode)
        assertTrue(second.questions.all { it.question.image != null })
        assertEquals(ReadingBookMode.ClosedBook, second.mode)
    }

    @Test fun shortPoolsExposeTheActualIntegerRequirementAndIncompleteQuizzesCannotPass() {
        val record = content(highestPool = 2)
        val plan = ReadingQuiz.capture(record.content, record.hash, 2, ReadingBookMode.ClosedBook, Random(1))
        assertTrue(plan.limitedPool)
        assertEquals(2, plan.requiredCorrect)
        assertFalse(ReadingQuiz.finish(plan, answers(plan, 1)).passed)
        assertTrue(ReadingQuiz.finish(plan, answers(plan, 2)).passed)
        assertThrows(IllegalArgumentException::class.java) { ReadingQuiz.finish(plan, emptyList()) }
    }

    @Test fun staleUnknownAndChangedSnapshotAnswersAreRejected() {
        val record = content()
        val plan = ReadingQuiz.capture(record.content, record.hash, 1, ReadingBookMode.OpenBook, Random(1))
        ReadingQuiz.validate(plan, record.content, record.hash)
        val first = plan.questions.first().question
        val accepted = ReadingQuiz.answer(plan, emptyList(), first.id, first.correctOptionId)
        assertThrows(IllegalArgumentException::class.java) { ReadingQuiz.answer(plan, accepted, first.id, first.correctOptionId) }
        assertThrows(IllegalArgumentException::class.java) { ReadingQuiz.answer(plan, emptyList(), first.id, "unknown") }
        val changed = plan.copy(questions = plan.questions.map {
            it.copy(question = it.question.copy(explanation = "Changed explanation"))
        })
        assertThrows(IllegalArgumentException::class.java) { ReadingQuiz.validate(changed, record.content, record.hash) }
        val restored = Json.decodeFromString(ReadingQuizPlan.serializer(), Json.encodeToString(ReadingQuizPlan.serializer(), plan))
        ReadingQuiz.validate(restored, record.content, record.hash)
        assertEquals(plan, restored)
    }

    private fun answers(plan: ReadingQuizPlan, highestCorrect: Int, lowerCorrect: Boolean = true): List<ReadingQuizAnswer> {
        var correctRemaining = highestCorrect
        return plan.questions.fold(emptyList()) { answers, captured ->
            val question = captured.question
            val correct = if (question.tier == plan.highestTier) correctRemaining-- > 0 else lowerCorrect
            ReadingQuiz.answer(plan, answers, question.id, if (correct) question.correctOptionId else null)
        }
    }

    private fun content(highestPool: Int = 4): ReadingRecord {
        val fixture = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
        val original = ReadingContent.parse("reading/reading-fixture-peach.json", fixture).content
        val base = original.questions.single()
        val questions = (1..4).flatMap { tier ->
            (0 until if (tier == 2) highestPool else 4).map { index ->
                base.copy(id = "tier-$tier-question-$index", tier = tier,
                    image = ReadingPicture("reading-images/fixture.webp", "a".repeat(64), "A fixture illustration."))
            }
        }
        val content = original.copy(questions = questions)
        return ReadingContent.parse("reading/${content.id}.json", Json.encodeToString(ReadingPackage.serializer(), content))
    }
}
