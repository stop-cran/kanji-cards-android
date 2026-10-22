package io.github.stopcran.kanji.core.listening

import io.github.stopcran.kanji.core.srs.DayLevel
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import kotlinx.serialization.Serializable
import java.time.Duration

const val LISTENING_STACK = "listening"
const val LISTENING_MODE = "Listening"

@Serializable
data class ListeningAttempt(
    val optionOrder: Map<String, List<String>>,
    val answers: Map<String, String> = emptyMap(),
    val assistance: Set<String> = emptySet(),
    val starts: Int = 0,
    val completedPlaybacks: Int = 0,
    val positionMs: Long = 0,
    val phase: String = "listen",
    val correct: Int? = null,
    val grade: Int? = null,
    val vocabularyBodies: Map<String, String> = emptyMap(),
)

data class ListeningAllowance(val count: Int, val milliseconds: Long)
data class ListeningResult(val correct: Int, val grade: Grade)

object ListeningPolicy {
    const val COOLDOWN_MS = 24 * 60 * 60 * 1000L
    const val FULL_DAY_MS = 300_000L
    val scheduler = Fsrs(againStep = Duration.ofHours(24))

    fun estimatedMs(durationMs: Long): Long = 2 * durationMs + 90_000

    fun allowance(
        sharedRemaining: Int, day: DayLevel, introduced: Int, spentMs: Long, actionableDueMs: Long,
    ): ListeningAllowance {
        require(sharedRemaining >= 0 && introduced >= 0 && spentMs >= 0 && actionableDueMs >= 0)
        val cap = when (day) { DayLevel.Full -> 2; DayLevel.Light -> 1; DayLevel.Off -> 0 }
        val backlog = (2.0 - actionableDueMs.toDouble() / FULL_DAY_MS).coerceIn(0.0, 1.0)
        val time = (FULL_DAY_MS * day.factor * backlog).toLong()
        return ListeningAllowance(minOf(sharedRemaining, (cap - introduced).coerceAtLeast(0)), (time - spentMs).coerceAtLeast(0))
    }

    fun grade(passage: ListeningPassage, answers: Map<String, String>, assisted: Boolean, giveUp: Boolean = false): ListeningResult {
        require(passage.questions.size == 3 && passage.questions.count { it.mainPoint } == 1)
        require(answers.all { (id, answer) -> passage.questions.any { q -> q.id == id && q.options.any { it.id == answer } } }) {
            "Response refers to an unpresented question or option"
        }
        require(giveUp || answers.size == 3) { "Answer all three questions before submission" }
        val correct = passage.questions.count { answers[it.id] == it.answer }
        val main = passage.questions.single { it.mainPoint }
        val grade = when {
            giveUp || correct < 2 || answers[main.id] != main.answer -> Grade.Again
            correct == 2 || assisted -> Grade.Hard
            else -> Grade.Good
        }
        return ListeningResult(correct, grade)
    }
}
