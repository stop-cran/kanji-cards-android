package io.github.stopcran.kanji.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.ListeningTaskContent
import io.github.stopcran.kanji.core.listening.ListeningTaskRecord
import io.github.stopcran.kanji.data.AndroidListeningPlayer
import io.github.stopcran.kanji.data.ListeningPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** Private unapproved bundles use the real host/player without creating an approval or learner data. */
class ListeningTaskAuthoringTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun unapprovedBundlesPlayInNativeStudyWithoutGrades() {
        val model = mutableStateOf<ListeningTaskViewModel?>(null)
        val dark = mutableStateOf(false)
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            it.enableEdgeToEdge()
        }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = if (dark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                KanjiTheme { key(model.value) {
                    model.value?.let { ListeningTaskEncounterView(it, {}) { _, _ -> } } ?: Text("Author preview fixture")
                } }
            }
        }
        ListeningUiFixture().use { fixture ->
            val bundles = bundles(fixture)
            for (bundle in bundles) {
                val player = StartedAudio(AndroidListeningPlayer(fixture.context))
                val vm = fixture.authorPreview(bundle.record, bundle.assets, bundle.words, player)
                rule.runOnIdle { model.value = vm }
                try {
                    rule.waitUntil(10_000) { vm.ready && !vm.busy || vm.error != null }
                    assertNull(vm.error)
                    assertNull(vm.task?.approval)
                    assertEquals("study", vm.encounter?.kind)
                    rule.onNodeWithText("Check answer").assertDoesNotExist()
                    listOf(false, true).forEach { night ->
                        rule.runOnIdle { dark.value = night }
                        rule.waitForIdle()
                        if (bundle.record.task.visual != null) rule.onNodeWithTag("listening-task-picture").assertIsDisplayed()
                        screenshot("${bundle.record.task.id}-${if (night) "dark" else "light"}")
                    }
                    rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                    val stages = bundle.record.task.stages
                    rule.waitUntil(requireNotNull(bundle.record.durationMs) + 30_000) {
                        vm.error != null || vm.attempt?.completedStages?.size == stages.size && !vm.busy
                    }
                    assertNull(vm.error)
                    val expected = stages.map { requireNotNull(bundle.record.task.media).audio.getValue(it.id).sha256 }
                    assertEquals(expected, player.hashes)
                    assertNull(vm.response)
                    assertFalse(requireNotNull(vm.encounter).newReservation)
                    assertFalse(requireNotNull(vm.encounter).introduced)
                    assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
                    report(bundle.record, player.hashes)
                } finally {
                    var closed = false
                    rule.runOnIdle { vm.close(false) { closed = true } }
                    rule.waitUntil(10_000) { closed && !vm.busy }
                    rule.runOnIdle { model.value = null }
                    rule.waitForIdle()
                }
            }
        }
    }

    private data class Bundle(val record: ListeningTaskRecord, val assets: Map<String, ByteArray>, val words: Map<String, String>)

    private fun bundles(fixture: ListeningUiFixture): List<Bundle> {
        val id = InstrumentationRegistry.getArguments().getString("authorTask")
        if (id == null) return fixture.records.map { record ->
            val raw = Json.parseToJsonElement(record.json).jsonObject
            val draft = ListeningTaskContent.parse("listening-tasks/${record.task.id}.json",
                JsonObject(raw.filterKeys { it != "approval" }).toString())
            Bundle(draft, fixture.assets, mapOf("窓" to "# Captured window article"))
        }
        require(id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Invalid authorTask ID" }
        val root = File(requireNotNull(fixture.context.getExternalFilesDir(null)), "listening-author-preview")
        val record = ListeningTaskContent.parse("listening-tasks/$id.json",
            read(root, "packages/$id.json").decodeToString(throwOnInvalidSequence = true))
        require(!record.ready && record.task.approval == null) { "Author input must stay unapproved" }
        val media = requireNotNull(record.task.media) { "Author input needs complete generated media" }
        val paths = media.audio.values.associate { it.sha256 to it.path }.toMutableMap()
        media.image?.let { paths[it.sha256] = it.path }
        val assets = paths.mapValues { (hash, path) ->
            read(root, path).also { require(ContentParser.rawSha256(it) == hash) { "Author asset hash mismatch" } }
        }
        val words = record.task.vocabulary.mapNotNull { it.word }.associateWith {
            read(root, "words/$it.md").decodeToString(throwOnInvalidSequence = true)
        }
        return listOf(Bundle(record, assets, words))
    }

    private fun read(root: File, relative: String): ByteArray {
        val file = File(root, relative)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator) && file.isFile) { "Missing or unsafe author file" }
        val bytes = ByteArray(512 * 1024 + 1)
        val size = file.inputStream().use { stream ->
            var count = 0
            while (count < bytes.size) {
                val read = stream.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            count
        }
        require(size <= 512 * 1024) { "Author file exceeds 512 KiB" }
        return bytes.copyOf(size)
    }

    private fun screenshot(name: String) {
        val file = File(requireNotNull(rule.activity.getExternalFilesDir(null)), "E-author-$name.png")
        file.outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) {
                "Author screenshot encoding failed"
            }
        }
    }

    private fun report(record: ListeningTaskRecord, hashes: List<String>) {
        val report = buildJsonObject {
            put("taskId", record.task.id)
            put("contentSha256", record.contentHash)
            put("mediaSha256", record.mediaHash)
            put("nativeStartedAudioSha256", JsonArray(hashes.map(::JsonPrimitive)))
            put("unapproved", true)
            put("reviewCount", 0)
            put("actualListeningApproval", false)
        }
        File(requireNotNull(rule.activity.getExternalFilesDir(null)), "E-author-${record.task.id}.json").writeText(report.toString())
    }

    private class StartedAudio(private val native: ListeningPlayer) : ListeningPlayer by native {
        private var prepared = ""
        val hashes = mutableListOf<String>()
        override suspend fun prepare(bytes: ByteArray) {
            prepared = ContentParser.rawSha256(bytes)
            native.prepare(bytes)
        }
        override suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit, interrupted: () -> Unit,
            failed: (IOException) -> Unit) {
            native.play(positionMs, speed, completed, interrupted, failed)
            hashes += prepared
        }
    }
}
