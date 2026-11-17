package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import io.github.stopcran.kanji.core.readingtext.ReadingBookMode
import io.github.stopcran.kanji.core.readingtext.ReadingQuiz
import io.github.stopcran.kanji.core.readingtext.ReadingQuizAnswer
import io.github.stopcran.kanji.core.readingtext.ReadingQuizPlan
import io.github.stopcran.kanji.core.readingtext.ReadingQuizResult
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

const val READING_TEXT_STACK = "reading-texts"

fun ReadingBookMode.studyMode() = when (this) {
    ReadingBookMode.OpenBook -> StudyMode.ReadingOpenBook
    ReadingBookMode.ClosedBook -> StudyMode.ReadingClosedBook
}

data class CapturedReadingQuiz(
    val saved: ReadingQuizEntity,
    val reader: ReadingReader,
    val plan: ReadingQuizPlan,
    val answers: List<ReadingQuizAnswer>,
    val result: ReadingQuizResult?,
)

class ReadingQuizStore(
    private val db: AppDatabase,
    private val readers: TextReadingRepository,
    private val settings: Settings,
    private val clock: () -> Instant = Instant::now,
) {
    private val answerSerializer = ListSerializer(ReadingQuizAnswer.serializer())

    suspend fun create(reader: ReadingReader, mode: ReadingBookMode): CapturedReadingQuiz = durable {
        val saved = reader.saved
        val current = readers.open(saved.sourceId, saved.textId)
        require(current.saved.captureId == saved.captureId) { "Reading text changed; reopen it before starting a quiz" }
        require(current.saved.completedAtMs != null) { "Finish reading the text before starting its quiz" }
        require(db.reading().openQuiz(saved.sourceId, saved.textId) == null) { "Resume or discard the unfinished Reading quiz first" }
        val now = clock()
        val reserve = !db.reading().introduced(saved.sourceId, saved.textId)
        require(!reserve || db.newAllowance(settings, saved.sourceId, now).remaining > 0) { "No shared new-item allowance remains for this text" }
        val tier = db.reading().progress(saved.sourceId, saved.textId)?.unlockedTier ?: 1
        val plan = ReadingQuiz.capture(current.content, saved.contentHash, tier, mode)
        val row = ReadingQuizEntity(
            UUID.randomUUID().toString(), saved.sourceId, saved.textId, saved.captureId,
            Json.encodeToString(ReadingQuizPlan.serializer(), plan), "[]", now.toEpochMilli(), newReservation = reserve,
        )
        db.reading().insertQuiz(row)
        val prior = db.reviews().states(saved.sourceId, READING_TEXT_STACK, mode.studyMode().name).singleOrNull { it.kanji == saved.textId }
        db.reviews().insertSession(StudySessionEntity(row.id, saved.sourceId, READING_TEXT_STACK, mode.studyMode().name,
            prior?.let { it.phase != "New" && it.dueMs > now.toEpochMilli() } == true, now.toEpochMilli()))
        CapturedReadingQuiz(row, current, plan, emptyList(), null)
    }

    suspend fun load(id: String): CapturedReadingQuiz = db.withTransaction { capture(id) }

    suspend fun pictures(quiz: CapturedReadingQuiz): Map<String, SceneImageResult> = withContext(Dispatchers.IO) {
        quiz.plan.questions.mapNotNull { it.question.image }.distinctBy { it.path }.associate { picture ->
            val saved = quiz.saved
            val row = db.reading().readerImage(saved.sourceId, saved.textId, saved.captureId, picture.path)
            picture.path to if (row == null || row.sha256 != picture.sha256 || ContentParser.rawSha256(row.bytes) != picture.sha256) {
                SceneImageResult.Unavailable("Captured Reading picture is missing or changed.")
            } else SceneImages.decode(row.bytes)
        }
    }

    suspend fun answer(id: String, questionId: String, optionId: String?): CapturedReadingQuiz = durable {
        val current = capture(id)
        require(current.saved.submittedAtMs == null && current.saved.closedAtMs == null) { "Reading quiz is no longer accepting answers" }
        val answers = ReadingQuiz.answer(current.plan, current.answers, questionId, optionId)
        val encoded = Json.encodeToString(answerSerializer, answers)
        check(db.reading().saveQuizAnswers(id, current.saved.answersJson, encoded) == 1) { "Reading quiz changed before the answer was saved" }
        current.copy(saved = current.saved.copy(answersJson = encoded), answers = answers)
    }

    suspend fun submit(id: String): CapturedReadingQuiz = durable {
        val current = capture(id)
        if (current.result != null) return@durable current
        require(current.saved.closedAtMs == null) { "A discarded Reading quiz cannot earn progress" }
        val result = ReadingQuiz.finish(current.plan, current.answers)
        val row = current.saved
        val mode = current.plan.mode.studyMode()
        val now = clock()
        val previous = db.reviews().states(row.sourceId, READING_TEXT_STACK, mode.name)
            .singleOrNull { it.kanji == row.textId }?.toSrs() ?: SrsState()
        val grade = if (result.passed) Grade.Good else Grade.Again
        val next = Fsrs().review(previous, grade, now, firstSuccessCapDays = Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS,
            fuzzSeed = Fsrs.seed(row.textId, mode.name, previous.reps))
        db.reviews().putState(next.toEntity(row.sourceId, READING_TEXT_STACK, row.textId, mode))
        val logId = db.reviews().insertLog(ReviewLogEntity(
            sourceId = row.sourceId, stack = READING_TEXT_STACK, kanji = row.textId, mode = mode.name,
            grade = grade.value, atMs = now.toEpochMilli(), sessionId = row.id,
        ))
        val priorTier = db.reading().progress(row.sourceId, row.textId)?.unlockedTier ?: 1
        db.reading().putProgress(ReadingProgressEntity(row.sourceId, row.textId, maxOf(priorTier, result.unlockedTier)))
        val encoded = Json.encodeToString(ReadingQuizResult.serializer(), result)
        check(db.reading().submitQuiz(id, now.toEpochMilli(), encoded, logId) == 1) { "Reading quiz changed before submission" }
        check(db.reviews().endSession(id, now.toEpochMilli(), "completed", result.answers.size, result.answers.count { it.correct }) == 1) {
            "Could not close the submitted Reading session"
        }
        current.copy(saved = row.copy(submittedAtMs = now.toEpochMilli(), resultJson = encoded, reviewLogId = logId, newReservation = false),
            result = result)
    }

    suspend fun close(id: String) = durable {
        val quiz = requireNotNull(db.reading().quiz(id)) { "Captured Reading quiz is unavailable" }
        val now = clock().toEpochMilli()
        if (db.reading().closeQuiz(id, now) == 1 && quiz.resultJson == null) {
            val answered = Json.decodeFromString(answerSerializer, quiz.answersJson).size
            check(db.reviews().endSession(id, now, "left", answered, 0) == 1) { "Could not close the discarded Reading session" }
        }
        Unit
    }

    private suspend fun capture(id: String): CapturedReadingQuiz {
        val row = requireNotNull(db.reading().quiz(id)) { "Captured Reading quiz is unavailable" }
        val reader = readers.open(row.sourceId, row.textId, row.captureId)
        val plan = Json.decodeFromString(ReadingQuizPlan.serializer(), row.planJson)
        ReadingQuiz.validate(plan, reader.content, reader.saved.contentHash)
        val answers = Json.decodeFromString(answerSerializer, row.answersJson)
        var checked = emptyList<ReadingQuizAnswer>()
        answers.forEach { checked = ReadingQuiz.answer(plan, checked, it.questionId, it.optionId) }
        val result = row.resultJson?.let { Json.decodeFromString(ReadingQuizResult.serializer(), it) }
        require((row.submittedAtMs != null) == (result != null) && (row.reviewLogId != null) == (result != null)) {
            "Incomplete Reading quiz submission"
        }
        require(result == null || result == ReadingQuiz.finish(plan, answers)) { "Captured Reading result changed" }
        return CapturedReadingQuiz(row, reader, plan, answers, result)
    }

    private suspend fun <T> durable(block: suspend () -> T): T = withContext(NonCancellable) {
        db.withTransaction { block() }
    }
}
