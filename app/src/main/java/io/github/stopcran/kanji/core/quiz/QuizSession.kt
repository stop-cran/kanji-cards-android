package io.github.stopcran.kanji.core.quiz

import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.Relearn

/**
 * Session rules shared by every quiz mode: the queue of cards still to ask, the answered/correct tallies, how an answer maps to
 * a grade and whether a failed card is asked again ([Relearn]). Pure, so it is tested without Android; each ViewModel only adds
 * its own presentation and persistence.
 */
class QuizSession<T : Any>(items: List<T>, private val idOf: (T) -> String, confirm: Boolean = true) {
    /** [grade] to apply and whether to [record] it (a practice-only confirmation is not saved). */
    data class Result(val grade: Grade, val record: Boolean)

    private val queue = ArrayDeque(items)
    private val relearn = Relearn(confirm)

    var answered = 0
        private set
    var correct = 0
        private set

    /** Cards still waiting behind the one just taken. */
    val pending: Int get() = queue.size

    /** The next card to ask, or null when the session is over. */
    fun take(): T? = queue.removeFirstOrNull()

    /**
     * Records the answer to [item]: wrong is Again, [weak] (right but flawed, e.g. stroke mistakes) and [guessed] are Hard, otherwise Good.
     * [ok] is "recalled at all"; [countsAsCorrect] is what the final score counts (defaults to [ok]).
     */
    fun answer(item: T, ok: Boolean, guessed: Boolean = false, weak: Boolean = false, countsAsCorrect: Boolean = ok): Result {
        val step = relearn.answered(idOf(item), ok, queue.size, weak)
        answered++
        if (countsAsCorrect) correct++
        step.reinsertAt?.let { queue.add(it, item) }
        val grade = when {
            !ok -> Grade.Again
            guessed || weak -> Grade.Hard
            else -> Grade.Good
        }
        return Result(grade, step.record)
    }
}
