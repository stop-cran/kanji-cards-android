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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

internal fun isOrdinaryEntryAllowed(source: RepoSource): Boolean =
    source.id != "stop-cran/learning-japanese-ru" || source.branch != "main"

enum class SourceCandidateFailure {
    UpdatesUnavailable,
    InvalidContent,
    NoCards,
    LanguageChanged,
    DamagedUpdate,
    Cancelled,
}

sealed interface SourceCandidateResult {
    data class Validated(val candidate: ValidatedSourceCandidate) : SourceCandidateResult
    data class Rejected(val reason: SourceCandidateFailure) : SourceCandidateResult
}

data class ValidatedSourceCandidate internal constructor(
    val source: RepoSource,
    val baseLanguage: String,
    internal val head: String,
    internal val parsed: io.github.stopcran.kanji.core.content.ParsedContent,
    internal val candidateToken: String?,
)

internal sealed interface SyncActivity {
    data object Running : SyncActivity
    data class Finished(val result: SyncResult) : SyncActivity
    data object Interrupted : SyncActivity
}

// Equal terminal results can belong to different attempts, including a conflated periodic refresh.
internal data class SyncActivityEvent(val state: SyncActivity, val revision: Long)

internal fun MutableStateFlow<Map<RepoSource, SyncActivityEvent>>.record(source: RepoSource, state: SyncActivity) {
    update { it + (source to SyncActivityEvent(state, (it[source]?.revision ?: 0) + 1)) }
}

// A non-cancellation request failure must not terminate the process-lifetime admission loop.
@Suppress("TooGenericExceptionCaught")
internal suspend fun syncOutcome(reportFailure: (Exception) -> Unit, block: suspend () -> SyncResult): SyncResult {
    currentCoroutineContext().ensureActive()
    val result = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ContentException) {
        SyncResult.Failed(error.message ?: "Invalid content", retryable = false)
    } catch (error: java.io.IOException) {
        SyncResult.Failed(error.message ?: "Network error", retryable = true)
    } catch (error: Exception) {
        reportFailure(error)
        SyncResult.Failed(error.message ?: error.javaClass.simpleName, retryable = false)
    }
    // Blocking I/O can throw an ordinary exception after its coroutine was cancelled.
    currentCoroutineContext().ensureActive()
    return result
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
    kanjiReadingParserEpoch = ContentParser.KANJI_READING_PARSER_EPOCH,
    kanjiMeaningParserEpoch = ContentParser.KANJI_MEANING_PARSER_EPOCH,
    baseLanguage = manifest.baseLanguage,
    targetLanguage = manifest.targetLanguage,
)

private fun io.github.stopcran.kanji.core.content.ParsedContent.toRows(sourceId: String): ContentRows {
    val kanjiRows = kanji.mapIndexed { i, c ->
        KanjiEntity(
            sourceId, c.kanji, c.title, c.jlpt, c.tags.joinSep(), c.strokeCount, c.radical, c.phonetic,
            c.onyomi.joinSep(), c.kunyomi.joinSep(), c.distractors.joinSep(), c.body,
            strokes[c.kanji]?.let { Json.encodeToString(io.github.stopcran.kanji.core.content.StrokeData.serializer(), it) },
            i, c.quizOnyomi?.joinSep(), c.quizKunyomi?.joinSep(), c.quizExclusions.joinSep(),
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

class ContentSync(
    private val db: AppDatabase,
    private val syncState: SyncPreferences,
    private val http: ContentHttp = UrlConnectionHttp,
    private val selectedSource: (() -> RepoSource?)? = null,
) {
    private val lock = Mutex()
    private val _activity = MutableStateFlow<Map<RepoSource, SyncActivityEvent>>(emptyMap())
    internal val activity = _activity.asStateFlow()
    internal var beforeCandidateCommit: suspend () -> Unit = {}
    internal var beforeDefaultSelectionMirror: suspend () -> Unit = {}

    suspend fun beginSourceCandidate(settings: Settings, url: String, branch: String): PendingSourceCandidate? =
        lock.withLock { settings.beginSourceCandidate(url, branch) }

    suspend fun cancelSourceCandidate(settings: Settings, token: String? = null) =
        lock.withLock { settings.cancelSourceCandidate(token) }

    suspend fun recoverCommittedCandidate(settings: Settings): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val accepted = db.sourceAcceptance().current() ?: return@withLock null
            sourceSelectionLanguage(accepted.token)?.let { language ->
                if (settings.recoverLanguageSelection(accepted, language)) return@withLock accepted.token
                return@withLock null
            }
            if (!settings.isCurrentSourceCandidate(accepted.token)) return@withLock null
            mirrorAcceptedMetadata(accepted)
            settings.acceptSourceCandidate(accepted)
            null
        }
    }

    /** Changes language intent and, for built-in sources, serializes the selected-default authority transition. */
    suspend fun selectAppLanguage(
        settings: Settings,
        language: AppLanguage,
        applyPlatformLocale: suspend () -> Unit = {},
    ): RepoSource? = lock.withLock {
        val current = settings.source.value
        val target = LanguageSources.default(language).takeIf { LanguageSources.isDefault(current) }
        val selected = target ?: current
        val needsSync = target != null && (target != current || !authorityMatches(target))
        val marker = persistLanguageSelection(settings, selected, language)
        applyPlatformLocale()
        acknowledgeLanguageSelectionLocked(marker)
        if (needsSync) selected else null
    }

    /** Explicitly selects a language-matching built-in repository after the learner confirms replacement. */
    suspend fun selectDefaultSource(settings: Settings, source: RepoSource, language: AppLanguage): Boolean =
        lock.withLock {
            if (source != LanguageSources.default(language)) return@withLock false
            acknowledgeLanguageSelectionLocked(persistLanguageSelection(settings, source, language))
            true
        }

    /** A System locale change can move the selected built-in branch without changing language intent. */
    suspend fun reconcileSystemLanguage(settings: Settings): RepoSource? = lock.withLock {
        if (settings.appLanguage.value != AppLanguage.System) return@withLock null
        val current = settings.source.value
        val target = LanguageSources.defaultForBase(AppLocale.systemLanguage())
            .takeIf { LanguageSources.isDefault(current) } ?: return@withLock null
        if (target == current && authorityMatches(target)) return@withLock null
        acknowledgeLanguageSelectionLocked(persistLanguageSelection(settings, target, AppLanguage.System))
        target
    }

    suspend fun acknowledgeLanguageSelection(token: String) = lock.withLock {
        acknowledgeLanguageSelectionLocked(token)
    }

    private suspend fun acknowledgeLanguageSelectionLocked(token: String) {
        val accepted = db.sourceAcceptance().current()?.takeIf { it.token == token } ?: return
        if (sourceSelectionLanguage(token) == null) return
        db.withTransaction {
            db.sourceAcceptance().put(accepted.copy(token = "source-authority:${java.util.UUID.randomUUID()}"))
        }
    }

    private suspend fun authorityMatches(source: RepoSource): Boolean {
        val current = db.sourceAcceptance().current()
        return current?.sourceId == source.id && current.branch == source.branch
    }

    private suspend fun persistLanguageSelection(settings: Settings, source: RepoSource, language: AppLanguage): String {
        val previous = db.sourceAcceptance().current()?.takeIf { it.sourceId == source.id }
        val baseLanguage = syncState.baseLanguage(source.id) ?: db.content().meta(source.id)?.baseLanguage
            ?: previous?.baseLanguage ?: when (source.id) {
                LanguageSources.defaultForBase("ru").id -> "ru"
                else -> "en"
            }
        val expectedDefaultLanguage = when (source.id) {
            LanguageSources.defaultForBase("ru").id -> "ru"
            LanguageSources.defaultForBase("en").id -> "en"
            else -> null
        }
        check(expectedDefaultLanguage == null || expectedDefaultLanguage == baseLanguage) {
            "The built-in repository's accepted language does not match the selected default."
        }
        val accepted = AcceptedSourceEntity(
            token = sourceSelectionToken(language),
            sourceId = source.id,
            url = "https://github.com/${source.owner}/${source.repo}",
            branch = source.branch,
            baseLanguage = baseLanguage,
            head = previous?.takeIf { it.branch == source.branch }?.head.orEmpty(),
            grammarParserEpoch = previous?.grammarParserEpoch ?: syncState.grammarParserEpoch(source.id),
            counterParserEpoch = previous?.counterParserEpoch ?: syncState.counterParserEpoch(source.id),
            sceneParserEpoch = previous?.sceneParserEpoch ?: syncState.sceneParserEpoch(source.id),
            listeningParserEpoch = previous?.listeningParserEpoch ?: syncState.listeningParserEpoch(source.id),
            acceptedAtMs = System.currentTimeMillis(),
        )
        db.withTransaction { db.sourceAcceptance().put(accepted) }
        withContext(kotlinx.coroutines.NonCancellable) {
            beforeDefaultSelectionMirror()
            settings.applyLanguageSelection(language, source)
        }
        return accepted.token
    }

    /** Cheap check at app start: at most every [LAUNCH_INTERVAL_MS], and only downloads when the branch head moved. */
    suspend fun syncOnLaunch(source: RepoSource): SyncResult = lock.withLock { trackActivity(source) {
        if (isGatedRussianDefault(source)) return@trackActivity russianDefaultBlocked()
        val now = System.currentTimeMillis()
        if (!needsParserRefresh(source) && now - syncState.lastCheckMs < LAUNCH_INTERVAL_MS) return@trackActivity SyncResult.UpToDate
        val result = syncContent(source, force = false)
        if (result !is SyncResult.Failed) syncState.lastCheckMs = now
        result
    } }

    private suspend fun needsParserRefresh(source: RepoSource): Boolean =
        (db.content().meta(source.id)?.kanjiReadingParserEpoch ?: 0) < ContentParser.KANJI_READING_PARSER_EPOCH ||
            (db.content().meta(source.id)?.kanjiMeaningParserEpoch ?: 0) < ContentParser.KANJI_MEANING_PARSER_EPOCH ||
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
        _activity.record(source, SyncActivity.Running)
        val result = try {
            syncOutcome({ error -> android.util.Log.e("ContentSync", "Sync failed for ${source.id}@${source.branch}", error) }, block)
        } catch (cancelled: CancellationException) {
            _activity.record(source, SyncActivity.Interrupted)
            throw cancelled
        }
        _activity.record(source, SyncActivity.Finished(result))
        return result
    }

    suspend fun sync(source: RepoSource, force: Boolean = false): SyncResult =
        lock.withLock {
            trackActivity(source) {
                if (isGatedRussianDefault(source)) russianDefaultBlocked() else syncContent(source, force)
            }
        }

    fun ordinaryEntryAllowed(source: RepoSource): Boolean = isOrdinaryEntryAllowed(source)

    @Suppress("TooGenericExceptionCaught")
    suspend fun validateSourceCandidate(source: RepoSource, candidateToken: String? = null): SourceCandidateResult = lock.withLock {
        if (isGatedRussianDefault(source)) return@withLock SourceCandidateResult.Rejected(SourceCandidateFailure.InvalidContent)
        try {
            withContext(Dispatchers.IO) {
                val head = headCommit(source)
                    ?: return@withContext SourceCandidateResult.Rejected(SourceCandidateFailure.UpdatesUnavailable)
                val files = download(source.commitZipUrl(head)) { SafeZip.read(it) }
                val parsed = ContentParser.parse(files, SceneImages::problem)
                val acceptedLanguage = syncState.baseLanguage(source.id) ?: db.content().meta(source.id)?.baseLanguage
                candidateRejection(parsed, source.id, acceptedLanguage)?.let(SourceCandidateResult::Rejected)
                    ?: SourceCandidateResult.Validated(
                        ValidatedSourceCandidate(source, parsed.manifest.baseLanguage, head, parsed, candidateToken),
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ContentException) {
            SourceCandidateResult.Rejected(SourceCandidateFailure.InvalidContent)
        } catch (_: java.io.IOException) {
            SourceCandidateResult.Rejected(SourceCandidateFailure.UpdatesUnavailable)
        } catch (error: Exception) {
            android.util.Log.e("ContentSync", "Candidate validation failed for ${source.id}@${source.branch}", error)
            SourceCandidateResult.Rejected(SourceCandidateFailure.InvalidContent)
        }
    }

    private suspend fun candidateRejection(
        parsed: io.github.stopcran.kanji.core.content.ParsedContent,
        sourceId: String,
        acceptedLanguage: String?,
    ): SourceCandidateFailure? =
        when {
            acceptedLanguage != null && acceptedLanguage != parsed.manifest.baseLanguage -> SourceCandidateFailure.LanguageChanged
            parsed.kanji.isEmpty() -> SourceCandidateFailure.NoCards
            parsed.problems.isNotEmpty() && shrinks(parsed, sourceId) -> SourceCandidateFailure.DamagedUpdate
            legacyListeningFailure(parsed, sourceId) != null -> SourceCandidateFailure.InvalidContent
            else -> null
        }

    /** Imports only the still-current, already validated candidate; selection changes cancel its transaction. */
    suspend fun acceptSourceCandidate(
        candidate: ValidatedSourceCandidate,
        isCurrent: () -> Boolean,
        activate: (AcceptedSourceEntity) -> Unit,
    ): SyncResult = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!isCurrent()) return@withContext SyncResult.Failed("Source selection was cancelled", retryable = true)
            val knownLanguage = syncState.baseLanguage(candidate.source.id) ?: db.content().meta(candidate.source.id)?.baseLanguage
            if (knownLanguage != null && knownLanguage != candidate.baseLanguage) {
                return@withContext SyncResult.Failed("Repository language changed; keeping previous content", retryable = false)
            }
            if (candidate.parsed.kanji.isEmpty()) {
                return@withContext SyncResult.Failed("No valid cards in the selected repository", retryable = false)
            }
            if (candidate.parsed.problems.isNotEmpty() && shrinks(candidate.parsed, candidate.source.id)) {
                return@withContext SyncResult.Failed("Invalid update; keeping previous content", retryable = false)
            }
            val accepted = AcceptedSourceEntity(
                token = currentCandidateToken(candidate, isCurrent) ?: return@withContext SyncResult.Failed(
                    "Source selection was cancelled", retryable = true,
                ),
                sourceId = candidate.source.id,
                url = "https://github.com/${candidate.source.owner}/${candidate.source.repo}",
                branch = candidate.source.branch,
                baseLanguage = candidate.baseLanguage,
                head = candidate.head,
                grammarParserEpoch = ContentParser.GRAMMAR_PARSER_EPOCH,
                counterParserEpoch = io.github.stopcran.kanji.core.counters.CounterExpressions.PARSER_EPOCH,
                sceneParserEpoch = SceneCatalog.PARSER_EPOCH,
                listeningParserEpoch = io.github.stopcran.kanji.core.listening.ListeningContent.PARSER_EPOCH,
                acceptedAtMs = System.currentTimeMillis(),
            )
            currentCoroutineContext().ensureActive()
            val result = try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    val imported = replaceContent(candidate.parsed, candidate.source, candidate.head, { isCurrent() }, accepted)
                    mirrorAcceptedMetadata(accepted)
                    activate(accepted)
                    imported
                }
            } catch (_: StaleSourceCandidateException) {
                return@withContext SyncResult.Failed("Source selection was cancelled", retryable = true)
            }
            db.rebuildGrammarReadiness(candidate.source.id)
            result
        }
    }

    private suspend fun syncContent(source: RepoSource, force: Boolean): SyncResult = withContext(Dispatchers.IO) {
        if (!ordinarySourceIsCurrent(source)) return@withContext SyncResult.UpToDate
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
        val acceptedLanguage = syncState.baseLanguage(source.id) ?: dao.meta(source.id)?.baseLanguage
        if (acceptedLanguage != null && acceptedLanguage != parsed.manifest.baseLanguage) {
            return@withContext SyncResult.Failed(
                "Repository language changed from $acceptedLanguage to ${parsed.manifest.baseLanguage}; keeping previous content",
                retryable = false,
                rejected = parsed.rejectedContent(),
            )
        }
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
        val result = replaceOrdinaryContent(parsed, source, head) ?: return@withContext SyncResult.UpToDate
        check(syncState.bindBaseLanguage(source.id, parsed.manifest.baseLanguage)) {
            "Repository language changed while accepting content"
        }
        syncState.setGrammarParserEpoch(source.id, ContentParser.GRAMMAR_PARSER_EPOCH)
        syncState.setCounterParserEpoch(source.id, io.github.stopcran.kanji.core.counters.CounterExpressions.PARSER_EPOCH)
        syncState.setSceneParserEpoch(source.id, SceneCatalog.PARSER_EPOCH)
        syncState.setListeningParserEpoch(source.id, io.github.stopcran.kanji.core.listening.ListeningContent.PARSER_EPOCH)
        head?.let { syncState.setSyncedCommit(source, it) }
        db.rebuildGrammarReadiness(source.id)
        result
    }

    private suspend fun replaceOrdinaryContent(
        parsed: io.github.stopcran.kanji.core.content.ParsedContent,
        source: RepoSource,
        head: String?,
    ): SyncResult.Updated? {
        if (!ordinarySourceIsCurrent(source)) return null
        return try {
            replaceContent(parsed, source, head, isCurrent = { ordinarySourceIsCurrent(source) })
        } catch (_: StaleSourceCandidateException) {
            null
        }
    }

    private suspend fun replaceContent(
        parsed: io.github.stopcran.kanji.core.content.ParsedContent, source: RepoSource, head: String?,
        isCurrent: suspend () -> Boolean = { true },
        accepted: AcceptedSourceEntity? = null,
    ): SyncResult.Updated = db.withTransaction {
        if (!isCurrent()) throw StaleSourceCandidateException()
        val rows = parsed.toRows(source.id)
        val meta = parsed.toSyncMeta(source.id)
        val tasks = db.importListeningTasks(parsed, source, head)
        db.content().replaceContent(source.id, rows, meta.copy(problems = meta.problems + tasks.problems.size))
        db.listening().pruneAudio(source.id)
        if (!isCurrent()) throw StaleSourceCandidateException()
        accepted?.let { db.sourceAcceptance().put(it) }
        if (accepted != null) beforeCandidateCommit()
        if (!isCurrent()) throw StaleSourceCandidateException()
        SyncResult.Updated(
            rows.kanji.size, rows.words.size, parsed.problems + tasks.problems,
            parsed.grammar.sumOf { it.exercises.size }, rows.listening.size + db.listeningTasks().importedCount(source.id),
            parsed.rejectedContent(tasks.rejectedPaths),
        )
    }

    private suspend fun ordinarySourceIsCurrent(source: RepoSource): Boolean {
        if (selectedSource?.invoke()?.let { it != source } == true) return false
        val accepted = db.sourceAcceptance().current()?.takeIf { it.sourceId == source.id }
        return accepted == null || accepted.branch == source.branch
    }

    private fun isGatedRussianDefault(source: RepoSource) = !isOrdinaryEntryAllowed(source)

    private fun currentCandidateToken(candidate: ValidatedSourceCandidate, isCurrent: () -> Boolean): String? =
        candidate.candidateToken?.takeIf { isCurrent() }

    private fun mirrorAcceptedMetadata(accepted: AcceptedSourceEntity) {
        check(syncState.bindBaseLanguage(accepted.sourceId, accepted.baseLanguage)) {
            "Repository language changed while mirroring accepted source"
        }
        syncState.setGrammarParserEpoch(accepted.sourceId, accepted.grammarParserEpoch)
        syncState.setCounterParserEpoch(accepted.sourceId, accepted.counterParserEpoch)
        syncState.setSceneParserEpoch(accepted.sourceId, accepted.sceneParserEpoch)
        syncState.setListeningParserEpoch(accepted.sourceId, accepted.listeningParserEpoch)
        val source = RepoSource.parse(accepted.url, accepted.branch)
        check(source?.id == accepted.sourceId) { "Accepted source identity is invalid" }
        syncState.setSyncedCommit(checkNotNull(source), accepted.head)
    }

    private fun russianDefaultBlocked() =
        SyncResult.Failed("Russian default content is not enabled for ordinary study yet", retryable = false)

    private class StaleSourceCandidateException : Exception()

    private fun sourceSelectionToken(language: AppLanguage) =
        "$SOURCE_SELECTION_PENDING_PREFIX${language.storedValue}:${java.util.UUID.randomUUID()}"

    private fun sourceSelectionLanguage(token: String): AppLanguage? {
        if (!token.startsWith(SOURCE_SELECTION_PENDING_PREFIX)) return null
        val stored = token.removePrefix(SOURCE_SELECTION_PENDING_PREFIX).substringBefore(':')
        return AppLanguage.parse(stored).takeIf { it.storedValue == stored }
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
        const val SOURCE_SELECTION_PENDING_PREFIX = "source-selection-pending:"
        const val MAX_DOWNLOAD_BYTES = 48L * 1024 * 1024
        const val LAUNCH_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
