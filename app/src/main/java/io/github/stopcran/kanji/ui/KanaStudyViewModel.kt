package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.StrokeData
import io.github.stopcran.kanji.core.draw.DrawGrader
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.DrawGate
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.Stroke
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import io.github.stopcran.kanji.core.kana.KANA_SOURCE_ID
import io.github.stopcran.kanji.core.kana.KanaCard
import io.github.stopcran.kanji.core.kana.KanaCatalog
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.practiceMinimum
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant

sealed interface KanaStudyUi {
    data object Loading : KanaStudyUi
    data class Empty(val message: String) : KanaStudyUi
    data class Done(val answered: Int, val correct: Int) : KanaStudyUi
    data class QuizQuestion(val card: KanaCard, val options: List<String>, val remaining: Int) : KanaStudyUi
    data class QuizAnswer(val card: KanaCard, val options: List<String>, val picked: String, val remaining: Int) : KanaStudyUi
    data class TypeQuestion(val card: KanaCard, val remaining: Int) : KanaStudyUi
    data class TypeAnswer(val card: KanaCard, val typed: String, val correct: Boolean, val remaining: Int) : KanaStudyUi
    data class DrawQuestion(val card: KanaCard, val remaining: Int, val trace: Boolean) : KanaStudyUi
    data class DrawChecking(val card: KanaCard) : KanaStudyUi
    data class DrawAnswer(
        val card: KanaCard,
        val reference: List<Stroke>,
        val drawn: List<List<TimedPt>>,
        val canvasPx: Float,
        val outcome: DrawOutcome,
        val match: io.github.stopcran.kanji.core.draw.MatchResult,
        val recognizerUsed: Boolean,
        val remaining: Int,
        val trace: Boolean,
    ) : KanaStudyUi
}

class KanaStudyViewModel(private val app: Services) : StudyViewModel(app) {
    private val fsrs = Fsrs()
    private val matcher = StrokeMatcher()
    private val recognizer = InkRecognizer()
    private val references = mutableMapOf<String, List<Stroke>>()
    private val states = mutableMapOf<String, SrsState>()
    private var open: Set<String> = emptySet()
    private val traced = mutableSetOf<String>()
    private var script = KanaScript.Hiragana
    private var mode = KanaMode.QuizKanaToRomaji
    private var session = QuizSession<String>(emptyList(), { it }, confirm = false)
    private var started = false

    var ui: KanaStudyUi by mutableStateOf(KanaStudyUi.Loading)
        private set
    var draft: String by mutableStateOf("")
        private set
    var modelState: ModelState by mutableStateOf(ModelState.Unknown)
        private set
    var look: BrushLook by mutableStateOf(BrushLook.random())
        private set
    val ink = androidx.compose.runtime.mutableStateListOf<List<TimedPt>>()
    var inkSize = 1f
        private set

    fun ensureStarted(script: KanaScript, mode: KanaMode, extra: Boolean) {
        if (started) return
        started = true
        this.script = script
        this.mode = mode
        if (mode == KanaMode.Draw) {
            modelState = ModelState.Downloading
            viewModelScope.launch { modelState = recognizer.prepare() }
        }
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        val cards = KanaCatalog.all(script)
        val now = Instant.now()
        app.db.reviews().states(KANA_SOURCE_ID, script.id, mode.name).forEach { states[it.kanji] = it.toSrs() }
        if (mode == KanaMode.Draw) loadReferences(cards)
        val allowance = app.db.newAllowance(app.settings, KANA_SOURCE_ID, now).remaining
        open = KanaCatalog.unlocked(script, states).mapTo(HashSet()) { it.kana }
        val candidates = cards.map { it.kana }.filter { it in open }
        val ids = if (extra) {
            QueueBuilder.extra(candidates, states, now, app.settings.practiceMinimum()).map { it.kanji }
        } else {
            QueueBuilder.build(candidates, states, now, allowance).map { it.kanji }
        }
        if (ids.isEmpty()) {
            ui = KanaStudyUi.Empty(if (extra) "No kana available to practise." else "Nothing is due. Come back later or choose practice more.")
            return
        }
        session = trackSession(QuizSession(ids, { it }, confirm = false), KANA_SOURCE_ID, script.id, mode.name, extra)
        showNext(cards.associateBy { it.kana })
    }

    private suspend fun loadReferences(cards: List<KanaCard>) {
        val data = withContext(Dispatchers.IO) {
            app.context.assets.open("kana-strokes.json").bufferedReader().use {
                Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, StrokeData>>(it.readText())
            }
        }
        val chars = cards.flatMap { it.kana.map(Char::toString) }.distinct()
        chars.forEach { char ->
            val raw = data[char]?.strokes.orEmpty().map { stroke -> stroke.points.map { Pt(it[0], it[1]) } }
            if (raw.isNotEmpty()) references[char] = raw
        }
    }

    fun reference(card: KanaCard): List<Stroke> = composeReference(card.kana)

    private fun composeReference(kana: String): List<Stroke> {
        val glyphs = kana.map(Char::toString)
        val cells = glyphs.mapIndexed { index, glyph ->
            val source = references[glyph] ?: return emptyList()
            val points = source.flatten()
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            val minY = points.minOf { it.y }
            val maxY = points.maxOf { it.y }
            val smallSecond = glyphs.size == 2 && index == 1 && glyph in SMALL_YOON
            val width = if (glyphs.size == 1) 90.0 else if (smallSecond) 37.0 else 67.0
            val height = if (glyphs.size == 1) 90.0 else if (smallSecond) 42.0 else 79.0
            val scale = minOf(width / (maxX - minX).coerceAtLeast(1.0), height / (maxY - minY).coerceAtLeast(1.0))
            val xOrigin = when {
                glyphs.size == 1 -> 54.5 - (minX + maxX) * scale / 2
                smallSecond -> 109.0 - maxX * scale - 3.0
                else -> 2.0 - minX * scale
            }
            val yOrigin = when {
                glyphs.size == 1 -> 54.5 - (minY + maxY) * scale / 2
                smallSecond -> 109.0 - maxY * scale - 5.0
                else -> 54.5 - (minY + maxY) * scale / 2
            }
            source.map { stroke -> stroke.map { Pt(it.x * scale + xOrigin, it.y * scale + yOrigin) } }
        }
        return cells.flatten()
    }

    private fun showNext(cards: Map<String, KanaCard>) {
        ink.clear()
        draft = ""
        val id = session.take()
        if (id == null) {
            finishSession()
            ui = if (session.answered == 0) KanaStudyUi.Empty("Nothing is due.") else KanaStudyUi.Done(session.answered, session.correct)
            return
        }
        val card = cards.getValue(id)
        val remaining = session.pending + 1
        ui = when (mode) {
            KanaMode.QuizKanaToRomaji -> KanaStudyUi.QuizQuestion(card, options(card, cards.values, KanaMode.QuizKanaToRomaji), remaining)
            KanaMode.QuizRomajiToKana -> KanaStudyUi.QuizQuestion(card, options(card, cards.values, KanaMode.QuizRomajiToKana), remaining)
            KanaMode.Draw -> {
                val trace = DrawGate.shouldTrace(true, states[id], traced, id)
                if (trace) traced += id
                KanaStudyUi.DrawQuestion(card, remaining, trace)
            }
            KanaMode.TypeKanaToRomaji, KanaMode.TypeRomajiToKana -> KanaStudyUi.TypeQuestion(card, remaining)
        }
    }

    private fun options(card: KanaCard, all: Collection<KanaCard>, mode: KanaMode): List<String> {
        val answer = if (mode == KanaMode.QuizKanaToRomaji) card.romaji else card.kana
        val distractors = all.filter { it.kana in open }.map { if (mode == KanaMode.QuizKanaToRomaji) it.romaji else it.kana }
            .filter { it != answer }.distinct().shuffled().take(3)
        return (distractors + answer).shuffled()
    }

    fun pick(value: String) {
        val question = ui as? KanaStudyUi.QuizQuestion ?: return
        ui = KanaStudyUi.QuizAnswer(question.card, question.options, value, question.remaining)
    }

    fun onDraft(text: String) {
        if (ui is KanaStudyUi.TypeQuestion) draft = text
    }

    fun submit(giveUp: Boolean = false) {
        val question = ui as? KanaStudyUi.TypeQuestion ?: return
        val typed = if (giveUp) "" else draft.trim()
        if (!giveUp && typed.isBlank()) return
        val expected = when (mode) {
            KanaMode.TypeKanaToRomaji -> question.card.romaji
            KanaMode.TypeRomajiToKana -> question.card.kana
            else -> return
        }
        val acceptsAlternate = mode == KanaMode.TypeKanaToRomaji &&
            question.card.alternateRomaji.any { typed.equals(it, ignoreCase = true) }
        val matches = typed.equals(expected, ignoreCase = true) ||
            acceptsAlternate
        ui = KanaStudyUi.TypeAnswer(question.card, typed, matches, question.remaining)
    }

    fun padSized(px: Float) {
        if (px <= 0f) return
        if (ink.isNotEmpty() && inkSize > 1f && kotlin.math.abs(px - inkSize) > 0.5f) {
            val ratio = px / inkSize
            for (index in ink.indices) ink[index] = ink[index].map { TimedPt(it.x * ratio, it.y * ratio, it.t) }
        }
        inkSize = px
    }

    fun checkDrawing(strokes: List<List<TimedPt>>, canvasPx: Float) {
        val question = ui as? KanaStudyUi.DrawQuestion ?: return
        ui = KanaStudyUi.DrawChecking(question.card)
        viewModelScope.launch {
            val reference = reference(question.card)
            val points = strokes.map { stroke -> stroke.map { Pt(it.x.toDouble(), it.y.toDouble()) } }
            val match = matcher.match(reference, points)
            val candidates = if (strokes.isEmpty()) emptyList() else recognizer.candidates(strokes)
            val outcome = DrawGrader.outcome(candidates, question.card.kana, match)
            ui = KanaStudyUi.DrawAnswer(
                question.card,
                reference,
                strokes,
                canvasPx,
                outcome,
                match,
                candidates != null,
                question.remaining,
                question.trace,
            )
        }
    }

    fun next(guessed: Boolean = false) {
        val correct: Boolean
        val weak: Boolean
        val id: String
        when (val current = ui) {
            is KanaStudyUi.QuizAnswer -> {
                id = current.card.kana
                correct = current.picked == if (mode == KanaMode.QuizKanaToRomaji) current.card.romaji else current.card.kana
                weak = guessed
            }
            is KanaStudyUi.TypeAnswer -> {
                id = current.card.kana
                correct = current.correct
                weak = false
            }
            is KanaStudyUi.DrawAnswer -> {
                id = current.card.kana
                if (current.trace) {
                    session.defer(id, 3)
                    showNext(KanaCatalog.all(script).associateBy { it.kana })
                    return
                }
                correct = current.outcome != DrawOutcome.NotRecognized
                weak = current.outcome == DrawOutcome.Mistakes
            }
            else -> return
        }
        record(id, correct, weak, countsAsCorrect = correct && !weak)
        showNext(KanaCatalog.all(script).associateBy { it.kana })
    }

    private fun record(id: String, correct: Boolean, weak: Boolean, countsAsCorrect: Boolean) {
        val result = session.answer(id, correct, weak = weak, countsAsCorrect = countsAsCorrect)
        if (!result.record) return
        val now = Instant.now()
        val state = fsrs.review(
            states[id] ?: SrsState(),
            result.grade,
            now,
            fuzzSeed = Fsrs.seed(id, mode.name, states[id]?.reps ?: 0),
        )
        states[id] = state
        val entry = ReviewStateEntity(
            KANA_SOURCE_ID,
            script.id,
            id,
            mode.name,
            state.phase.name,
            state.stability,
            state.difficulty,
            state.due.toEpochMilli(),
            state.lastReview?.toEpochMilli(),
            state.reps,
            state.lapses,
        )
        recordSession {
            app.db.reviews().record(
                entry,
                ReviewLogEntity(0, KANA_SOURCE_ID, script.id, id, mode.name, result.grade.value, now.toEpochMilli(), sessionId = reviewSessionId),
            )
        }
    }

    override fun onCleared() {
        recognizer.close()
        super.onCleared()
    }

    private companion object {
        const val SMALL_YOON = "ゃゅょャュョ"
    }
}
