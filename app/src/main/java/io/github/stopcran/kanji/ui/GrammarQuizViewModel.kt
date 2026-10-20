package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarVocabularySpan
import io.github.stopcran.kanji.core.words.Advancement
import io.github.stopcran.kanji.core.grammar.GRAMMAR_NOT_DUE
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarResponseEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.GrammarFocusTicket
import io.github.stopcran.kanji.data.grammarStudy
import io.github.stopcran.kanji.data.recordGrammarAnswer
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.data.toSrs
import io.github.stopcran.kanji.data.WordEntity
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
class GrammarQuizViewModel(private val app: Services) : StudyViewModel(app) {
    private val fsrs = Fsrs()
    var ui: GrammarUi by mutableStateOf(GrammarUi.Loading)
        private set
    var draft: String by mutableStateOf("")
        private set
    var lookup: GrammarVocabularySpan? by mutableStateOf(null)
        private set
    var lookupArticle: WordEntity? by mutableStateOf(null)
        private set
    var vocabularyListOpen: Boolean by mutableStateOf(false)
        private set

    private lateinit var exercises: Map<String, GrammarExercise>
    private lateinit var articlePaths: Map<String, String>
    private val states = mutableMapOf<String, SrsState>()
    private var session = QuizSession<String>(emptyList(), { it })
    var sourceId = ""
        private set
    private var vocabularyWords = emptyMap<String, WordEntity>()
    private var familiarWords = emptySet<String>()
    private var asked = 0
    private var started = false
    private var emptyMessage = GRAMMAR_NOT_DUE
    private var focusTicket: GrammarFocusTicket? = null
    var focusNotice: String? by mutableStateOf(null)
        private set

    fun ensureStarted(extra: Boolean) {
        if (started) return
        started = true
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        sourceId = app.settings.source.value.id
        val now = Instant.now()
        val (study, queue) = app.db.withTransaction {
            val captured = app.db.grammarStudy(app.settings, sourceId, now, reserve = !extra, extra = extra)
            val selected = if (extra) {
                QueueBuilder.extra(captured.extraIds, captured.states, now, limit = app.settings.practiceMinimum())
            } else {
                QueueBuilder.build(captured.ordinaryIds, captured.states, now, captured.budget)
            }
            val queuedIds = selected.map { it.kanji }.toSet()
            val ids = captured.sets.flatMap { it.exercises }.filter { it.id in queuedIds }.flatMap { it.vocabulary?.spans.orEmpty() }
                .mapNotNull { it.word }.distinct()
            vocabularyWords = ids.chunked(900).flatMap { app.db.content().referencedWords(sourceId, it) }.associateBy { it.word }
            familiarWords = if (ids.isEmpty()) emptySet() else {
                app.db.reviews().statesAcrossStacks(sourceId, StudyMode.WordJpEn.name)
                    .filter { it.kanji in vocabularyWords && Advancement.isSolid(it.toSrs(), now) }.map { it.kanji }.toSet()
            }
            captured to selected
        }
        val sets = study.sets
        exercises = sets.flatMap { it.exercises }.associateBy { it.id }
        articlePaths = sets.flatMap { set -> set.exercises.map { it.id to set.article } }.toMap()
        if (exercises.isEmpty()) {
            ui = GrammarUi.Empty("No grammar exercises are available. Sync the content first (⚙ settings).")
            return
        }
        states += study.states
        focusTicket = study.ticket
        focusNotice = if (!extra && study.preferences.guided) study.notice else null
        emptyMessage = study.notice ?: GRAMMAR_NOT_DUE
        session = trackSession(QuizSession(queue.map { it.kanji }, { it }), sourceId, GRAMMAR_STACK, StudyMode.Grammar.name, extra)
        showNext()
    }

    fun articlePath(exerciseId: String): String = articlePaths.getValue(exerciseId)

    val currentExercise: GrammarExercise? get() = when (val state = ui) {
        is GrammarUi.Choose -> state.exercise
        is GrammarUi.Type -> state.exercise
        is GrammarUi.Answer -> state.exercise
        else -> null
    }

    fun isFamiliar(span: GrammarVocabularySpan): Boolean = span.word in familiarWords

    fun openVocabularyList() { vocabularyListOpen = true }
    fun closeVocabularyList() { vocabularyListOpen = false }

    fun openLookup(span: GrammarVocabularySpan) {
        if (span !in currentExercise?.vocabulary?.spans.orEmpty()) return
        vocabularyListOpen = false
        lookup = span
    }

    fun closeLookup() { lookup = null }
    fun lookupHasArticle(): Boolean = lookup?.word in vocabularyWords
    fun openLookupArticle() { lookupArticle = vocabularyWords[lookup?.word] }
    fun closeLookupArticle() { lookupArticle = null }

    private fun showNext() {
        lookup = null
        lookupArticle = null
        vocabularyListOpen = false
        val id = session.take()
        if (id == null) {
            finishSession()
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
        recordSession {
            val log = ReviewLogEntity(
                0, sourceId, GRAMMAR_STACK, answer.exercise.id, StudyMode.Grammar.name, grade.value, now.toEpochMilli(), sessionId = reviewSessionId,
            )
            val error = app.db.recordGrammarAnswer(
                app.settings, updated.toEntity(sourceId, GRAMMAR_STACK, answer.exercise.id, StudyMode.Grammar), log, detail, focusTicket,
            )
            if (error != null) focusNotice = error
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)

    private companion object {
        const val MAX_TYPED_LENGTH = 80
    }
}
