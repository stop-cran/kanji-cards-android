package io.github.stopcran.kanji.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class KanaPacingFlowTest {
    @get:Rule val rule = createComposeRule()

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
            rule.onNodeWithText("0 due, 3 new today").assertIsDisplayed()
            screenshot("kana-pacing-before.png")
            rule.onNodeWithText("Start").performClick()
            repeat(3) { answerCorrectly { requireNotNull(active.value).second } }
            rule.onNodeWithText("Session complete").assertIsDisplayed()
            waitForWrites(fixture, 1)
            rule.onNodeWithText("Done").performClick()
            rule.waitUntil(10_000) { active.value == null }
            rule.onNodeWithText("Practice more").assertIsDisplayed()
            rule.onNodeWithText("0 due, 3 new today").assertDoesNotExist()
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

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferences = "kana-pacing-${UUID.randomUUID()}"
        private val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        private val settings = Settings(isolated).also { it.setWeekPlan("FFFFFFF"); it.setDailyNewCards(3) }
        private val services = Services(db, settings, context)
        val models = ViewModelStore()
        val script = KanaScript.Katakana
        val mode = KanaMode.QuizRomajiToKana

        init {
            runBlocking {
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
