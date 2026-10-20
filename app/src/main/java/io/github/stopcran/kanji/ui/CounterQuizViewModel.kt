package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.counters.CounterGrader
import io.github.stopcran.kanji.core.counters.CounterIndex
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterOptions
import io.github.stopcran.kanji.core.counters.CounterQueueRequest
import io.github.stopcran.kanji.core.counters.CounterQueues
import io.github.stopcran.kanji.core.counters.CounterResult
import io.github.stopcran.kanji.core.counters.CounterVerdict
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.data.COUNTER_STACK
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.counterIndex
import io.github.stopcran.kanji.data.counterStates
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.toEntity
import kotlinx.coroutines.launch
import java.time.Instant

/** A noun choice of the reverse question: [id] is the forward card it comes from. */
data class NounOption(val id: String, val label: String)

sealed interface CounterUi {
    data object Loading : CounterUi
    data class Empty(val message: String) : CounterUi
    data class Done(val answered: Int, val correct: Int) : CounterUi

    data class Choose(val card: CounterCard, val file: CounterFile, val options: List<String>, val remaining: Int) : CounterUi
    data class ChooseAnswer(val card: CounterCard, val file: CounterFile, val options: List<String>, val picked: String, val remaining: Int) : CounterUi {
        val correct: Boolean get() = picked == file.counter
    }

    data class TypeQuestion(val card: CounterCard, val file: CounterFile, val remaining: Int) : CounterUi
    data class TypeAnswer(
        val card: CounterCard, val file: CounterFile, val typed: String, val result: CounterResult, val gaveUp: Boolean, val remaining: Int,
    ) : CounterUi

    data class NounQuestion(val reverseId: String, val file: CounterFile, val options: List<NounOption>, val remaining: Int) : CounterUi
    data class NounAnswer(
        val reverseId: String, val file: CounterFile, val answer: CounterCard, val options: List<NounOption>, val picked: String, val remaining: Int,
    ) : CounterUi {
        val correct: Boolean get() = picked == answer.id
    }
}

/** One counter quiz session in a single [CounterMode]; answers are saved under the shared "counters" stack. */
class CounterQuizViewModel(private val app: Services) : StudyViewModel(app) {
    private val fsrs = Fsrs()

    var ui: CounterUi by mutableStateOf(CounterUi.Loading)
        private set

    /** The typed text of the current question; cleared whenever a new question (including a re-ask) is shown. */
    var draft: String by mutableStateOf("")
        private set

    lateinit var index: CounterIndex
        private set
    private var mode = CounterMode.Choose
    private var sourceId = ""
    private val states = mutableMapOf<String, SrsState>()
    private var session = QuizSession<String>(emptyList(), { it })
    private var started = false

    fun ensureStarted(mode: CounterMode, extra: Boolean) {
        if (started) return
        started = true
        this.mode = mode
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        sourceId = app.settings.source.value.id
        index = app.db.counterIndex(sourceId)
        if (index.cards.isEmpty()) {
            ui = CounterUi.Empty("No counter cards yet. Sync the content first (⚙ settings).")
            return
        }
        states += app.db.counterStates(sourceId, mode)
        val choose = if (mode == CounterMode.Choose) states else app.db.counterStates(sourceId, CounterMode.Choose)
        val budget = app.db.newAllowance(app.settings, sourceId, Instant.now()).remaining
        val limit = if (extra) app.settings.practiceMinimum() else null
        val queue = CounterQueues.build(index, CounterQueueRequest(mode, states, choose, Instant.now(), budget, limit))
        session = trackSession(QuizSession(queue.map { it.kanji }, { it }), sourceId, COUNTER_STACK, mode.mode.name, extra)
        showNext()
    }

    private fun showNext() {
        val id = session.take()
        if (id == null) {
            finishSession()
            ui = if (session.answered == 0) CounterUi.Empty(emptyMessage()) else CounterUi.Done(session.answered, session.correct)
            return
        }
        val remaining = session.pending + 1
        draft = ""
        ui = when (mode) {
            CounterMode.Choose -> {
                val card = index.cards.getValue(id)
                val file = index.fileOf.getValue(id)
                CounterUi.Choose(card, file, CounterOptions.choose(card, file.counter), remaining)
            }
            CounterMode.Type -> CounterUi.TypeQuestion(index.cards.getValue(id), index.fileOf.getValue(id), remaining)
            CounterMode.Noun -> {
                val reverse = index.reverse.getValue(id)
                val options = CounterOptions.nouns(reverse).map { NounOption(it, index.nounLabel(index.cards.getValue(it))) }
                CounterUi.NounQuestion(id, index.reverseFile(id)!!, options, remaining)
            }
        }
    }

    private fun emptyMessage() = when (mode) {
        CounterMode.Choose -> "Nothing is due. Come back later or raise the daily new-card limit in ⚙ settings."
        CounterMode.Type -> "Nothing is due. Typing opens for sentences you have answered in “Choose the counter” on two different days."
        CounterMode.Noun -> "Nothing is due. This opens for a counter once three of its sentences are solid in “Choose the counter”."
    }

    fun pick(value: String) {
        when (val q = ui) {
            is CounterUi.Choose -> ui = CounterUi.ChooseAnswer(q.card, q.file, q.options, value, q.remaining)
            is CounterUi.NounQuestion -> {
                val answer = index.cards.getValue(index.reverse.getValue(q.reverseId).answer)
                ui = CounterUi.NounAnswer(q.reverseId, q.file, answer, q.options, value, q.remaining)
            }
            else -> Unit
        }
    }

    fun onDraft(text: String) {
        if (ui is CounterUi.TypeQuestion) draft = text
    }

    /** Checks the typed text, or marks the question as not known when [giveUp]. */
    fun submit(giveUp: Boolean = false) {
        val q = ui as? CounterUi.TypeQuestion ?: return
        val typed = if (giveUp) "" else draft
        if (!giveUp && typed.isBlank()) return
        val result = CounterGrader.grade(typed, q.card, q.file.reading)
        ui = CounterUi.TypeAnswer(q.card, q.file, typed, result, giveUp, q.remaining)
    }

    /**
     * Wrong answers (and "don't know") are graded Again and relearned in-session. Right ones are Good, or Hard when guessed; a
     * typed yellow answer is Hard and is never re-asked.
     */
    fun next(guessed: Boolean = false) {
        when (val a = ui) {
            is CounterUi.ChooseAnswer -> finish(a.card.id, a.correct, guessed)
            is CounterUi.NounAnswer -> finish(a.reverseId, a.correct, guessed)
            is CounterUi.TypeAnswer -> {
                val verdict = a.result.verdict
                finish(a.card.id, a.result.ok, guessed = verdict == CounterVerdict.Yellow, countsAsCorrect = verdict == CounterVerdict.Good)
            }
            else -> return
        }
        showNext()
    }

    private fun finish(id: String, ok: Boolean, guessed: Boolean, countsAsCorrect: Boolean = ok) {
        val result = session.answer(id, ok, guessed, countsAsCorrect = countsAsCorrect)
        if (result.record) record(id, result.grade)
    }

    private fun record(id: String, grade: Grade) {
        val now = Instant.now()
        val cap = if (mode == CounterMode.Type) null else Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS
        val updated = fsrs.review(
            states[id] ?: SrsState(), grade, now,
            firstSuccessCapDays = cap, fuzzSeed = Fsrs.seed(id, mode.mode.name, states[id]?.reps ?: 0),
        )
        states[id] = updated
        recordSession {
            val log = ReviewLogEntity(0, sourceId, COUNTER_STACK, id, mode.mode.name, grade.value, now.toEpochMilli(), sessionId = reviewSessionId)
            app.db.reviews().record(updated.toEntity(sourceId, COUNTER_STACK, id, mode.mode), log)
        }
    }
}
