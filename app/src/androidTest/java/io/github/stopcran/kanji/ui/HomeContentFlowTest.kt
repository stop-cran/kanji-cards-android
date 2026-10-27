package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentHttp
import io.github.stopcran.kanji.data.ContentResponse
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.WordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HomeContentFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun firstImportDoesNotLookEmptyAndReturningFromKanaUsesTheCompletedImport() {
        Fixture().use { f ->
            val gate = f.http.hold(200)
            val job = f.start()
            f.render(rule)
            try {
                assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
                rule.waitUntil(10_000) { f.state.value?.ready == false }
                rule.onNodeWithText("Kana is available").assertIsDisplayed()
                rule.onAllNodesWithText("Loading content…").assertCountEquals(2)
                rule.onNodeWithText("No exercises yet").assertDoesNotExist()
                rule.runOnIdle { f.home.value = false }
                rule.onNodeWithText("Kana practice").assertIsDisplayed()
                gate.release.countDown()
                runBlocking { job.join() }
                rule.runOnIdle { f.home.value = true }
                rule.waitUntil(10_000) { f.state.value?.usable == true }
                rule.onNodeWithText("No exercises yet").assertIsDisplayed()
                rule.onNodeWithText("Cards ready").assertIsDisplayed()
                rule.onNodeWithText("Getting your content… Kana is ready to try.").assertDoesNotExist()
            } finally { f.hide(rule) }
        }
    }

    @Test fun failureRetryMetadataLessRefreshSourceSwitchAndCancellationKeepHonestSummaries() {
        Fixture().use { f ->
            val gate = f.http.hold(503)
            val job = f.start()
            f.render(rule)
            try {
                assertTrue(gate.entered.await(10, TimeUnit.SECONDS))
                rule.runOnIdle { f.home.value = false }
                gate.release.countDown()
                runBlocking { job.join() }
                rule.runOnIdle { f.home.value = true }
                rule.waitUntil(10_000) { f.state.value?.let { it.failed && it.ready == false } == true }
                rule.onNodeWithText("Couldn’t download content. Kana is still available.").assertIsDisplayed()
                rule.onAllNodesWithText("Content unavailable").assertCountEquals(2)
                rule.onNodeWithText("No exercises yet").assertDoesNotExist()
                f.http.allow()
                rule.onNodeWithText("Retry").performClick()
                rule.waitUntil(10_000) { f.state.value?.usable == true }

                val cached = RepoSource("fixture", "cached", "main")
                runBlocking {
                    f.db.content().insertWords(listOf(WordEntity(cached.id, "語", "ご", "word", null, "語", "", "body")))
                }
                rule.runOnIdle { f.source.value = cached }
                rule.waitUntil(10_000) { f.state.value?.usable == true && f.observedSource == cached }
                val refresh = f.http.hold(503)
                val refreshJob = f.start()
                assertTrue(refresh.entered.await(10, TimeUnit.SECONDS))
                rule.onNodeWithText("Cards ready").assertIsDisplayed()
                rule.onNodeWithText("No exercises yet").assertIsDisplayed()
                refresh.release.countDown()
                runBlocking { refreshJob.join() }
                rule.waitUntil(10_000) { f.state.value?.let { it.usable && it.failed } == true }
                rule.onNodeWithText("Couldn’t update content. Saved content is still available.").assertIsDisplayed()
                rule.onNodeWithText("Cards ready").assertIsDisplayed()

                val empty = RepoSource("fixture", "empty", "main")
                rule.runOnIdle { f.source.value = empty }
                rule.waitUntil(10_000) { f.observedSource == empty && f.state.value?.ready == false }
                rule.runOnIdle { assertFalse(f.frames.any { it.first == empty && it.second.usable }) }
                rule.onNodeWithText("No exercises yet").assertDoesNotExist()
                rule.onNodeWithText("Couldn’t update content. Saved content is still available.").assertDoesNotExist()
                val cancelled = f.http.hold(200)
                val cancelledJob = f.start()
                assertTrue(cancelled.entered.await(10, TimeUnit.SECONDS))
                cancelledJob.cancel()
                cancelled.release.countDown()
                runBlocking { cancelledJob.join() }
                rule.waitUntil(10_000) { f.state.value?.interrupted == true }
                rule.onNodeWithText("Download interrupted. Kana is still available.").assertIsDisplayed()
                rule.onNodeWithText("Retry").assertIsDisplayed()
            } finally { f.hide(rule) }
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferences = "home-content-${UUID.randomUUID()}"
        private val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val http = GateHttp()
        private val sync = ContentSync(db, Settings(isolated), http)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val jobs = mutableListOf<Job>()
        val source = mutableStateOf(RepoSource("fixture", "first", "main"))
        val home = mutableStateOf(true)
        val state = mutableStateOf<HomeContentState?>(null)
        var observedSource: RepoSource? = null
        val frames = mutableListOf<Pair<RepoSource, HomeContentState>>()

        fun start(): Job {
            val selected = source.value
            return scope.launch { sync.sync(selected, force = true) }.also { jobs += it }
        }

        fun render(rule: ComposeContentTestRule) {
            rule.setContent {
                MaterialTheme {
                    if (home.value) {
                        val selected = source.value
                        val current = rememberHomeContent(db, sync, selected)
                        SideEffect { state.value = current; observedSource = selected; frames += selected to current }
                        Page("Home content fixture") {
                            HomeContentNotice(current) { start() }
                            Text("Kana is available")
                            Text(current.summary("Cards ready"))
                            Text(current.summary("No exercises yet"))
                        }
                    } else Text("Kana practice")
                }
            }
        }

        fun hide(rule: ComposeContentTestRule) { rule.runOnIdle { home.value = false }; rule.waitForIdle() }

        override fun close() {
            scope.cancel()
            http.allow()
            runBlocking { jobs.forEach { it.join() } }
            db.close()
            context.deleteSharedPreferences(preferences)
        }
    }

    private class Gate(val code: Int, held: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (held) 1 else 0)
    }

    private class GateHttp : ContentHttp {
        @Volatile private var gate = Gate(200, false)
        fun hold(code: Int): Gate = Gate(code, true).also { gate = it }
        fun allow() { gate.release.countDown(); gate = Gate(200, false) }

        override fun open(url: String, headers: Map<String, String>, connectTimeoutMs: Int, readTimeoutMs: Int): ContentResponse {
            val head = url.startsWith("https://api.github.com")
            val selected = gate
            if (!head) { selected.entered.countDown(); check(selected.release.await(10, TimeUnit.SECONDS)) }
            val bytes = if (head) "a".repeat(40).toByteArray() else archive()
            return object : ContentResponse {
                override val code = if (head) 200 else selected.code
                override val body = ByteArrayInputStream(bytes)
                override fun header(name: String): String? = null
                override fun close() {}
            }
        }
    }
}

private fun archive(): ByteArray {
    val card = "---\nkanji: 日\ntitle: sun\njlpt: 5\ntags: [jlpt-n5]\nstrokes: 4\nonyomi: [ニチ]\nkunyomi: [ひ]\ndistractors: []\n---\n\n# 日\n".toByteArray()
    val manifest = Json.encodeToString(Manifest.serializer(), Manifest(1, "fixture", mapOf("kanji/日.md" to ContentParser.sha256(card))))
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        for ((path, bytes) in mapOf("kanji/日.md" to card, "manifest.json" to manifest.toByteArray())) {
            zip.putNextEntry(ZipEntry("fixture-main/$path")); zip.write(bytes); zip.closeEntry()
        }
    }
    return output.toByteArray()
}
