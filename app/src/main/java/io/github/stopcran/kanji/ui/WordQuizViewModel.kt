package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.FontPolicy
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordOption
import io.github.stopcran.kanji.core.words.WordQuizBuilder
import io.github.stopcran.kanji.core.words.WordQueues
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.forDirection
import io.github.stopcran.kanji.data.inWordStack
import io.github.stopcran.kanji.data.levels
import io.github.stopcran.kanji.data.toCard
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

sealed interface WordQuizUi {
    data object Loading : WordQuizUi
    data class Empty(val message: String) : WordQuizUi
    data class Question(val word: WordEntity, val direction: WordDirection, val options: List<WordOption>, val remaining: Int, val font: KanjiFont) : WordQuizUi
    data class Answer(
        val word: WordEntity, val direction: WordDirection, val options: List<WordOption>, val picked: String, val remaining: Int, val font: KanjiFont, val heard: String? = null,
    ) : WordQuizUi {
        val key: String get() = if (direction == WordDirection.Reading) word.reading else word.word
        val correct: Boolean get() = picked == key
    }
    data class Done(val answered: Int, val correct: Int) : WordQuizUi
}

/** Word quiz session in one direction; only that direction's scheduling state is updated. */
class WordQuizViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KanjiApp
    private val fsrs = Fsrs()
    private val random = Random.Default

    var ui: WordQuizUi by mutableStateOf(WordQuizUi.Loading)
        private set

    private var direction = WordDirection.JpToEn
    private var sourceId = ""
    private var stackId = WordStacks.n5.id
    private var words: Map<String, WordEntity> = emptyMap()
    private var cards: List<WordCard> = emptyList()
    private val states = mutableMapOf<String, SrsState>()
    private val queue = ArrayDeque<String>()
    private var answered = 0
    private var correct = 0
    private var started = false
    private var lastFont: KanjiFont? = null

    fun ensureStarted(direction: WordDirection, extra: Boolean) {
        if (started) return
        started = true
        this.direction = direction
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        val source = app.settings.source.value
        sourceId = source.id
        val offered = WordStacks.offered(app.settings.n4Unlocked.value)
        val stack = WordStacks.find(app.settings.wordStack.value, offered)
        stackId = stack.id
        val levels = app.db.content().kanji(sourceId).levels()
        val all = app.db.content().words(sourceId).inWordStack(stack, levels)
        if (all.size < 2) {
            ui = WordQuizUi.Empty("No words yet. Sync the content first (⚙ settings).")
            return
        }
        words = all.associateBy { it.word }
        cards = all.map { it.toCard() }
        app.db.reviews().states(sourceId, stackId, direction.mode.name).forEach { states[it.kanji] = it.toSrs() }
        val other = app.db.reviews().states(sourceId, stackId, direction.other.mode.name).associate { it.kanji to it.toSrs() }

        val budget = app.db.newAllowance(app.settings, sourceId, Instant.now()).remaining
        queue.addAll(WordQueues.build(direction, all.forDirection(direction).map { it.word }, states, other, Instant.now(), budget, extra).map { it.kanji })
        showNext()
    }

    private fun nextFont(word: String): KanjiFont {
        if (!app.settings.varyFonts.value) return KanjiFont.Gothic
        val stability = states[word]?.takeIf { it.phase != CardPhase.New }?.stability ?: 0.0
        return FontPolicy.pick(stability, random, lastFont).also { lastFont = it }
    }

    private fun showNext() {
        val word = queue.removeFirstOrNull()
        if (word == null) {
            ui = if (answered == 0) {
                WordQuizUi.Empty(
                    when (direction) {
                        WordDirection.EnToJp -> "Nothing is due. English → Japanese opens for words you have answered twice in Japanese → English."
                        WordDirection.Reading -> "Nothing is due. The reading quiz opens for words with kanji that you have answered twice in Japanese → English."
                        WordDirection.JpToEn -> "Nothing is due. Come back later or raise the daily new-card limit in ⚙ settings."
                    },
                )
            } else WordQuizUi.Done(answered, correct)
            return
        }
        val entity = words.getValue(word)
        ui = WordQuizUi.Question(entity, direction, WordQuizBuilder.options(entity.toCard(), cards, direction, random), queue.size + 1, nextFont(word))
    }

    fun pick(word: String, heard: String? = null) {
        val q = ui as? WordQuizUi.Question ?: return
        ui = WordQuizUi.Answer(q.word, q.direction, q.options, word, q.remaining, q.font, heard)
    }

    /** Takes back a voice pick that was misheard; nothing has been recorded before [next]. */
    fun retry() {
        val a = ui as? WordQuizUi.Answer ?: return
        if (a.heard != null) ui = WordQuizUi.Question(a.word, a.direction, a.options, a.remaining, a.font)
    }

    /** Wrong answers are graded Again and asked again a few cards later; right ones Good, or Hard when guessed. */
    fun next(guessed: Boolean = false) {
        val a = ui as? WordQuizUi.Answer ?: return
        val grade = when {
            !a.correct -> Grade.Again
            guessed -> Grade.Hard
            else -> Grade.Good
        }
        val now = Instant.now()
        val updated = fsrs.review(
            states[a.word.word] ?: SrsState(), grade, now,
            firstSuccessCapDays = Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS, fuzzSeed = Fsrs.seed(a.word.word, direction.mode.name, states[a.word.word]?.reps ?: 0),
        )
        states[a.word.word] = updated
        answered++
        if (a.correct) correct++
        if (grade == Grade.Again) queue.add(minOf(3, queue.size), a.word.word)
        val mode = direction.mode
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            app.db.reviews().record(updated.toEntity(sourceId, stackId, a.word.word, mode), ReviewLogEntity(0, sourceId, stackId, a.word.word, mode.name, grade.value, now.toEpochMilli()))
        }
        showNext()
    }
}
