package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.content.SceneCatalog
import io.github.stopcran.kanji.core.content.WordScene
import io.github.stopcran.kanji.core.content.WordSceneFile
import io.github.stopcran.kanji.core.grammar.GrammarScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

private fun kanjiMd(kanji: String, title: String) = """
    ---
    kanji: $kanji
    title: $title
    jlpt: 5
    tags: [jlpt-n5]
    strokes: 4
    onyomi: [ニチ]
    kunyomi: [ひ]
    distractors: []
    ---

    # $kanji
""".trimIndent()

/** Builds a GitHub-style archive (single top-level folder) with a valid manifest. */
private fun archive(
    version: String,
    cards: Map<String, String>,
    corrupt: Set<String> = emptySet(),
    grammar: Pair<String, String>? = null,
    additionalGrammar: Map<String, String> = emptyMap(),
): ByteArray {
    val files = cards.entries.associate { (k, title) ->
        "kanji/$k.md" to (if (k in corrupt) "no front matter here" else kanjiMd(k, title)).toByteArray()
    } + additionalGrammar.map { (id, text) -> "grammar/$id.json" to text.toByteArray() }.toMap() +
        if (grammar == null) emptyMap() else mapOf(
        "articles/pilot.md" to "# Pilot\n".toByteArray(),
        "grammar/${grammar.first}.json" to grammar.second.toByteArray(),
    )
    return archiveFiles(version, files)
}

private fun archiveFiles(version: String, files: Map<String, ByteArray>): ByteArray {
    val images = files.filterKeys { it.startsWith("images/") }
    val scenes = files.filterKeys { it.startsWith("scenes/") }
    val text = files - images.keys - scenes.keys
    val manifest = Json.encodeToString(
        Manifest.serializer(),
        Manifest(
            1, version, text.mapValues { ContentParser.sha256(it.value) },
            images.mapValues { ContentParser.rawSha256(it.value) }, scenes.mapValues { ContentParser.sha256(it.value) },
        ),
    ).toByteArray()
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        (files + ("manifest.json" to manifest)).forEach { (p, b) ->
            zip.putNextEntry(ZipEntry("repo-main/$p"))
            zip.write(b)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private class FakeHttp : ContentHttp {
    var headCode = 200
    var headSha: String? = SHA_A
    var headEtag: String? = "\"etag-1\""
    var headFails = false
    var archiveCode = 200
    var archiveBytes: ByteArray = ByteArray(0)
    var archiveFails = false
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    val downloads get() = requests.count { it.first.startsWith("https://codeload") }
    val headChecks get() = requests.count { it.first.startsWith("https://api.github.com") }

    override fun open(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): ContentResponse {
        requests += url to headers
        val isHead = url.startsWith("https://api.github.com")
        if (if (isHead) headFails else archiveFails) throw IOException("offline")
        val code = if (isHead) headCode else archiveCode
        val bytes = if (isHead) (headSha ?: "").toByteArray() else archiveBytes
        return object : ContentResponse {
            override val code = code
            override val body: InputStream = ByteArrayInputStream(bytes)
            override fun header(name: String) = if (isHead && name == "ETag") headEtag else null
            override fun close() {}
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ContentSyncTest {
    private val source = RepoSource("Owner", "Repo", "main")
    private val http = FakeHttp()
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private lateinit var sync: ContentSync

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = Settings(context)
        sync = ContentSync(db, settings, http)
        http.archiveBytes = archive("v1", mapOf("日" to "sun"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun run(force: Boolean = false) = runBlocking { sync.sync(source, force) }

    private fun sceneArchive(
        version: String = "scenes",
        eligible: Boolean = true,
        second: Boolean = false,
        bytes: ByteArray = sceneWebp(),
        grammar: Pair<String, String>? = null,
    ): ByteArray {
        val legacy = if (eligible) "image: images/a.webp\nimage_scene: A scene\n" else ""
        val word = "---\nword: a\nreading: a\ntitle: warmth\nkanji: []\ntags: [onomatopoeia]\n$legacy---\nBody\n"
        val catalog = WordSceneFile(
            1, "a",
            listOf(WordScene("warmth", "Warmth", "images/a.webp", "A scene", eligible)) +
                if (second) listOf(WordScene("diagram", "Diagram", "images/b.webp", "A diagram", false)) else emptyList(),
        )
        return archiveFiles(version, buildMap {
            put("kanji/日.md", kanjiMd("日", "sun").toByteArray())
            put("words/a.md", word.toByteArray())
            put("images/a.webp", bytes)
            if (second) put("images/b.webp", sceneWebp(blue = true))
            put("scenes/a.json", Json.encodeToString(WordSceneFile.serializer(), catalog).toByteArray())
            grammar?.let {
                put("articles/pilot.md", "# Pilot\n".toByteArray())
                put("grammar/${it.first}.json", it.second.toByteArray())
            }
        })
    }

    @Test
    fun sceneEpochRefreshesAnUnchangedSnapshotWithoutResettingWordOrGrammarProgress() = runBlocking {
        val grammar = io.github.stopcran.kanji.core.grammarFixture().copy(article = "articles/pilot.md")
        http.archiveBytes = sceneArchive(second = true, grammar = grammar.id to Json.encodeToString(GrammarSet.serializer(), grammar))
        assertEquals(SyncResult.Updated(1, 1, emptyList()), sync.sync(source))
        assertEquals(listOf(true, false), db.content().wordScenes(source.id).map { it.quizEligible })
        assertEquals(SceneCatalog.PARSER_EPOCH, settings.sceneParserEpoch(source.id))
        assertEquals(0, settings.sceneParserEpoch("other/repo"))
        db.reviews().record(
            ReviewStateEntity(source.id, "words-onomatopoeia", "a", "WordPicture", "Review", 12.5, 5.0, 999, 500, 3, 1),
            ReviewLogEntity(0, source.id, "words-onomatopoeia", "a", "WordPicture", 3, 500),
        )
        db.reviews().recordGrammar(
            ReviewStateEntity(source.id, GRAMMAR_STACK, "alpha-a-0", "Grammar", "Review", 12.5, 5.0, 999, 500, 3, 1),
            ReviewLogEntity(0, source.id, GRAMMAR_STACK, "alpha-a-0", "Grammar", 3, 500),
            GrammarResponseEntity(0, source.id, GRAMMAR_STACK, "alpha-a-0", "a", "contrast", "type", "a", true),
        )
        val before = Triple(db.reviews().allStates(), db.reviews().allLog(), db.reviews().allGrammarResponses())
        db.content().clearScenes(source.id)
        settings.setSceneParserEpoch(source.id, 0)
        settings.setGrammarNewScope(source.id, GrammarScope.N3)
        settings.lastCheckMs = System.currentTimeMillis()
        assertEquals(SyncResult.Updated(1, 1, emptyList()), sync.syncOnLaunch(source))
        assertEquals(2, http.downloads)
        assertEquals(2, db.content().wordScenes(source.id).size)
        assertEquals(GrammarScope.N3, settings.grammarFor(source.id).newScope)
        assertEquals(GRAMMAR_GROUP_EPOCH, settings.grammarFor(source.id).parserEpoch)
        assertEquals(listOf(grammar), db.grammarSets(source.id))
        assertEquals(before, Triple(db.reviews().allStates(), db.reviews().allLog(), db.reviews().allGrammarResponses()))
        http.headFails = true
        http.archiveFails = true
        assertTrue(sync.sync(source, force = true) is SyncResult.Failed)
        assertEquals(2, db.content().wordScenes(source.id).size)
        org.junit.Assert.assertArrayEquals(sceneWebp(), db.content().imageBytes(source.id, "a.webp"))
    }

    @Test
    fun rejectedSceneInsertionRollsBackTheWholeSnapshotAndDoesNotMarkTheUpgradeComplete() = runBlocking {
        http.archiveBytes = sceneArchive()
        assertTrue(sync.sync(source) is SyncResult.Updated)
        val beforeWords = db.content().words(source.id)
        val beforeScenes = db.content().wordScenes(source.id)
        val beforeMeta = db.content().meta(source.id)
        db.content().insertWords(beforeWords.map { it.copy(sourceId = "other/repo") })
        db.content().insertScenes(beforeScenes.map { it.copy(sourceId = "other/repo") })
        db.content().insertImages(listOf(ImageEntity("other/repo", "a.webp", sceneWebp())))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_scene BEFORE INSERT ON word_scenes BEGIN SELECT RAISE(ABORT, 'injected scene failure'); END",
        )
        settings.setSceneParserEpoch(source.id, 0)
        http.headSha = SHA_B
        http.archiveBytes = sceneArchive("changed", bytes = sceneWebp(blue = true))
        val rejected = sync.sync(source)
        assertTrue(rejected is SyncResult.Failed)
        assertEquals(beforeWords, db.content().words(source.id))
        assertEquals(beforeScenes, db.content().wordScenes(source.id))
        assertEquals(beforeMeta, db.content().meta(source.id))
        org.junit.Assert.assertArrayEquals(sceneWebp(), db.content().imageBytes(source.id, "a.webp"))
        assertEquals(0, settings.sceneParserEpoch(source.id))
        assertEquals(SHA_A, settings.syncedCommit("${source.id}@main"))
        assertEquals(beforeScenes.map { it.copy(sourceId = "other/repo") }, db.content().wordScenes("other/repo"))
    }

    @Test
    fun sceneUpdatesRemoveOldAssetsAndNeverQuizArticleOnlyOrUndecodableImages() = runBlocking {
        http.archiveBytes = sceneArchive(second = true)
        assertTrue(sync.sync(source) is SyncResult.Updated)
        http.archiveBytes = sceneArchive("article-only", eligible = false, bytes = sceneWebp(blue = true))
        assertEquals(SyncResult.Updated(1, 1, emptyList()), sync.sync(source, force = true))
        assertEquals(1, db.content().wordScenes(source.id).size)
        assertNull(db.content().imageBytes(source.id, "b.webp"))
        org.junit.Assert.assertArrayEquals(sceneWebp(blue = true), db.content().imageBytes(source.id, "a.webp"))
        assertEquals("images/a.webp", db.content().word(source.id, "a")!!.image)
        assertNull(db.content().word(source.id, "a")!!.quizImage)
        assertTrue(db.content().observeWordsLite(source.id).first().forDirection(io.github.stopcran.kanji.core.words.WordDirection.Picture).isEmpty())
        val fake = "RIFF".toByteArray() + byteArrayOf(8, 0, 0, 0) + "WEBPxxxx".toByteArray()
        http.archiveBytes = sceneArchive("undecodable", bytes = fake)
        val result = sync.sync(source, force = true) as SyncResult.Updated
        assertTrue(result.problems.any { "Picture" in it })
        assertEquals(1, db.content().words(source.id).size)
        assertNull(db.content().word(source.id, "a")!!.quizImage)
        assertTrue(db.content().wordScenes(source.id).isEmpty())
        assertNull(db.content().imageBytes(source.id, "a.webp"))
    }

    @Test
    fun freshSyncImportsCards() {
        val r = run()
        assertEquals(SyncResult.Updated(1, 0, emptyList()), r)
        assertEquals(1, runBlocking { db.content().kanjiCount(source.id) })
        assertEquals(SHA_A, settings.syncedCommit("${source.id}@main"))
    }

    @Test
    fun syncStoresValidatedGrammarSetsWithTheirContentSnapshot() {
        val grammar = """{"schemaVersion":1,"id":"pilot","title":"Pilot","article":"articles/pilot.md",
            "constructions":[{"id":"alpha","label":"甲"},{"id":"beta","label":"乙"}],
            "relationships":[{"id":"contrast","label":"contrast","constructions":["alpha","beta"]}],
            "exercises":[
              {"id":"alpha-one","target":"alpha","relationship":"contrast","prompt":"A ____.","translation":"A.",
               "jlpt":4,
               "evidence":"Context.","explanation":"Reason.","answer":"alpha","acceptedAnswers":["甲"],
               "options":[{"id":"alpha","label":"甲","rationale":"Fits."},{"id":"beta","label":"乙","rationale":"No."}]},
              {"id":"beta-one","target":"beta","relationship":"contrast","prompt":"B ____.","translation":"B.",
               "evidence":"Context.","explanation":"Reason.","answer":"beta","acceptedAnswers":["乙"],
               "options":[{"id":"alpha","label":"甲","rationale":"No."},{"id":"beta","label":"乙","rationale":"Fits."}]}
            ]}"""
        http.archiveBytes = archive("v2", mapOf("日" to "sun"), grammar = "pilot" to grammar)
        assertEquals(SyncResult.Updated(1, 0, emptyList()), run())
        val stored = runBlocking { db.grammarSets(source.id) }
        assertEquals(listOf("alpha-one", "beta-one"), stored.single().exercises.map { it.id })
        assertEquals(listOf(4, null), stored.single().exercises.map { it.jlpt })
        assertEquals(1, runBlocking { db.content().grammarCount(source.id) })
        val before = runBlocking {
            db.reviews().recordGrammar(
                ReviewStateEntity(source.id, GRAMMAR_STACK, "alpha-one", "Grammar", "Review", 12.5, 5.0, 999, 500, 3, 1),
                ReviewLogEntity(0, source.id, GRAMMAR_STACK, "alpha-one", "Grammar", 3, 500),
                GrammarResponseEntity(0, source.id, GRAMMAR_STACK, "alpha-one", "alpha", "contrast", "type", "甲", true),
            )
            Triple(db.reviews().allStates(), db.reviews().allLog(), db.reviews().allGrammarResponses())
        }
        // Reproduce the old parser's typed cache without changing either upstream version.
        runBlocking {
            db.content().insertGrammar(stored.mapIndexed { index, set ->
                val old = set.copy(exercises = set.exercises.map { it.copy(jlpt = null) })
                GrammarEntity(source.id, set.id, Json.encodeToString(GrammarSet.serializer(), old), index)
            })
        }
        settings.setGrammarParserEpoch(source.id, 0)
        settings.setGrammarNewScope(source.id, GrammarScope.N3)
        settings.setGrammarBrowseScope(source.id, GrammarScope.Unclassified)
        settings.lastCheckMs = System.currentTimeMillis()
        assertEquals(SyncResult.Updated(1, 0, emptyList()), runBlocking { sync.syncOnLaunch(source) })
        assertEquals(2, http.downloads)
        assertEquals(listOf(4, null), runBlocking { db.grammarSets(source.id).single().exercises.map { it.jlpt } })
        assertEquals(GrammarPreferences(GrammarScope.N3, GrammarScope.Unclassified, GRAMMAR_GROUP_EPOCH), settings.grammarFor(source.id))
        assertEquals(before, runBlocking {
            Triple(db.reviews().allStates(), db.reviews().allLog(), db.reviews().allGrammarResponses())
        })
        http.headSha = SHA_B
        http.archiveBytes = archive("v3", mapOf("日" to "sun"), grammar = "pilot" to grammar.replace("Context.", "Updated context."))
        assertEquals(SyncResult.Updated(1, 0, emptyList()), run())
        assertEquals("Updated context.", runBlocking { db.grammarSets(source.id).single().exercises.first().evidence })
        assertEquals(before, runBlocking {
            Triple(db.reviews().allStates(), db.reviews().allLog(), db.reviews().allGrammarResponses())
        })
    }

    @Test
    fun groupEpochRefreshAndRejectedDownloadsPreserveTheLastAcceptedSnapshot() {
        val set = io.github.stopcran.kanji.core.grammarFixture().copy(article = "articles/pilot.md")
        val json = Json.encodeToString(GrammarSet.serializer(), set)
        http.archiveBytes = archive("groups", mapOf("日" to "sun"), grammar = set.id to json)
        assertTrue(run() is SyncResult.Updated)
        runBlocking {
            val old = set.copy(introductionGroups = emptyList())
            db.content().insertGrammar(listOf(GrammarEntity(source.id, set.id, Json.encodeToString(GrammarSet.serializer(), old), 0)))
        }
        settings.setGrammarParserEpoch(source.id, GRAMMAR_LEVEL_EPOCH)
        settings.lastCheckMs = System.currentTimeMillis()
        assertTrue(runBlocking { sync.syncOnLaunch(source) } is SyncResult.Updated)
        assertEquals(2, http.downloads)
        assertEquals(listOf(set), runBlocking { db.grammarSets(source.id) })
        assertEquals(GRAMMAR_GROUP_EPOCH, settings.grammarFor(source.id).parserEpoch)
        val before = runBlocking { db.content().meta(source.id) }
        val broken = set.copy(introductionGroups = set.introductionGroups.map { it.copy(requires = listOf("absent")) })
        http.archiveBytes = archive("broken", mapOf("日" to "sun"), grammar = set.id to Json.encodeToString(GrammarSet.serializer(), broken))
        http.headSha = SHA_B
        assertTrue(run() is SyncResult.Failed)
        assertEquals(before, runBlocking { db.content().meta(source.id) })
        assertEquals(listOf(set), runBlocking { db.grammarSets(source.id) })
        assertNull(before!!.grammarProblem)
    }

    @Test
    fun acceptedPartialSnapshotBlocksGuidedNewMaterialButPreservesAvailableReviews() {
        val good = io.github.stopcran.kanji.core.grammarFixture().copy(article = "articles/pilot.md")
        val bad = io.github.stopcran.kanji.core.grammarFixture("beta", requires = listOf("absent")).copy(article = "articles/pilot.md")
        http.archiveBytes = archive(
            "partial", mapOf("日" to "sun"), grammar = good.id to Json.encodeToString(GrammarSet.serializer(), good),
            additionalGrammar = mapOf(bad.id to Json.encodeToString(GrammarSet.serializer(), bad)),
        )
        assertTrue(run() is SyncResult.Updated)
        runBlocking {
            assertEquals(listOf(good), db.grammarSets(source.id))
            assertTrue(db.content().meta(source.id)!!.grammarProblem!!.contains("beta"))
            db.reviews().recordGrammar(
                ReviewStateEntity(source.id, GRAMMAR_STACK, "alpha-a-0", "Grammar", "Review", 12.5, 5.0, 999, 500, 3, 1),
                ReviewLogEntity(0, source.id, GRAMMAR_STACK, "alpha-a-0", "Grammar", 3, 500),
                GrammarResponseEntity(0, source.id, GRAMMAR_STACK, "alpha-a-0", "a", "contrast", "choose", "a", true),
            )
            db.changeGrammarGuided(settings, source.id, true)
            val study = db.grammarStudy(settings, source.id, java.time.Instant.now(), reserve = true)
            assertEquals(listOf("alpha-a-0"), study.ordinaryIds)
            assertEquals(6, study.extraIds.size)
            assertTrue(study.notice!!.contains("content errors"))
            assertNull(study.ticket)
            assertEquals(1, db.reviews().allGrammarResponses().size)
            db.changeGrammarGuided(settings, source.id, false)
            assertEquals(6, db.grammarStudy(settings, source.id, java.time.Instant.now()).ordinaryIds.size)
        }
    }

    @Test
    fun unchangedHeadSkipsTheDownload() {
        run()
        val r = run()
        assertEquals(SyncResult.UpToDate, r)
        assertEquals(1, http.downloads)
    }

    @Test
    fun notModifiedReusesTheCachedHeadAndSendsTheEtag() {
        run()
        http.headCode = 304
        http.headSha = null
        assertEquals(SyncResult.UpToDate, run())
        assertEquals(1, http.downloads)
        assertEquals("\"etag-1\"", http.requests.last { it.first.startsWith("https://api") }.second["If-None-Match"])
    }

    @Test
    fun movedHeadWithSameContentVersionOnlyRecordsTheCommit() {
        run()
        http.headSha = SHA_B
        http.headEtag = "\"etag-2\""
        assertEquals(SyncResult.UpToDate, run())
        assertEquals(SHA_B, settings.syncedCommit("${source.id}@main"))
    }

    @Test
    fun rateLimitedHeadFallsBackToTheArchiveOnlyOncePerInterval() {
        run()
        http.headCode = 403
        val r1 = run()
        assertEquals(SyncResult.UpToDate, r1)
        assertEquals(2, http.downloads)
        assertTrue(http.requests.last().first.endsWith("/refs/heads/main"))
        val r2 = run()
        assertEquals(SyncResult.Failed("Could not check for updates", retryable = false), r2)
        assertEquals(2, http.downloads)
    }

    @Test
    fun knownHeadDownloadsTheArchivePinnedToThatCommit() {
        run()
        assertTrue(http.requests.last().first.endsWith("/zip/$SHA_A"))
    }

    @Test
    fun offlineHeadWithNoContentStillDownloads() {
        http.headFails = true
        assertTrue(run() is SyncResult.Updated)
    }

    @Test
    fun forceBypassesTheHeadShortCut() {
        run()
        run(force = true)
        assertEquals(2, http.downloads)
    }

    @Test
    fun missingRepositoryIsAPermanentFailure() {
        http.archiveCode = 404
        val r = run() as SyncResult.Failed
        assertEquals(false, r.retryable)
        assertTrue(r.message, r.message.contains("not found"))
    }

    @Test
    fun serverErrorIsRetryable() {
        http.archiveCode = 503
        val r = run() as SyncResult.Failed
        assertEquals(true, r.retryable)
        assertEquals("HTTP 503", r.message)
    }

    @Test
    fun networkFailureIsRetryable() {
        http.archiveFails = true
        val r = run() as SyncResult.Failed
        assertEquals(true, r.retryable)
    }

    @Test
    fun garbageArchiveIsAPermanentFailureNotACrash() {
        http.archiveBytes = "not a zip".toByteArray()
        val r = run() as SyncResult.Failed
        assertEquals(false, r.retryable)
    }

    @Test
    fun archiveWithoutValidCardsIsRejected() {
        http.archiveBytes = archive("v1", mapOf("日" to "sun"), corrupt = setOf("日"))
        val r = run() as SyncResult.Failed
        assertEquals(false, r.retryable)
        assertTrue(r.message, r.message.startsWith("No valid cards"))
    }

    @Test
    fun updateThatLosesCardsKeepsThePreviousContent() {
        http.archiveBytes = archive("v1", mapOf("日" to "sun", "月" to "moon"))
        run()
        http.archiveBytes = archive("v2", mapOf("日" to "sun", "月" to "moon"), corrupt = setOf("月"))
        http.headSha = SHA_B
        val r = run() as SyncResult.Failed
        assertEquals(true, r.retryable)
        assertEquals(2, runBlocking { db.content().kanjiCount(source.id) })
    }

    @Test
    fun pendingParserUpgradeDoesNotBypassBadContentOrOfflineFallbackSafeguards() {
        http.archiveBytes = archive("v1", mapOf("日" to "sun", "月" to "moon"))
        assertTrue(run() is SyncResult.Updated)
        settings.setGrammarParserEpoch(source.id, 0)
        settings.setGrammarNewScope(source.id, GrammarScope.Unclassified)
        val before = runBlocking { db.content().kanji(source.id) }
        val preferences = settings.grammarFor(source.id)
        val lastCheck = System.currentTimeMillis()
        settings.lastCheckMs = lastCheck
        http.archiveBytes = archive("v1", mapOf("日" to "sun", "月" to "moon"), corrupt = setOf("月"))
        val rejected = runBlocking { sync.syncOnLaunch(source) } as SyncResult.Failed
        assertTrue(rejected.message, rejected.message.contains("keeping previous cards"))
        assertTrue(rejected.retryable)
        assertEquals(preferences, settings.grammarFor(source.id))
        assertEquals(lastCheck, settings.lastCheckMs)
        assertEquals(before, runBlocking { db.content().kanji(source.id) })

        http.headFails = true
        http.archiveFails = true
        assertTrue(runBlocking { sync.syncOnLaunch(source) } is SyncResult.Failed)
        val downloads = http.downloads
        assertTrue(runBlocking { sync.syncOnLaunch(source) } is SyncResult.Failed)
        assertEquals(downloads, http.downloads)
        assertEquals(preferences, settings.grammarFor(source.id))
        assertEquals(lastCheck, settings.lastCheckMs)
        assertEquals(before, runBlocking { db.content().kanji(source.id) })
    }

    @Test
    fun newerContentReplacesOldCards() {
        run()
        http.archiveBytes = archive("v2", mapOf("日" to "sun, day"))
        http.headSha = SHA_B
        assertTrue(run() is SyncResult.Updated)
        assertEquals("sun, day", runBlocking { db.content().kanjiCard(source.id, "日")?.title })
    }

    @Test
    fun launchCheckIsThrottledAndFailuresDoNotConsumeTheInterval() {
        settings.lastCheckMs = 0
        http.archiveCode = 503
        assertTrue(runBlocking { sync.syncOnLaunch(source) } is SyncResult.Failed)
        assertEquals(0L, settings.lastCheckMs)
        http.archiveCode = 200
        assertTrue(runBlocking { sync.syncOnLaunch(source) } is SyncResult.Updated)
        val before = http.requests.size
        assertEquals(SyncResult.UpToDate, runBlocking { sync.syncOnLaunch(source) })
        assertEquals(before, http.requests.size)
    }
}
