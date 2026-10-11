package io.github.stopcran.kanji.data

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
        val states = db.reviews().allStates()
        val log = db.reviews().allLog()
        ZipOutputStream(out).use { zip ->
            zip.entry("review_state.json", JsonArray(states.map(::stateJson)))
            zip.entry("review_log.json", JsonArray(log.map(::logJson)))
            zip.entry("settings.json", JsonObject(settings.snapshot().mapValues { primitive(it.value) }))
        }
        return log.size
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
            "sourceId" to JsonPrimitive(l.sourceId), "stack" to JsonPrimitive(l.stack), "kanji" to JsonPrimitive(l.kanji),
            "mode" to JsonPrimitive(l.mode), "reading" to JsonPrimitive(l.reading), "grade" to JsonPrimitive(l.grade),
            "atMs" to JsonPrimitive(l.atMs),
        ),
    )
}
