package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.quiz.QuizBuilder
import io.github.stopcran.kanji.core.quiz.QuizCard
import io.github.stopcran.kanji.core.quiz.QuizOption
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Confusion
import io.github.stopcran.kanji.core.srs.FontPolicy
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.PadContext
import io.github.stopcran.kanji.core.srs.PracticePadding
import io.github.stopcran.kanji.data.padCandidates
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.similarityIndex
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.core.srs.ReviewIdentity
import io.github.stopcran.kanji.core.srs.ReviewRecording
import io.github.stopcran.kanji.data.ReviewStore
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.inStack
import io.github.stopcran.kanji.data.newKanjiInStack
import io.github.stopcran.kanji.data.stacks
import io.github.stopcran.kanji.data.toQuizCard
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.focusStatus
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

sealed interface QuizUi {
    data object Loading : QuizUi
    data class Empty(val messageId: Int) : QuizUi
    /** First sight of a new kanji: shown before it is ever tested (not a review). */
    data class Study(val card: KanjiEntity, val remaining: Int, val font: KanjiFont) : QuizUi
    data class Question(val card: KanjiEntity, val options: List<QuizOption>, val remaining: Int, val font: KanjiFont) : QuizUi
    data class Answer(val card: KanjiEntity, val options: List<QuizOption>, val picked: String, val remaining: Int, val font: KanjiFont, val heard: String? = null) : QuizUi {
        val correct: Boolean get() = picked == card.kanji
    }
    data class Done(val answered: Int, val correct: Int) : QuizUi
}

/** Quiz session: question state -> answer-check state per card; wrong answers are asked again a few cards later. */
class QuizViewModel(
    private val app: Services,
    private val recording: ReviewRecording = ReviewRecording(),
    private val reviews: ReviewStore = app.reviewStore,
) : StudyViewModel(app.sessionStore, app.kanjiWrites) {
    private val random = Random.Default

    var ui: QuizUi by mutableStateOf(QuizUi.Loading)
        private set
    var contentLanguage by mutableStateOf("en")
        private set

    private var sourceId = ""
    private var stack = Stacks.ALL
    private var cards: Map<String, KanjiEntity> = emptyMap()
    private var quizCards: List<QuizCard> = emptyList()
    private val states = mutableMapOf<String, SrsState>()
    /** Stack that owns a borrowed (padding) card; its answers are recorded there, never in the selected stack. */
    private val owners = mutableMapOf<String, String>()
    private var session = QuizSession<String>(emptyList(), { it })
    private val studied = mutableSetOf<String>()

    private var started = false
    private var extra = false

    fun ensureStarted(extra: Boolean) {
        if (started) return
        started = true
        this.extra = extra
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        app.kanjiWrites.awaitPending()
        val source = app.settings.source.value
        sourceId = source.id
        contentLanguage = app.settings.sync.baseLanguage(sourceId) ?: app.db.content().meta(sourceId)?.baseLanguage ?: "en"
        val content = app.db.content().kanji(sourceId)
        stack = Stacks.find(app.settings.stack.value, content.stacks()).id
        val all = content.inStack(stack)
        if (all.size < 2) {
            ui = QuizUi.Empty(R.string.kanji_quiz_no_cards)
            return
        }
        cards = all.associateBy { it.kanji }
        quizCards = all.map { it.toQuizCard() }
        app.db.reviews().states(sourceId, Stacks.stateId(stack), StudyMode.Quiz.name).forEach { states[it.kanji] = it.toSrs() }

        val budget = app.db.newAllowance(app.settings, sourceId, Instant.now()).remaining
        val capacity = app.db.focusStatus(app.settings, sourceId, stack, all, System.currentTimeMillis())?.capacity
        val newEligible = all.newKanjiInStack(stack)
        val items = if (extra) {
            val minimum = app.settings.practiceMinimum()
            val base = QueueBuilder.extra(
                all.map { it.kanji }, states, Instant.now(), minimum,
                newEligible = newEligible, newCardsLimit = capacity ?: Int.MAX_VALUE,
            ).map { it.kanji }
            base + pad(base, minimum - base.size, all)
        } else {
            QueueBuilder.build(all.map { it.kanji }, states, Instant.now(), minOf(budget, capacity ?: budget), newEligible = newEligible)
                .map { it.kanji }
        }
        session = trackSession(QuizSession(items, { it }), sourceId, stack, StudyMode.Quiz.name, extra)
        showNext()
    }

    /** Tops a short extra session up with weak, already-learned kanji of other stacks; they keep being scheduled in their own stack. */
    private suspend fun pad(base: List<String>, need: Int, inStack: List<KanjiEntity>): List<String> {
        if (need <= 0) return emptyList()
        val content = app.db.content().kanji(sourceId)
        val candidates = app.db.padCandidates(sourceId, stack, StudyMode.Quiz, content, inStack.mapTo(HashSet()) { it.kanji })
        val fillers = PracticePadding.fill(candidates, PadContext(base.toSet() + states.keys, base, content.similarityIndex()), need, Instant.now())
        val byKanji = content.associateBy { it.kanji }
        for (f in fillers) {
            val k = byKanji.getValue(f.kanji)
            cards = cards + (k.kanji to k)
            quizCards = quizCards + k.toQuizCard()
            states[k.kanji] = f.state
            owners[k.kanji] = f.owner
        }
        return fillers.map { it.kanji }
    }

    private var lastFont: KanjiFont? = null

    private fun nextFont(kanji: String): KanjiFont {
        if (!app.settings.varyFonts.value) return KanjiFont.Gothic
        val stability = states[kanji]?.takeIf { it.phase != CardPhase.New }?.stability ?: 0.0
        return FontPolicy.pick(stability, random, lastFont).also { lastFont = it }
    }

    private fun showNext() {
        val kanji = session.take()
        if (kanji == null) {
            finishSession()
            ui = if (session.answered == 0) QuizUi.Empty(R.string.kanji_quiz_nothing_due) else QuizUi.Done(session.answered, session.correct)
            return
        }
        val card = cards.getValue(kanji)
        if (kanji !in studied && states[kanji]?.phase.let { it == null || it == CardPhase.New }) {
            studied += kanji
            ui = QuizUi.Study(card, session.pending + 1, nextFont(kanji))
            return
        }
        ask(card, session.pending + 1, nextFont(kanji))
    }

    private fun ask(card: KanjiEntity, remaining: Int, font: KanjiFont) {
        val target = quizCards.first { it.kanji == card.kanji }
        ui = QuizUi.Question(card, QuizBuilder.options(target, quizCards, random), remaining, font)
    }

    /** The study view is done: the same card is now tested. */
    fun startQuestion() {
        val s = ui as? QuizUi.Study ?: return
        ask(s.card, s.remaining, s.font)
    }

    fun pick(kanji: String, heard: String? = null) {
        val q = ui as? QuizUi.Question ?: return
        ui = QuizUi.Answer(q.card, q.options, kanji, q.remaining, q.font, heard)
    }

    /** Takes back a voice pick that was misheard; nothing has been recorded before [next]. */
    fun retry() {
        val a = ui as? QuizUi.Answer ?: return
        if (a.heard != null) ui = QuizUi.Question(a.card, a.options, a.remaining, a.font)
    }

    /** Wrong answers (and "don't know") are graded Again and relearned in-session; right ones Good, or Hard when the user admits guessing. */
    fun next(guessed: Boolean = false) {
        val a = ui as? QuizUi.Answer ?: return
        val result = session.answer(a.card.kanji, a.correct, guessed)
        val grade = result.grade
        if (result.record) {
            val owner = owners[a.card.kanji] ?: Stacks.stateId(stack)
            val record = recording.confirm(
                ReviewIdentity(sourceId, owner, a.card.kanji, StudyMode.Quiz, reviewSessionId),
                states, grade, Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS,
            )
            recordSession { reviews.record(record) }
        }
        if (!a.correct && a.picked != NO_ANSWER && !extra) nudge(a.picked)
        showNext()
    }

    /** The mis-picked card is brought forward without touching its score; see [Confusion]. */
    private fun nudge(kanji: String) {
        val updated = Confusion.nudged(states[kanji], Instant.now()) ?: return
        states[kanji] = updated
        val owner = owners[kanji] ?: Stacks.stateId(stack)
        recordSession { app.db.reviews().advanceDue(sourceId, owner, kanji, StudyMode.Quiz.name, "", updated.due.toEpochMilli()) }
    }
}
