package io.github.stopcran.kanji.ui

import android.util.Log
import android.database.sqlite.SQLiteException
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
import io.github.stopcran.kanji.core.grammar.GrammarChoices
import io.github.stopcran.kanji.core.grammar.GrammarChoiceSnapshot
import io.github.stopcran.kanji.core.grammar.GrammarPracticePreview
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.QueueItem
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarResponseEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.GrammarFocusTicket
import io.github.stopcran.kanji.data.GrammarStudy
import io.github.stopcran.kanji.data.GrammarPracticeSnapshot
import io.github.stopcran.kanji.data.GrammarRoadmapSnapshot
import io.github.stopcran.kanji.data.grammarPractice
import io.github.stopcran.kanji.data.grammarStudy
import io.github.stopcran.kanji.data.recordGrammarAnswer
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.data.toSrs
import io.github.stopcran.kanji.data.WordEntity
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.random.Random

sealed interface GrammarUi {
    data object Loading : GrammarUi
    data class Preview(val preview: GrammarPracticePreview, val message: String? = null) : GrammarUi
    data class Empty(val message: String) : GrammarUi
    data class Done(val answered: Int, val correct: Int) : GrammarUi
    data class Choose(val exercise: GrammarExercise, val choices: GrammarChoiceSnapshot, val remaining: Int) : GrammarUi {
        val options: List<GrammarOption> get() = choices.options
    }
    data class Type(val exercise: GrammarExercise, val remaining: Int) : GrammarUi
    data class Answer(
        val exercise: GrammarExercise,
        val type: String,
        val response: String,
        val correct: Boolean,
        val remaining: Int,
        val choices: GrammarChoiceSnapshot? = null,
    ) : GrammarUi {
        val options: List<GrammarOption> get() = choices?.options ?: (exercise.options + exercise.additionalCorrectOptions)
        val correctOptionIds: Set<String> get() = choices?.correctOptionIds
            ?: (setOf(exercise.answer) + exercise.additionalCorrectOptions.map { it.id })
        val responseLabel: String get() = if (choices == null || response.isEmpty()) response
            else choices.options.first { it.id == response }.label
        val completion: String get() = if (correct && choices != null) responseLabel
            else exercise.options.first { it.id == exercise.answer }.label
    }
}

/** One SRS queue per authored exercise; the choose/type prompt is presentation detail, not another schedule. */
class GrammarQuizViewModel(
    private val app: Services,
    private val choiceRandom: Random = Random.Default,
    val target: GrammarPracticeTarget? = null,
) : StudyViewModel(app) {
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
    var approvedPractice: GrammarPracticePreview? by mutableStateOf(null)
        private set
    var practiceOverride = false
        private set
    var practiceDetails: GrammarRoadmapSnapshot? by mutableStateOf(null)
        private set

    fun ensureStarted(extra: Boolean, interrupted: Boolean = false) {
        if (started) return
        started = true
        viewModelScope.launch {
            if (target == null) start(extra) else prepareTarget {
                val snapshot = app.db.grammarPractice(app.settings, target, Instant.now())
                showPreview(snapshot, if (interrupted)
                    "This topic was reopened after the app was interrupted. Review a fresh batch; previous override consent has expired." else null)
            }
        }
    }

    fun startTargeted(override: Boolean, origin: GrammarUi.Preview) {
        if (ui !== origin || target == null) {
            Log.w("GrammarPractice", "Ignored stale practice start")
            return
        }
        ui = GrammarUi.Loading
        viewModelScope.launch {
            prepareTarget {
                val now = Instant.now()
                val prepared = app.db.withTransaction {
                    val current = app.db.grammarPractice(app.settings, target, now)
                    if (current.preview != origin.preview) return@withTransaction current to null
                    val queue = QueueBuilder.extra(
                        current.preview.ids(override), current.study.states, now, limit = current.preview.limit,
                    )
                    captureVocabulary(current.study, queue, target.sourceId, now)
                    require(target.matches(app.settings.source.value)) { "The selected source changed before practice started. Choose a topic again." }
                    current to queue
                }
                val (snapshot, queue) = prepared
                when {
                    queue == null -> showPreview(
                        snapshot, "Content, scope or availability changed. Review this updated preview and approve any exceptions again.",
                    )
                    queue.isEmpty() -> showPreview(snapshot, "No exercises are available with these choices. No session was started.")
                    else -> {
                        sourceId = target.sourceId
                        approvedPractice = snapshot.preview
                        practiceOverride = override
                        activate(snapshot.study, queue, extra = true)
                    }
                }
            }
        }
    }

    suspend fun refreshTargeted() {
        val origin = ui as? GrammarUi.Preview ?: return
        val selected = target ?: return
        prepareTarget(current = { ui === origin }) {
            val snapshot = app.db.grammarPractice(app.settings, selected, Instant.now())
            if (ui === origin) showPreview(snapshot, if (snapshot.preview == origin.preview) origin.message else
                "Content, scope or availability changed. Review this updated preview and approve any exceptions again.")
        }
    }

    private fun showPreview(snapshot: GrammarPracticeSnapshot, message: String? = null) {
        practiceDetails = snapshot.roadmap
        ui = GrammarUi.Preview(snapshot.preview, message)
    }

    override fun onSessionLeft() {
        // Route-keyed ViewModels live until the Activity ends; do not retain a corpus snapshot for every visited topic.
        ui = GrammarUi.Loading
        practiceDetails = null
    }

    private suspend fun prepareTarget(current: () -> Boolean = { true }, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            if (!current()) return
            Log.e("GrammarPractice", "Could not prepare targeted practice", error)
            ui = GrammarUi.Empty(error.message ?: "Targeted practice is unavailable. Return to Grammar and choose a topic again.")
        } catch (error: SQLiteException) {
            if (!current()) return
            Log.e("GrammarPractice", "Could not read or start targeted practice", error)
            ui = GrammarUi.Empty("Could not access practice data. Return to Grammar and try again.")
        }
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
            captureVocabulary(captured, selected, sourceId, now)
            captured to selected
        }
        activate(study, queue, extra)
    }

    private suspend fun captureVocabulary(study: GrammarStudy, queue: List<QueueItem>, source: String, now: Instant) {
        val queuedIds = queue.map { it.kanji }.toSet()
        val ids = study.sets.flatMap { it.exercises }.filter { it.id in queuedIds }.flatMap { it.vocabulary?.spans.orEmpty() }
            .mapNotNull { it.word }.distinct()
        vocabularyWords = ids.chunked(900).flatMap { app.db.content().referencedWords(source, it) }.associateBy { it.word }
        familiarWords = if (ids.isEmpty()) emptySet() else {
            app.db.reviews().statesAcrossStacks(source, StudyMode.WordJpEn.name)
                .filter { it.kanji in vocabularyWords && Advancement.isSolid(it.toSrs(), now) }.map { it.kanji }.toSet()
        }
    }

    private suspend fun activate(study: GrammarStudy, queue: List<QueueItem>, extra: Boolean) {
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
            GrammarUi.Choose(exercise, GrammarChoices.build(exercise, choiceRandom), remaining)
        } else {
            GrammarUi.Type(exercise, remaining)
        }
    }

    fun pick(optionId: String?, origin: GrammarUi.Choose? = ui as? GrammarUi.Choose) {
        val question = ui as? GrammarUi.Choose ?: return
        if (question !== origin || (optionId != null && question.options.none { it.id == optionId })) {
            Log.w("GrammarQuiz", "Ignored stale or non-displayed choice")
            return
        }
        ui = GrammarUi.Answer(
            question.exercise, "choose", optionId.orEmpty(), optionId in question.choices.correctOptionIds,
            question.remaining, question.choices,
        )
    }

    fun onDraft(value: String) {
        if (ui is GrammarUi.Type) draft = value.take(MAX_TYPED_LENGTH)
    }

    fun check(giveUp: Boolean = false) {
        val question = ui as? GrammarUi.Type ?: return
        val typed = if (giveUp) "" else GrammarChoices.normalizeAnswer(draft)
        if (!giveUp && typed.isEmpty()) return
        val accepted = question.exercise.acceptedAnswers.map(GrammarChoices::normalizeAnswer)
        ui = GrammarUi.Answer(question.exercise, "type", typed, typed in accepted, question.remaining)
    }

    fun next(guessed: Boolean = false, origin: GrammarUi.Answer? = ui as? GrammarUi.Answer) {
        val answer = ui as? GrammarUi.Answer ?: return
        if (answer !== origin) {
            Log.w("GrammarQuiz", "Ignored stale answer advancement")
            return
        }
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
            choiceSnapshot = answer.choices?.let(GrammarChoices::encode),
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

    private companion object {
        const val MAX_TYPED_LENGTH = 80
    }
}
