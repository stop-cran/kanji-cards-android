package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.grammar.GRAMMAR_NOT_DUE
import io.github.stopcran.kanji.core.grammar.GrammarEligibility
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarResponseEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.grammarSets
import io.github.stopcran.kanji.data.grammarStates
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.toEntity
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.Instant

sealed interface GrammarUi {
    data object Loading : GrammarUi
    data class Empty(val message: String) : GrammarUi
    data class Done(val answered: Int, val correct: Int) : GrammarUi
    data class Choose(val exercise: GrammarExercise, val options: List<GrammarOption>, val remaining: Int) : GrammarUi
    data class Type(val exercise: GrammarExercise, val remaining: Int) : GrammarUi
    data class Answer(
        val exercise: GrammarExercise,
        val type: String,
        val response: String,
        val correct: Boolean,
        val remaining: Int,
    ) : GrammarUi
}

/** One SRS queue per authored exercise; the choose/type prompt is presentation detail, not another schedule. */
class GrammarQuizViewModel(private val app: Services) : ViewModel() {
    private val fsrs = Fsrs()
    var ui: GrammarUi by mutableStateOf(GrammarUi.Loading)
        private set
    var draft: String by mutableStateOf("")
        private set

    private lateinit var exercises: Map<String, GrammarExercise>
    private lateinit var articlePaths: Map<String, String>
    private val states = mutableMapOf<String, SrsState>()
    private var session = QuizSession<String>(emptyList(), { it })
    private var sourceId = ""
    private var asked = 0
    private var started = false
    private var emptyMessage = GRAMMAR_NOT_DUE

    fun ensureStarted(extra: Boolean) {
        if (started) return
        started = true
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        sourceId = app.settings.source.value.id
        val preferences = app.settings.grammarFor(sourceId)
        val sets = app.db.grammarSets(sourceId)
        exercises = sets.flatMap { it.exercises }.associateBy { it.id }
        articlePaths = sets.flatMap { set -> set.exercises.map { it.id to set.article } }.toMap()
        if (exercises.isEmpty()) {
            ui = GrammarUi.Empty("No grammar exercises are available. Sync the content first (⚙ settings).")
            return
        }
        states += app.db.grammarStates(sourceId)
        val pool = GrammarEligibility.pool(exercises.values.toList(), states, preferences.newScope, preferences.levelsReady)
        emptyMessage = pool.newItemMessage ?: GRAMMAR_NOT_DUE
        val now = Instant.now()
        val allowance = app.db.newAllowance(app.settings, sourceId, now).remaining
        val queue = if (extra) {
            QueueBuilder.extra(pool.ids, states, now, limit = app.settings.practiceMinimum())
        } else {
            QueueBuilder.build(pool.ids, states, now, allowance)
        }
        session = QuizSession(queue.map { it.kanji }, { it })
        showNext()
    }

    fun articlePath(exerciseId: String): String = articlePaths.getValue(exerciseId)

    private fun showNext() {
        val id = session.take()
        if (id == null) {
            ui = if (session.answered == 0) {
                GrammarUi.Empty(emptyMessage)
            } else {
                GrammarUi.Done(session.answered, session.correct)
            }
            return
        }
        draft = ""
        val remaining = session.pending + 1
        val exercise = exercises.getValue(id)
        ui = if (asked++ % 2 == 0) {
            GrammarUi.Choose(exercise, exercise.options.shuffled(), remaining)
        } else {
            GrammarUi.Type(exercise, remaining)
        }
    }

    fun pick(optionId: String?) {
        val question = ui as? GrammarUi.Choose ?: return
        ui = GrammarUi.Answer(question.exercise, "choose", optionId.orEmpty(), optionId == question.exercise.answer, question.remaining)
    }

    fun onDraft(value: String) {
        if (ui is GrammarUi.Type) draft = value.take(MAX_TYPED_LENGTH)
    }

    fun check(giveUp: Boolean = false) {
        val question = ui as? GrammarUi.Type ?: return
        val typed = if (giveUp) "" else normalize(draft)
        if (!giveUp && typed.isEmpty()) return
        val accepted = question.exercise.acceptedAnswers.map(::normalize)
        ui = GrammarUi.Answer(question.exercise, "type", typed, typed in accepted, question.remaining)
    }

    fun next(guessed: Boolean = false) {
        val answer = ui as? GrammarUi.Answer ?: return
        val result = session.answer(answer.exercise.id, answer.correct, guessed)
        if (result.record) record(answer, result.grade)
        showNext()
    }

    private fun record(answer: GrammarUi.Answer, grade: Grade) {
        val now = Instant.now()
        val updated = fsrs.review(
            states[answer.exercise.id] ?: SrsState(),
            grade,
            now,
            firstSuccessCapDays = if (answer.type == "choose") Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS else null,
            fuzzSeed = Fsrs.seed(answer.exercise.id, StudyMode.Grammar.name, states[answer.exercise.id]?.reps ?: 0),
        )
        states[answer.exercise.id] = updated
        val detail = GrammarResponseEntity(
            reviewLogId = 0,
            sourceId = sourceId,
            stack = GRAMMAR_STACK,
            exerciseId = answer.exercise.id,
            constructionId = answer.exercise.target,
            relationshipId = answer.exercise.relationship,
            promptType = answer.type,
            response = answer.response,
            correct = answer.correct,
        )
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            val log = ReviewLogEntity(
                0, sourceId, GRAMMAR_STACK, answer.exercise.id, StudyMode.Grammar.name, grade.value, now.toEpochMilli(),
            )
            app.db.reviews().recordGrammar(updated.toEntity(sourceId, GRAMMAR_STACK, answer.exercise.id, StudyMode.Grammar), log, detail)
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)

    private companion object {
        const val MAX_TYPED_LENGTH = 80
    }
}
