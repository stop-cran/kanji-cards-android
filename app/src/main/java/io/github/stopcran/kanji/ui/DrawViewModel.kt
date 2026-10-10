package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.StrokeData
import io.github.stopcran.kanji.core.draw.DrawGate
import io.github.stopcran.kanji.core.draw.DrawGrader
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.LookalikeGate
import io.github.stopcran.kanji.core.draw.lookalikeFromCandidates
import io.github.stopcran.kanji.core.draw.MatchResult
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.Stroke
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.DrawingLog
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.inStack
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A different kanji that the drawing was taken for; [title] is null when it is not in the content. */
data class Lookalike(val kanji: String, val title: String?, val hasCard: Boolean)

sealed interface DrawUi {
    data object Loading : DrawUi
    data class Empty(val message: String) : DrawUi
    data class Question(val card: KanjiEntity, val remaining: Int, val seq: Int) : DrawUi
    data class Checking(val card: KanjiEntity) : DrawUi
    data class Answer(
        val card: KanjiEntity,
        val remaining: Int,
        val reference: List<Stroke>,
        val drawn: List<List<TimedPt>>,
        val canvasPx: Float,
        val outcome: DrawOutcome,
        val match: MatchResult,
        val recognizerUsed: Boolean,
        val lookalike: Lookalike? = null,
    ) : DrawUi
    data class Done(val answered: Int, val clean: Int) : DrawUi
}

/** Drawing session: caption + canvas -> check (recogniser gate + stroke matcher) -> graded answer with overlay. */
class DrawViewModel(private val app: Services) : ViewModel() {
    private val fsrs = Fsrs()
    private val matcher = StrokeMatcher()
    private val recognizer = InkRecognizer()
    private val json = Json { ignoreUnknownKeys = true }

    var ui: DrawUi by mutableStateOf(DrawUi.Loading)
        private set
    /** Fixed for the whole card (question, answer, undo, clear); re-rolled when the next card appears. */
    var look: BrushLook by mutableStateOf(BrushLook.random())
        private set
    var modelState: ModelState by mutableStateOf(ModelState.Unknown)
        private set

    private var sourceId = ""
    private var stack = Stacks.ALL
    private var cards: Map<String, KanjiEntity> = emptyMap()
    private val references = mutableMapOf<String, List<Stroke>>()
    private val orderVariants = mutableMapOf<String, List<List<Int>>>()
    private val states = mutableMapOf<String, SrsState>()
    private var session = QuizSession<String>(emptyList(), { it }, confirm = false)
    private var started = false

    fun ensureStarted(extra: Boolean) {
        if (started) return
        started = true
        modelState = ModelState.Downloading
        viewModelScope.launch { modelState = recognizer.prepare() }
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        val source = app.settings.source.value
        sourceId = source.id
        stack = app.settings.stack.value
        val all = app.db.content().kanji(sourceId).inStack(stack)
        for (k in all) {
            val data = k.strokesJson?.let { runCatching { json.decodeFromString(StrokeData.serializer(), it) }.getOrNull() } ?: continue
            references[k.kanji] = data.strokes.map { s -> s.points.map { Pt(it[0], it[1]) } }
            if (data.orderVariants.isNotEmpty()) orderVariants[k.kanji] = data.orderVariants
        }
        val drawable = all.filter { it.kanji in references }
        if (drawable.isEmpty()) {
            ui = DrawUi.Empty("No cards with stroke data yet. Sync the content first (⚙ settings).")
            return
        }
        cards = drawable.associateBy { it.kanji }
        app.db.reviews().states(sourceId, stack, StudyMode.Draw.name).forEach { states[it.kanji] = it.toSrs() }
        val ids = DrawGate.eligible(drawable.map { it.kanji }, states, app.db.reviews().meaningLearned(sourceId, stack).toSet())
        if (ids.isEmpty() && !extra) {
            ui = DrawUi.Empty("Drawing unlocks after a kanji's meaning is answered well on two days in the Quiz.")
            return
        }
        val budget = app.db.newAllowance(app.settings, sourceId, Instant.now()).remaining
        val items = if (extra) QueueBuilder.extra(ids, states, Instant.now()) else QueueBuilder.build(ids, states, Instant.now(), budget)
        session = QuizSession(items.map { it.kanji }, { it }, confirm = false)
        showNext()
    }

    private fun showNext() {
        val kanji = session.take()
        ui = if (kanji == null) {
            if (session.answered == 0) DrawUi.Empty("Nothing is due. Come back later or raise the daily new-card limit in ⚙ settings.") else DrawUi.Done(session.answered, session.correct)
        } else {
            look = BrushLook.random()
            DrawUi.Question(cards.getValue(kanji), session.pending + 1, session.answered)
        }
    }

    /** [strokes] are in canvas pixels; an empty list means the user gave up. */
    fun check(strokes: List<List<TimedPt>>, canvasPx: Float) {
        val q = ui as? DrawUi.Question ?: return
        ui = DrawUi.Checking(q.card)
        viewModelScope.launch {
            val ref = references.getValue(q.card.kanji)
            val drawn = strokes.map { s -> s.map { Pt(it.x.toDouble(), it.y.toDouble()) } }
            val match = matcher.match(ref, drawn, orderVariants[q.card.kanji].orEmpty())
            val candidates = if (strokes.isEmpty()) emptyList() else recognizer.candidates(strokes)
            val byStrokes = if (match.clean && match.drawnStrokes > 0) {
                withContext(Dispatchers.Default) { LookalikeGate(matcher).find(q.card.kanji, match, drawn, pool().filter { it.second.size == ref.size }) }
            } else null
            val outcome = DrawGrader.outcome(candidates, q.card.kanji, match, strokeLookalike = byStrokes)
            val guess = byStrokes ?: if (outcome == DrawOutcome.NotRecognized && strokes.isNotEmpty()) lookalikeFromCandidates(candidates, q.card.kanji) else null
            val lookalike = guess?.let { g -> app.db.content().kanji(sourceId).firstOrNull { it.kanji == g }.let { Lookalike(g, it?.title, it != null) } }
            if (app.settings.saveDrawings.value) DrawingLog.save(app.context, q.card.kanji, canvasPx, strokes, outcome, match, candidates)
            ui = DrawUi.Answer(q.card, q.remaining, ref, strokes, canvasPx, outcome, match, candidates != null, lookalike)
        }
    }

    private var poolCache: List<Pair<String, List<Stroke>>>? = null

    /** Reference strokes of every kanji in the source, whatever the stack, parsed once on first use. */
    private suspend fun pool(): List<Pair<String, List<Stroke>>> = poolCache ?: withContext(Dispatchers.Default) {
        app.db.content().kanji(sourceId).mapNotNull { k ->
            val data = k.strokesJson?.let { runCatching { json.decodeFromString(StrokeData.serializer(), it) }.getOrNull() } ?: return@mapNotNull null
            k.kanji to data.strokes.map { s -> s.points.map { Pt(it[0], it[1]) } }
        }
    }.also { poolCache = it }

    /** Not recognised = Again (relearned), recognised with stroke mistakes = Hard (asked once more), clean = Good. */
    fun next() {
        val a = ui as? DrawUi.Answer ?: return
        val grade = session.answer(a.card.kanji, a.outcome != DrawOutcome.NotRecognized, weak = a.outcome == DrawOutcome.Mistakes, countsAsCorrect = a.outcome == DrawOutcome.Clean).grade
        val now = Instant.now()
        val updated = fsrs.review(states[a.card.kanji] ?: SrsState(), grade, now, fuzzSeed = Fsrs.seed(a.card.kanji, StudyMode.Draw.name, states[a.card.kanji]?.reps ?: 0))
        states[a.card.kanji] = updated
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            app.db.reviews().record(
                updated.toEntity(sourceId, stack, a.card.kanji, StudyMode.Draw),
                ReviewLogEntity(0, sourceId, stack, a.card.kanji, StudyMode.Draw.name, grade.value, now.toEpochMilli()),
            )
        }
        showNext()
    }

    override fun onCleared() {
        recognizer.close()
    }
}
