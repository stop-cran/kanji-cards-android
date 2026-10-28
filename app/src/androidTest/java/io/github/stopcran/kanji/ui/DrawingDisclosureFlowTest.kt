package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class DrawingDisclosureFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun hiraganaTracingThenGradedRecall() = exercise(KanaScript.Hiragana, true)
    @Test fun katakanaTracingThenGradedRecall() = exercise(KanaScript.Katakana, true)
    @Test fun kanjiTracingThenGradedRecall() = exercise(null, true)
    @Test fun kanjiTraceOffStartsWithGradedRecall() = exercise(null, false)

    private fun exercise(script: KanaScript?, trace: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "drawing-disclosure-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = Settings(isolated).also {
            it.setSource("https://github.com/fixture/drawing", "main")
            it.setStack(Stacks.ALL)
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(1)
            it.setTraceNew(trace)
        }
        val models = ViewModelStore()
        val services = Services(db, settings, context)
        val kana = script?.let { KanaStudyViewModel(services) }
        val kanji = if (script == null) DrawViewModel(services) else null
        val route = if (script != null) "kana:${script.id}:${KanaMode.Draw.name}:${UUID.randomUUID()}" else "draw:${UUID.randomUUID()}"
        models.put(route, kana ?: requireNotNull(kanji))
        fun questionTrace() = (kana?.ui as? KanaStudyUi.DrawQuestion)?.trace ?: (kanji?.ui as? DrawUi.Question)?.trace
        fun answerTrace() = (kana?.ui as? KanaStudyUi.DrawAnswer)?.trace ?: (kanji?.ui as? DrawUi.Answer)?.trace
        fun recorded() = runBlocking { db.reviews().allLog().count { it.mode == StudyMode.Draw.name } }
        val notice = "Ungraded tracing practice. You'll try from memory later."
        val practiceFeedback = "Practice only: this attempt is not graded."
        try {
            if (script == null) runBlocking {
                val source = settings.source.value.id
                db.content().insertKanji(listOf(KanjiEntity(
                    source, "一", "one", 5, "", 1, null, null, "イチ", "ひと", "", "Fixture reference",
                    """{"schemaVersion":1,"kanji":"一","viewBox":[0,0,100,100],"strokeCount":1,"strokes":[{"id":1,"points":[[10,50],[90,50]]}]}""", 0,
                )))
                for (days in 1..2) db.reviews().putLog(ReviewLogEntity(
                    0, source, Stacks.ALL, "一", StudyMode.Quiz.name, 3, System.currentTimeMillis() - days * 86_400_000L,
                ))
            }
            rule.setContent { MaterialTheme {
                if (script != null) KanaStudyScreen(route, {}, { _, _ -> }, requireNotNull(kana))
                else DrawScreen(route, {}, {}, {}, requireNotNull(kanji))
            } }
            rule.waitUntil(10_000) { questionTrace() == trace }
            rule.onNodeWithText("Check").performScrollTo().assertIsNotEnabled()
            if (trace) {
                rule.onNodeWithText(notice).performScrollTo().assertIsDisplayed()
                capture("ux98-${script?.id ?: "kanji"}-trace.png")
                val pad = rule.onNode(hasContentDescription("Drawing area for the", substring = true))
                pad.performScrollTo().assertIsDisplayed().performTouchInput { swipeLeft() }
                rule.onNodeWithText("Undo").performScrollTo().performClick()
                rule.onNodeWithText("Check").assertIsNotEnabled()
                repeat(2) { pad.performScrollTo().performTouchInput { swipeLeft() } }
                rule.onNodeWithText("Clear").performScrollTo().performClick()
                rule.onNodeWithText("Check").assertIsNotEnabled()
                pad.performScrollTo().performTouchInput { swipeLeft() }
                rule.onNodeWithText("Check").performScrollTo().assertIsEnabled().performClick()
                rule.waitUntil(15_000) { answerTrace() == true }
                rule.onNodeWithText(practiceFeedback).performScrollTo().assertIsDisplayed()
                assertEquals(0, recorded())
                rule.onNodeWithText("Next").performScrollTo().performClick()
                rule.waitUntil(10_000) { questionTrace() == false }
                assertEquals(0, recorded())
            }
            rule.onNodeWithText(notice).assertDoesNotExist()
            rule.onNodeWithText(if (script == null) "Draw the kanji for" else "Draw the kana for")
                .performScrollTo().assertIsDisplayed()
            capture("ux98-${script?.id ?: "kanji"}-recall.png")
            rule.onNodeWithText("Check").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText("I don't remember").performScrollTo().performClick()
            rule.waitUntil(10_000) { answerTrace() == false }
            rule.onNodeWithText(practiceFeedback).assertDoesNotExist()
            assertEquals(0, recorded())
            rule.onNodeWithText("Next").performScrollTo().performClick()
            rule.waitUntil(10_000) { recorded() == 1 }
        } finally {
            rule.runOnIdle { models.clear() }
            runBlocking {
                withTimeout(10_000) {
                    while (db.reviews().allSessions().any { it.endedAtMs == null }) delay(10)
                }
            }
            db.close()
            context.deleteSharedPreferences(preferences)
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
