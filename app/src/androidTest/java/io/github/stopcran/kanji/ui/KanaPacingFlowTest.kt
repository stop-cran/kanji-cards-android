package io.github.stopcran.kanji.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.kana.KANA_SOURCE_ID
import io.github.stopcran.kanji.core.kana.KanaCatalog
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.newAllowance
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

class KanaPacingFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun homeCountsDelayedKanaWritesAndRebindsItsWeekAndSelectedSource() {
        Fixture(seedHistory = false).use { fixture ->
            val now = Instant.parse("2026-01-07T03:00:00Z")
            val clock = mutableStateOf(Clock.fixed(now, ZoneId.of("Asia/Tokyo")))
            val home = mutableStateOf<HomeData?>(null)
            val visible = mutableStateOf(true)
            val release = CompletableDeferred<Unit>()
            val initialSource = fixture.settings.source.value.id
            val switchedCounts = mutableListOf<Int?>()
            rule.setContent {
                if (visible.value) {
                    val data = rememberHomeData(fixture.services, clock.value)
                    SideEffect {
                        home.value = data
                        if (data.sourceId != initialSource) switchedCounts += data.week?.studied
                    }
                    LaunchedEffect(Unit) {
                        release.await()
                        fixture.recordWeekAnswer(KANA_SOURCE_ID, Instant.parse("2026-01-04T15:00:00Z"))
                    }
                    MaterialTheme {
                        Page("Home") {
                            data.week?.let { Text(stringResource(R.string.this_week_of_study_days, it.studied, it.goal, "")) }
                        }
                    }
                }
            }
            try {
                rule.waitUntil(10_000) { home.value?.let { !it.loading && it.week?.studied == 0 } == true }
                rule.onNodeWithText("This week: 0 of 5 study days").assertIsDisplayed()
                release.complete(Unit)
                rule.waitUntil(10_000) { home.value?.week?.studied == 1 }
                rule.onNodeWithText("This week: 1 of 5 study days").assertIsDisplayed()
                runBlocking {
                    fixture.recordWeekAnswer(initialSource, Instant.parse("2026-01-04T16:00:00Z"))
                    fixture.recordWeekAnswer(initialSource, Instant.parse("2026-01-05T15:00:00Z"))
                }
                rule.waitUntil(10_000) { home.value?.week?.studied == 2 }
                rule.runOnIdle { assertTrue(fixture.settings.setSource("https://github.com/fixture/other", "main")) }
                rule.waitUntil(10_000) {
                    home.value?.let { it.sourceId != initialSource && it.week?.studied == 1 } == true
                }
                rule.runOnIdle { assertTrue(switchedCounts.none { it == 2 }) }
                rule.runOnIdle { clock.value = Clock.fixed(now.plusSeconds(7 * 86_400), clock.value.zone) }
                rule.waitUntil(10_000) { home.value?.week?.studied == 0 }
                rule.runOnIdle {
                    clock.value = Clock.fixed(now, clock.value.zone)
                    fixture.settings.setWeekPlan("FFOOOOO")
                }
                rule.waitUntil(10_000) { home.value?.week?.let { it.studied == 1 && it.goal == 2 } == true }
                assertEquals(3, runBlocking { fixture.db.reviews().allLog().size })
            } finally {
                rule.runOnIdle { visible.value = false }
                rule.waitForIdle()
            }
        }
    }

    @Test fun completedReversePracticeSpendsTheAllowanceInsteadOfRefillingNormalSessions() {
        Fixture().use { fixture ->
            val home = mutableStateOf(runBlocking { fixture.overview() })
            val active = mutableStateOf<Pair<String, KanaStudyViewModel>?>(null)
            fun open(extra: Boolean) { active.value = fixture.session(extra) }
            rule.setContent {
                val scope = rememberCoroutineScope()
                MaterialTheme {
                    val current = active.value
                    if (current == null) {
                        Page("Kana pacing", onBack = {}) {
                            ModeRow(fixture.mode.title, home.value.modeState(fixture.script, fixture.mode), onStart = ::open)
                        }
                    } else {
                        KanaStudyScreen(current.first, onBack = {
                            scope.launch { home.value = fixture.overview(); active.value = null }
                        }, onPracticeMore = { _, _ -> open(true) }, vm = current.second)
                    }
                }
            }
            rule.onNodeWithText("0 due, 3 new").assertIsDisplayed()
            screenshot("kana-pacing-before.png")
            rule.onNodeWithText("Start").performClick()
            repeat(3) { answerCorrectly { requireNotNull(active.value).second } }
            rule.onNodeWithText("Session complete").assertIsDisplayed()
            waitForWrites(fixture, 1)
            rule.onNodeWithText("Done").performClick()
            rule.waitUntil(10_000) { active.value == null }
            rule.onNodeWithText("Practice more").assertIsDisplayed()
            rule.onNodeWithText("0 due, 3 new").assertDoesNotExist()
            screenshot("kana-pacing-after.png")
            assertEquals(0, runBlocking { fixture.remaining() })
            assertEquals(3, runBlocking {
                fixture.db.reviews().states(KANA_SOURCE_ID, fixture.script.id, fixture.mode.name).size
            })

            val ordinary = rule.runOnIdle { fixture.session(false).second.also { it.ensureStarted(fixture.script, fixture.mode, false) } }
            rule.waitUntil(10_000) { ordinary.ui is KanaStudyUi.Empty }
            rule.onNodeWithText("Practice more").performClick()
            answerCorrectly { requireNotNull(active.value).second }
            rule.runOnIdle { requireNotNull(active.value).second.leaveSession() }
            waitForWrites(fixture, 2)
            val sessions = runBlocking { fixture.db.reviews().allSessions() }
            assertEquals(listOf(false, true), sessions.sortedBy { it.startedAtMs }.map { it.extra })
            assertEquals(4, runBlocking {
                fixture.db.reviews().states(KANA_SOURCE_ID, fixture.script.id, fixture.mode.name).size
            })
            assertEquals(0, runBlocking { fixture.remaining() })
        }
    }

    @Test fun queuedKanaRepeatFollowsOtherQuestionsWithoutAConfirmation() {
        Fixture().use { fixture ->
            fixture.settings.setDailyNewCards(6)
            val vm = showSession(fixture)
            val seen = mutableListOf<String>()
            val remaining = listOf(6, 6, 5, 4, 3, 2, 1)
            remaining.forEachIndexed { index, count ->
                val question = question(vm)
                seen += question.card.kana
                assertEquals(count, question.remaining)
                rule.onNodeWithText("Attempts left: $count").assertIsDisplayed()
                rule.onNodeWithText("Includes queued repeats.", substring = true).assertIsDisplayed()
                if (index == 1) assertTrue(seen[0] != seen[1])
                if (index == 4) assertEquals(seen[0], question.card.kana)
                rule.onNodeWithText(if (index == 0) "I don't know" else question.card.kana)
                    .performScrollTo().performClick()
                assertEquals(index, recorded(fixture))
                rule.onNodeWithText("Next").performScrollTo().performClick()
                rule.waitUntil(10_000) {
                    vm.ui !is KanaStudyUi.QuizAnswer && recorded(fixture) == index + 1
                }
            }
            rule.onNodeWithText("Session complete").assertIsDisplayed()
            waitForWrites(fixture, 1)
            assertEquals(6, seen.distinct().size)
            assertEquals(7, recorded(fixture))
        }
    }

    @Test fun guessedKanaWaitsBehindAnotherQuestionAndTheFinalMissDoesNotPromiseAnotherAttempt() {
        Fixture().use { fixture ->
            fixture.settings.setDailyNewCards(2)
            val vm = showSession(fixture)
            val first = question(vm)
            rule.onNodeWithText(first.card.kana).performScrollTo().performClick()
            assertEquals(0, recorded(fixture))
            rule.onNodeWithText("I guessed").performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.ui is KanaStudyUi.QuizQuestion && recorded(fixture) == 1 }
            assertTrue(first.card.kana != question(vm).card.kana)
            assertEquals(2, question(vm).remaining)
            answerCorrectly { vm }
            rule.waitUntil(10_000) { recorded(fixture) == 2 }
            assertEquals(first.card.kana, question(vm).card.kana)
            rule.onNodeWithText("Attempts left: 1").assertIsDisplayed()
            rule.onNodeWithText("I don't know").performScrollTo().performClick()
            assertEquals(2, recorded(fixture))
            rule.onNodeWithText("Next").performScrollTo().performClick()
            rule.onNodeWithText("Session complete").assertIsDisplayed()
            waitForWrites(fixture, 1)
            assertEquals(listOf(2, 3, 1), runBlocking {
                fixture.db.reviews().allLog().filter { it.mode == fixture.mode.name }.sortedBy { it.id }.map { it.grade }
            })
        }
    }

    private fun showSession(fixture: Fixture): KanaStudyViewModel {
        val (route, vm) = fixture.session(false)
        rule.setContent { MaterialTheme { KanaStudyScreen(route, {}, { _, _ -> }, vm) } }
        return vm
    }

    private fun question(vm: KanaStudyViewModel): KanaStudyUi.QuizQuestion {
        rule.waitUntil(10_000) { vm.ui is KanaStudyUi.QuizQuestion }
        return rule.runOnIdle { vm.ui as KanaStudyUi.QuizQuestion }
    }

    private fun recorded(fixture: Fixture): Int = runBlocking {
        fixture.db.reviews().allLog().count { it.mode == fixture.mode.name }
    }

    private fun answerCorrectly(vm: () -> KanaStudyViewModel) {
        rule.waitUntil(10_000) { vm().ui is KanaStudyUi.QuizQuestion }
        val question = rule.runOnIdle { vm().ui as KanaStudyUi.QuizQuestion }
        rule.onNodeWithText(question.card.kana).performScrollTo().performClick()
        rule.onNodeWithText("Next").performScrollTo().performClick()
    }

    private fun waitForWrites(fixture: Fixture, count: Int) {
        rule.waitUntil(10_000) {
            runBlocking { fixture.db.reviews().allSessions().let { it.size == count && it.all { row -> row.endedAtMs != null } } }
        }
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private class Fixture(seedHistory: Boolean = true) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferences = "kana-pacing-${UUID.randomUUID()}"
        private val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = Settings(isolated).also { it.setWeekPlan("FFFFFFF"); it.setDailyNewCards(3) }
        val services = Services(db, settings, context)
        val models = ViewModelStore()
        val script = KanaScript.Katakana
        val mode = KanaMode.QuizRomajiToKana

        init {
            if (seedHistory) runBlocking {
                val earlier = Instant.now().minusSeconds(2 * 86_400).toEpochMilli()
                for (card in KanaCatalog.all(script)) {
                    db.reviews().record(
                        ReviewStateEntity(KANA_SOURCE_ID, script.id, card.kana, KanaMode.QuizKanaToRomaji.name,
                            "Review", 30.0, 5.0, earlier + 30 * 86_400_000L, earlier, 4, 0),
                        ReviewLogEntity(0, KANA_SOURCE_ID, script.id, card.kana, KanaMode.QuizKanaToRomaji.name, 3, earlier),
                    )
                }
                assertTrue(db.reviews().allSessions().isEmpty())
            }
        }

        suspend fun recordWeekAnswer(sourceId: String, at: Instant) {
            val mode = if (sourceId == KANA_SOURCE_ID) KanaMode.QuizKanaToRomaji.name else "WordJpEn"
            val stack = if (sourceId == KANA_SOURCE_ID) "hiragana" else "words-n5"
            val item = if (sourceId == KANA_SOURCE_ID) "あ" else "語"
            db.reviews().record(
                ReviewStateEntity(sourceId, stack, item, mode, "Review", 10.0, 5.0,
                    at.plusSeconds(7 * 86_400).toEpochMilli(), at.toEpochMilli(), 1, 0),
                ReviewLogEntity(0, sourceId, stack, item, mode, 3, at.toEpochMilli()),
            )
        }

        fun session(extra: Boolean): Pair<String, KanaStudyViewModel> {
            val route = "kana:${script.id}:${mode.name}:${UUID.randomUUID()}" + if (extra) ":extra" else ""
            return route to KanaStudyViewModel(services).also { models.put(route, it) }
        }

        suspend fun remaining() = db.newAllowance(settings, KANA_SOURCE_ID, Instant.now()).remaining

        suspend fun overview(): KanaOverview {
            val states = mapOf(KanaStateKey(script, mode) to db.reviews().states(KANA_SOURCE_ID, script.id, mode.name))
            val now = Instant.now()
            return KanaOverview(states, db.newAllowance(settings, KANA_SOURCE_ID, now), now)
        }

        override fun close() {
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
                runBlocking {
                    withTimeout(10_000) {
                        while (db.reviews().allSessions().any { it.endedAtMs == null }) delay(10)
                    }
                }
            } finally {
                db.close()
                context.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
                context.deleteSharedPreferences(preferences)
            }
        }
    }
}
