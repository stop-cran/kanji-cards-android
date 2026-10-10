package io.github.stopcran.kanji.ui

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A drawn point with its time in milliseconds (ML Kit uses timing). */
data class TimedPt(val x: Float, val y: Float, val t: Long)

enum class ModelState { Unknown, Downloading, Ready, Unavailable }

/** ML Kit Digital Ink (Japanese). Advisory: every failure path returns null so the caller falls back to the stroke matcher. */
class InkRecognizer {
    private val model: DigitalInkRecognitionModel? = DigitalInkRecognitionModelIdentifier.fromLanguageTag("ja")
        ?.let { DigitalInkRecognitionModel.builder(it).build() }
    private val manager = RemoteModelManager.getInstance()
    private var recognizer: DigitalInkRecognizer? = null

    suspend fun prepare(): ModelState {
        val m = model ?: return ModelState.Unavailable
        return try {
            if (await(manager.isModelDownloaded(m)) == true) return ModelState.Ready
            // download() yields Void, so success is "finished within the timeout", not a non-null value
            val finished = withTimeoutOrNull(120_000) { await(manager.download(m, DownloadConditions.Builder().build())); true }
            if (finished == true) ModelState.Ready else ModelState.Unavailable
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ModelState.Unavailable
        }
    }

    /** Candidate texts, best first, or null when recognition is unavailable. */
    suspend fun candidates(strokes: List<List<TimedPt>>): List<String>? {
        val m = model ?: return null
        if (strokes.isEmpty()) return emptyList()
        return try {
            val rec = recognizer ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build()).also { recognizer = it }
            val ink = Ink.builder().apply {
                strokes.forEach { s ->
                    addStroke(Ink.Stroke.builder().apply { s.forEach { addPoint(Ink.Point.create(it.x, it.y, it.t)) } }.build())
                }
            }.build()
            withTimeoutOrNull(10_000) { await(rec.recognize(ink)) }?.candidates?.map { it.text }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }
}

// ML Kit tasks cannot be cancelled; a late result is simply dropped once the coroutine is cancelled.
private suspend fun <T> await(task: Task<T>): T? = suspendCancellableCoroutine { c ->
    task.addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWith(Result.failure(it)) }.addOnCanceledListener { c.resume(null) }
}
