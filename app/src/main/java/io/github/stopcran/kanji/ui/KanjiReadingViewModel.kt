package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.reading.KanjiReadingQuiz
import io.github.stopcran.kanji.core.reading.ReadingCard
import io.github.stopcran.kanji.core.reading.ReadingItem
import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.core.reading.ReadingKey
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.reading.ReadingQuestion
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.FontPolicy
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.inStack
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.ReadingRequest
import io.github.stopcran.kanji.data.readingData
import io.github.stopcran.kanji.data.padCandidates
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.similarityIndex
import io.github.stopcran.kanji.data.toReadingCard
import io.github.stopcran.kanji.core.reading.KanjiReadingQueue
import io.github.stopcran.kanji.core.srs.PadContext
import io.github.stopcran.kanji.core.srs.PracticePadding
import io.github.stopcran.kanji.data.toEntity
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.random.Random

sealed interface ReadingUi {
    data object Loading : ReadingUi
    data class Empty(val message: String) : ReadingUi
    data class Question(val card: KanjiEntity, val question: ReadingQuestion, val remaining: Int, val font: KanjiFont) : ReadingUi
    data class Answer(val card: KanjiEntity, val question: ReadingQuestion, val picked: String, val remaining: Int, val font: KanjiFont, val allReadings: List<String>, val pickedBelongsTo: List<String> = emptyList()) : ReadingUi {
        val correct: Boolean get() = picked == question.correctKey
    }
    data class Done(val answered: Int, val correct: Int) : ReadingUi
}

/** Kanji readings session over on'yomi and kun'yomi; each (kanji, kind, reading) has its own scheduling state. */
class KanjiReadingViewModel(private val app: Services) : StudyViewModel(app.sessionStore) {
    private val fsrs = Fsrs()
    private val random = Random.Default

    var ui: ReadingUi by mutableStateOf(ReadingUi.Loading)
        private set

    private var sourceId = ""
    private var stack = Stacks.ALL
    private var cards: Map<String, KanjiEntity> = emptyMap()
    private var readingCards: List<ReadingCard> = emptyList()
    private val states = ReadingKind.entries.associateWith { mutableMapOf<String, SrsState>() }
    /** Stack that owns a borrowed (padding) reading card, keyed by kind and id; its answers are recorded there. */
    private val owners = mutableMapOf<String, String>()
    private var session = QuizSession<ReadingItem>(emptyList(), { it.id })
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
        val remaining = app.db.newAllowance(app.settings, sourceId, Instant.now()).remaining
        val minimum = app.settings.practiceMinimum()
        val request = ReadingRequest(sourceId, stack, remaining, Instant.now(), extra, maxOf(minimum, KanjiReadingQueue.EXTRA_LIMIT))
        val data = app.db.readingData(app.settings, all, request)
        readingCards = data.cards
        data.states.forEach { (kind, m) -> states.getValue(kind) += m }
        val items = if (extra && data.items.size < minimum) {
            KanjiReadingQueue.interleave(data.items + pad(data.items, minimum - data.items.size, all))
        } else {
            data.items
        }
        session = trackSession(QuizSession(items, { it.id }), sourceId, stack, "KanjiReadings", extra)
        showNext()
    }

    private fun padKey(kind: ReadingKind, id: String) = "${kind.name}|$id"

    /** Tops a short extra session up with weak, already-learned readings of other stacks; they keep being scheduled in their own stack. */
    private suspend fun pad(base: List<ReadingItem>, need: Int, inStack: List<KanjiEntity>): List<ReadingItem> {
        val content = app.db.content().kanji(sourceId)
        val inStackKanji = inStack.mapTo(HashSet()) { it.kanji }
        val byKanji = content.associateBy { it.kanji }
        val candidates = ReadingKind.entries.flatMap { kind ->
            app.db.padCandidates(sourceId, stack, kind.mode, content, inStackKanji).filter { c ->
                byKanji[c.kanji]?.toReadingCard()?.quizKeys(kind)?.contains(ReadingId.key(c.id)) == true
            }.map { it.copy(id = padKey(kind, it.id)) }
        }
        val exclude = base.map { padKey(it.kind, it.id) }.toSet() + states.flatMap { (kind, m) -> m.keys.map { padKey(kind, it) } }
        val context = PadContext(exclude, base.map { it.kanji }, content.similarityIndex())
        val fillers = PracticePadding.fill(candidates, context, need, Instant.now())
        return fillers.map { f ->
            val kind = ReadingKind.valueOf(f.id.substringBefore('|'))
            val id = f.id.substringAfter('|')
            val k = byKanji.getValue(f.kanji)
            if (cards[k.kanji] == null) {
                cards = cards + (k.kanji to k)
                readingCards = readingCards + k.toReadingCard()
            }
            states.getValue(kind)[id] = f.state
            owners[f.id] = f.owner
            ReadingItem(f.kanji, kind, ReadingId.key(id), false)
        }
    }

    private fun nextFont(id: String, kind: ReadingKind): KanjiFont {
        if (!app.settings.varyFonts.value) return KanjiFont.Gothic
        val stability = states.getValue(kind)[id]?.takeIf { it.phase != CardPhase.New }?.stability ?: 0.0
        return FontPolicy.pick(stability, random, lastFont).also { lastFont = it }
    }

    private fun showNext() {
        while (true) {
            val item = session.take()
            if (item == null) {
                finishSession()
                ui = if (session.answered == 0) ReadingUi.Empty("Nothing to practise yet. ${app.settings.unlockRule.value.hint.replace("Unlocks", "Readings unlock")}.") else ReadingUi.Done(session.answered, session.correct)
                return
            }
            val target = readingCards.first { it.kanji == item.kanji }
            // A question with too few safe options is skipped rather than shown with a doubtful answer.
            val q = KanjiReadingQuiz.question(target, item.kind, readingCards, random, reading = item.reading) ?: continue
            ui = ReadingUi.Question(cards.getValue(item.kanji), q, session.pending + 1, nextFont(item.id, item.kind))
            return
        }
    }

    fun pick(key: String) {
        val q = ui as? ReadingUi.Question ?: return
        val target = readingCards.first { it.kanji == q.card.kanji }
        val all = (target.on.map { ReadingKey.key(it) to ReadingKey.display(it, ReadingKind.On) } + target.kun.map { ReadingKey.key(it) to ReadingKey.display(it, ReadingKind.Kun) })
            .distinctBy { it.first }.map { it.second }
        val belongsTo = if (key == NO_ANSWER) emptyList() else readingCards.filter { c ->
            c.kanji != q.card.kanji && (c.on + c.kun).any { ReadingKey.key(it) == key }
        }.map { it.kanji }.take(3)
        ui = ReadingUi.Answer(q.card, q.question, key, q.remaining, q.font, all, belongsTo)
    }

    /** Wrong answers (and "don't know") are graded Again and relearned in-session; right ones Good, or Hard when the user admits guessing. */
    fun next(guessed: Boolean = false) {
        val a = ui as? ReadingUi.Answer ?: return
        val kind = a.question.kind
        val kanji = a.card.kanji
        val id = ReadingId.of(kanji, a.question.correctKey)
        val result = session.answer(ReadingItem(kanji, kind, a.question.correctKey, false), a.correct, guessed)
        val grade = result.grade
        if (result.record) {
            val now = Instant.now()
            val updated = fsrs.review(
                states.getValue(kind)[id] ?: SrsState(), grade, now,
                firstSuccessCapDays = Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS, fuzzSeed = Fsrs.seed(id, kind.mode.name, states.getValue(kind)[id]?.reps ?: 0),
            )
            states.getValue(kind)[id] = updated
            val owner = owners[padKey(kind, id)] ?: stack
            recordSession {
                val log = ReviewLogEntity(0, sourceId, owner, kanji, kind.mode.name, grade.value, now.toEpochMilli(), a.question.correctKey, reviewSessionId)
                app.db.reviews().record(updated.toEntity(sourceId, owner, kanji, kind.mode, a.question.correctKey), log)
            }
        }
        showNext()
    }
}
