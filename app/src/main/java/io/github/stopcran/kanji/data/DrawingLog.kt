package io.github.stopcran.kanji.data

import android.content.Context
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.MatchResult
import io.github.stopcran.kanji.ui.TimedPt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedIssue(val type: String, val refStroke: Int?, val drawnStroke: Int?)

@Serializable
data class SavedDrawing(
    val kanji: String,
    val savedAt: Long,
    val canvasPx: Float,
    val outcome: String,
    val issues: List<SavedIssue>,
    val candidates: List<String>?,
    /** Strokes as [x, y, t] triples in canvas pixels / ms. */
    val strokes: List<List<List<Float>>>,
)

/** Opt-in local-only log of drawings, used to tune the matcher. Nothing is uploaded. */
object DrawingLog {
    private val json = Json { prettyPrint = false }

    fun dir(context: Context) = File(context.filesDir, "drawings")

    fun save(context: Context, kanji: String, canvasPx: Float, strokes: List<List<TimedPt>>, outcome: DrawOutcome, match: MatchResult, candidates: List<String>?) {
        if (strokes.isEmpty()) return
        val rec = SavedDrawing(
            kanji, System.currentTimeMillis(), canvasPx, outcome.name,
            match.issues.map { SavedIssue(it.type.name, it.refIndex?.plus(1), it.drawnIndex?.plus(1)) },
            candidates?.take(5),
            strokes.map { s -> s.map { listOf(it.x, it.y, it.t.toFloat()) } },
        )
        runCatching {
            val d = dir(context).apply { mkdirs() }
            File(d, "${rec.savedAt}-$kanji.json").writeText(json.encodeToString(rec))
        }
    }

    fun count(context: Context): Int = dir(context).listFiles { f -> f.extension == "json" }?.size ?: 0

    /** Writes every saved drawing into a zip so the data can be kept off the device (the app's private storage is wiped on uninstall). */
    fun exportZip(context: Context, out: java.io.OutputStream): Int {
        var n = 0
        java.util.zip.ZipOutputStream(out).use { zip ->
            dir(context).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { f ->
                zip.putNextEntry(java.util.zip.ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                n++
            }
        }
        return n
    }
}
