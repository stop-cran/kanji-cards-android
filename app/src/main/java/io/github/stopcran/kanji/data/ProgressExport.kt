package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes the learning progress (review states, the answer log and the settings) into a zip so it can be backed up or inspected off the device. */
object ProgressExport {
    private val json = Json { prettyPrint = false }

    /** Returns the number of logged answers written. */
    suspend fun write(db: AppDatabase, settings: Settings, out: OutputStream): Int {
        val snapshot = db.withTransaction {
            Snapshot(db.reviews().allStates(), db.reviews().allLog(), db.reviews().allTransitionChecks(), db.reviews().allGrammarResponses(),
                db.reviews().allSessions(), db.listening().allEncounters(),
                db.listeningTasks().allEncounters(), db.listeningTasks().allAssessments(), db.listeningTasks().allExposures(),
                db.reading().allQuizzes().map { quiz ->
                    ReadingAttemptSnapshot(quiz, requireNotNull(db.reading().reader(quiz.sourceId, quiz.textId, quiz.captureId)) {
                        "A Reading quiz has lost its captured text"
                    }, db.reading().readerWords(quiz.sourceId, quiz.textId, quiz.captureId))
                }, db.reading().allProgress())
        }
        ZipOutputStream(out).use { zip ->
            zip.entry("review_state.json", JsonArray(snapshot.states.map(::stateJson)))
            zip.entry("review_log.json", JsonArray(snapshot.log.map(::logJson)))
            zip.entry("jlpt_transition_checks.json", JsonArray(snapshot.transitionChecks.map(::transitionCheckJson)))
            zip.entry("grammar_responses.json", JsonArray(snapshot.grammarResponses.map(::grammarResponseJson)))
            zip.entry("study_sessions.json", JsonArray(snapshot.sessions.map(::sessionJson)))
            zip.entry("listening_encounters.json", JsonArray(snapshot.listening.map(::listeningJson)))
            zip.entry("listening_task_encounters.json", JsonArray(snapshot.tasks.map(::taskJson)))
            zip.entry("listening_assessments.json", JsonArray(snapshot.assessments.map(::assessmentJson)))
            zip.entry("listening_exposures.json", JsonArray(snapshot.exposures.map {
                JsonObject(mapOf("sourceId" to primitive(it.sourceId), "exposureId" to primitive(it.exposureId),
                    "firstAtMs" to primitive(it.firstAtMs), "lastAtMs" to primitive(it.lastAtMs)))
            }))
            zip.entry("reading_quizzes.json", JsonArray(snapshot.reading.map(::readingJson)))
            zip.entry("reading_progress.json", JsonArray(snapshot.readingProgress.map {
                JsonObject(mapOf("sourceId" to primitive(it.sourceId), "textId" to primitive(it.textId), "unlockedTier" to primitive(it.unlockedTier)))
            }))
            zip.entry("export_manifest.json", JsonObject(mapOf("formatVersion" to JsonPrimitive(8))))
            zip.entry("settings.json", JsonObject(settings.snapshot().mapValues { primitive(it.value) }))
        }
        return snapshot.log.size
    }

    private data class Snapshot(
        val states: List<ReviewStateEntity>,
        val log: List<ReviewLogEntity>,
        val transitionChecks: List<JlptTransitionCheckEntity>,
        val grammarResponses: List<GrammarResponseEntity>,
        val sessions: List<StudySessionEntity>,
        val listening: List<ListeningEncounterEntity>,
        val tasks: List<ListeningTaskEncounterEntity>,
        val assessments: List<ListeningAssessmentEntity>,
        val exposures: List<ListeningExposureEntity>,
        val reading: List<ReadingAttemptSnapshot>,
        val readingProgress: List<ReadingProgressEntity>,
    )

    private data class ReadingAttemptSnapshot(
        val quiz: ReadingQuizEntity, val reader: ReadingReaderEntity, val words: List<ReadingReaderWordEntity>,
    )

    private fun readingJson(value: ReadingAttemptSnapshot): JsonObject {
        val quiz = value.quiz
        val reader = value.reader
        return JsonObject(mapOf(
            "id" to primitive(quiz.id), "sourceId" to primitive(quiz.sourceId), "textId" to primitive(quiz.textId),
            "captureId" to primitive(quiz.captureId), "branch" to primitive(reader.branch), "revision" to primitive(reader.revision),
            "contentVersion" to primitive(reader.contentVersion), "contentHash" to primitive(reader.contentHash),
            "content" to json.parseToJsonElement(reader.contentJson), "wordBodies" to JsonArray(value.words.map {
                JsonObject(mapOf("word" to primitive(it.word), "reading" to primitive(it.reading),
                    "title" to primitive(it.title), "body" to primitive(it.body)))
            }),
            "plan" to json.parseToJsonElement(quiz.planJson), "answers" to json.parseToJsonElement(quiz.answersJson),
            "result" to (quiz.resultJson?.let(json::parseToJsonElement) ?: JsonNull), "startedAtMs" to primitive(quiz.startedAtMs),
            "submittedAtMs" to primitive(quiz.submittedAtMs), "reviewLogId" to primitive(quiz.reviewLogId),
            "newReservation" to primitive(quiz.newReservation), "closedAtMs" to primitive(quiz.closedAtMs),
        ))
    }

    private fun ZipOutputStream.entry(name: String, element: JsonElement) {
        putNextEntry(ZipEntry(name))
        write(json.encodeToString(JsonElement.serializer(), element).toByteArray())
        closeEntry()
    }

    private fun primitive(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Set<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
        else -> JsonPrimitive(v.toString())
    }

    private fun stateJson(s: ReviewStateEntity) = JsonObject(
        mapOf(
            "sourceId" to JsonPrimitive(s.sourceId), "stack" to JsonPrimitive(s.stack), "kanji" to JsonPrimitive(s.kanji),
            "mode" to JsonPrimitive(s.mode), "reading" to JsonPrimitive(s.reading), "phase" to JsonPrimitive(s.phase),
            "stability" to JsonPrimitive(s.stability), "difficulty" to JsonPrimitive(s.difficulty), "dueMs" to JsonPrimitive(s.dueMs),
            "lastReviewMs" to JsonPrimitive(s.lastReviewMs), "reps" to JsonPrimitive(s.reps), "lapses" to JsonPrimitive(s.lapses),
        ),
    )

    private fun logJson(l: ReviewLogEntity) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(l.id), "sourceId" to JsonPrimitive(l.sourceId),
            "stack" to JsonPrimitive(l.stack), "kanji" to JsonPrimitive(l.kanji),
            "mode" to JsonPrimitive(l.mode), "reading" to JsonPrimitive(l.reading), "grade" to JsonPrimitive(l.grade),
            "atMs" to JsonPrimitive(l.atMs), "sessionId" to JsonPrimitive(l.sessionId),
            "previousReadingStarted" to primitive(l.previousReadingStarted),
        ),
    )

    private fun transitionCheckJson(c: JlptTransitionCheckEntity) = JsonObject(
        mapOf(
            "id" to primitive(c.id), "sourceId" to primitive(c.sourceId), "triggerKanji" to primitive(c.triggerKanji),
            "kanji" to primitive(c.kanji), "mode" to primitive(c.mode), "reading" to primitive(c.reading),
            "dueMs" to primitive(c.dueMs), "originalDueMs" to primitive(c.originalDueMs),
            "completedAtMs" to primitive(c.completedAtMs), "cancelledAtMs" to primitive(c.cancelledAtMs),
        ),
    )

    private fun sessionJson(s: StudySessionEntity) = JsonObject(
        mapOf(
            "id" to JsonPrimitive(s.id), "sourceId" to JsonPrimitive(s.sourceId), "stack" to JsonPrimitive(s.stack),
            "mode" to JsonPrimitive(s.mode), "extra" to JsonPrimitive(s.extra), "startedAtMs" to JsonPrimitive(s.startedAtMs),
            "endedAtMs" to JsonPrimitive(s.endedAtMs), "endReason" to JsonPrimitive(s.endReason),
            "answered" to JsonPrimitive(s.answered), "correct" to JsonPrimitive(s.correct),
        ),
    )

    private fun grammarResponseJson(r: GrammarResponseEntity) = JsonObject(
        mapOf(
            "reviewLogId" to JsonPrimitive(r.reviewLogId), "sourceId" to JsonPrimitive(r.sourceId),
            "stack" to JsonPrimitive(r.stack), "exerciseId" to JsonPrimitive(r.exerciseId),
            "constructionId" to JsonPrimitive(r.constructionId), "relationshipId" to JsonPrimitive(r.relationshipId),
            "promptType" to JsonPrimitive(r.promptType), "response" to JsonPrimitive(r.response), "correct" to JsonPrimitive(r.correct),
            "choiceSnapshot" to (r.choiceSnapshot?.let(json::parseToJsonElement) ?: JsonNull),
        ),
    )

    private fun taskJson(e: ListeningTaskEncounterEntity) = JsonObject(mapOf(
        "id" to primitive(e.id), "sourceId" to primitive(e.sourceId), "taskId" to primitive(e.taskId),
        "exposureId" to primitive(e.exposureId), "branch" to primitive(e.branch), "revision" to primitive(e.revision),
        "contentVersion" to primitive(e.contentVersion), "content" to json.parseToJsonElement(e.contentJson),
        "contentHash" to primitive(e.contentHash), "mediaHash" to primitive(e.mediaHash), "policyVersion" to primitive(e.policyVersion),
        "vocabularyBodies" to json.parseToJsonElement(e.vocabularyJson), "state" to json.parseToJsonElement(e.stateJson),
        "response" to (e.responseJson?.let(json::parseToJsonElement) ?: JsonNull), "kind" to primitive(e.kind),
        "assessmentId" to primitive(e.assessmentId), "createdAtMs" to primitive(e.createdAtMs), "estimatedMs" to primitive(e.estimatedMs),
        "newReservation" to primitive(e.newReservation), "exposedAtMs" to primitive(e.exposedAtMs),
        "firstEncounter" to primitive(e.firstEncounter), "reviewLogId" to primitive(e.reviewLogId),
        "submittedAtMs" to primitive(e.submittedAtMs), "introduced" to primitive(e.introduced),
        "closedAtMs" to primitive(e.closedAtMs), "closeReason" to primitive(e.closeReason),
    ))

    private fun assessmentJson(e: ListeningAssessmentEntity) = JsonObject(mapOf(
        "id" to primitive(e.id), "sourceId" to primitive(e.sourceId), "branch" to primitive(e.branch),
        "revision" to primitive(e.revision), "contentVersion" to primitive(e.contentVersion), "blueprint" to primitive(e.blueprint),
        "policyVersion" to primitive(e.policyVersion), "content" to json.parseToJsonElement(e.contentJson),
        "state" to json.parseToJsonElement(e.stateJson), "createdAtMs" to primitive(e.createdAtMs),
        "closedAtMs" to primitive(e.closedAtMs), "response" to (e.responseJson?.let(json::parseToJsonElement) ?: JsonNull),
    ))

    private fun listeningJson(e: ListeningEncounterEntity) = JsonObject(
        mapOf(
            "id" to primitive(e.id), "sourceId" to primitive(e.sourceId), "passageId" to primitive(e.passageId),
            "content" to json.parseToJsonElement(e.contentJson), "contentHash" to primitive(e.contentHash),
            "audioHash" to primitive(e.audioHash), "state" to json.parseToJsonElement(e.stateJson),
            "response" to (e.responseJson?.let(json::parseToJsonElement) ?: JsonNull),
            "kind" to primitive(e.kind), "createdAtMs" to primitive(e.createdAtMs), "estimatedMs" to primitive(e.estimatedMs),
            "newReservation" to primitive(e.newReservation), "exposedAtMs" to primitive(e.exposedAtMs),
            "lastExposureAtMs" to primitive(e.lastExposureAtMs), "lastStudyAtMs" to primitive(e.lastStudyAtMs),
            "firstEncounter" to primitive(e.firstEncounter), "reviewLogId" to primitive(e.reviewLogId),
            "submittedAtMs" to primitive(e.submittedAtMs), "introduced" to primitive(e.introduced),
            "closedAtMs" to primitive(e.closedAtMs), "closeReason" to primitive(e.closeReason),
        ),
    )
}
