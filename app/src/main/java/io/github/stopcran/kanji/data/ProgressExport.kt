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
            Snapshot(db.reviews().allStates(), db.reviews().allLog(), db.reviews().allGrammarResponses(),
                db.reviews().allSessions(), db.listening().allEncounters())
        }
        ZipOutputStream(out).use { zip ->
            zip.entry("review_state.json", JsonArray(snapshot.states.map(::stateJson)))
            zip.entry("review_log.json", JsonArray(snapshot.log.map(::logJson)))
            zip.entry("grammar_responses.json", JsonArray(snapshot.grammarResponses.map(::grammarResponseJson)))
            zip.entry("study_sessions.json", JsonArray(snapshot.sessions.map(::sessionJson)))
            zip.entry("listening_encounters.json", JsonArray(snapshot.listening.map(::listeningJson)))
            zip.entry("export_manifest.json", JsonObject(mapOf("formatVersion" to JsonPrimitive(5))))
            zip.entry("settings.json", JsonObject(settings.snapshot().mapValues { primitive(it.value) }))
        }
        return snapshot.log.size
    }

    private data class Snapshot(
        val states: List<ReviewStateEntity>,
        val log: List<ReviewLogEntity>,
        val grammarResponses: List<GrammarResponseEntity>,
        val sessions: List<StudySessionEntity>,
        val listening: List<ListeningEncounterEntity>,
    )

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
