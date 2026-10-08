package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.ContentException
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.content.SafeZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface SyncResult {
    data class Updated(val kanji: Int, val words: Int, val problems: List<String>) : SyncResult
    data object UpToDate : SyncResult
    data class Failed(val message: String, val retryable: Boolean) : SyncResult
}

class ContentSync(private val db: AppDatabase) {
    private val json = Json

    suspend fun sync(source: RepoSource, force: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        try {
            val files = SafeZip.read(download(source.zipUrl).inputStream())
            val parsed = ContentParser.parse(files)
            val dao = db.content()
            if (!force && dao.meta(source.id)?.contentVersion == parsed.manifest.contentVersion) {
                return@withContext SyncResult.UpToDate
            }
            if (parsed.kanji.isEmpty() && parsed.problems.isNotEmpty()) {
                return@withContext SyncResult.Failed("No valid cards: ${parsed.problems.first()}", retryable = false)
            }
            val kanji = parsed.kanji.mapIndexed { i, c ->
                KanjiEntity(
                    source.id, c.kanji, c.title, c.jlpt, c.tags.joinSep(), c.strokeCount, c.radical, c.phonetic,
                    c.onyomi.joinSep(), c.kunyomi.joinSep(), c.distractors.joinSep(), c.body,
                    parsed.strokes[c.kanji]?.let { json.encodeToString(io.github.stopcran.kanji.core.content.StrokeData.serializer(), it) },
                    i,
                )
            }
            val words = parsed.words.map { WordEntity(source.id, it.word, it.reading, it.title, it.type, it.kanji.joinSep(), it.tags.joinSep(), it.body) }
            val articles = parsed.articles.map { ArticleEntity(source.id, it.slug, it.title, it.body) }
            val meta = SyncMetaEntity(source.id, parsed.manifest.contentVersion, System.currentTimeMillis(), parsed.problems.size)
            dao.replaceContent(source.id, kanji, words, articles, meta)
            SyncResult.Updated(kanji.size, words.size, parsed.problems)
        } catch (e: ContentException) {
            SyncResult.Failed(e.message ?: "Invalid content", retryable = false)
        } catch (e: java.io.IOException) {
            SyncResult.Failed(e.message ?: "Network error", retryable = true)
        }
    }

    private fun download(url: String): ByteArrayOutputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code == 404) throw ContentException("Repository or branch not found (or private)")
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DOWNLOAD_BYTES) throw ContentException("Archive is too large")
                    out.write(buffer, 0, n)
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun ByteArrayOutputStream.inputStream() = java.io.ByteArrayInputStream(toByteArray())

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 30L * 1024 * 1024
    }
}
