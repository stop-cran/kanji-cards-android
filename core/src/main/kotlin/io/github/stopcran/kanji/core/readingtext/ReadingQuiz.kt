package io.github.stopcran.kanji.core.readingtext

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class ReadingBookMode { OpenBook, ClosedBook }

@Serializable
data class ReadingQuizQuestion(val question: ReadingQuestion, val optionOrder: List<String>)

@Serializable
data class ReadingQuizPlan(
    val packageHash: String,
    val mode: ReadingBookMode,
    val unlockedTier: Int,
    val highestTier: Int,
    val nextTier: Int?,
    val questions: List<ReadingQuizQuestion>,
) {
    val highestCount get() = questions.count { it.question.tier == highestTier }
    val requiredCorrect get() = (highestCount * 3 + 3) / 4
    val limitedPool get() = highestCount < 4
}

@Serializable
data class ReadingQuizAnswer(val questionId: String, val optionId: String?)

@Serializable
data class ReadingQuizJudgement(val questionId: String, val optionId: String?, val correct: Boolean)

@Serializable
data class ReadingQuizResult(
    val answers: List<ReadingQuizJudgement>,
    val highestTier: Int,
    val highestCorrect: Int,
    val highestCount: Int,
    val requiredCorrect: Int,
    val passed: Boolean,
    val unlockedTier: Int,
    val mode: ReadingBookMode,
)

object ReadingQuiz {
    fun capture(
        content: ReadingPackage, packageHash: String, unlockedTier: Int, mode: ReadingBookMode, random: Random = Random.Default,
    ): ReadingQuizPlan {
        require(unlockedTier in 1..4) { "Invalid Reading unlock tier" }
        val highest = requireNotNull(content.questions.filter { it.tier <= unlockedTier }.maxOfOrNull { it.tier }) {
            "No Reading questions at the unlocked tiers"
        }
        val chosen = (1..highest).flatMap { tier ->
            content.questions.filter { it.tier == tier }.shuffled(random).take(if (tier == highest) 4 else 1)
        }.map { ReadingQuizQuestion(it, it.options.map { option -> option.id }.shuffled(random)) }
        return ReadingQuizPlan(packageHash, mode, unlockedTier, highest, nextTier(content, unlockedTier, highest), chosen)
    }

    fun validate(plan: ReadingQuizPlan, content: ReadingPackage, packageHash: String) {
        require(plan.packageHash == packageHash && plan.unlockedTier in 1..4) { "Reading quiz capture does not match its package" }
        require(plan.highestTier == content.questions.filter { it.tier <= plan.unlockedTier }.maxOfOrNull { it.tier }) {
            "Reading quiz highest tier changed"
        }
        require(plan.nextTier == nextTier(content, plan.unlockedTier, plan.highestTier)) { "Reading quiz unlock target changed" }
        require(plan.questions.map { it.question.id }.distinct().size == plan.questions.size) { "Repeated Reading quiz question" }
        plan.questions.forEach { captured ->
            val question = content.questions.singleOrNull { it.id == captured.question.id }
            require(question == captured.question) { "Reading quiz question changed" }
            require(captured.optionOrder.size == question.options.size &&
                captured.optionOrder.toSet() == question.options.map { it.id }.toSet()) { "Reading quiz options changed" }
        }
        val expected = (1..plan.highestTier).associateWith { tier ->
            minOf(content.questions.count { it.tier == tier }, if (tier == plan.highestTier) 4 else 1)
        }.filterValues { it > 0 }
        require(plan.questions.groupingBy { it.question.tier }.eachCount() == expected) { "Reading quiz tier sample changed" }
    }

    fun answer(plan: ReadingQuizPlan, answers: List<ReadingQuizAnswer>, questionId: String, optionId: String?): List<ReadingQuizAnswer> {
        validateAnswers(plan, answers)
        val question = plan.questions.getOrNull(answers.size)?.question
        require(question?.id == questionId) { "Reading answer does not belong to the current question" }
        require(optionId == null || question.options.any { it.id == optionId }) { "Unknown Reading answer option" }
        return answers + ReadingQuizAnswer(questionId, optionId)
    }

    fun finish(plan: ReadingQuizPlan, answers: List<ReadingQuizAnswer>): ReadingQuizResult {
        validateAnswers(plan, answers)
        require(answers.size == plan.questions.size) { "An incomplete Reading quiz cannot earn progress" }
        val judgements = answers.mapIndexed { index, answer ->
            ReadingQuizJudgement(answer.questionId, answer.optionId, answer.optionId == plan.questions[index].question.correctOptionId)
        }
        val correct = judgements.count { result ->
            result.correct && plan.questions.single { it.question.id == result.questionId }.question.tier == plan.highestTier
        }
        val passed = correct >= plan.requiredCorrect
        return ReadingQuizResult(judgements, plan.highestTier, correct, plan.highestCount, plan.requiredCorrect, passed,
            if (passed) plan.nextTier ?: plan.unlockedTier else plan.unlockedTier, plan.mode)
    }

    private fun nextTier(content: ReadingPackage, unlocked: Int, highest: Int): Int? =
        (unlocked + 1).takeIf { highest == unlocked && it <= 4 && content.questions.any { question -> question.tier == it } }

    private fun validateAnswers(plan: ReadingQuizPlan, answers: List<ReadingQuizAnswer>) {
        require(answers.size <= plan.questions.size) { "Too many Reading answers" }
        answers.forEachIndexed { index, answer ->
            val question = plan.questions[index].question
            require(answer.questionId == question.id) { "Reading answer order changed" }
            require(answer.optionId == null || question.options.any { it.id == answer.optionId }) { "Unknown Reading answer option" }
        }
    }
}
