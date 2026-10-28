package io.github.stopcran.kanji.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class ListeningAssessmentFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun foreground() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; it.enableEdgeToEdge() }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
    }

    @Test fun unavailablePoolNeverOffersAFalseFullSection() {
        ListeningUiFixture().use { fixture ->
            val vm = ListeningAssessmentPreviewModel(fixture.services)
            val returned = mutableStateOf(false)
            rule.setContent { KanjiTheme {
                if (returned.value) Text("Returned") else ListeningAssessmentPreviewView(vm, { returned.value = true }) { error("Unavailable") }
            } }
            try {
                rule.waitUntil(10_000) { !vm.busy && vm.preview != null }
                rule.onNodeWithText("Capture this section").assertDoesNotExist()
                rule.onNodeWithText("A full section is unavailable. No shorter exam or repeated story will be substituted.")
                    .performScrollTo().assertIsDisplayed()
                assertTrue(runBlocking { fixture.db.listeningTasks().allAssessments().isEmpty() })
                assertTrue(runBlocking { fixture.db.listeningTasks().allExposures().isEmpty() })
                screenshot("F-unavailable.png")
                rule.onNodeWithText("N4").performScrollTo().performClick()
                rule.waitUntil(10_000) { !vm.busy && vm.preview?.profile == ListeningAssessmentProfile.N4 }
                rule.onNodeWithText("Capture this section").assertDoesNotExist()
                assertTrue(vm.preview!!.selection.items.isEmpty())
                screenshot("N4-C-unavailable.png")
            } finally { rule.runOnIdle { returned.value = true; vm.leave() }; rule.waitForIdle() }
        }
    }

    @Test fun fullFixtureHostKeepsSpokenChoicesHiddenAndMatchesExactSimulatedTimeline() =
        fullFixtureTimeline(ListeningAssessmentProfile.N5)

    @Test fun androidBackClosesPartialResultAfterAsynchronousLoad() {
        ListeningUiFixture().use { fixture ->
            fixture.assessmentPool()
            val id = runBlocking {
                fixture.services.listeningAssessments.start(fixture.services.listeningAssessments.preview(42))
            }
            val vm = ListeningAssessmentViewModel(fixture.services, id)
            val returned = mutableStateOf(false)
            rule.setContent { KanjiTheme {
                if (returned.value) Text("Returned") else ListeningAssessmentView(vm, { returned.value = true }) { _, _ -> }
            } }
            try {
                ready(vm)
                rule.onNodeWithText("End with partial results").performScrollTo().performClick()
                rule.onNodeWithText("End section").performClick()
                rule.waitUntil(10_000) { vm.snapshot?.result != null && !vm.busy }
                rule.onNodeWithText("Partial / interrupted diagnostic").assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                rule.waitUntil(5_000) { returned.value && !vm.busy }
                assertNotNull(runBlocking { fixture.db.listeningTasks().assessment(id)?.closedAtMs })
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
            } finally { finish(vm) { returned.value = true } }
        }
    }

    @Test fun n4FullFixtureUsesCapturedTwentyEightItemTotalsAndExactSimulatedTimeline() =
        fullFixtureTimeline(ListeningAssessmentProfile.N4)

    private fun fullFixtureTimeline(profile: ListeningAssessmentProfile) {
        ListeningUiFixture().use { fixture ->
            fixture.assessmentPool(profile)
            val elapsed = AtomicLong(1_000)
            val study = ListeningAssessmentStudy(fixture.db, fixture.services.settings, fixture.services.listeningTasks, elapsed = elapsed::get)
            val preview = runBlocking { study.preview(42, profile = profile) }
            val id = runBlocking { study.start(preview) }
            val player = InstantFixturePlayer(elapsed)
            val vm = ListeningAssessmentViewModel(fixture.services, id, player, study)
            val returned = mutableStateOf(false)
            rule.setContent { KanjiTheme {
                if (returned.value) Text("Returned") else ListeningAssessmentView(vm, { returned.value = true }) { _, _ -> }
            } }
            try {
                ready(vm)
                rule.onNodeWithText("Begin section").performScrollTo().performClick()
                repeat(profile.itemCount) { index ->
                    rule.waitUntil(15_000) {
                        val current = vm.snapshot
                        current?.state?.index == index && current.state.phase in setOf(AssessmentPhase.Preview, AssessmentPhase.Answer)
                    }
                    if (vm.snapshot!!.state.phase == AssessmentPhase.Preview) {
                        ready(vm)
                        rule.waitUntil(5_000) { vm.snapshot!!.state.deadlineMs != null }
                        expire(study, id, vm, elapsed)
                    }
                    rule.waitUntil(10_000) { vm.snapshot!!.state.phase == AssessmentPhase.Answer && !vm.busy }
                    val task = vm.snapshot!!.record.task
                    task.turns.filterNot { it.id in task.stages.first().turns }.forEach {
                        rule.onNodeWithText(it.text).assertDoesNotExist()
                    }
                    if (task.choiceMode == TaskChoiceMode.Audio) task.options.forEach {
                        rule.onNodeWithText(it.label).assertDoesNotExist()
                    }
                    val option = task.options.single { it.id == task.answer }
                    val label = if (task.choiceMode == TaskChoiceMode.Text) "${option.number}. ${option.label}" else option.number.toString()
                    rule.onNodeWithText(label).performScrollTo().performClick()
                    rule.waitUntil(5_000) { !vm.busy }
                    expire(study, id, vm, elapsed)
                    if (task.usage == TaskUsage.Example) {
                        rule.onNodeWithText(task.explanation).performScrollTo().assertIsDisplayed()
                        rule.waitUntil(5_000) { vm.snapshot!!.state.deadlineMs != null }
                        expire(study, id, vm, elapsed)
                    }
                }
                rule.waitUntil(10_000) { vm.snapshot!!.result != null }
                assertEquals(profile.scoredCount, vm.snapshot!!.result!!.correct)
                assertEquals(profile.jlpt, vm.snapshot!!.result!!.jlpt)
                assertTrue(vm.snapshot!!.result!!.completedFullForm)
                assertEquals(preview.selection.durationMs, elapsed.get() - 1_000)
                val expected = preview.selection.items.flatMap { candidate ->
                    val task = candidate.record.task
                    (if (task.usage == TaskUsage.Example) task.stages else task.stages.drop(1))
                        .map { requireNotNull(task.media).audio.getValue(it.id).sha256 }
                }
                assertEquals(expected, player.hashes)
                rule.onNodeWithText("${profile.scoredCount} / ${profile.scoredCount} correct").performScrollTo().assertIsDisplayed()
                val result = requireNotNull(vm.snapshot!!.result)
                val firstScored = result.responses.indexOfFirst { !it.example }
                val response = result.responses[firstScored]
                val label = "Scored item 1/${profile.scoredCount} - ${response.type.label}"
                rule.onNodeWithText("$label: Correct").performScrollTo().assertIsDisplayed()
                rule.onAllNodesWithText("Feedback and captured transcript")[firstScored].performScrollTo().performClick()
                rule.waitUntil(10_000) { !vm.busy && vm.detail?.id == response.encounterId }
                ready(vm)
                rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("Back to results").performScrollTo().performClick()
                ready(vm)
                assertNull(vm.detail)
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
                screenshot("N${profile.jlpt}-simulated-full-result.png")
                File(fixture.context.filesDir, "N${profile.jlpt}-simulated-full-result.json").writeText(
                    """{"profile":"${profile.id}","fixturePlayback":true,"actualJapaneseListeningApproval":false,"nativeFullSection":false,"durationMs":${elapsed.get() - 1_000}}""")
            } finally { finish(vm) { returned.value = true } }
        }
    }

    @Test fun actualNativeInstructionAndSilentPreviewFocusOrBackgroundLossNeverAutoResume() =
        nativeInterruption(ListeningAssessmentProfile.N5)

    @Test fun n4NativeInstructionAndSilentWindowInterruptionsRetainTheCapturedProfile() =
        nativeInterruption(ListeningAssessmentProfile.N4)

    private fun nativeInterruption(profile: ListeningAssessmentProfile) {
        ListeningUiFixture().use { fixture ->
            fixture.assessmentPool(profile)
            val id = runBlocking {
                fixture.services.listeningAssessments.start(fixture.services.listeningAssessments.preview(77, profile = profile))
            }
            val vm = ListeningAssessmentViewModel(fixture.services, id)
            val returned = mutableStateOf(false)
            rule.setContent { KanjiTheme {
                if (returned.value) Text("Returned") else ListeningAssessmentView(vm, { returned.value = true }) { _, _ -> }
            } }
            val manager = fixture.context.getSystemService(AudioManager::class.java)
            val competing = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener({}).build()
            try {
                ready(vm)
                rule.onNodeWithText("Begin section").performScrollTo().performClick()
                rule.waitUntil(30_000) { vm.snapshot?.state?.phase == AssessmentPhase.Preview || vm.error != null }
                assertNull(vm.error)
                assertTrue(vm.snapshot!!.attempt.completedStages.contains(vm.snapshot!!.record.task.stages.first().id))
                rule.onNodeWithTag("listening-task-picture").assertIsDisplayed()
                rule.runOnIdle { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competing)) }
                rule.waitUntil(5_000) { vm.snapshot!!.state.paused && !vm.busy }
                assertTrue(vm.snapshot!!.state.interrupted)
                rule.runOnIdle { manager.abandonAudioFocusRequest(competing) }
                rule.onNodeWithText("Continue as interrupted practice").performScrollTo().assertIsDisplayed().performClick()
                rule.waitUntil(5_000) { !vm.busy && !vm.snapshot!!.state.paused }
                rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                rule.waitUntil(5_000) { !vm.busy && vm.snapshot!!.state.paused }
                rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                ready(vm)
                assertTrue(vm.snapshot!!.state.paused)
                assertTrue(vm.snapshot!!.state.interruptions.any { "backgrounded" in it })
                assertEquals(1, vm.snapshot!!.attempt.completedStages.size)
                assertEquals(1, runBlocking { fixture.db.listeningTasks().allExposures().size })
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
                rule.onNodeWithText("Continue as interrupted practice").performScrollTo().assertIsDisplayed()
                screenshot("N${profile.jlpt}-native-focus-background-interruption.png")
                rule.runOnIdle { vm.end() }
                rule.waitUntil(5_000) { vm.snapshot!!.result != null && !vm.busy }
                assertEquals(profile.scoredCount, vm.snapshot!!.result!!.notAssessed)
                assertEquals(profile.id, vm.snapshot!!.result!!.blueprint)
            } finally { manager.abandonAudioFocusRequest(competing); finish(vm) { returned.value = true } }
        }
    }

    @Test fun n4PreflightCapturesOnlyTheConfirmedDiagnosticLevel() {
        ListeningUiFixture().use { fixture ->
            fixture.assessmentPool(ListeningAssessmentProfile.N4)
            val vm = ListeningAssessmentPreviewModel(fixture.services)
            val returned = mutableStateOf(false)
            var captured: String? = null
            rule.setContent { KanjiTheme {
                if (returned.value) Text("Captured") else ListeningAssessmentPreviewView(vm, { returned.value = true }) {
                    captured = it
                    returned.value = true
                }
            } }
            try {
                rule.waitUntil(10_000) { !vm.busy && vm.preview != null }
                rule.onNodeWithText("Capture this section").assertDoesNotExist()
                rule.onNodeWithText("N4").performScrollTo().performClick()
                rule.waitUntil(10_000) { !vm.busy && vm.preview?.profile == ListeningAssessmentProfile.N4 }
                rule.onNodeWithText("Capture this section").performScrollTo().assertIsDisplayed()
                screenshot("N4-C-confirmed-preview.png")
                rule.onNodeWithText("Capture this section").performClick()
                rule.waitUntil(10_000) { captured != null && !vm.busy }
                runBlocking {
                    val snapshot = fixture.services.listeningAssessments.snapshot(requireNotNull(captured))
                    assertEquals(4, snapshot.form.jlpt)
                    assertEquals(32, snapshot.form.items.size)
                    assertTrue(fixture.db.listeningTasks().allExposures().isEmpty())
                    assertTrue(fixture.db.reviews().allLog().isEmpty())
                    fixture.services.listeningAssessments.end(requireNotNull(captured))
                    fixture.services.listeningAssessments.close(requireNotNull(captured))
                }
            } finally { rule.runOnIdle { returned.value = true; vm.leave() }; rule.waitForIdle() }
        }
    }

    @Test fun missingPictureCannotHideFinalizedPartialResults() {
        ListeningUiFixture().use { fixture ->
            fixture.assessmentPool()
            val study = fixture.services.listeningAssessments
            val id = runBlocking {
                val preview = study.preview(42)
                val captured = study.start(preview)
                val image = requireNotNull(preview.selection.items.first().record.task.media?.image)
                fixture.db.listeningTasks().putAssets(listOf(ListeningTaskAssetEntity(preview.sourceId, image.sha256, byteArrayOf(1))))
                captured
            }
            val vm = ListeningAssessmentViewModel(fixture.services, id)
            val returned = mutableStateOf(false)
            rule.setContent { KanjiTheme {
                if (returned.value) Text("Returned") else ListeningAssessmentView(vm, { returned.value = true }) { _, _ -> }
            } }
            try {
                rule.waitUntil(10_000) { vm.error != null && !vm.busy }
                rule.onNodeWithText("End with partial results").performScrollTo().performClick()
                rule.waitUntil(5_000) { vm.snapshot?.result != null && !vm.busy }
                rule.onNodeWithText("0 / 0 correct").assertIsDisplayed()
                assertEquals(24, vm.snapshot!!.result!!.notAssessed)
                rule.onAllNodesWithText("Feedback and captured transcript")[0].performScrollTo().performClick()
                rule.waitUntil(5_000) { vm.detail != null && vm.error != null && !vm.busy }
                rule.onNodeWithText("Retry captured evidence").performScrollTo().assertIsDisplayed()
                rule.runOnIdle { vm.hideDetail() }
                rule.waitUntil(5_000) { vm.detail == null && !vm.busy }
                rule.onNodeWithText("0 / 0 correct").assertIsDisplayed()
                assertTrue(runBlocking { fixture.db.listeningTasks().allExposures().isEmpty() })
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
                screenshot("F-media-failure-partial-result.png")
            } finally { finish(vm) { returned.value = true } }
        }
    }

    private fun ready(vm: ListeningAssessmentViewModel) {
        rule.waitUntil(15_000) { !vm.busy && (vm.ready || vm.error != null) }
        assertNull(vm.error)
        assertTrue(vm.ready)
    }

    private fun expire(study: ListeningAssessmentStudy, id: String, vm: ListeningAssessmentViewModel, elapsed: AtomicLong) {
        val current = requireNotNull(vm.snapshot)
        elapsed.set(requireNotNull(current.state.deadlineMs))
        val after = runBlocking { study.pulse(id, current.state.generation) }
        rule.waitUntil(10_000) { vm.snapshot!!.state.version >= after.state.version }
        assertNull(vm.error)
    }

    private fun finish(vm: ListeningAssessmentViewModel, leave: () -> Unit) {
        rule.runOnIdle { vm.pause("Fixture cleanup", leave = true); leave() }
        rule.waitUntil(10_000) { !vm.busy && !vm.playing }
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 3_000)
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.filesDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private class InstantFixturePlayer(private val elapsed: AtomicLong) : ListeningPlayer {
        val hashes = mutableListOf<String>()
        private var duration = 0L
        private var hash = ""
        override val playing get() = false
        override val positionMs get() = 0L
        override suspend fun prepare(bytes: ByteArray) {
            duration = OpusAudio.durationMs(bytes, 250, 90_000)
            hash = ContentParser.rawSha256(bytes)
        }
        override suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit,
            interrupted: () -> Unit, failed: (IOException) -> Unit) {
            hashes += hash
            elapsed.addAndGet(duration)
            completed()
        }
        override fun pause() = Unit
        override fun close() = Unit
    }
}
