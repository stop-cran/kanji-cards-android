package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.stopcran.kanji.core.content.GrammarCurriculum
import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.grammar.GrammarAvailability
import io.github.stopcran.kanji.core.grammar.GrammarEvidence
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarMapGroup
import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import io.github.stopcran.kanji.core.grammar.GrammarRoadmap
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammarFixture
import io.github.stopcran.kanji.data.GrammarPreferences
import io.github.stopcran.kanji.data.GrammarRoadmapSnapshot
import io.github.stopcran.kanji.data.GrammarStudy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GrammarRoadmapUiTest {
    @get:Rule val rule = createComposeRule()
    private val destination = GrammarMapDestination("owner/repo", "main")

    @Test
    fun thousandNodeDiagramCullsCardsPansWithoutSelectionAndRestoresItsViewport() {
        val ids = (0 until 1000).map { "node-${it.toString().padStart(4, '0')}" }
        val layout = GrammarRoadmap.layout(ids, (40 until ids.size).map { GrammarLeafEdge(ids[it - 40], ids[it]) })
        val nodes = mutableStateOf(ids.associateWith { id ->
            GrammarMapNode(destination.group(id), id, GrammarMapProgress(1, 4, 4, 0, 0, 1, 0, 0, 1, 0))
        })
        val restoration = StateRestorationTester(rule)
        var selected: GrammarMapDestination? = null
        var density = 1f
        restoration.setContent {
            density = LocalDensity.current.density
            GrammarRoadmapDiagram(layout, nodes.value) { selected = it }
        }
        val card = SemanticsMatcher("roadmap card") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("grammar-node:") == true }
        fun bounded() {
            val count = rule.onAllNodes(card).fetchSemanticsNodes().size
            assertTrue("Only viewport cards may be composed: $count", count in 1..12)
        }
        bounded()
        rule.onNodeWithTag("grammar-diagram").performSemanticsAction(SemanticsActions.ScrollBy) {
            it(344f * density * 10, 392f * density * 15)
        }
        bounded()
        rule.onNodeWithTag("grammar-node:node-0415").assertIsDisplayed()
        val scrollOffset = rule.onNodeWithTag("grammar-diagram").fetchSemanticsNode().config[SemanticsActions.ScrollByOffset]
        rule.runOnIdle { runBlocking { scrollOffset(Offset(-344f * density, -392f * density)) } }
        rule.onNodeWithTag("grammar-node:node-0374").assertIsDisplayed()
        rule.onNodeWithTag("grammar-diagram").performSemanticsAction(SemanticsActions.ScrollBy) { it(344f * density, 392f * density) }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("grammar-node:node-0415").assertIsDisplayed()
        rule.runOnIdle {
            nodes.value = nodes.value.mapValues { (_, node) -> node.copy(progress = node.progress.copy(introduced = 1)) }
        }
        rule.onNodeWithTag("grammar-node:node-0415").assertIsDisplayed().performClick()
        assertEquals("node-0415", selected?.group)
        selected = null
        val beforeDrag = rule.onNodeWithTag("grammar-diagram").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        rule.onNodeWithTag("grammar-diagram").performTouchInput { swipeLeft() }
        rule.runOnIdle { assertNull(selected) }
        val afterDrag = rule.onNodeWithTag("grammar-diagram").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue(afterDrag > beforeDrag)
        bounded()
    }

    @Test
    fun unmappedFoundationHasAnAuthoredDetailsDestinationAndArticleAndPracticeRemainSeparate() {
        val snapshot = partialSnapshot()
        var navigated: GrammarMapDestination? = null
        var practiced: String? = null
        var article: String? = null
        var focus: String? = null
        val actions = GrammarMapActions({ navigated = it }, { practiced = it.id }, { article = it }, { focus = it }, {})
        val current = mutableStateOf("q")
        rule.setContent {
            Page("Grammar roadmap", onBack = {}) {
                GrammarMapGroupDetails(snapshot, snapshot.groups.getValue(current.value), destination.group(current.value), actions)
            }
        }
        rule.onNodeWithText("Practice this group").performScrollTo().performClick()
        assertEquals("q", practiced)
        assertNull(focus)
        rule.onNodeWithText("Unmapped foundation").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(destination.group("p"), navigated)
        rule.runOnIdle { current.value = requireNotNull(navigated?.group) }
        rule.onNodeWithText("Unmapped authored group — its real foundations and earned readiness still apply.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Read: p").performScrollTo().performClick()
        assertEquals("articles/x.md", article)
        assertNull(focus)
    }

    @Test
    fun loadingFramePreservesFullPageScrollOnArticleBackAndProcessRestoration() {
        val snapshot = partialSnapshot()
        val loaded = mutableStateOf<GrammarMapState>(GrammarMapState.Ready(snapshot))
        val route = mutableStateOf("map")
        val actions = GrammarMapActions({}, {}, { route.value = "article" }, {}, {})
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(route.value) {
                if (route.value == "map") {
                    GrammarRoadmapPage(loaded.value, destination.group("q"), actions, {})
                } else {
                    Page("Article", onBack = { loaded.value = GrammarMapState.Loading; route.value = "map" }) { }
                }
            }
        }
        rule.onNodeWithText("Read: q").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        rule.runOnIdle { loaded.value = GrammarMapState.Ready(snapshot) }
        rule.onNodeWithText("Read: q").assertIsDisplayed()
        rule.runOnIdle { loaded.value = GrammarMapState.Loading }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.runOnIdle { loaded.value = GrammarMapState.Ready(snapshot) }
        rule.onNodeWithText("Read: q").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp")
    fun chapterSelectionSurvivesDetailsBackModeChangesAndProcessRestoration() {
        val snapshot = partialSnapshot()
        val route = mutableStateOf("chapter")
        val actions = GrammarMapActions({ route.value = "group" }, {}, {}, {}, {})
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(route.value) {
                if (route.value == "chapter") {
                    GrammarRoadmapPage(GrammarMapState.Ready(snapshot), destination.chapter("later"), actions, {})
                } else {
                    Page("Group", onBack = { route.value = "chapter" }) { }
                }
            }
        }
        rule.onNodeWithTag("grammar-node:q").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag("grammar-node:q").assertIsSelected()
        rule.onNodeWithText("Accessible list").performScrollTo().performClick()
        rule.onNodeWithTag("grammar-node:q").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("grammar-node:q").assertIsSelected()
        rule.onNodeWithText("Diagram").performScrollTo().performClick()
        rule.onNodeWithTag("grammar-node:q").assertIsSelected()
    }

    @Test
    fun listAlternativeKeepsLongLabelsReadableAtLargeTextAndRoutesRetainSourceBranchAndIdentity() {
        val target = GrammarMapDestination("owner/repo", "feature/roadmap", group = "legacy:example:contrast")
        assertEquals(target, GrammarRoadmapRoutes.parse(GrammarRoadmapRoutes.create(target)))
        val stack = listOf("home", GrammarRoadmapRoutes.create(target), "doc:articles/x.md")
        assertEquals(stack, io.github.stopcran.kanji.restoreStudyRoutes(stack, sameProcess = false))
        assertFalse(target.matches(requireNotNull(RepoSource.parse("https://github.com/owner/repo", "main"))))
        assertThrows(IllegalArgumentException::class.java) { GrammarRoadmapRoutes.parse("grammar-map:%bad") }
        val label = "A long topic label that should wrap in the accessible list without losing its explanation or actions"
        var selected: GrammarMapDestination? = null
        rule.setContent {
            val base = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(base.density, 1.5f)) {
                Column {
                    GrammarMapList(listOf(GrammarMapNode(target, label, GrammarMapProgress(1, 6, 0, 3, 1, 0, 0, 0, 1, 0)))) { selected = it }
                }
            }
        }
        rule.onNodeWithText(label).assertIsDisplayed().performClick()
        assertEquals(target, selected)
    }

    private fun partialSnapshot(): GrammarRoadmapSnapshot {
        val sets = listOf(grammarFixture("p"), grammarFixture("q", requires = listOf("p")))
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val groups = GrammarFocus.groups(sets)
        val exercises = sets.flatMap { it.exercises }.associateBy { it.id }
        val availability = GrammarAvailability.evaluate(groups, exercises, emptyMap(), GrammarScope.All, true, emptySet(), 0, now)
        val study = GrammarStudy(sets, emptyMap(), GrammarPreferences(parserEpoch = 6), emptyList(), emptyList(), 0, now, null, availability = availability)
        val items = groups.associate { group ->
            group.id to GrammarMapGroup(group, availability.getValue(group.id),
                GrammarEvidence.evaluate(group, exercises, emptyMap(), emptyList(), now), false, 0, mapOf("articles/x.md" to group.label))
        }
        val catalog = GrammarCurriculum.decode("""{"schemaVersion":1,"chapters":[{"id":"later","label":"Later","groups":["q"]}]}""", sets)
        return GrammarRoadmapSnapshot(study, catalog, items)
    }
}
