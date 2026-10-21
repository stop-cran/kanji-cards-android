package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.ContentException
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.content.SafeZip
import io.github.stopcran.kanji.core.content.SceneCatalog
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

private fun List<io.github.stopcran.kanji.core.content.GrammarSet>.toGrammarEntities(sourceId: String): List<GrammarEntity> =
    mapIndexed { index, set ->
        GrammarEntity(
            sourceId,
            set.id,
            Json.encodeToString(io.github.stopcran.kanji.core.content.GrammarSet.serializer(), set),
            index,
        )
    }

private fun io.github.stopcran.kanji.core.content.ParsedContent.toSyncMeta(sourceId: String) = SyncMetaEntity(
    sourceId, manifest.contentVersion, System.currentTimeMillis(), problems.size,
    grammarProblems.takeIf { it.isNotEmpty() }?.joinToString("\n"),
)

private fun io.github.stopcran.kanji.core.content.ParsedContent.toRows(sourceId: String): ContentRows {
    val kanjiRows = kanji.mapIndexed { i, c ->
        KanjiEntity(
            sourceId, c.kanji, c.title, c.jlpt, c.tags.joinSep(), c.strokeCount, c.radical, c.phonetic,
            c.onyomi.joinSep(), c.kunyomi.joinSep(), c.distractors.joinSep(), c.body,
            strokes[c.kanji]?.let { Json.encodeToString(io.github.stopcran.kanji.core.content.StrokeData.serializer(), it) },
            i,
        )
    }
    val counterRows = counters.mapIndexed { i, c ->
        CounterEntity(sourceId, c.id, Json.encodeToString(io.github.stopcran.kanji.core.content.CounterFile.serializer(), c), i)
    }
    val sceneRows = words.flatMap { word ->
        word.scenes.mapIndexed { index, scene ->
            WordSceneEntity(
                sourceId, word.word, scene.id, scene.sense, scene.image, scene.description,
                scene.quizEligible, manifest.images.getValue(scene.image), index,
            )
        }
    }
    return ContentRows(
        kanjiRows, words.map { it.toEntity(sourceId) }, articles.map { ArticleEntity(sourceId, it.slug, it.title, it.body) },
        counterRows, images.map { (path, bytes) -> ImageEntity(sourceId, path.removePrefix("images/"), bytes) },
        grammar.toGrammarEntities(sourceId), sceneRows,
    )
}

class ContentSync(private val db: AppDatabase, private val settings: Settings, private val http: ContentHttp = UrlConnectionHttp) {
    private val lock = Mutex()

    /** Cheap check at app start: at most every [LAUNCH_INTERVAL_MS], and only downloads when the branch head moved. */
    suspend fun syncOnLaunch(source: RepoSource): SyncResult {
        val now = System.currentTimeMillis()
        if (!needsParserRefresh(source) && now - settings.lastCheckMs < LAUNCH_INTERVAL_MS) return SyncResult.UpToDate
        val result = sync(source)
        if (result !is SyncResult.Failed) settings.lastCheckMs = now
        return result
    }

    private fun needsParserRefresh(source: RepoSource): Boolean =
        settings.grammarFor(source.id).parserEpoch < ContentParser.GRAMMAR_PARSER_EPOCH || settings.sceneParserEpoch(source.id) < SceneCatalog.PARSER_EPOCH

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
            val maySkipImport = !force && !needsParserRefresh(source)
            val commitKey = "${source.id}@${source.branch}"
            val head = headCommit(source)
            // Rate-limited or offline: fall back to the branch archive, but at most once per interval so launches and worker retries cannot hammer codeload.
            val url = head?.let { source.commitZipUrl(it) } ?: source.zipUrl
            if (!force && head == null && db.content().meta(source.id) != null) {
                val now = System.currentTimeMillis()
                if (now - settings.fallbackAttemptMs(commitKey) < LAUNCH_INTERVAL_MS) {
                    return@withContext SyncResult.Failed("Could not check for updates", retryable = false)
                }
                settings.setFallbackAttemptMs(commitKey, now)
            }
            val headUnchanged = head != null && head == settings.syncedCommit(commitKey)
            if (maySkipImport && headUnchanged && db.content().meta(source.id) != null) return@withContext SyncResult.UpToDate
            val files = download(url) { SafeZip.read(it) }
            val parsed = ContentParser.parse(files, SceneImages::problem)
            val dao = db.content()
            if (maySkipImport && dao.meta(source.id)?.contentVersion == parsed.manifest.contentVersion) {
                head?.let { settings.setSyncedCommit(commitKey, it) }
                return@withContext SyncResult.UpToDate
            }
            if (parsed.kanji.isEmpty()) {
                return@withContext SyncResult.Failed("No valid cards: ${parsed.problems.firstOrNull() ?: "empty manifest"}", retryable = false)
            }
            if (!force && parsed.problems.isNotEmpty() && shrinks(parsed, source.id)) {
                return@withContext SyncResult.Failed(
                    "${parsed.problems.size} files rejected (${parsed.problems.first()}); keeping previous cards", retryable = true,
                )
            }
            val rows = parsed.toRows(source.id)
            val meta = parsed.toSyncMeta(source.id)
            dao.replaceContent(source.id, rows, meta)
            settings.setGrammarParserEpoch(source.id, ContentParser.GRAMMAR_PARSER_EPOCH)
            settings.setSceneParserEpoch(source.id, SceneCatalog.PARSER_EPOCH)
            head?.let { settings.setSyncedCommit(commitKey, it) }
            SyncResult.Updated(rows.kanji.size, rows.words.size, parsed.problems)
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

    /** True when a previous snapshot exists and the new one has fewer kanji, words or counters (rejected files must not shrink the corpus). */
    private suspend fun shrinks(parsed: io.github.stopcran.kanji.core.content.ParsedContent, sourceId: String): Boolean {
        val dao = db.content()
        if (dao.meta(sourceId) == null) return false
        return parsed.kanji.size < dao.kanjiCount(sourceId) || parsed.words.size < dao.wordCount(sourceId) ||
            parsed.counters.size < dao.counterCount(sourceId) || parsed.grammar.size < dao.grammarCount(sourceId)
    }

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
