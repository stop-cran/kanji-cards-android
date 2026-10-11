package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.FontPolicy
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.PracticeSuggestion
import io.github.stopcran.kanji.core.words.TypedAnswer
import io.github.stopcran.kanji.core.words.TypedResult
import io.github.stopcran.kanji.core.words.TypedVerdict
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
import io.github.stopcran.kanji.core.srs.PadContext
import io.github.stopcran.kanji.core.srs.PracticePadding
import io.github.stopcran.kanji.data.padWordCandidates
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.wordSimilarityIndex
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
        val pickedWord: WordEntity? = null,
    ) : WordQuizUi {
        val key: String get() = if (direction == WordDirection.Reading) word.reading else word.word
        val correct: Boolean get() = picked == key
    }
    data class Done(val answered: Int, val correct: Int, val suggestion: WordDirection? = null) : WordQuizUi

    /** Typed mode: the English prompt and a free-text answer; the draft text lives in the view model. */
    data class TypeQuestion(val word: WordEntity, val remaining: Int, val font: KanjiFont) : WordQuizUi
    data class TypeAnswer(
        val word: WordEntity, val typed: String, val result: TypedResult, val remaining: Int, val font: KanjiFont, val gaveUp: Boolean,
    ) : WordQuizUi
}

/** Word quiz session in one direction; only that direction's scheduling state is updated. */
class WordQuizViewModel(private val app: Services) : ViewModel() {
    private val fsrs = Fsrs()
    private val random = Random.Default

    var ui: WordQuizUi by mutableStateOf(WordQuizUi.Loading)
        private set

    private var direction = WordDirection.JpToEn
    private var sourceId = ""
    private var stackId = WordStacks.n5.id
    private var wordList: List<WordEntity> = emptyList()
    private var words: Map<String, WordEntity> = emptyMap()
    private var cards: List<WordCard> = emptyList()
    private val states = mutableMapOf<String, SrsState>()
    private var session = QuizSession<String>(emptyList(), { it })
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
        wordList = all
        words = all.associateBy { it.word }
        cards = all.map { it.toCard() }
        app.db.reviews().states(sourceId, stackId, direction.mode.name).forEach { states[it.kanji] = it.toSrs() }
        val other = app.db.reviews().states(sourceId, stackId, direction.gate.mode.name).associate { it.kanji to it.toSrs() }

        val budget = app.db.newAllowance(app.settings, sourceId, Instant.now()).remaining
        val ids = all.forDirection(direction).map { it.word }
        val minimum = app.settings.practiceMinimum()
        val base = WordQueues.build(direction, ids, states, other, Instant.now(), budget, extra, limit = if (extra) minimum else Int.MAX_VALUE).map { it.kanji }
        val items = if (extra) base + pad(base, minimum - base.size, ids, all) else base
        session = QuizSession(items, { it })
        showNext()
    }

    /** Tops a short extra session up with weak words learned in other word stacks; they keep being scheduled in their own stack. */
    private suspend fun pad(base: List<String>, need: Int, ids: List<String>, inStack: List<WordEntity>): List<String> {
        if (need <= 0) return emptyList()
        val exclude = base.toSet() + states.keys
        val candidates = app.db.padWordCandidates(sourceId, stackId, direction.mode, ids.toSet(), exclude)
        val fillers = PracticePadding.fill(candidates, PadContext(exclude, base, inStack.wordSimilarityIndex()), need, Instant.now())
        for (f in fillers) {
            states[f.kanji] = f.state
            owners[f.kanji] = f.owner
        }
        return fillers.map { it.kanji }
    }

    /** Stack that owns a borrowed (padding) word; its answers are recorded there, never in the selected stack. */
    private val owners = mutableMapOf<String, String>()

    private fun nextFont(word: String): KanjiFont {
        if (!app.settings.varyFonts.value) return KanjiFont.Gothic
        val stability = states[word]?.takeIf { it.phase != CardPhase.New }?.stability ?: 0.0
        return FontPolicy.pick(stability, random, lastFont).also { lastFont = it }
    }

    private fun showNext() {
        val word = session.take()
        if (word == null) {
            ui = if (session.answered == 0) {
                WordQuizUi.Empty(
                    when (direction) {
                        WordDirection.EnToJp -> "Nothing is due. English → Japanese opens for words you have answered twice in Japanese → English."
                        WordDirection.Reading -> "Nothing is due. The reading quiz opens for words with kanji that you have answered twice in Japanese → English."
                        WordDirection.JpToEn -> "Nothing is due. Come back later or raise the daily new-card limit in ⚙ settings."
                        WordDirection.Type -> "Nothing is due. Typing opens for words you have answered twice in English → Japanese."
                    },
                )
            } else WordQuizUi.Done(session.answered, session.correct)
            if (ui is WordQuizUi.Done) suggest()
            return
        }
        val entity = words.getValue(word)
        if (direction == WordDirection.Type) {
            draft = ""
            ui = WordQuizUi.TypeQuestion(entity, session.pending + 1, nextFont(word))
            return
        }
        ui = WordQuizUi.Question(entity, direction, WordQuizBuilder.options(entity.toCard(), cards, direction, random), session.pending + 1, nextFont(word))
    }

    /** The typed text of the current question; cleared whenever a new question (including a re-ask) is shown. */
    var draft: String by mutableStateOf("")
        private set

    fun onDraft(text: String) {
        if (ui is WordQuizUi.TypeQuestion) draft = text
    }

    /** Checks the typed text, or marks the question as not known when [giveUp]. */
    fun submit(giveUp: Boolean = false) {
        val q = ui as? WordQuizUi.TypeQuestion ?: return
        val typed = if (giveUp) "" else draft
        if (!giveUp && typed.isBlank()) return
        ui = WordQuizUi.TypeAnswer(q.word, typed, TypedAnswer.grade(typed, q.word.toCard(), cards), q.remaining, q.font, giveUp)
    }

    private fun nextTyped(a: WordQuizUi.TypeAnswer) {
        val verdict = a.result.verdict
        val result = session.answer(a.word.word, a.result.ok, weak = verdict == TypedVerdict.KanaOnly, countsAsCorrect = verdict == TypedVerdict.Exact)
        if (result.record) record(a.word.word, result.grade, null)
        showNext()
    }

    private fun record(word: String, grade: Grade, firstSuccessCapDays: Double?) {
        val now = Instant.now()
        val updated = fsrs.review(
            states[word] ?: SrsState(), grade, now,
            firstSuccessCapDays = firstSuccessCapDays, fuzzSeed = Fsrs.seed(word, direction.mode.name, states[word]?.reps ?: 0),
        )
        states[word] = updated
        val mode = direction.mode
        val stack = owners[word] ?: stackId
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            val log = ReviewLogEntity(0, sourceId, stack, word, mode.name, grade.value, now.toEpochMilli())
            app.db.reviews().record(updated.toEntity(sourceId, stack, word, mode), log)
        }
    }

    fun pick(word: String, heard: String? = null) {
        val q = ui as? WordQuizUi.Question ?: return
        ui = WordQuizUi.Answer(q.word, q.direction, q.options, word, q.remaining, q.font, heard, words[word])
    }

    /** Takes back a voice pick that was misheard; nothing has been recorded before [next]. */
    fun retry() {
        val a = ui as? WordQuizUi.Answer ?: return
        if (a.heard != null) ui = WordQuizUi.Question(a.word, a.direction, a.options, a.remaining, a.font)
    }

    /** At the end of a session, offers "Practice more" in a better-stocked direction when this one would give only a few cards. */
    private fun suggest() {
        val done = ui as? WordQuizUi.Done ?: return
        viewModelScope.launch {
            val minimum = app.settings.practiceMinimum()
            val now = Instant.now()
            val stored = WordDirection.entries.associateWith { d ->
                app.db.reviews().states(sourceId, stackId, d.mode.name).associate { it.kanji to it.toSrs() }
            }
            val sizes = WordDirection.entries.associateWith { d ->
                val ids = wordList.forDirection(d).map { it.word }
                val own = stored.getValue(d)
                val n = WordQueues.build(d, ids, own, stored.getValue(d.gate), now, 0, extra = true, limit = minimum).size
                if (n >= minimum) n else n + app.db.padWordCandidates(sourceId, stackId, d.mode, ids.toSet(), own.keys).size.coerceAtMost(minimum - n)
            }
            val alternative = PracticeSuggestion.alternative(direction, sizes)
            if (alternative != null && ui === done) ui = done.copy(suggestion = alternative)
        }
    }

    /** Wrong answers (and "don't know") are graded Again and relearned in-session; right ones Good, or Hard when guessed. */
    fun next(guessed: Boolean = false) {
        (ui as? WordQuizUi.TypeAnswer)?.let { return nextTyped(it) }
        val a = ui as? WordQuizUi.Answer ?: return
        val result = session.answer(a.word.word, a.correct, guessed)
        if (result.record) record(a.word.word, result.grade, Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS)
        showNext()
    }
}
