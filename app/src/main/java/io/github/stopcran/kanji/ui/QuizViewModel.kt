package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.quiz.QuizBuilder
import io.github.stopcran.kanji.core.quiz.QuizCard
import io.github.stopcran.kanji.core.quiz.QuizOption
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.FontPolicy
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.inStack
import io.github.stopcran.kanji.data.splitSep
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

sealed interface QuizUi {
    data object Loading : QuizUi
    data class Empty(val message: String) : QuizUi
    data class Question(val card: KanjiEntity, val options: List<QuizOption>, val remaining: Int, val font: KanjiFont) : QuizUi
    data class Answer(val card: KanjiEntity, val options: List<QuizOption>, val picked: String, val remaining: Int, val font: KanjiFont, val heard: String? = null) : QuizUi {
        val correct: Boolean get() = picked == card.kanji
    }
    data class Done(val answered: Int, val correct: Int) : QuizUi
}

/** Quiz session: question state -> answer-check state per card; wrong answers are asked again a few cards later. */
class QuizViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KanjiApp
    private val fsrs = Fsrs()
    private val random = Random.Default

    var ui: QuizUi by mutableStateOf(QuizUi.Loading)
        private set

    private var sourceId = ""
    private var stack = Stacks.ALL
    private var cards: Map<String, KanjiEntity> = emptyMap()
    private var quizCards: List<QuizCard> = emptyList()
    private val states = mutableMapOf<String, SrsState>()
    private val queue = ArrayDeque<String>()
    private var answered = 0
    private var correct = 0

    private var started = false

    fun ensureStarted(extra: Boolean) {
        if (started) return
        started = true
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        val source = app.settings.source.value
        sourceId = source.id
        stack = app.settings.stack.value
        val all = app.db.content().kanji(sourceId).inStack(stack)
        if (all.size < 2) {
            ui = QuizUi.Empty("No cards yet. Sync the content first (⚙ settings).")
            return
        }
        cards = all.associateBy { it.kanji }
        quizCards = all.map { QuizCard(it.kanji, it.title, it.tags.splitSep(), it.distractors.splitSep()) }
        app.db.reviews().states(sourceId, stack, StudyMode.Quiz.name).forEach { states[it.kanji] = it.toSrs() }

        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val introduced = app.db.reviews().newCardsIntroducedSince(sourceId, stack, StudyMode.Quiz.name, startOfDay)
        val budget = app.settings.dailyNewCards.value - introduced
        val items = if (extra) QueueBuilder.extra(all.map { it.kanji }, states, Instant.now()) else QueueBuilder.build(all.map { it.kanji }, states, Instant.now(), budget)
        queue.addAll(items.map { it.kanji })
        showNext()
    }

    private var lastFont: KanjiFont? = null

    private fun nextFont(kanji: String): KanjiFont {
        if (!app.settings.varyFonts.value) return KanjiFont.Gothic
        val stability = states[kanji]?.takeIf { it.phase != CardPhase.New }?.stability ?: 0.0
        return FontPolicy.pick(stability, random, lastFont).also { lastFont = it }
    }

    private fun showNext() {
        val kanji = queue.removeFirstOrNull()
        if (kanji == null) {
            ui = if (answered == 0) QuizUi.Empty("Nothing is due. Come back later or raise the daily new-card limit in ⚙ settings.") else QuizUi.Done(answered, correct)
            return
        }
        val card = cards.getValue(kanji)
        val target = quizCards.first { it.kanji == kanji }
        ui = QuizUi.Question(card, QuizBuilder.options(target, quizCards, random), queue.size + 1, nextFont(kanji))
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

    /** Wrong answers are graded Again; right ones Good, or Hard when the user admits guessing. */
    fun next(guessed: Boolean = false) {
        val a = ui as? QuizUi.Answer ?: return
        val grade = when {
            !a.correct -> Grade.Again
            guessed -> Grade.Hard
            else -> Grade.Good
        }
        val now = Instant.now()
        val updated = fsrs.review(states[a.card.kanji] ?: SrsState(), grade, now)
        states[a.card.kanji] = updated
        answered++
        if (a.correct) correct++
        if (grade == Grade.Again) queue.add(minOf(3, queue.size), a.card.kanji)
        viewModelScope.launch {
            app.db.reviews().record(updated.toEntity(sourceId, stack, a.card.kanji, StudyMode.Quiz), ReviewLogEntity(0, sourceId, stack, a.card.kanji, StudyMode.Quiz.name, grade.value, now.toEpochMilli()))
        }
        showNext()
    }
}
