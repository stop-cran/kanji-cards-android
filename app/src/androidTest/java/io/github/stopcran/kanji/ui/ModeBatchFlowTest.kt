package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterRoutes
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.ReviewRecord
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.COUNTER_STACK
import io.github.stopcran.kanji.data.CounterEntity
import io.github.stopcran.kanji.data.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/** Disposable in-memory content/history; never edits the installed learner's data or preferences. */
class ModeBatchFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun counterTypeHomeAndContinueStayBoundedAtZeroAllowance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = "mode-batch-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val settings = Settings(isolated).apply { setDailyNewCards(0); setWeekPlan("FFFFFFF") }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val services = Services(db, settings, context)
        val source = settings.source.value.id
        val route = mutableStateOf<String?>(null)
        val vm = mutableStateOf(CounterQuizViewModel(services.counters, services.sessionStore))
        val visible = mutableStateOf(true)
        val ids = (1..23).map { "pen-$it" }
        try {
            runBlocking {
                val file = CounterFile(1, "pens", "本", "ほん", "Pens", cards = ids.map {
                    CounterCard(it, text = "ペンが三{}あります。", noun = "ペン", phrase = "三本", reading = "さんぼん",
                        good = listOf("本"), distractors = listOf("匹", "冊", "回"))
                })
                db.content().insertCounters(listOf(CounterEntity(source, file.id, Json.encodeToString(CounterFile.serializer(), file), 0)))
                val yesterday = Instant.now().minusSeconds(86400)
                for (id in ids) services.reviewStore.record(ReviewRecord(
                    SrsState(CardPhase.Review, stability = 3.0, due = yesterday.plusSeconds(864000), reps = 3),
                    source, COUNTER_STACK, id, CounterMode.Choose.mode, Grade.Good, yesterday, "fixture",
                ))
            }
            rule.activityRule.scenario.onActivity {
                it.enableEdgeToEdge()
                it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            rule.setContent {
                MaterialTheme {
                    if (visible.value) {
                        if (route.value == null) CountersPage(services, {}, { mode, extra, group, scope ->
                            assertEquals(CounterMode.Type, mode)
                            assertFalse(extra)
                            route.value = CounterRoutes.create(mode, group, scope)
                        }, {}) else CounterQuizScreen(
                            requireNotNull(route.value), {}, { error("Continue must not invoke extra practice") }, { _, _ -> },
                            vm.value, onContinue = {
                                vm.value.leaveSession()
                                route.value = CounterRoutes.nextBatch(requireNotNull(route.value))
                                vm.value = CounterQuizViewModel(services.counters, services.sessionStore)
                            },
                        )
                    }
                }
            }
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasText("0 due, 10 ready to learn")).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNode(hasText(CounterMode.Type.label) and hasText("0 due, 10 ready to learn"))
                .performScrollTo().assertIsDisplayed()
            screenshot("mode-batch-home")
            rule.onNode(hasText("Start") and hasAnyAncestor(hasText(CounterMode.Type.label)))
                .performScrollTo().performTouchInput { click() }
            for ((batch, size) in listOf(10, 10, 3).withIndex()) {
                repeat(size) {
                    rule.waitUntil(10_000) { vm.value.ui is CounterUi.TypeQuestion }
                    rule.onNode(hasSetTextAction()).performTextReplacement("本")
                    rule.onNodeWithText("Check").performScrollTo().performTouchInput { click() }
                    rule.waitUntil(10_000) { vm.value.ui is CounterUi.TypeAnswer }
                    rule.onNodeWithText("Next").performScrollTo().performTouchInput { click() }
                }
                rule.waitUntil(10_000) { vm.value.ui is CounterUi.Done }
                assertEquals(size, (vm.value.ui as CounterUi.Done).answered)
                assertFalse(requireNotNull(route.value).endsWith(":extra"))
                if (batch < 2) {
                    rule.waitUntil(10_000) { (vm.value.ui as? CounterUi.Done)?.canContinue == true }
                    rule.onNodeWithText("Continue").performScrollTo().assertIsDisplayed()
                    screenshot("mode-batch-${batch + 1}-complete")
                    rule.onNodeWithText("Continue").performTouchInput { click() }
                } else {
                    rule.waitUntil(10_000) { runBlocking { db.reviews().allSessions().all { it.endedAtMs != null } } }
                    rule.onNodeWithText("Continue").assertDoesNotExist()
                    screenshot("mode-batch-final")
                }
            }
            runBlocking {
                assertEquals(23, db.reviews().states(source, COUNTER_STACK, CounterMode.Type.mode.name).size)
                assertEquals(23, db.reviews().itemsIntroducedSince(source, 0))
                assertTrue(db.reviews().allSessions().all { !it.extra })
            }
        } finally {
            rule.runOnIdle { visible.value = false; vm.value.leaveSession() }
            rule.waitForIdle()
            db.close()
            context.getSharedPreferences(preferences, 0).edit().clear().commit()
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            check(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
