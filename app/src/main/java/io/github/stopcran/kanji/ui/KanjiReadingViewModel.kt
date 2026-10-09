package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.reading.KanjiReadingQuiz
import io.github.stopcran.kanji.core.reading.ReadingCard
import io.github.stopcran.kanji.core.reading.ReadingItem
import io.github.stopcran.kanji.core.reading.ReadingKey
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.reading.ReadingQuestion
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.FontPolicy
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.inStack
import io.github.stopcran.kanji.data.readingData
import io.github.stopcran.kanji.data.toEntity
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.random.Random

sealed interface ReadingUi {
    data object Loading : ReadingUi
    data class Empty(val message: String) : ReadingUi
    data class Question(val card: KanjiEntity, val question: ReadingQuestion, val remaining: Int, val font: KanjiFont) : ReadingUi
    data class Answer(val card: KanjiEntity, val question: ReadingQuestion, val picked: String, val remaining: Int, val font: KanjiFont, val allReadings: List<String>) : ReadingUi {
        val correct: Boolean get() = picked == question.correctKey
    }
    data class Done(val answered: Int, val correct: Int) : ReadingUi
}

/** Kanji readings session over on'yomi and kun'yomi; each (kanji, kind) has its own scheduling state. */
class KanjiReadingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KanjiApp
    private val fsrs = Fsrs()
    private val random = Random.Default

    var ui: ReadingUi by mutableStateOf(ReadingUi.Loading)
        private set

    private var sourceId = ""
    private var stack = Stacks.ALL
    private var cards: Map<String, KanjiEntity> = emptyMap()
    private var readingCards: List<ReadingCard> = emptyList()
    private val states = ReadingKind.entries.associateWith { mutableMapOf<String, SrsState>() }
    private val queue = ArrayDeque<ReadingItem>()
    private var answered = 0
    private var correct = 0
    private var lastFont: KanjiFont? = null
    private var started = false

    fun ensureStarted(extra: Boolean) {
        if (started) return
        started = true
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        sourceId = app.settings.source.value.id
        stack = app.settings.stack.value
        val all = app.db.content().kanji(sourceId).inStack(stack)
        if (all.isEmpty()) {
            ui = ReadingUi.Empty("No cards yet. Sync the content first (⚙ settings).")
            return
        }
        cards = all.associateBy { it.kanji }
        val data = app.db.readingData(sourceId, stack, all, app.settings.dailyNewCards.value, Instant.now(), extra)
        readingCards = data.cards
        data.states.forEach { (kind, m) -> states.getValue(kind) += m }
        queue.addAll(data.items)
        showNext()
    }

    private fun nextFont(kanji: String, kind: ReadingKind): KanjiFont {
        if (!app.settings.varyFonts.value) return KanjiFont.Gothic
        val stability = states.getValue(kind)[kanji]?.takeIf { it.phase != CardPhase.New }?.stability ?: 0.0
        return FontPolicy.pick(stability, random, lastFont).also { lastFont = it }
    }

    private fun showNext() {
        while (true) {
            val item = queue.removeFirstOrNull()
            if (item == null) {
                ui = if (answered == 0) ReadingUi.Empty("Nothing to practise yet. Readings unlock after a kanji's meaning is answered well twice in the Quiz.") else ReadingUi.Done(answered, correct)
                return
            }
            val target = readingCards.first { it.kanji == item.kanji }
            // A question with too few safe options is skipped rather than shown with a doubtful answer.
            val q = KanjiReadingQuiz.question(target, item.kind, readingCards, random) ?: continue
            ui = ReadingUi.Question(cards.getValue(item.kanji), q, queue.size + 1, nextFont(item.kanji, item.kind))
            return
        }
    }

    fun pick(key: String) {
        val q = ui as? ReadingUi.Question ?: return
        val target = readingCards.first { it.kanji == q.card.kanji }
        val all = (target.on.map { ReadingKey.key(it) to ReadingKey.display(it, ReadingKind.On) } + target.kun.map { ReadingKey.key(it) to ReadingKey.display(it, ReadingKind.Kun) })
            .distinctBy { it.first }.map { it.second }
        ui = ReadingUi.Answer(q.card, q.question, key, q.remaining, q.font, all)
    }

    /** Wrong answers are graded Again; right ones Good, or Hard when the user admits guessing. */
    fun next(guessed: Boolean = false) {
        val a = ui as? ReadingUi.Answer ?: return
        val kind = a.question.kind
        val kanji = a.card.kanji
        val grade = when {
            !a.correct -> Grade.Again
            guessed -> Grade.Hard
            else -> Grade.Good
        }
        val now = Instant.now()
        val updated = fsrs.review(states.getValue(kind)[kanji] ?: SrsState(), grade, now)
        states.getValue(kind)[kanji] = updated
        answered++
        if (a.correct) correct++
        if (grade == Grade.Again) queue.add(minOf(3, queue.size), ReadingItem(kanji, kind, false))
        viewModelScope.launch {
            app.db.reviews().record(updated.toEntity(sourceId, stack, kanji, kind.mode), ReviewLogEntity(0, sourceId, stack, kanji, kind.mode.name, grade.value, now.toEpochMilli()))
        }
        showNext()
    }
}
