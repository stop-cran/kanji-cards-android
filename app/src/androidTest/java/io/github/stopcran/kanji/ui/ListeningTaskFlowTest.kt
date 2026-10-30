package io.github.stopcran.kanji.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.listening.ListeningTaskType
import io.github.stopcran.kanji.core.listening.ListeningScope
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.data.ListeningPlayer
import io.github.stopcran.kanji.data.ListeningTaskAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

class ListeningTaskFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun portrait() { orientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) }

    private fun orientation(request: Int, expected: Int) {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = request }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == expected }
        rule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @Test fun androidBackClosesLoadedUnscoredStudy() = closeStudy(described = false)

    @Test fun androidBackClosesLoadedDescribedStudy() = closeStudy(described = true)

    @Test fun unscoredStudyDoneStaysVisibleBesideScrolledFeedback() = closeStudy(described = false, useDone = true)

    private fun closeStudy(described: Boolean, useDone: Boolean = false) {
        ListeningUiFixture().use { fixture ->
            val record = fixture.records.single { it.task.taskType == ListeningTaskType.TaskBased }
            val id = runBlocking {
                fixture.services.listeningTasks.start(fixture.source, record.task.id, study = true, described = described)
            }
            val vm = ListeningTaskViewModel(fixture.services, id)
            val returned = mutableStateOf(false)
            var fallbackCalls = 0
            rule.setContent { KanjiTheme {
                BackHandler(enabled = !returned.value) { fallbackCalls++; returned.value = true }
                if (returned.value) Text("Returned to Listening")
                else ListeningTaskEncounterView(vm, { returned.value = true }) { _, _ -> }
            } }
            try {
                awaitReady(vm)
                rule.waitUntil(5_000) { runBlocking { fixture.db.listeningTasks().encounter(id)?.exposedAtMs != null } }
                if (useDone) {
                    val done = rule.onNodeWithText("Done").assertIsDisplayed()
                    val position = done.fetchSemanticsNode().boundsInRoot
                    rule.onNodeWithText(record.task.explanation).performScrollTo().assertIsDisplayed()
                    assertEquals(position, done.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
                    done.performClick()
                } else InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                rule.waitUntil(5_000) { returned.value && !vm.busy }
                assertEquals("The encounter must handle Back before the route fallback", 0, fallbackCalls)
                runBlocking {
                    val closed = requireNotNull(fixture.db.listeningTasks().encounter(id))
                    assertNotNull("Android Back must close unscored study after asynchronous loading", closed.closedAtMs)
                    assertNotNull(closed.exposedAtMs)
                    assertNull(closed.reviewLogId)
                    assertTrue(fixture.db.reviews().allLog().isEmpty())
                    assertEquals(1, fixture.db.listeningTasks().allExposures().size)
                }
            } finally { finish(vm) }
        }
    }

    @Test fun n4ScopeAndFormatStayUsableInNarrowLargeThemesAndOpenOnlyUnscoredFixtureStudy() {
        ListeningUiFixture().use { fixture ->
            val n4 = fixture.addN4Fixtures().single { it.task.taskType == ListeningTaskType.QuickResponse }
            val dark = mutableStateOf(false)
            val show = mutableStateOf(false)
            var vm: ListeningTaskViewModel? = null
            val restoration = StateRestorationTester(rule)
            restoration.setContent {
                val config = Configuration(LocalConfiguration.current).apply {
                    uiMode = if (dark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides config,
                    LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    KanjiTheme { Box(Modifier.width(320.dp)) {
                        if (show.value) ListeningTaskEncounterView(requireNotNull(vm), {}) { _, _ -> }
                        else ListeningBrowser(fixture.services, {}, onEncounter = { id ->
                            vm = ListeningTaskViewModel(fixture.services, id)
                            show.value = true
                        })
                    } }
                }
            }
            try {
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Study without scoring").fetchSemanticsNodes().size == 4 }
                rule.onNodeWithText("N5").assertIsSelected()
                rule.onNodeWithText(n4.task.title).assertDoesNotExist()
                rule.onNodeWithText("N4").performScrollTo().performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithText(n4.task.title).fetchSemanticsNodes().isNotEmpty() }
                val quick = hasText("Quick response") and hasClickAction()
                rule.onNode(quick).performScrollTo().performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Study without scoring").fetchSemanticsNodes().size == 2 }
                rule.onNodeWithText("N4").performScrollTo().assertIsSelected().assertIsDisplayed()
                rule.onNodeWithText("Includes easier levels.").assertIsDisplayed()
                screenshot("N4-B-scope-narrow-large-light.png")
                rule.runOnIdle { dark.value = true }
                rule.waitForIdle()
                rule.onNodeWithText("N4").assertIsDisplayed()
                screenshot("N4-B-scope-narrow-large-dark.png")
                restoration.emulateSavedInstanceStateRestore()
                rule.onNodeWithText("N4").assertIsSelected()
                rule.onNode(quick).assertIsSelected()
                rule.waitUntil(10_000) { rule.onAllNodesWithText(n4.task.title).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithText(n4.task.title).performScrollTo().assertIsDisplayed()
                screenshot("N4-B-scoped-entry-narrow-large-dark.png")
                rule.onAllNodesWithText("Study without scoring").onLast().performScrollTo().performClick()
                rule.waitUntil(10_000) { vm?.ready == true && vm?.busy == false }
                val current = requireNotNull(vm)
                assertEquals(4, current.task?.jlpt)
                assertEquals("study", current.encounter?.kind)
                assertFalse(requireNotNull(current.encounter).newReservation)
                rule.runOnIdle { fixture.services.settings.setListeningScope(fixture.source, ListeningScope.N5) }
                rule.onNodeWithText("N4 - Unscored study", substring = true).performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("Check answer").assertDoesNotExist()
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(15_000) { current.attempt?.completedStages?.size == n4.task.stages.size && !current.busy }
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
                assertEquals(4, current.task?.jlpt)
                screenshot("N4-B-unscored-capture-outside-scope.png")
            } finally { vm?.let(::finish) }
        }
    }

    @Test fun allFormatsWrapReadablyAndKeepTheirSelectionAndDisabledState() {
        val scale = mutableStateOf(1f)
        val selected = mutableStateOf("all")
        val enabled = mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                KanjiTheme {
                    Page("Format layout") {
                        Box(Modifier.width(320.dp)) {
                            ListeningFormatFilter(selected.value, enabled.value) { selected.value = it }
                        }
                    }
                }
            }
        }
        val formats = listOf("All formats", "English listen-first") + ListeningTaskType.entries.map { it.label }
        for (size in listOf(1f, 1.5f, 2f)) {
            rule.runOnIdle { scale.value = size }
            for (label in formats) {
                val chip = rule.onNode(hasText(label) and hasClickAction()).performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                chip.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                // The reused paragraph box can exceed the tight text width; check rendered lines instead.
                assertFalse("$label at $size must fit vertically", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse("$label at $size must not be ellipsized", layout.isLineEllipsized(line))
                    assertTrue("$label at $size must fit horizontally",
                        layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                }
                val bounds = chip.getUnclippedBoundsInRoot()
                assertTrue("$label must fit the selector width", bounds.right - bounds.left <= 320.dp)
            }
            rule.onNodeWithText("All formats").performScrollTo()
            val name = "ux105-formats-$size.png"
            screenshot(name)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, name).copyTo(File(context.getExternalFilesDir(null), name), overwrite = true)
        }
        rule.onNodeWithText("Quick response").performScrollTo().performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(ListeningTaskType.QuickResponse.wire, selected.value); enabled.value = false }
        rule.onNodeWithText("All formats").performScrollTo().assertIsNotEnabled().performClick()
        rule.onNodeWithText("Quick response").performScrollTo().assertIsSelected()
    }

    @Test fun taskBasedVisualHostPlaysChecksAndReturnsThroughCapturedArticle() {
        ListeningUiFixture().use { fixture ->
            val id = fixture.start(ListeningTaskType.TaskBased)
            val record = fixture.records.single { it.task.taskType == ListeningTaskType.TaskBased }
            val vm = ListeningTaskViewModel(fixture.services, id)
            val returned = mutableStateOf(false)
            rule.setContent { KanjiTheme {
                if (returned.value) Text("Returned to Listening")
                else ListeningTaskEncounterView(vm, { returned.value = true }) { _, _ -> error("Use captured article body first") }
            } }
            try {
                awaitReady(vm)
                rule.onNodeWithText("Stage 1/4: Instructions").performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("Check answer").assertDoesNotExist()
                rule.onNodeWithText(record.task.id).assertDoesNotExist()
                record.task.options.forEach { rule.onNodeWithText(it.label).assertDoesNotExist() }
                screenshot("C-task-based-ready.png")
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(15_000) { vm.attempt?.phase == "answer" && !vm.busy }
                val number = record.task.options.single { it.id == record.task.answer }.number.toString()
                rule.onNodeWithText(number).performScrollTo().performClick()
                rule.waitUntil(5_000) { !vm.busy }
                rule.onNodeWithText("Check answer").performScrollTo().performClick()
                rule.waitUntil(10_000) { vm.response != null && !vm.busy }
                assertEquals(Grade.Good.value, vm.response?.grade)
                rule.onNodeWithText("Correct - Good").assertIsDisplayed()
                screenshot("C-task-based-result.png")
                rule.onNodeWithText("Read captured word article: 窓").performScrollTo().performClick()
                rule.onNodeWithText("Captured window article").assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                rule.onNodeWithText("Read captured word article: 窓").assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                rule.waitUntil(5_000) { returned.value && !vm.busy }
                rule.onNodeWithText("Returned to Listening").assertIsDisplayed()
                assertEquals(1, runBlocking { fixture.db.reviews().allLog().size })
                assertNotNull(runBlocking { fixture.db.listeningTasks().encounter(id)?.closedAtMs })
            } finally { finish(vm) }
        }
    }

    @Test fun keyPointHostRetainsWrittenChoicesAndDeliberateHelpUntilFeedback() {
        ListeningUiFixture().use { fixture ->
            val id = fixture.start(ListeningTaskType.KeyPoint)
            val record = fixture.records.single { it.task.taskType == ListeningTaskType.KeyPoint }
            val vm = ListeningTaskViewModel(fixture.services, id)
            rule.setContent { KanjiTheme { ListeningTaskEncounterView(vm, {}) { _, _ -> } } }
            try {
                awaitReady(vm)
                rule.onNodeWithText("Key-point comprehension").assertIsDisplayed()
                rule.onNodeWithText("1. ${record.task.options.first().label}").performScrollTo().assertIsDisplayed()
                rule.onNodeWithText(record.task.explanation).assertDoesNotExist()
                rule.onNodeWithText("Show transcript and translation (help)").performScrollTo().performClick()
                rule.waitUntil(5_000) { !vm.busy }
                rule.onNodeWithText("Transcript and translation").performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("Check answer").assertDoesNotExist()
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(15_000) { vm.attempt?.phase == "answer" && !vm.busy }
                val option = record.task.options.single { it.id == record.task.answer }
                rule.onNodeWithText("${option.number}. ${option.label}").performScrollTo().performClick()
                rule.waitUntil(5_000) { !vm.busy }
                rule.onNodeWithText("Check answer").performScrollTo().performClick()
                rule.waitUntil(10_000) { vm.response != null && !vm.busy }
                assertEquals(Grade.Hard.value, vm.response?.grade)
                rule.onNodeWithText("Correct - Hard").assertIsDisplayed()
                screenshot("C-key-point-help-result.png")
            } finally { finish(vm) }
        }
    }

    @Test fun essentialPictureStaysVisibleInLandscapeBothThemesAndLargeText() {
        orientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        ListeningUiFixture().use { fixture ->
            val id = fixture.start(ListeningTaskType.TaskBased)
            val player = HeldPlayer()
            val vm = ListeningTaskViewModel(fixture.services, id, player)
            val dark = mutableStateOf(false)
            rule.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    uiMode = if (dark.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                CompositionLocalProvider(LocalConfiguration provides configuration,
                    LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    KanjiTheme { ListeningTaskEncounterView(vm, {}) { _, _ -> } }
                }
            }
            try {
                awaitReady(vm)
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(5_000) { vm.attempt?.started == true }
                rule.onNodeWithTag("listening-task-picture").assertIsDisplayed()
                rule.onNodeWithTag("listening-task-picture").assertContentDescriptionEquals(
                    "Essential numbered picture. Described study is available in Listening.")
                assertPictureFits()
                screenshot("C-landscape-large-light-playing.png")
                rule.runOnIdle { dark.value = true }
                rule.waitForIdle()
                assertPictureFits()
                screenshot("C-landscape-large-dark-playing.png")
                rule.onNodeWithText("Enlarge picture").performClick()
                rule.onNodeWithText("Zoom in").performClick()
                rule.onNodeWithText("Zoom in").performClick()
                val zoom = rule.onNodeWithTag("listening-picture-zoom")
                val beforePan = zoom.captureToImage().asAndroidBitmap()
                zoom.performTouchInput { swipeUp() }
                val afterPan = zoom.captureToImage().asAndroidBitmap()
                assertFalse("Touch pan must move the enlarged picture", beforePan.sameAs(afterPan))
                val down = zoom.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Move picture down" }
                rule.runOnIdle { assertTrue(down.action()) }
                assertFalse("Accessible pan must move the enlarged picture", afterPan.sameAs(zoom.captureToImage().asAndroidBitmap()))
                screenshot("C-landscape-large-dark-enlarged.png")
                rule.onNodeWithText("Pause audio").performClick()
                rule.waitUntil(5_000) { !vm.busy }
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
                rule.onNodeWithText("Return to task").performClick()
                rule.onNodeWithText("Check answer").assertDoesNotExist()
            } finally { finish(vm) }
        }
    }

    @Test fun filtersDoNotRetargetAnOpenCaptureAndNonvisualChoiceIsExplicitStudy() {
        ListeningUiFixture().use { fixture ->
            val sync = io.github.stopcran.kanji.data.ContentSync(fixture.db, fixture.services.settings.sync)
            val id = fixture.start(ListeningTaskType.TaskBased, described = true)
            val before = runBlocking { requireNotNull(fixture.db.listeningTasks().encounter(id)) }
            var selected: String? = null
            val showEncounter = mutableStateOf(false)
            val returned = mutableStateOf(false)
            val vm = ListeningTaskViewModel(fixture.services, id)
            val restoration = StateRestorationTester(rule)
            restoration.setContent { KanjiTheme {
                when {
                    returned.value -> Text("Study closed without a grade")
                    showEncounter.value -> ListeningTaskEncounterView(vm, { returned.value = true }) { _, _ -> }
                    else -> {
                        val source = fixture.services.settings.source.collectAsState().value
                        val content = rememberHomeContent(fixture.db, sync, source)
                        ListeningBrowser(fixture.services, {}, { selected = it }, content)
                    }
                }
            } }
            try {
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Resume encounter").fetchSemanticsNodes().isNotEmpty() }
                val filter = hasText("Key-point comprehension") and hasClickAction()
                rule.onNode(filter).performScrollTo().performClick()
                restoration.emulateSavedInstanceStateRestore()
                rule.onNode(filter).assertIsSelected()
                rule.runOnIdle { fixture.services.settings.setSource("https://github.com/different/source", "other") }
                val resume = "Resume captured encounter - ${fixture.source}"
                rule.waitUntil(10_000) { rule.onAllNodesWithText(resume).fetchSemanticsNodes().isNotEmpty() }
                restoration.emulateSavedInstanceStateRestore()
                rule.waitUntil(10_000) { rule.onAllNodesWithText(resume).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithText(resume).performScrollTo().performClick()
                assertEquals(id, selected)
                rule.onNodeWithText("Content not downloaded yet. Kana is available.").assertExists()
                rule.onNodeWithText("Retry").assertExists()
                rule.onNodeWithText("No items in this level/format yet. Sync an approved collection in Settings.").assertDoesNotExist()
                val after = runBlocking { requireNotNull(fixture.db.listeningTasks().encounter(id)) }
                assertEquals(before.stateJson, after.stateJson)
                assertNull(after.exposedAtMs)
                assertFalse(after.newReservation)
                assertTrue(fixture.services.listeningTasks.attempt(after).describedStudy)
                rule.runOnIdle { showEncounter.value = true }
                awaitReady(vm)
                rule.onNodeWithText("Described study is unscored; it is not an equivalent scored nonvisual presentation.")
                    .performScrollTo().assertIsDisplayed()
                rule.onNodeWithTag("listening-task-picture").assertDoesNotExist()
                rule.onNodeWithText("Check answer").assertDoesNotExist()
                rule.onNodeWithText("I cannot follow this (Again)").assertDoesNotExist()
                restoration.emulateSavedInstanceStateRestore()
                awaitReady(vm)
                assertTrue(vm.attempt?.describedStudy == true)
                assertEquals(fixture.source, vm.encounter?.sourceId)
                rule.onNodeWithText("Done").assertIsDisplayed().performClick()
                rule.waitUntil(5_000) { returned.value && !vm.busy }
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
            } finally { finish(vm) }
        }
    }

    @Test fun choicesExposeWhileInstructionPlaysButNotWhileOffscreen() {
        ListeningUiFixture().use { fixture ->
            val id = fixture.start(ListeningTaskType.KeyPoint)
            val vm = ListeningTaskViewModel(fixture.services, id, HeldPlayer())
            rule.setContent { KanjiTheme {
                Box(Modifier.height(300.dp)) { ListeningTaskEncounterView(vm, {}) { _, _ -> } }
            } }
            try {
                awaitReady(vm)
                assertNull(runBlocking { fixture.db.listeningTasks().encounter(id)?.exposedAtMs })
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(5_000) { vm.attempt?.started == true }
                assertNull(runBlocking { fixture.db.listeningTasks().encounter(id)?.exposedAtMs })
                val option = fixture.records.single { it.task.taskType == ListeningTaskType.KeyPoint }.task.options.first()
                rule.onNodeWithText("1. ${option.label}").performScrollTo().assertIsDisplayed()
                rule.waitUntil(5_000) { vm.encounter?.exposedAtMs != null }
                assertTrue(vm.playing)
                assertEquals(0, vm.attempt?.stageIndex)
                assertNotNull(runBlocking { fixture.db.listeningTasks().encounter(id)?.exposedAtMs })
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
            } finally { finish(vm) }
        }
    }

    @Test fun missingRequiredPictureShowsRetryAndDiscardWithoutAnAnswer() {
        orientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        ListeningUiFixture().use { fixture ->
            val id = fixture.start(ListeningTaskType.TaskBased)
            val hash = requireNotNull(fixture.records.first().task.media?.image).sha256
            runBlocking {
                fixture.services.listeningTasks.revealEvidence(id)
                fixture.db.listeningTasks().putAssets(listOf(ListeningTaskAssetEntity(fixture.source, hash, byteArrayOf(0))))
            }
            val vm = ListeningTaskViewModel(fixture.services, id)
            val returned = mutableStateOf(false)
            rule.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    KanjiTheme {
                        if (returned.value) Text("Discarded without a grade")
                        else ListeningTaskEncounterView(vm, { returned.value = true }) { _, _ -> }
                    }
                }
            }
            try {
                rule.waitUntil(10_000) { vm.error != null && !vm.busy }
                rule.onNodeWithText("Retry captured evidence").performScrollTo().assertIsDisplayed().performClick()
                rule.waitUntil(10_000) { vm.error != null && !vm.busy }
                rule.onNodeWithText("Check answer").assertDoesNotExist()
                rule.onNodeWithText("I cannot follow this (Again)").assertDoesNotExist()
                rule.onNodeWithText("Discard encounter").performScrollTo().assertIsDisplayed()
                screenshot("C-required-picture-unavailable.png")
                rule.onNodeWithText("Discard encounter").performClick()
                rule.onNodeWithText("Discard", substring = false).performClick()
                rule.waitUntil(5_000) { returned.value && !vm.busy }
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
            } finally { finish(vm) }
        }
    }

    @Test fun activeRotationAndActivityRecreationKeepCapturedPositionAndSource() {
        ListeningUiFixture().use { fixture ->
            val id = fixture.start(ListeningTaskType.TaskBased)
            val player = HeldPlayer()
            val original = ListeningTaskViewModel(fixture.services, id, player)
            val model = mutableStateOf(original)
            val returned = mutableStateOf(false)
            val content: @Composable () -> Unit = {
                KanjiTheme {
                    if (returned.value) Text("Left captured encounter")
                    else ListeningTaskEncounterView(model.value, { returned.value = true }) { _, _ -> }
                }
            }
            rule.activityRule.scenario.onActivity { it.setContent(content = content) }
            try {
                awaitReady(original)
                rule.onNodeWithText("Play remaining stages").performScrollTo().performClick()
                rule.waitUntil(5_000) { original.attempt?.started == true }
                rule.runOnIdle { player.position = 80 }
                orientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                rule.waitUntil(10_000) { !original.playing && !original.busy }
                assertEquals(80L, runBlocking {
                    fixture.services.listeningTasks.attempt(requireNotNull(fixture.db.listeningTasks().encounter(id))).positionMs
                })
                rule.activityRule.scenario.onActivity { it.setContent(content = content) }
                awaitReady(original)
                rule.onNodeWithTag("listening-task-picture").assertIsDisplayed()
                assertEquals(80L, original.attempt?.positionMs)
                assertTrue(original.attempt?.assistance?.isEmpty() == true)
                fixture.services.settings.setSource("https://github.com/different/source", "other")
                rule.activityRule.scenario.recreate()
                val restored = ListeningTaskViewModel(fixture.services, id, HeldPlayer())
                rule.activityRule.scenario.onActivity {
                    model.value = restored
                    it.enableEdgeToEdge()
                    it.setContent(content = content)
                }
                awaitReady(restored)
                assertEquals(fixture.source, restored.encounter?.sourceId)
                assertEquals(80L, restored.attempt?.positionMs)
                assertTrue(restored.attempt?.assistance?.isEmpty() == true)
                rule.onNodeWithText("Resume audio").performScrollTo().assertIsDisplayed()
                rule.onNodeWithContentDescription("Back").performClick()
                rule.waitUntil(5_000) { returned.value && !restored.busy }
                assertNull(runBlocking { fixture.db.listeningTasks().encounter(id)?.closedAtMs })
                assertTrue(runBlocking { fixture.db.reviews().allLog().isEmpty() })
            } finally {
                finish(model.value)
                finish(original)
            }
        }
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

    private fun assertPictureFits() {
        val picture = rule.onNodeWithTag("listening-task-picture").fetchSemanticsNode().boundsInRoot
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Picture must have useful visible height", picture.height > 100)
        assertTrue(picture.top >= root.top && picture.bottom <= root.bottom && picture.left >= root.left && picture.right <= root.right)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        rule.waitForIdle()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private class HeldPlayer : ListeningPlayer {
        override var playing = false
        var position = 0L
        override val positionMs get() = position
        override suspend fun prepare(bytes: ByteArray) = Unit
        override suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit,
            interrupted: () -> Unit, failed: (IOException) -> Unit) { position = positionMs; playing = true }
        override fun pause() { playing = false }
        override fun close() { playing = false }
    }
}
