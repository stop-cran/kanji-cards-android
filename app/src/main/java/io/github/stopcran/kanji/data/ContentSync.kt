package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.ContentException
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.ContentRejections
import io.github.stopcran.kanji.core.content.rejectedContent
import io.github.stopcran.kanji.core.content.GrammarCurriculumData
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.content.SafeZip
import io.github.stopcran.kanji.core.content.SceneCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface SyncResult {
    data class Updated(
        val kanji: Int, val words: Int, val problems: List<String>,
        val grammar: Int = 0, val listening: Int = 0, val rejected: ContentRejections = ContentRejections(),
    ) : SyncResult
    data object UpToDate : SyncResult
    data class Failed(
        val message: String, val retryable: Boolean, val rejected: ContentRejections = ContentRejections(),
    ) : SyncResult
}

internal sealed interface SyncActivity {
    data object Running : SyncActivity
    data class Finished(val result: SyncResult) : SyncActivity
    data object Interrupted : SyncActivity
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
    listeningProblems.takeIf { it.isNotEmpty() }?.joinToString("\n"),
    (curriculum as? GrammarCurriculumState.Ready)?.let { Json.encodeToString(GrammarCurriculumData.serializer(), it.curriculum) },
    (curriculum as? GrammarCurriculumState.Invalid)?.reason,
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
        listening.mapIndexed { index, record ->
            ListeningEntity(sourceId, record.passage.id, record.json, record.contentHash,
                record.passage.audio?.sha256, record.passage.audio?.durationMs, record.ready, record.reason, index)
        },
        audio.map { (hash, bytes) -> ListeningAudioEntity(sourceId, hash, bytes) },
    )
}

class ContentSync(private val db: AppDatabase, private val syncState: SyncPreferences, private val http: ContentHttp = UrlConnectionHttp) {
    private val lock = Mutex()
    private val _activity = MutableStateFlow<Map<RepoSource, SyncActivity>>(emptyMap())
    internal val activity = _activity.asStateFlow()

    /** Cheap check at app start: at most every [LAUNCH_INTERVAL_MS], and only downloads when the branch head moved. */
    suspend fun syncOnLaunch(source: RepoSource): SyncResult = lock.withLock { trackActivity(source) {
        val now = System.currentTimeMillis()
        if (!needsParserRefresh(source) && now - syncState.lastCheckMs < LAUNCH_INTERVAL_MS) return@trackActivity SyncResult.UpToDate
        val result = syncContent(source, force = false)
        if (result !is SyncResult.Failed) syncState.lastCheckMs = now
        result
    } }

    private suspend fun needsParserRefresh(source: RepoSource): Boolean =
        syncState.grammarParserEpoch(source.id) < ContentParser.GRAMMAR_PARSER_EPOCH ||
            syncState.counterParserEpoch(source.id) < io.github.stopcran.kanji.core.counters.CounterExpressions.PARSER_EPOCH ||
            syncState.sceneParserEpoch(source.id) < SceneCatalog.PARSER_EPOCH ||
            syncState.listeningParserEpoch(source.id) < io.github.stopcran.kanji.core.listening.ListeningContent.PARSER_EPOCH ||
            db.listeningTasks().sync(source.id).let {
                it == null || it.parserEpoch < io.github.stopcran.kanji.core.listening.ListeningTaskContent.PARSER_EPOCH ||
                    it.problem != null || it.acceptedBranch != source.branch
            }

    /** Head commit of the branch via the GitHub API (one tiny request); null when unavailable, e.g. offline or rate limited. */
    private fun headCommit(source: RepoSource): String? {
        // A 304 does not count against the API rate limit.
        val known = syncState.headEtag(source)
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
                        if (sha != null && etag != null) syncState.setHeadEtag(source, etag, sha)
                        sha
                    }
                    else -> null
                }
            }
        } catch (e: java.io.IOException) {
            null
        }
    }
    private suspend fun trackActivity(source: RepoSource, block: suspend () -> SyncResult): SyncResult {
        _activity.update { it + (source to SyncActivity.Running) }
        return try {
            block().also { result ->
                _activity.update { it + (source to SyncActivity.Finished(result)) }
            }
        } catch (cancelled: CancellationException) {
            _activity.update { it + (source to SyncActivity.Interrupted) }
            throw cancelled
        }
    }

    suspend fun sync(source: RepoSource, force: Boolean = false): SyncResult =
        lock.withLock { trackActivity(source) { syncContent(source, force) } }

    private suspend fun syncContent(source: RepoSource, force: Boolean): SyncResult = withContext(Dispatchers.IO) {
        try {
            if (db.content().meta(source.id) != null && db.reviews().grammarReadinessStatus(source.id)?.complete != true) {
                db.rebuildGrammarReadiness(source.id)
            }
            val maySkipImport = !force && !needsParserRefresh(source)
            val head = headCommit(source)
            // Rate-limited or offline: fall back to the branch archive, but at most once per interval so launches and worker retries cannot hammer codeload.
            val url = head?.let { source.commitZipUrl(it) } ?: source.zipUrl
            if (!force && head == null && db.content().meta(source.id) != null) {
                val now = System.currentTimeMillis()
                if (now - syncState.fallbackAttemptMs(source) < LAUNCH_INTERVAL_MS) {
                    return@withContext SyncResult.Failed("Could not check for updates", retryable = false)
                }
                syncState.setFallbackAttemptMs(source, now)
            }
            val headUnchanged = head != null && head == syncState.syncedCommit(source)
            if (maySkipImport && headUnchanged && db.content().meta(source.id) != null) return@withContext SyncResult.UpToDate
            val files = download(url) { SafeZip.read(it) }
            val parsed = ContentParser.parse(files, SceneImages::problem)
            val dao = db.content()
            legacyListeningFailure(parsed, source.id)?.let { return@withContext it }
            if (maySkipImport && parsed.listeningTasks.problems.isEmpty() &&
                dao.meta(source.id)?.contentVersion == parsed.manifest.contentVersion) {
                head?.let { syncState.setSyncedCommit(source, it) }
                return@withContext SyncResult.UpToDate
            }
            if (parsed.kanji.isEmpty()) {
                return@withContext SyncResult.Failed(
                    "No valid cards: ${parsed.problems.firstOrNull() ?: "empty manifest"}",
                    retryable = false, rejected = parsed.rejectedContent(),
                )
            }
            if (!force && parsed.problems.isNotEmpty() && shrinks(parsed, source.id)) {
                return@withContext SyncResult.Failed(
                    "${parsed.problems.size} content issue(s) (${parsed.problems.first()}); keeping previous cards", retryable = true,
                    rejected = parsed.rejectedContent(),
                )
            }
            val result = replaceContent(parsed, source, head)
            syncState.setGrammarParserEpoch(source.id, ContentParser.GRAMMAR_PARSER_EPOCH)
            syncState.setCounterParserEpoch(source.id, io.github.stopcran.kanji.core.counters.CounterExpressions.PARSER_EPOCH)
            syncState.setSceneParserEpoch(source.id, SceneCatalog.PARSER_EPOCH)
            syncState.setListeningParserEpoch(source.id, io.github.stopcran.kanji.core.listening.ListeningContent.PARSER_EPOCH)
            head?.let { syncState.setSyncedCommit(source, it) }
            db.rebuildGrammarReadiness(source.id)
            result
        } catch (e: ContentException) {
            SyncResult.Failed(e.message ?: "Invalid content", retryable = false)
        } catch (e: java.io.IOException) {
            SyncResult.Failed(e.message ?: "Network error", retryable = true)
        } catch (e: Exception) {
            // Parse, database or security failures must not crash the caller (a worker or the UI); cancellation still propagates.
            if (e is kotlinx.coroutines.CancellationException) throw e
            SyncResult.Failed(e.message ?: e.javaClass.simpleName, retryable = false)
        }
    }

    private suspend fun replaceContent(
        parsed: io.github.stopcran.kanji.core.content.ParsedContent, source: RepoSource, head: String?,
    ): SyncResult.Updated = db.withTransaction {
        val rows = parsed.toRows(source.id)
        val meta = parsed.toSyncMeta(source.id)
        val tasks = db.importListeningTasks(parsed, source, head)
        db.content().replaceContent(source.id, rows, meta.copy(problems = meta.problems + tasks.problems.size))
        db.listening().pruneAudio(source.id)
        SyncResult.Updated(
            rows.kanji.size, rows.words.size, parsed.problems + tasks.problems,
            parsed.grammar.sumOf { it.exercises.size }, rows.listening.size + db.listeningTasks().importedCount(source.id),
            parsed.rejectedContent(tasks.rejectedPaths),
        )
    }

    private suspend fun legacyListeningFailure(parsed: io.github.stopcran.kanji.core.content.ParsedContent, source: String): SyncResult.Failed? {
        val taskIds = db.listeningTasks().identities(source).mapTo(hashSetOf()) { it.taskId }
        val collisions = parsed.listening.filter { it.passage.id in taskIds }.mapTo(hashSetOf()) { "listening/${it.passage.id}.json" }
        return when {
            collisions.isNotEmpty() -> SyncResult.Failed(
                "A legacy passage reused an existing Japanese listening task's scored ID; keeping previous content",
                retryable = false, rejected = parsed.rejectedContent(collisions),
            )
            parsed.listeningProblems.isNotEmpty() && db.listening().passages(source).isNotEmpty() ->
                SyncResult.Failed(
                    "Listening content rejected (${parsed.listeningProblems.first()}); keeping previous playable content",
                    retryable = false, rejected = parsed.rejectedContent(),
                )
            else -> null
        }
    }

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
            block(CappedInputStream(r.body))
        }
    internal class CappedInputStream(private val inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        private var total = 0L

        override fun read(): Int = inner.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count(it) }
        private fun count(n: Int) {
            total += n
            if (total > MAX_DOWNLOAD_BYTES) throw ContentException("Archive is too large")
        }
    }
    private companion object {
        const val MAX_DOWNLOAD_BYTES = 48L * 1024 * 1024
        const val LAUNCH_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
