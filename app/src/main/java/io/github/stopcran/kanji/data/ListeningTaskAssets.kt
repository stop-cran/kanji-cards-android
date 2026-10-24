package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*

internal class ListeningTaskAssets(private val db: AppDatabase) {
    suspend fun check(source: String, record: ListeningTaskRecord) {
        val media = requireNotNull(record.task.media)
        media.audio.values.forEach { audio(source, it) }
        media.image?.let { image(source, it) }
    }

    suspend fun image(source: String, image: ListeningImage): ByteArray {
        val bytes = requireNotNull(db.listeningTasks().asset(source, image.sha256)) { "Captured task picture is missing" }
        val validEnvelope = bytes.size <= ContentParser.MAX_IMAGE_BYTES && ContentParser.hasWebpEnvelope(bytes)
        require(validEnvelope && ContentParser.rawSha256(bytes) == image.sha256 && SceneImages.problem(bytes) == null) {
            "Captured task picture is invalid"
        }
        return bytes
    }

    suspend fun audio(source: String, audio: TaskAudio): ByteArray {
        val bytes = requireNotNull(db.listeningTasks().asset(source, audio.sha256)) { "Captured task audio is missing" }
        require(ContentParser.rawSha256(bytes) == audio.sha256 &&
            kotlin.math.abs(OpusAudio.durationMs(bytes, 250, 90_000) - audio.durationMs) <= 100) { "Captured task audio is invalid" }
        return bytes
    }
}
