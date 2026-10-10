package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.ContentException
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.content.SafeZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface SyncResult {
    data class Updated(val kanji: Int, val words: Int, val problems: List<String>) : SyncResult
    data object UpToDate : SyncResult
    data class Failed(val message: String, val retryable: Boolean) : SyncResult
}

class ContentSync(private val db: AppDatabase, private val settings: Settings, private val http: ContentHttp = UrlConnectionHttp) {
    private val json = Json
    private val lock = Mutex()

    /** Cheap check at app start: at most every [LAUNCH_INTERVAL_MS], and only downloads when the branch head moved. */
    suspend fun syncOnLaunch(source: RepoSource): SyncResult {
        val now = System.currentTimeMillis()
        if (now - settings.lastCheckMs < LAUNCH_INTERVAL_MS) return SyncResult.UpToDate
        val result = sync(source)
        if (result !is SyncResult.Failed) settings.lastCheckMs = now
        return result
    }

    /** Head commit of the branch via the GitHub API (one tiny request); null when unavailable, e.g. offline or rate limited. */
    private fun headCommit(source: RepoSource): String? {
        val key = "${source.id}@${source.branch}"
        // A 304 does not count against the API rate limit.
        val known = settings.headEtag(key)
        val headers = buildMap {
            put("Accept", "application/vnd.github.sha")
            if (known != null) put("If-None-Match", known.first)
        }
        return try {
            http.open("https://api.github.com/repos/${source.owner}/${source.repo}/commits/${source.branch}", headers, 8_000, 8_000).use { r ->
                when (r.code) {
                    304 -> known?.second
                    200 -> {
                        val sha = r.body.bufferedReader().readText().trim().takeIf { it.matches(Regex("[0-9a-f]{40}")) }
                        val etag = r.header("ETag")
                        if (sha != null && etag != null) settings.setHeadEtag(key, etag, sha)
                        sha
                    }
                    else -> null
                }
            }
        } catch (e: java.io.IOException) {
            null
        }
    }
    suspend fun sync(source: RepoSource, force: Boolean = false): SyncResult = lock.withLock { withContext(Dispatchers.IO) {
        try {
            val commitKey = "${source.id}@${source.branch}"
            val head = headCommit(source)
            // Rate-limited or offline: keep what we have rather than downloading the whole archive on every check.
            if (!force && head == null && db.content().meta(source.id) != null) {
                return@withContext SyncResult.Failed("Could not check for updates", retryable = true)
            }
            if (!force && head != null && head == settings.syncedCommit(commitKey) && db.content().meta(source.id) != null) {
                return@withContext SyncResult.UpToDate
            }
            val files = download(source.zipUrl) { SafeZip.read(it) }
            val parsed = ContentParser.parse(files)
            val dao = db.content()
            if (!force && dao.meta(source.id)?.contentVersion == parsed.manifest.contentVersion) {
                head?.let { settings.setSyncedCommit(commitKey, it) }
                return@withContext SyncResult.UpToDate
            }
            if (parsed.kanji.isEmpty()) {
                return@withContext SyncResult.Failed("No valid cards: ${parsed.problems.firstOrNull() ?: "empty manifest"}", retryable = false)
            }
            if (!force && parsed.problems.isNotEmpty() && dao.meta(source.id) != null &&
                (parsed.kanji.size < dao.kanjiCount(source.id) || parsed.words.size < dao.wordCount(source.id))
            ) {
                return@withContext SyncResult.Failed(
                    "${parsed.problems.size} files rejected (${parsed.problems.first()}); keeping previous cards", retryable = true,
                )
            }
            val kanji = parsed.kanji.mapIndexed { i, c ->
                KanjiEntity(
                    source.id, c.kanji, c.title, c.jlpt, c.tags.joinSep(), c.strokeCount, c.radical, c.phonetic,
                    c.onyomi.joinSep(), c.kunyomi.joinSep(), c.distractors.joinSep(), c.body,
                    parsed.strokes[c.kanji]?.let { json.encodeToString(io.github.stopcran.kanji.core.content.StrokeData.serializer(), it) },
                    i,
                )
            }
            val words = parsed.words.map { it.toEntity(source.id) }
            val articles = parsed.articles.map { ArticleEntity(source.id, it.slug, it.title, it.body) }
            val meta = SyncMetaEntity(source.id, parsed.manifest.contentVersion, System.currentTimeMillis(), parsed.problems.size)
            dao.replaceContent(source.id, kanji, words, articles, meta)
            head?.let { settings.setSyncedCommit(commitKey, it) }
            SyncResult.Updated(kanji.size, words.size, parsed.problems)
        } catch (e: ContentException) {
            SyncResult.Failed(e.message ?: "Invalid content", retryable = false)
        } catch (e: java.io.IOException) {
            SyncResult.Failed(e.message ?: "Network error", retryable = true)
        } catch (e: Exception) {
            // Parse, database or security failures must not crash the caller (a worker or the UI); cancellation still propagates.
            if (e is kotlinx.coroutines.CancellationException) throw e
            SyncResult.Failed(e.message ?: e.javaClass.simpleName, retryable = false)
        }
    } }

    /** Streams the archive to [block] without holding it in memory; the compressed size is capped. */
    private fun <T> download(url: String, block: (java.io.InputStream) -> T): T =
        http.open(url, emptyMap(), 15_000, 30_000).use { r ->
            if (r.code == 404) throw ContentException("Repository or branch not found (or private)")
            if (r.code !in 200..299) throw java.io.IOException("HTTP ${r.code}")
            block(CappedInputStream(r.body, MAX_DOWNLOAD_BYTES))
        }
    private class CappedInputStream(private val inner: java.io.InputStream, private val max: Long) : java.io.FilterInputStream(inner) {
        private var total = 0L

        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it) }
        private fun count(n: Int) {
            total += n
            if (total > max) throw ContentException("Archive is too large")
        }
    }
    private companion object {
        const val MAX_DOWNLOAD_BYTES = 30L * 1024 * 1024
        const val LAUNCH_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
