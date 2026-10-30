package io.github.stopcran.kanji.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.AppNav
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.ArticleEntity
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.SyncMetaEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in synthetic-source acceptance on a disposable emulator, never a learner's phone. */
class ArchitectureNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun drawDocumentsRecreationReplacementAndRemovalUseTheRealNavigationOwner() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("architectureAcceptance") == "true")
        val app = rule.activity.application as KanjiApp
        val settings = app.settings
        val previousSource = settings.source.value
        val previousStack = settings.stack.value
        val previousWeek = settings.weekPlan.value
        val previousNew = settings.dailyNewCards.value
        val previousTrace = settings.traceNew.value
        val models = CopyOnWriteArrayList<DrawViewModel>()
        val inks = CopyOnWriteArrayList<CountingInk>()
        val source = "fixture/architecture-${UUID.randomUUID()}"
        fun mount() = rule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            activity.setContent {
                KanjiTheme {
                    AppNav(app) { services ->
                        val ink = CountingInk().also { inks += it }
                        DrawViewModel(services, ink).also { models += it }
                    }
                }
            }
        }
        try {
            settings.setSource("https://github.com/$source", "main")
            settings.setStack(Stacks.ALL)
            settings.setWeekPlan("FFFFFFF")
            settings.setDailyNewCards(1)
            settings.setTraceNew(false)
            runBlocking {
                app.db.content().insertKanji(listOf(KanjiEntity(
                    source, "一", "one", 5, "", 1, null, null, "イチ", "ひと", "",
                    "[Reference](../articles/navigation.md)",
                    """{"schemaVersion":1,"kanji":"一","viewBox":[0,0,100,100],"strokeCount":1,"strokes":[{"id":1,"points":[[10,50],[90,50]]}]}""", 0,
                )))
                app.db.content().insertArticles(listOf(ArticleEntity(source, "navigation", "Navigation reference",
                    "Top marker.\n\n" + (1..40).joinToString("\n\n") { "Paragraph $it." } +
                        "\n\nLast paragraph.\n\n[Open another copy](navigation.md)")))
                app.db.content().putMeta(SyncMetaEntity(source, "architecture-fixture", System.currentTimeMillis(), 0))
                for (days in 1..2) app.db.reviews().putLog(ReviewLogEntity(
                    0, source, Stacks.ALL, "一", StudyMode.Quiz.name, 3, System.currentTimeMillis() - days * 86_400_000L,
                ))
            }
            mount()
            click(rule.onNodeWithText("Kanji"))
            click(rule.onNode(hasText("Start") and hasAnyAncestor(hasText("Drawing"))))
            rule.waitUntil(15_000) { models.singleOrNull()?.ui is DrawUi.Question }
            val model = models.single()
            val look = model.look
            rule.onNode(hasContentDescription("Drawing area for the", substring = true))
                .performScrollTo().performTouchInput { swipeRight() }
            click(rule.onNodeWithText("Check"))
            rule.waitUntil(15_000) { model.ui is DrawUi.Answer }
            val answer = model.ui as DrawUi.Answer
            assertEquals(DrawOutcome.Clean, answer.outcome)
            click(rule.onNodeWithText("Reference"))
            awaitShown("Navigation reference")
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Last paragraph."))
            click(rule.onNodeWithText("Open another copy"))
            awaitShown("Top marker.")
            android.util.Log.i("ArchitectureNav", "Duplicate document ready; recreating Activity")
            val previousActivity = rule.activity
            rule.activityRule.scenario.recreate()
            android.util.Log.i("ArchitectureNav", "Activity recreated; mounting restored navigation")
            mount()
            assertNotSame(previousActivity, rule.activity)
            awaitShown("Top marker.")
            android.util.Log.i("ArchitectureNav", "Restored document is visible")
            assertEquals(1, models.size)
            assertEquals(0, inks.single().closed.get())
            rule.onNodeWithContentDescription("Back").performClick()
            awaitShown("Last paragraph.")
            rule.onNodeWithContentDescription("Back").performClick()
            rule.runOnIdle {
                assertSame(answer, model.ui)
                assertEquals(look, model.look)
                assertEquals(0, inks.single().closed.get())
            }
            click(rule.onNodeWithText("Next"))
            rule.onNodeWithText("Session complete").assertIsDisplayed()
            click(rule.onNodeWithText("Practice more"))
            rule.waitUntil(15_000) { models.size == 2 && models.last().ui is DrawUi.Question }
            assertEquals(1, inks.first().closed.get())
            assertEquals(0, inks.last().closed.get())
            repeat(2) {
                if (inks.last().closed.get() == 0) rule.onNodeWithContentDescription("Back").performClick()
            }
            rule.waitUntil(10_000) {
                runBlocking { app.db.reviews().allSessions().filter { it.sourceId == source }.let {
                    it.size == 2 && it.all { session -> session.endedAtMs != null }
                } }
            }
            assertTrue(inks.all { it.closed.get() == 1 })
            runBlocking {
                assertEquals(setOf("completed", "left"),
                    app.db.reviews().allSessions().filter { it.sourceId == source }.map { it.endReason }.toSet())
                assertEquals(1, app.db.reviews().allLog().count { it.sourceId == source && it.mode == StudyMode.Draw.name })
            }
            File(app.getExternalFilesDir(null), "architecture-navigation-native.json").writeText(JSONObject()
                .put("api", Build.VERSION.SDK_INT).put("source", source)
                .put("host", "Production AppNav and DrawViewModel; injected counting InkRecognition")
                .put("actualActivityRecreation", true).put("duplicateDocumentScrollRestored", true)
                .put("drawAnswerRetained", true).put("resourceClosedOnceOnReplaceAndPop", true)
                .put("sessions", 2).put("drawReviews", 1).toString(2))
        } catch (error: Throwable) {
            runCatching {
                File(app.getExternalFilesDir(null), "architecture-navigation-failure.txt").writeText(rule.onRoot().printToString())
            }
            throw error
        } finally {
            rule.activityRule.scenario.close()
            settings.setSource("https://github.com/${previousSource.id}", previousSource.branch)
            settings.setStack(previousStack)
            settings.setWeekPlan(previousWeek)
            settings.setDailyNewCards(previousNew)
            settings.setTraceNew(previousTrace)
        }
    }

    private fun click(node: SemanticsNodeInteraction) {
        rule.waitUntil(20_000) { runCatching { node.fetchSemanticsNode() }.isSuccess }
        rule.waitUntil(20_000) {
            node.isDisplayed() || node.onAncestors().filter(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        }
        if (node.onAncestors().filter(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun awaitShown(text: String) {
        rule.waitUntil(20_000) { rule.onNodeWithText(text).isDisplayed() }
    }

    private class CountingInk : InkRecognition {
        val closed = AtomicInteger()
        override suspend fun prepare() = ModelState.Ready
        override suspend fun candidates(strokes: List<List<TimedPt>>) = listOf("一")
        override fun close() { closed.incrementAndGet() }
    }
}
