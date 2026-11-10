package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Confusion
import io.github.stopcran.kanji.core.srs.FontPolicy
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.PictureQuestion
import io.github.stopcran.kanji.core.words.PictureQuestions
import io.github.stopcran.kanji.core.words.PictureScene
import io.github.stopcran.kanji.core.words.PracticeSuggestion
import io.github.stopcran.kanji.core.words.TypedAnswer
import io.github.stopcran.kanji.core.words.TypedResult
import io.github.stopcran.kanji.core.words.TypedVerdict
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordOption
import io.github.stopcran.kanji.core.words.WordKanjiHelp
import io.github.stopcran.kanji.core.words.WordQuizBuilder
import io.github.stopcran.kanji.core.words.WordQueues
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.core.srs.ReviewIdentity
import io.github.stopcran.kanji.core.srs.ReviewRecording
import io.github.stopcran.kanji.data.ReviewStore
import io.github.stopcran.kanji.data.SceneImageResult
import io.github.stopcran.kanji.data.sceneImage
import io.github.stopcran.kanji.data.toPictureScene
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.forDirection
import io.github.stopcran.kanji.data.inWordStack
import io.github.stopcran.kanji.data.offeredStacks
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
import kotlin.random.Random

data class WordPictureUi(val question: PictureQuestion, val images: Map<String, ImageBitmap>)

sealed interface WordQuizUi {
    data object Loading : WordQuizUi
    data class Empty(val messageId: Int) : WordQuizUi
    data class Question(
        val word: WordEntity, val direction: WordDirection, val options: List<WordOption>, val remaining: Int, val font: KanjiFont,
        val picture: WordPictureUi? = null,
        val kanjiHelp: Map<String, WordKanjiHelp> = emptyMap(),
    ) : WordQuizUi
    data class Answer(
        val word: WordEntity, val direction: WordDirection, val options: List<WordOption>, val picked: String, val remaining: Int, val font: KanjiFont, val heard: String? = null,
        val pickedWord: WordEntity? = null,
        val picture: WordPictureUi? = null,
        val kanjiHelp: Map<String, WordKanjiHelp> = emptyMap(),
    ) : WordQuizUi {
        val key: String get() = if (direction == WordDirection.Reading) word.reading else word.word
        val correct: Boolean get() = picked == key
    }
    data class Done(val answered: Int, val correct: Int, val suggestion: WordDirection? = null, val canContinue: Boolean = false) : WordQuizUi

    /** Typed mode: the English prompt and a free-text answer; the draft text lives in the view model. */
    data class TypeQuestion(val word: WordEntity, val remaining: Int, val font: KanjiFont) : WordQuizUi
    data class TypeAnswer(
        val word: WordEntity, val typed: String, val result: TypedResult, val remaining: Int, val font: KanjiFont, val gaveUp: Boolean,
    ) : WordQuizUi
}

class WordReadingTip(val question: WordQuizUi.Question, val help: WordKanjiHelp)

/** Word quiz session in one direction; only that direction's scheduling state is updated. */
class WordQuizViewModel(
    private val app: Services,
    private val recording: ReviewRecording = ReviewRecording(),
    private val reviews: ReviewStore = app.reviewStore,
    private val loadPicture: suspend (String, PictureScene) -> SceneImageResult = { source, scene ->
        app.db.content().sceneImage(source, scene.scene.image, scene.imageHash)
    },
) : StudyViewModel(app.sessionStore) {
    private val random = Random.Default

    var ui: WordQuizUi by mutableStateOf(WordQuizUi.Loading)
        private set

    var pictureNoticeId: Int? by mutableStateOf(null)
        private set
    var contentLanguage by mutableStateOf("en")
        private set

    var readingTip: WordReadingTip? by mutableStateOf(null)
        private set
    var voiceGeneration: Int by mutableStateOf(0)
        private set

    private var direction = WordDirection.JpToEn
    private var sourceId = ""
    private var stackId = WordStacks.n5.id
    private var wordList: List<WordEntity> = emptyList()
    private var words: Map<String, WordEntity> = emptyMap()
    private var cards: List<WordCard> = emptyList()
    private var kanjiHelp: Map<String, WordKanjiHelp> = emptyMap()
    private var scenes: List<PictureScene> = emptyList()
    private val unavailableImages = mutableSetOf<String>()
    private val selectedScenes = mutableMapOf<String, String>()
    private var lastPictureWord: String? = null
    private val states = mutableMapOf<String, SrsState>()
    private var session = QuizSession<String>(emptyList(), { it })
    private var started = false
    private var extra = false
    private var lastFont: KanjiFont? = null

    fun ensureStarted(direction: WordDirection, extra: Boolean) {
        if (started) return
        started = true
        this.direction = direction
        this.extra = extra
        viewModelScope.launch { start(extra) }
    }

    private suspend fun start(extra: Boolean) {
        val source = app.settings.source.value
        val selectedStack = app.settings.wordStack.value
        val n4Unlocked = app.settings.n4Unlocked.value
        sourceId = source.id
        contentLanguage = app.settings.sync.baseLanguage(sourceId) ?: app.db.content().meta(sourceId)?.baseLanguage ?: "en"
        val (allWords, storedScenes, levels) = app.db.withTransaction {
            Triple(
                app.db.content().words(sourceId),
                if (direction.usesPictures) app.db.content().wordScenes(sourceId) else emptyList(),
                app.db.content().kanji(sourceId).levels(),
            )
        }
        scenes = storedScenes.map { it.toPictureScene() }
        val offered = allWords.offeredStacks(n4Unlocked)
        val stack = WordStacks.find(selectedStack, offered)
        stackId = stack.id
        val all = allWords.inWordStack(stack, levels)
        kanjiHelp = allWords.mapNotNull { word ->
            WordKanjiHelp.build(word.word, word.reading, stack, levels)?.let { word.word to it }
        }.toMap()
        if (all.size < 2) {
            ui = WordQuizUi.Empty(R.string.word_quiz_no_words)
            return
        }
        wordList = all
        words = all.associateBy { it.word }
        cards = all.forDirection(direction).map { it.toCard() }
        app.db.reviews().states(sourceId, stackId, direction.mode.name).forEach { states[it.kanji] = it.toSrs() }
        val other = app.db.reviews().states(sourceId, stackId, direction.gate.mode.name).associate { it.kanji to it.toSrs() }

        val budget = app.db.newAllowance(app.settings, sourceId, Instant.now()).remaining
        val ids = all.forDirection(direction).map { it.word }
        val minimum = app.settings.practiceMinimum()
        val introduced = app.db.reviews().introducedItems(sourceId, stackId).toSet()
        val base = WordQueues.build(direction, ids, states, other, Instant.now(), budget, extra,
            limit = if (extra) minimum else Int.MAX_VALUE, introduced = introduced).map { it.kanji }
        val items = if (extra) base + pad(base, minimum - base.size, ids, all) else base
        session = trackSession(QuizSession(items, { it }), sourceId, stackId, direction.mode.name, extra)
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
        readingTip = null
        voiceGeneration++
        val word = session.take()
        if (word == null) {
            finish()
            return
        }
        val entity = words.getValue(word)
        if (direction == WordDirection.Type) {
            draft = ""
            ui = WordQuizUi.TypeQuestion(entity, session.pending + 1, nextFont(word))
            return
        }
        if (direction.usesPictures) {
            ui = WordQuizUi.Loading
            viewModelScope.launch { showPicture(word) }
            return
        }
        val options = WordQuizBuilder.options(entity.toCard(), cards, direction, random)
        ui = WordQuizUi.Question(entity, direction, options, session.pending + 1, nextFont(word),
            kanjiHelp = questionHelp(word, options))
    }

    private fun questionHelp(word: String, options: List<WordOption>): Map<String, WordKanjiHelp> {
        val ids = when (direction) {
            WordDirection.JpToEn, WordDirection.WordToPicture -> listOf(word)
            WordDirection.EnToJp, WordDirection.Picture -> options.map { it.word }
            WordDirection.Reading, WordDirection.Type -> emptyList()
        }
        return ids.mapNotNull { id -> kanjiHelp[id]?.let { id to it } }.toMap()
    }

    private fun finish() {
        finishSession()
        ui = if (session.answered == 0) {
            WordQuizUi.Empty(
                when (direction) {
                    WordDirection.EnToJp -> R.string.word_quiz_nothing_due_en_to_jp
                    WordDirection.Reading -> R.string.word_quiz_nothing_due_reading
                    WordDirection.JpToEn -> R.string.word_quiz_nothing_due_jp_to_en
                    WordDirection.Type -> R.string.word_quiz_nothing_due_type
                    WordDirection.Picture, WordDirection.WordToPicture -> R.string.word_quiz_nothing_due_picture
                },
            )
        } else WordQuizUi.Done(session.answered, session.correct)
        if (ui is WordQuizUi.Done) suggest()
    }

    private suspend fun showPicture(first: String) {
        var word: String? = first
        while (word != null) {
            val entity = words.getValue(word)
            val picture = if (word == lastPictureWord) {
                if (session.pending > 0) session.defer(word, session.pending)
                null
            } else preparePicture(entity)
            if (picture != null) {
                selectedScenes[word] = picture.question.target.scene.id
                lastPictureWord = word
                ui = WordQuizUi.Question(entity, direction, picture.question.options, session.pending + 1, nextFont(word), picture,
                    questionHelp(word, picture.question.options))
                return
            }
            if (pictureNoticeId == null) pictureNoticeId = R.string.picture_questions_skipped
            word = session.take()
        }
        finish()
    }

    private suspend fun preparePicture(word: WordEntity): WordPictureUi? {
        while (true) {
            val available = scenes.filter { it.scene.image !in unavailableImages }
            val question = PictureQuestions.build(word.toCard(), cards, available, random, selectedScenes[word.word]) ?: return null
            val decoded = loadPictures(question)
            if (decoded != null) return WordPictureUi(question, decoded)
        }
    }

    private suspend fun loadPictures(question: PictureQuestion): Map<String, ImageBitmap>? {
        val decoded = mutableMapOf<String, android.graphics.Bitmap>()
        var delivered = false
        try {
            for (scene in question.requiredScenes(direction)) {
                when (val result = loadPicture(sourceId, scene)) {
                    is SceneImageResult.Ready -> decoded[scene.scene.image] = result.bitmap
                    is SceneImageResult.Unavailable -> {
                        unavailableImages += scene.scene.image
                        pictureNoticeId = R.string.picture_unavailable
                        return null
                    }
                }
            }
            val bitmaps = decoded.mapValues { it.value.asImageBitmap() }
            delivered = true
            return bitmaps
        } finally {
            if (!delivered) decoded.values.forEach { it.recycle() }
        }
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
        val stack = owners[word] ?: stackId
        val record = recording.confirm(
            ReviewIdentity(sourceId, stack, word, direction.mode, reviewSessionId), states, grade, firstSuccessCapDays,
        )
        recordSession { reviews.record(record) }
    }

    fun openReadingTip(origin: WordQuizUi.Question) {
        if (ui !== origin || origin.direction !in setOf(WordDirection.JpToEn, WordDirection.WordToPicture)) return
        val help = origin.kanjiHelp[origin.word.word] ?: return
        voiceGeneration++
        readingTip = WordReadingTip(origin, help)
    }

    fun closeReadingTip(origin: WordReadingTip) {
        if (readingTip === origin) readingTip = null
    }

    fun pick(
        word: String, heard: String? = null, origin: WordQuizUi.Question? = ui as? WordQuizUi.Question,
        generation: Int = voiceGeneration,
    ) {
        val q = origin ?: return
        if (ui !== q || readingTip != null) return
        if (heard != null && generation != voiceGeneration) return
        ui = WordQuizUi.Answer(q.word, q.direction, q.options, word, q.remaining, q.font, heard, words[word], q.picture, q.kanjiHelp)
    }

    /** Takes back a voice pick that was misheard; nothing has been recorded before [next]. */
    fun retry(origin: WordQuizUi.Answer? = ui as? WordQuizUi.Answer) {
        val a = origin ?: return
        if (ui !== a) return
        if (a.heard != null) ui = WordQuizUi.Question(a.word, a.direction, a.options, a.remaining, a.font, a.picture, a.kanjiHelp)
    }

    /** At the end of a session, offers "Practice more" in a better-stocked direction when this one would give only a few cards. */
    private fun suggest() {
        val done = ui as? WordQuizUi.Done ?: return
        viewModelScope.launch {
            awaitSessionWrites()
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
            val next = if (extra) emptyList() else WordQueues.build(
                direction, wordList.forDirection(direction).map { it.word }, stored.getValue(direction),
                stored.getValue(direction.gate), now, app.db.newAllowance(app.settings, sourceId, now).remaining,
                introduced = app.db.reviews().introducedItems(sourceId, stackId).toSet(),
            )
            if (ui === done) ui = done.copy(suggestion = alternative, canContinue = next.isNotEmpty())
        }
    }

    /** Wrong answers (and "don't know") are graded Again and relearned in-session; right ones Good, or Hard when guessed. */
    fun next(guessed: Boolean = false, origin: WordQuizUi = ui) {
        if (ui !== origin) return
        (ui as? WordQuizUi.TypeAnswer)?.let { return nextTyped(it) }
        val a = ui as? WordQuizUi.Answer ?: return
        val result = session.answer(a.word.word, a.correct, guessed)
        if (result.record) record(a.word.word, result.grade, Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS)
        if (!a.correct && !extra) a.pickedWord?.let { nudge(it.word) }
        showNext()
    }

    /** The mis-picked word is brought forward without touching its score; see [Confusion]. */
    private fun nudge(word: String) {
        val updated = Confusion.nudged(states[word], Instant.now()) ?: return
        states[word] = updated
        val mode = direction.mode
        val stack = owners[word] ?: stackId
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) { app.db.reviews().putState(updated.toEntity(sourceId, stack, word, mode)) }
    }
}
