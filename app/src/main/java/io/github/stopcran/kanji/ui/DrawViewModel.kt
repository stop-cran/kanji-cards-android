package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.StrokeData
import io.github.stopcran.kanji.core.content.RepoSource
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
import io.github.stopcran.kanji.core.srs.PadContext
import io.github.stopcran.kanji.core.srs.PracticePadding
import io.github.stopcran.kanji.data.padCandidates
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.similarityIndex
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.DrawingLog
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.inStack
import io.github.stopcran.kanji.data.newKanjiInStack
import io.github.stopcran.kanji.data.recordKanjiReview
import io.github.stopcran.kanji.data.stacks
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.meaningLearned
import io.github.stopcran.kanji.data.toSrs
import io.github.stopcran.kanji.data.AppLocale
import io.github.stopcran.kanji.data.isOrdinaryEntryAllowed
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
    data class Empty(val reason: DrawEmptyReason) : DrawUi
    data class Question(val card: KanjiEntity, val remaining: Int, val seq: Int, val trace: Boolean = false) : DrawUi
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
        val trace: Boolean = false,
    ) : DrawUi
    data class Done(val answered: Int, val clean: Int) : DrawUi
}

enum class DrawEmptyReason {
    NoStrokeCards,
    UnlockBlocked,
    NothingDue,
    ContentLanguageMismatch,
    RussianDefaultGated,
    CapturedSourceUnavailable,
}

/** Drawing session: caption + canvas -> check (recogniser gate + stroke matcher) -> graded answer with overlay. */
class DrawViewModel(private val app: Services, private val recognizer: InkRecognition = InkRecognizer()) :
    StudyViewModel(app.sessionStore, app.kanjiWrites) {
    private val fsrs = Fsrs()
    private val matcher = StrokeMatcher()
    private val json = Json { ignoreUnknownKeys = true }

    var ui: DrawUi by mutableStateOf(DrawUi.Loading)
        private set
    /** Fixed for the whole session, including its question and answer views. */
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
    /** Stack that owns a borrowed (padding) card; its answers are recorded there, never in the selected stack. */
    private val owners = mutableMapOf<String, String>()
    private var session = QuizSession<String>(emptyList(), { it }, confirm = false)
    private var started = false

    fun ensureStarted(extra: Boolean) {
        if (started) return
        started = true
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        app.kanjiWrites.awaitPending()
        val source = app.settings.source.value
        val capturedSourceId = registeredCapturedSourceId()
        if (!admitDrawingSource(source, capturedSourceId)) return
        sourceId = source.id
        stack = Stacks.find(app.settings.stack.value, app.db.content().kanjiLite(sourceId).stacks()).id
        prepareDrawingSession(extra, source, capturedSourceId)
    }

    private suspend fun admitDrawingSource(source: RepoSource, capturedSourceId: String?): Boolean = when {
        capturedSourceId != null && capturedSourceId != source.id -> {
            ui = DrawUi.Empty(DrawEmptyReason.CapturedSourceUnavailable)
            false
        }
        capturedSourceId == null -> {
            val failure = drawingAdmissionFailure(source)
            if (failure == null) true else {
                ui = DrawUi.Empty(failure)
                false
            }
        }
        else -> true
    }

    private suspend fun prepareDrawingSession(extra: Boolean, source: RepoSource, capturedSourceId: String?) {
        val all = app.db.content().kanji(sourceId).inStack(stack)
        if (rejectFreshDrawingIfChanged(source, capturedSourceId)) return
        loadReferenceStrokes(all)
        val drawable = all.filter { it.kanji in references }
        if (drawable.isEmpty()) {
            if (rejectFreshDrawingIfChanged(source, capturedSourceId)) return
            ui = DrawUi.Empty(DrawEmptyReason.NoStrokeCards)
            return
        }
        cards = drawable.associateBy { it.kanji }
        app.db.reviews().states(sourceId, Stacks.stateId(stack), StudyMode.Draw.name).forEach { states[it.kanji] = it.toSrs() }
        val ids = DrawGate.eligible(drawable.map { it.kanji }, states, app.db.meaningLearned(app.settings, sourceId, stack))
        if (rejectFreshDrawingIfChanged(source, capturedSourceId)) return
        if (ids.isEmpty() && !extra) {
            ui = DrawUi.Empty(DrawEmptyReason.UnlockBlocked)
            return
        }
        val budget = app.db.newAllowance(app.settings, sourceId, Instant.now()).remaining
        val newEligible = drawable.newKanjiInStack(stack)
        val items = if (extra) {
            val minimum = app.settings.practiceMinimum()
            val base = QueueBuilder.extra(ids, states, Instant.now(), minimum, newEligible = newEligible).map { it.kanji }
            base + pad(base, minimum - base.size, all)
        } else {
            QueueBuilder.build(ids, states, Instant.now(), budget, newEligible = newEligible).map { it.kanji }
        }
        if (rejectFreshDrawingIfChanged(source, capturedSourceId)) return
        modelState = ModelState.Downloading
        viewModelScope.launch { modelState = recognizer.prepare() }
        session = trackSession(QuizSession(items, { it }, confirm = false), sourceId, stack, StudyMode.Draw.name, extra)
        showNext()
    }

    private fun loadReferenceStrokes(all: List<KanjiEntity>) {
        for (kanji in all) {
            val data = kanji.strokesJson?.let { runCatching { json.decodeFromString(StrokeData.serializer(), it) }.getOrNull() } ?: continue
            references[kanji.kanji] = data.strokes.map { stroke -> stroke.points.map { Pt(it[0], it[1]) } }
            if (data.orderVariants.isNotEmpty()) orderVariants[kanji.kanji] = data.orderVariants
        }
    }

    private suspend fun drawingAdmissionFailure(source: RepoSource): DrawEmptyReason? {
        if (!isOrdinaryEntryAllowed(source)) return DrawEmptyReason.RussianDefaultGated
        val sourceLanguage = app.db.content().meta(source.id)?.baseLanguage ?: app.settings.sync.baseLanguage(source.id) ?: "en"
        val uiLanguage = AppLocale.resolvedLanguage(app.settings.appLanguage.value)
        return DrawEmptyReason.ContentLanguageMismatch.takeIf { sourceLanguage != uiLanguage }
    }

    private suspend fun rejectFreshDrawingIfChanged(source: RepoSource, capturedSourceId: String?): Boolean {
        if (capturedSourceId != null) return false
        val reason = if (app.settings.source.value != source) {
            DrawEmptyReason.CapturedSourceUnavailable
        } else {
            drawingAdmissionFailure(source)
        } ?: return false
        ui = DrawUi.Empty(reason)
        return true
    }

    /** Tops a short extra session up with weak, already-drawn kanji of other stacks; they keep being scheduled in their own stack. */
    private suspend fun pad(base: List<String>, need: Int, inStack: List<KanjiEntity>): List<String> {
        if (need <= 0) return emptyList()
        val content = app.db.content().kanji(sourceId)
        val candidates = app.db.padCandidates(sourceId, stack, StudyMode.Draw, content.filter { it.strokesJson != null }, inStack.mapTo(HashSet()) { it.kanji })
        val fillers = PracticePadding.fill(candidates, PadContext(base.toSet() + states.keys, base, content.similarityIndex()), need, Instant.now())
        val byKanji = content.associateBy { it.kanji }
        val added = mutableListOf<String>()
        for (f in fillers) {
            val k = byKanji.getValue(f.kanji)
            val data = k.strokesJson?.let { runCatching { json.decodeFromString(StrokeData.serializer(), it) }.getOrNull() } ?: continue
            references[k.kanji] = data.strokes.map { s -> s.points.map { Pt(it[0], it[1]) } }
            if (data.orderVariants.isNotEmpty()) orderVariants[k.kanji] = data.orderVariants
            cards = cards + (k.kanji to k)
            states[k.kanji] = f.state
            owners[k.kanji] = f.owner
            added += k.kanji
        }
        return added
    }

    /** Strokes of the question being drawn; kept here so they survive rotation. [inkSize] is the pad size they are in. */
    val ink = androidx.compose.runtime.mutableStateListOf<List<TimedPt>>()
    var inkSize = 1f
        private set

    /** The pad was laid out at [px]; strokes drawn at another size (after rotation) are rescaled to it. */
    fun padSized(px: Float) {
        if (px <= 0f) return
        if (ink.isNotEmpty() && inkSize > 1f && kotlin.math.abs(px - inkSize) > 0.5f) {
            val k = px / inkSize
            for (i in ink.indices) ink[i] = ink[i].map { TimedPt(it.x * k, it.y * k, it.t) }
        }
        inkSize = px
    }

    fun reference(kanji: String): List<Stroke>? = references[kanji]

    private fun showNext() {
        ink.clear()
        val kanji = session.take()
        ui = if (kanji == null) {
            finishSession()
            if (session.answered == 0) DrawUi.Empty(DrawEmptyReason.NothingDue) else DrawUi.Done(session.answered, session.correct)
        } else {
            val trace = DrawGate.shouldTrace(app.settings.traceNew.value, states[kanji], traced, kanji)
            if (trace) traced += kanji
            DrawUi.Question(cards.getValue(kanji), session.pending + 1, session.answered, trace)
        }
    }

    /** Kanji already traced in this session; not persisted, so an unfinished session traces them again. */
    private val traced = mutableSetOf<String>()

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
            if (app.settings.saveDrawings.value && !q.trace) DrawingLog.save(app.context, q.card.kanji, canvasPx, strokes, outcome, match, candidates)
            ui = DrawUi.Answer(q.card, q.remaining, ref, strokes, canvasPx, outcome, match, candidates != null, lookalike, q.trace)
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
        if (a.trace) {
            session.defer(a.card.kanji, 3)
            showNext()
            return
        }
        val ok = a.outcome != DrawOutcome.NotRecognized
        val grade = session.answer(a.card.kanji, ok, weak = a.outcome == DrawOutcome.Mistakes, countsAsCorrect = a.outcome == DrawOutcome.Clean).grade
        val now = Instant.now()
        val updated = fsrs.review(states[a.card.kanji] ?: SrsState(), grade, now, fuzzSeed = Fsrs.seed(a.card.kanji, StudyMode.Draw.name, states[a.card.kanji]?.reps ?: 0))
        states[a.card.kanji] = updated
        val owner = owners[a.card.kanji] ?: Stacks.stateId(stack)
        recordSession {
            app.db.recordKanjiReview(
                updated.toEntity(sourceId, owner, a.card.kanji, StudyMode.Draw),
                ReviewLogEntity(0, sourceId, owner, a.card.kanji, StudyMode.Draw.name, grade.value, now.toEpochMilli(), sessionId = reviewSessionId),
            )
        }
        showNext()
    }

    override fun onCleared() {
        recognizer.close()
        super.onCleared()
    }
}
