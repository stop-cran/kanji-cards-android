package io.github.stopcran.kanji.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.data.AndroidListeningPlayer
import io.github.stopcran.kanji.data.ListeningPlayer
import io.github.stopcran.kanji.data.ListeningTaskAssetEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

class ListeningSpokenChoiceFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun foreground() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @Test fun verbalExpressionKeepsSceneAndSpokenNumbersAligned() = spokenFlow(ListeningTaskType.VerbalExpression)

    @Test fun quickResponseKeepsUtteranceAndChoicesAuditory() = spokenFlow(ListeningTaskType.QuickResponse)

    private fun spokenFlow(type: ListeningTaskType) {
        ListeningUiFixture().use { fixture ->
            val record = fixture.records.single { it.task.taskType == type }
            val task = record.task
            val audio = requireNotNull(task.media).audio
            val secondChoice = audio.getValue(task.stages[3].id).sha256
            val player = RecordingPlayer(fixture.context, secondChoice)
            val id = fixture.start(type)
            val vm = ListeningTaskViewModel(fixture.services, id, player)
            val returned = mutableStateOf(false)
            val restoration = StateRestorationTester(rule)
            restoration.setContent { KanjiTheme {
                if (returned.value) Text("Returned to Listening")
                else ListeningTaskEncounterView(vm, { returned.value = true }) { _, _ -> }
            } }
            try {
                awaitReady(vm)
                rule.onNodeWithText("Stage 1/5: Instructions").performScrollTo().assertIsDisplayed()
                assertHidden(task)
                if (type == ListeningTaskType.VerbalExpression) {
                    rule.onNodeWithTag("listening-task-picture").assertIsDisplayed().assertContentDescriptionEquals(
                        "Essential scene picture. Described study is available in Listening.")
                } else {
                    rule.onNodeWithTag("listening-task-picture").assertDoesNotExist()
                    assertNull(runBlocking { fixture.db.listeningTasks().encounter(id)?.exposedAtMs })
                }
                screenshot("D-${type.wire}-ready.png")
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(15_000) { player.held && vm.attempt?.stageIndex == 3 }
                rule.onNodeWithText("Stage 4/5: Spoken choice 2").performScrollTo().assertIsDisplayed()
                if (type == ListeningTaskType.VerbalExpression) rule.onNodeWithTag("listening-task-picture").assertIsDisplayed()
                rule.onNodeWithText("Check answer").assertDoesNotExist()
                assertHidden(task)
                restoration.emulateSavedInstanceStateRestore()
                awaitReady(vm)
                assertEquals(task.options, vm.task?.options)
                assertEquals(3, vm.attempt?.stageIndex)
                assertTrue(requireNotNull(vm.attempt).assistance.isEmpty())
                player.release.complete(Unit)
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(15_000) { vm.attempt?.phase == "answer" && !vm.busy }
                val expected = task.stages.map { audio.getValue(it.id).sha256 }
                assertEquals(expected, player.startedHashes)
                assertEquals(3, expected.drop(2).distinct().size)
                if (type == ListeningTaskType.QuickResponse) {
                    rule.onNodeWithText("Replay whole task").performScrollTo().performClick()
                    rule.waitUntil(15_000) { player.startedHashes.size == 9 && !vm.busy }
                    assertEquals(expected + expected.drop(1), player.startedHashes)
                }
                assertHidden(task)
                val correct = task.options.single { it.id == task.answer }
                rule.onNodeWithText(correct.number.toString()).performScrollTo().performClick()
                rule.waitUntil(5_000) { !vm.busy }
                rule.onNodeWithText("Check answer").performScrollTo().performClick()
                rule.waitUntil(10_000) { vm.response != null && !vm.busy }
                rule.onNodeWithText("Done").assertIsDisplayed()
                assertEquals(task.answer, vm.response?.answer)
                assertEquals(Grade.Good.value, vm.response?.grade)
                rule.onNodeWithText("Correct - Good").assertIsDisplayed()
                rule.onNodeWithText("Correct: ${correct.number}. ${correct.label}").performScrollTo().assertIsDisplayed()
                task.options.forEach { rule.onNodeWithText(it.rationale).assertExists() }
                screenshot("D-${type.wire}-result.png")
                rule.onNodeWithText("Done").assertIsDisplayed().performClick()
                rule.waitUntil(5_000) { returned.value && !vm.busy }
                assertEquals(1, runBlocking { fixture.db.reviews().allLog().size })
            } finally { finish(vm) }
        }
    }

    @Test fun missingChoiceAudioCannotGradeAndRepairResumesOnlyTheUnheardChoices() {
        ListeningUiFixture().use { fixture ->
            val task = fixture.records.single { it.task.taskType == ListeningTaskType.QuickResponse }.task
            val id = fixture.start(task.taskType)
            val hash = requireNotNull(task.media).audio.getValue(task.stages[3].id).sha256
            runBlocking { fixture.db.listeningTasks().putAssets(listOf(ListeningTaskAssetEntity(fixture.source, hash, byteArrayOf(0)))) }
            val player = RecordingPlayer(fixture.context)
            val vm = ListeningTaskViewModel(fixture.services, id, player)
            rule.setContent { KanjiTheme { ListeningTaskEncounterView(vm, {}) { _, _ -> } } }
            try {
                awaitReady(vm)
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(15_000) { vm.error != null && !vm.busy }
                assertEquals(task.stages.take(3).map { it.id }.toSet(), vm.attempt?.completedStages)
                assertEquals(setOf(task.stages[3].id), vm.attempt?.failedStages)
                rule.onNodeWithText("Check answer").assertDoesNotExist()
                rule.onNodeWithText("I cannot follow this (Again)").assertDoesNotExist()
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
                runBlocking { fixture.db.listeningTasks().putAssets(listOf(
                    ListeningTaskAssetEntity(fixture.source, hash, fixture.assets.getValue(hash)))) }
                rule.onNodeWithText("Retry captured evidence").performScrollTo().performClick()
                awaitReady(vm)
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(15_000) { vm.attempt?.phase == "answer" && !vm.busy }
                assertEquals(task.stages.map { requireNotNull(task.media).audio.getValue(it.id).sha256 }, player.startedHashes)
                assertTrue(requireNotNull(vm.attempt).assistance.isEmpty())
                rule.onNodeWithText("3").performScrollTo().performClick()
                rule.waitUntil(5_000) { !vm.busy }
                rule.onNodeWithText("Check answer").performScrollTo().performClick()
                rule.waitUntil(10_000) { vm.response != null && !vm.busy }
                assertEquals(Grade.Again.value, vm.response?.grade)
                assertEquals(1, runBlocking { fixture.db.reviews().allLog().size })
            } finally { finish(vm) }
        }
    }

    private fun assertHidden(task: ListeningTaskPackage) {
        task.options.forEach {
            rule.onAllNodesWithText(it.label, substring = true).assertCountEquals(0)
            rule.onAllNodesWithContentDescription(it.label, substring = true).assertCountEquals(0)
            rule.onNodeWithText(it.rationale).assertDoesNotExist()
        }
        task.stages.drop(1).flatMap { it.turns }.distinct().forEach { id ->
            val turn = task.turns.single { it.id == id }
            rule.onNodeWithText(turn.text).assertDoesNotExist()
            rule.onNodeWithText(turn.translation).assertDoesNotExist()
        }
        task.visual?.let { rule.onNodeWithText(it.description).assertDoesNotExist() }
        rule.onNodeWithText(task.explanation).assertDoesNotExist()
    }

    private fun awaitReady(vm: ListeningTaskViewModel) {
        rule.waitUntil(10_000) { vm.ready && !vm.busy || vm.error != null }
        assertNull(vm.error)
        rule.waitForIdle()
    }

    private fun finish(vm: ListeningTaskViewModel) {
        rule.runOnIdle { vm.pause(release = true) }
        rule.waitUntil(10_000) { !vm.busy }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        rule.waitForIdle()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    /** Distinct 1/2/3-pulse assets test audible order, not Japanese pronunciation approval. */
    private class RecordingPlayer(
        context: Context, private val holdHash: String? = null, private val native: ListeningPlayer = AndroidListeningPlayer(context),
    ) : ListeningPlayer by native {
        val release = CompletableDeferred<Unit>()
        var held = false
        val startedHashes = mutableListOf<String>()
        private var preparedHash = ""
        override suspend fun prepare(bytes: ByteArray) {
            preparedHash = ContentParser.rawSha256(bytes)
            if (preparedHash == holdHash && !release.isCompleted) { held = true; release.await() }
            native.prepare(bytes)
        }
        override suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit,
            interrupted: () -> Unit, failed: (IOException) -> Unit) {
            native.play(positionMs, speed, completed, interrupted, failed)
            startedHashes += preparedHash
        }
    }
}
