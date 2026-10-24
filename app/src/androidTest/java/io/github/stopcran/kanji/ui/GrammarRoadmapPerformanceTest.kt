package io.github.stopcran.kanji.ui

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import io.github.stopcran.kanji.core.grammar.GrammarRoadmap
import io.github.stopcran.kanji.data.grammarRoadmap
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.system.measureNanoTime

/** Real renderer, synthetic navigation only: never inserts fixtures into the learner's database. */
class GrammarRoadmapPerformanceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun thousandNodesMeetTheDebugEmulatorBudgetWithoutComposingTheWholeGraph() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.resources.configuration.fontScale == 1f) { "The benchmark profile requires default text size." }
        val ids = (0 until 1000).map { "node-${it.toString().padStart(4, '0')}" }
        val edges = (40 until ids.size).map { GrammarLeafEdge(ids[it - 40], ids[it]) }
        repeat(10) { GrammarRoadmap.layout(ids, edges) }
        val layoutTimes = (0 until 30).map { measureNanoTime { GrammarRoadmap.layout(ids, edges) } / 1_000_000.0 }.sorted()
        val layout = GrammarRoadmap.layout(ids, edges)
        val destination = GrammarMapDestination("benchmark/local", "main")
        val nodes = ids.mapIndexed { index, id ->
            id to GrammarMapNode(destination.group(id), "Synthetic topic $id",
                GrammarMapProgress(1, 4, 4, 0, 0, 1, 0, 0, if (index < 40) 1 else 0, if (index < 40) 0 else 1))
        }.toMap()
        val shown = mutableStateOf(false)
        val realRoute = mutableStateOf<String?>(null)
        val app = context.applicationContext as KanjiApp
        var density = 1f
        rule.setContent {
            KanjiTheme {
                density = LocalDensity.current.density
                val route = realRoute.value
                if (route != null) {
                    key(route) {
                        if (route == "grammar-home") {
                            GrammarPage(app, {}, { _, _ -> }, {}, { realRoute.value = GrammarPracticeRoutes.create(it) })
                        } else {
                            GrammarQuizScreen(
                                route, {}, { _, _ -> }, {}, { realRoute.value = GrammarPracticeRoutes.create(it) },
                                { realRoute.value = "grammar-home" }, target = GrammarPracticeRoutes.target(route),
                            )
                        }
                    }
                } else {
                    Page("Roadmap performance fixture") {
                        if (shown.value) GrammarRoadmapDiagram(layout, nodes) {} else Text("Initializing renderer")
                    }
                }
            }
        }
        rule.waitForIdle()
        val firstMs = measureNanoTime { rule.runOnIdle { shown.value = true }; rule.waitForIdle() } / 1_000_000.0
        val card = SemanticsMatcher("roadmap card") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("grammar-node:") == true }
        val counts = mutableListOf(rule.onAllNodes(card).fetchSemanticsNodes().size)
        val interactionTimes = (0 until 8).map { index ->
            val direction = if (index % 2 == 0) 1 else -1
            val ms = measureNanoTime {
                rule.onNodeWithTag("grammar-diagram").performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(direction * 344f * density * 10, direction * 392f * density * 15)
                }
                rule.waitForIdle()
            } / 1_000_000.0
            counts += rule.onAllNodes(card).fetchSemanticsNodes().size
            ms
        }
        val report = JSONObject()
            .put("device", Build.MODEL).put("api", Build.VERSION.SDK_INT)
            .put("densityDpi", context.resources.displayMetrics.densityDpi)
            .put("nodes", ids.size).put("edges", edges.size)
            .put("layoutWarmP95Ms", layoutTimes[28]).put("firstRenderMs", firstMs)
            .put("interactionMs", JSONArray(interactionTimes)).put("maxComposedCards", counts.max())
        File(context.cacheDir, "roadmap-synthetic-benchmark.json").writeText(report.toString(2))
        File(context.cacheDir, "roadmap-synthetic-benchmark.png").outputStream().use {
            rule.onNodeWithTag("grammar-diagram").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Log.i("RoadmapPerf", report.toString())
        assertTrue("Layout budget exceeded: $report", layoutTimes[28] < 100)
        assertTrue("Render budget exceeded: $report", firstMs < 2000)
        assertTrue("Interaction budget exceeded: $report", interactionTimes.all { it < 1000 })
        assertTrue("Too many composed cards: $report", counts.all { it in 1..12 })
        if (InstrumentationRegistry.getArguments().getString("roadmapCorpus") == "real") {
            measureAcceptedCorpus(app, realRoute)
        }
    }

    private fun measureAcceptedCorpus(app: KanjiApp, route: MutableState<String?>) {
        val source = app.settings.source.value
        val snapshot = runBlocking { app.db.grammarRoadmap(app.settings, source, Instant.now()) }
        val catalog = snapshot.curriculum as GrammarCurriculumState.Ready
        val largest = catalog.curriculum.chapters.maxBy { it.groups.size }
        val largestSet = snapshot.study.sets.maxBy { it.exercises.size }
        val overview = GrammarMapDestination(source.id, source.branch)
        val preferences = app.settings.grammarBrowserFor(source)
        app.settings.setGrammarBrowser(source, io.github.stopcran.kanji.data.GrammarBrowserPreferences())
        val destinations = listOf(overview, overview.chapter(largest.id), overview.set(largestSet.id), overview)
        val times = try { destinations.map { destination ->
            measureNanoTime {
                rule.runOnIdle {
                    route.value?.let(app.services.studySessions::leave)
                    route.value = destination.target()?.let { GrammarPracticeRoutes.create(it) } ?: "grammar-home"
                }
                rule.waitUntil(timeoutMillis = 10_000) {
                    rule.onAllNodesWithTag("grammar-diagram").fetchSemanticsNodes().isNotEmpty()
                }
                rule.onNodeWithTag("grammar-diagram").performScrollTo()
                rule.waitForIdle()
            } / 1_000_000.0
        } } finally { app.settings.setGrammarBrowser(source, preferences) }
        rule.runOnIdle { route.value = GrammarPracticeRoutes.create(requireNotNull(overview.chapter(largest.id).target())) }
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithTag("grammar-diagram").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Practice").performScrollTo().assertIsEnabled()
        val report = JSONObject().put("source", source.id).put("chapters", catalog.curriculum.chapters.size)
            .put("authoredGroups", snapshot.groups.values.count { it.group.authored })
            .put("compatibilityGroups", snapshot.groups.values.count { !it.group.authored })
            .put("exercises", snapshot.study.sets.sumOf { it.exercises.size })
            .put("largestChapter", largest.id).put("largestChapterGroups", largest.groups.size)
            .put("largestSet", largestSet.id).put("largestSetExercises", largestSet.exercises.size)
            .put("navigationToInteractiveMs", JSONArray(times))
        File(app.cacheDir, "roadmap-real-benchmark.json").writeText(report.toString(2))
        Log.i("RoadmapPerf", report.toString())
        assertTrue("Real-corpus navigation budget exceeded: $report", times.all { it < 2000 })
    }
}
