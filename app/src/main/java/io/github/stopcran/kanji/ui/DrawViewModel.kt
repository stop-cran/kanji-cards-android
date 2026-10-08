package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.StrokeData
import io.github.stopcran.kanji.core.draw.DrawGrader
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.MatchResult
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.Stroke
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
    ) : DrawUi
    data class Done(val answered: Int, val clean: Int) : DrawUi
}

/** Drawing session: caption + canvas -> check (recogniser gate + stroke matcher) -> graded answer with overlay. */
class DrawViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as KanjiApp
    private val fsrs = Fsrs()
    private val matcher = StrokeMatcher()
    private val recognizer = InkRecognizer()
    private val json = Json { ignoreUnknownKeys = true }

    var ui: DrawUi by mutableStateOf(DrawUi.Loading)
        private set
    var modelState: ModelState by mutableStateOf(ModelState.Unknown)
        private set

    private var sourceId = ""
    private var cards: Map<String, KanjiEntity> = emptyMap()
    private val references = mutableMapOf<String, List<Stroke>>()
    private val states = mutableMapOf<String, SrsState>()
    private val queue = ArrayDeque<String>()
    private var answered = 0
    private var clean = 0
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
        val all = app.db.content().kanji(sourceId)
        for (k in all) {
            val data = k.strokesJson?.let { runCatching { json.decodeFromString(StrokeData.serializer(), it) }.getOrNull() } ?: continue
            references[k.kanji] = data.strokes.map { s -> s.points.map { Pt(it[0], it[1]) } }
        }
        val drawable = all.filter { it.kanji in references }
        if (drawable.isEmpty()) {
            ui = DrawUi.Empty("No cards with stroke data yet. Sync the content first (⚙ settings).")
            return
        }
        cards = drawable.associateBy { it.kanji }
        app.db.reviews().states(sourceId, StudyMode.Draw.name).forEach { states[it.kanji] = it.toSrs() }
        val ids = drawable.map { it.kanji }
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val budget = app.settings.dailyNewCards.value - app.db.reviews().newCardsIntroducedSince(sourceId, StudyMode.Draw.name, startOfDay)
        val items = if (extra) QueueBuilder.extra(ids, states, Instant.now()) else QueueBuilder.build(ids, states, Instant.now(), budget)
        queue.addAll(items.map { it.kanji })
        showNext()
    }

    private fun showNext() {
        val kanji = queue.removeFirstOrNull()
        ui = if (kanji == null) {
            if (answered == 0) DrawUi.Empty("Nothing is due. Come back later or raise the daily new-card limit in ⚙ settings.") else DrawUi.Done(answered, clean)
        } else {
            DrawUi.Question(cards.getValue(kanji), queue.size + 1, answered)
        }
    }

    /** [strokes] are in canvas pixels; an empty list means the user gave up. */
    fun check(strokes: List<List<TimedPt>>, canvasPx: Float) {
        val q = ui as? DrawUi.Question ?: return
        ui = DrawUi.Checking(q.card)
        viewModelScope.launch {
            val ref = references.getValue(q.card.kanji)
            val match = matcher.match(ref, strokes.map { s -> s.map { Pt(it.x.toDouble(), it.y.toDouble()) } })
            val candidates = if (strokes.isEmpty()) emptyList() else recognizer.candidates(strokes)
            val outcome = DrawGrader.outcome(candidates, q.card.kanji, match)
            ui = DrawUi.Answer(q.card, q.remaining, ref, strokes, canvasPx, outcome, match, candidates != null)
        }
    }

    /** Not recognised = Again (asked again soon), recognised with stroke mistakes = Hard, clean = Good. */
    fun next() {
        val a = ui as? DrawUi.Answer ?: return
        val grade = when (a.outcome) {
            DrawOutcome.NotRecognized -> Grade.Again
            DrawOutcome.Mistakes -> Grade.Hard
            DrawOutcome.Clean -> Grade.Good
        }
        val now = Instant.now()
        val updated = fsrs.review(states[a.card.kanji] ?: SrsState(), grade, now)
        states[a.card.kanji] = updated
        answered++
        if (a.outcome == DrawOutcome.Clean) clean++
        if (grade == Grade.Again) queue.add(minOf(3, queue.size), a.card.kanji)
        viewModelScope.launch {
            app.db.reviews().record(
                updated.toEntity(sourceId, a.card.kanji, StudyMode.Draw),
                ReviewLogEntity(0, sourceId, a.card.kanji, StudyMode.Draw.name, grade.value, now.toEpochMilli()),
            )
        }
        showNext()
    }

    override fun onCleared() {
        recognizer.close()
    }
}
