package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.RepoSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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
private fun archive(version: String, cards: Map<String, String>, corrupt: Set<String> = emptySet()): ByteArray {
    val files = cards.entries.associate { (k, title) ->
        "kanji/$k.md" to (if (k in corrupt) "no front matter here" else kanjiMd(k, title)).toByteArray()
    }
    val entries = files.entries.joinToString(",") { (p, b) -> "\"$p\":\"${ContentParser.sha256(b)}\"" }
    val manifest = """{"schemaVersion":1,"contentVersion":"$version","files":{$entries}}""".toByteArray()
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

    @Test
    fun freshSyncImportsCards() {
        val r = run()
        assertEquals(SyncResult.Updated(1, 0, emptyList()), r)
        assertEquals(1, runBlocking { db.content().kanjiCount(source.id) })
        assertEquals(SHA_A, settings.syncedCommit("${source.id}@main"))
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
    fun rateLimitedHeadKeepsExistingContentWithoutDownloading() {
        run()
        http.headCode = 403
        val r = run()
        assertEquals(SyncResult.Failed("Could not check for updates", retryable = true), r)
        assertEquals(1, http.downloads)
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
