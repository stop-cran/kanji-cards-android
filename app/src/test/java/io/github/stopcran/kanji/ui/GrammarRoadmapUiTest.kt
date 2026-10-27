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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.stopcran.kanji.core.content.GrammarCurriculum
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.grammar.GrammarAvailability
import io.github.stopcran.kanji.core.grammar.GrammarEvidence
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarMapGroup
import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import io.github.stopcran.kanji.core.grammar.GrammarRoadmap
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarPractice
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.stateKey
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
    fun unmappedFoundationHasCommonDetailsWithEmbeddedPracticeAndSeparateArticleAndFocusActions() {
        val snapshot = partialSnapshot()
        var navigated: GrammarMapDestination? = null
        var practiced: String? = null
        var article: Pair<String, String>? = null
        var focus: String? = null
        val actions = GrammarMapActions({ navigated = it }, { path, topic -> article = path to topic }, { focus = it }, {})
        val current = mutableStateOf("q")
        rule.setContent {
            Page("Grammar roadmap", onBack = {}) {
                GrammarMapGroupDetails(
                    snapshot, snapshot.groups.getValue(current.value), destination.group(current.value), actions, rememberSaveableStateHolder(),
                ) {
                    GrammarPracticePreviewContent(preview(snapshot, current.value), current.value, embedded = true) { practiced = current.value }
                }
            }
        }
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText("Practice").performScrollTo().performClick()
        assertEquals("q", practiced)
        assertNull(focus)
        rule.onNodeWithText("Unmapped foundation").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(destination.group("p"), navigated)
        rule.runOnIdle { current.value = requireNotNull(navigated?.group) }
        rule.onNodeWithText("Unmapped authored group — its real foundations and earned readiness still apply.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Read: p").performScrollTo().performClick()
        assertEquals("articles/x.md" to snapshot.groups.getValue("p").group.label, article)
        assertNull(focus)
    }

    @Test
    fun loadingFramePreservesFullPageScrollOnArticleBackAndProcessRestoration() {
        val snapshot = partialSnapshot()
        val loaded = mutableStateOf<GrammarMapState>(GrammarMapState.Ready(snapshot))
        val route = mutableStateOf("map")
        val actions = GrammarMapActions({}, { _, _ -> route.value = "article" }, {}, {})
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(route.value) {
                if (route.value == "map") {
                    GrammarRoadmapPage(loaded.value, destination.group("q"), actions, {}) {
                        GrammarPracticePreviewContent(preview(snapshot, "q"), "session", embedded = true) {}
                    }
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
        val actions = GrammarMapActions({ route.value = "group" }, { _, _ -> }, {}, {})
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
        val restored = io.github.stopcran.kanji.restoreStudyRoutes(stack, sameProcess = false)
        assertEquals(target.target(), GrammarPracticeRoutes.target(restored[1]))
        assertEquals(stack.last(), restored.last())
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

    @Test
    fun rotationKeepsPendingConsentButFreshSessionsAndChangedPreviewsClearItWithoutLosingDetailState() {
        val snapshot = partialSnapshot()
        val state = mutableStateOf(preview(snapshot, "q"))
        var route = GrammarPracticeRoutes.create(state.value.preview.target)
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(stateKey(2, route)) {
                GrammarRoadmapPage(GrammarMapState.Ready(snapshot), destination.group("q"), GrammarMapActions({}, { _, _ -> }, {}, {}), {}) {
                    GrammarPracticePreviewContent(state.value, route, embedded = true) {}
                }
            }
        }
        rule.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNode(isToggleable()).assertIsDisplayed().assertIsOn()
        route = GrammarPracticeRoutes.create(state.value.preview.target)
        restoration.emulateSavedInstanceStateRestore()
        rule.onNode(isToggleable()).assertIsDisplayed().assertIsOff().performClick()
        rule.runOnIdle { state.value = state.value.copy(preview = state.value.preview.copy(contentVersion = "new-version")) }
        rule.onNode(isToggleable()).assertIsOff()
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp")
    fun startLoadingPreservesCollectionContextButNotConsentForStalePreviewsAndFreshSessions() {
        val snapshot = collectionSnapshot()
        val target = destination.set(snapshot.study.sets.single().id)
        val state = mutableStateOf(GrammarUi.Preview(GrammarPractice.preview(
            requireNotNull(target.target()), snapshot.study.sets, snapshot.curriculum, snapshot.study.availability,
            "accepted-version", 6, GrammarScope.All, 10,
        )))
        val loaded = mutableStateOf<GrammarMapState>(GrammarMapState.Ready(snapshot))
        val phase = mutableStateOf("details")
        val session = mutableStateOf("first")
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(phase.value) {
                if (phase.value == "details") {
                    GrammarRoadmapPage(loaded.value, target, GrammarMapActions({}, { _, _ -> }, {}, {}), {}) {
                        GrammarPracticePreviewContent(state.value, session.value, embedded = true) { loaded.value = GrammarMapState.Loading }
                    }
                } else {
                    Page("Question", onBack = {}) { }
                }
            }
        }
        rule.onNodeWithTag("grammar-diagram").performScrollTo().performSemanticsAction(SemanticsActions.ScrollBy) { it(100f, 450f) }
        fun pan() = rule.onNodeWithTag("grammar-diagram").fetchSemanticsNode().config.let {
            it[SemanticsProperties.HorizontalScrollAxisRange].value() to it[SemanticsProperties.VerticalScrollAxisRange].value()
        }
        val originalPan = pan()
        assertTrue(originalPan.first > 0 && originalPan.second > 0)
        rule.onNodeWithText("Accessible list").performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("part")
        rule.onNode(hasSetTextAction()).assertTextContains("part")
        rule.onNodeWithTag("grammar-list").performScrollTo().performScrollToIndex(8)
        rule.onNodeWithTag("grammar-node:part-08").performClick()
        fun listPosition() = rule.onNodeWithTag("grammar-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val originalPosition = listPosition()
        assertTrue(originalPosition > 0)
        fun checkContext() {
            rule.onNode(hasSetTextAction()).assertTextContains("part")
            rule.onNodeWithText("Accessible list").assertIsSelected()
            rule.onNodeWithTag("grammar-list").performScrollTo()
            rule.onNodeWithTag("grammar-node:part-08").assertIsDisplayed().assertIsSelected()
            assertEquals(originalPosition, listPosition())
        }
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText("Practice").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Loading…").assertIsDisplayed()
        rule.runOnIdle {
            state.value = state.value.copy(preview = state.value.preview.copy(contentVersion = "updated-version"))
            loaded.value = GrammarMapState.Ready(snapshot)
        }
        checkContext()
        rule.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
        rule.onNodeWithText("Practice").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Loading…").assertIsDisplayed()
        rule.runOnIdle { phase.value = "question" }
        rule.onNodeWithText("Question").assertIsDisplayed()
        rule.runOnIdle { session.value = "fresh"; phase.value = "details" }
        rule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { loaded.value = GrammarMapState.Ready(snapshot) }
        checkContext()
        rule.onNode(isToggleable()).performScrollTo().assertIsOff()
        rule.onNodeWithText("Diagram").performScrollTo().performClick()
        rule.onNodeWithTag("grammar-diagram").performScrollTo()
        assertEquals(originalPan, pan())
    }

    private fun collectionSnapshot(): GrammarRoadmapSnapshot {
        val sets = listOf(grammarFixture("p"), grammarFixture("q", requires = listOf("p"))) +
            (0 until 12).map { grammarFixture("part-${it.toString().padStart(2, '0')}") }
        val combined = sets.first().copy(
            id = "combined", exercises = sets.flatMap { it.exercises }, introductionGroups = sets.flatMap { it.introductionGroups },
        )
        GrammarPractice.validateSnapshot(listOf(combined))
        return partialSnapshot(listOf(combined))
    }

    private fun preview(snapshot: GrammarRoadmapSnapshot, id: String) = GrammarUi.Preview(GrammarPractice.preview(
        requireNotNull(destination.group(id).target()), snapshot.study.sets, snapshot.curriculum, snapshot.study.availability,
        "accepted-version", 6, GrammarScope.All, 10,
    ))

    @Test
    fun articleSetContentsUseActualMembershipIncludingCompatibilityWithoutInventingSetArrows() {
        val p = grammarFixture("p")
        val q = grammarFixture("q", requires = listOf("p"))
        val legacy = grammarFixture("legacy", authored = false)
        val combined = p.copy(id = "combined", exercises = p.exercises + q.exercises + legacy.exercises,
            introductionGroups = p.introductionGroups + q.introductionGroups)
        GrammarPractice.validateSnapshot(listOf(combined))
        val snapshot = partialSnapshot(listOf(combined))
        val whole = grammarBrowserContent(snapshot, destination, GrammarTargetKind.Set)
        assertEquals(combined.exercises.size, whole.nodes.single().progress.total)
        assertTrue(whole.edges.isEmpty())
        val contents = grammarBrowserContent(snapshot, destination.set("combined"), GrammarTargetKind.Group)
        assertEquals(3, contents.nodes.size)
        assertEquals(listOf(GrammarLeafEdge("p", "q")), contents.edges)
        assertEquals(listOf("q"), grammarBrowserContent(snapshot, destination.chapter("later"), GrammarTargetKind.Group).nodes.map { it.id })
        assertEquals(listOf("p"), grammarBrowserContent(snapshot, destination, GrammarTargetKind.Group, GrammarTopicFilter.Unmapped).nodes.map { it.id })
        val compatibility = grammarBrowserContent(snapshot, destination, GrammarTargetKind.Group, GrammarTopicFilter.Compatibility).nodes.single()
        assertEquals(legacy.exercises.size, compatibility.progress.total)
    }

    @Test
    fun missingChaptersFallBackExplicitlyAndTheSameSearchWorksAcrossSetsAndTopicLists() {
        val snapshot = partialSnapshot().copy(curriculum = GrammarCurriculumState.Absent)
        rule.setContent {
            Page("Grammar", onBack = {}, ime = true) {
                GrammarMapBrowser(snapshot, destination, GrammarMapActions({}, { _, _ -> }, {}, {}))
            }
        }
        rule.onNodeWithText("Chapter browsing is unavailable. Showing the topic list; article sets remain available.").assertIsDisplayed()
        rule.onNodeWithTag("grammar-diagram").assertDoesNotExist()
        rule.onNodeWithText("Article sets").performScrollTo().performClick()
        rule.onNodeWithText("Article sets").assertIsSelected()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("q")
        rule.onNodeWithTag("grammar-list").performScrollTo()
        rule.onNodeWithTag("grammar-node:q").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("grammar-node:p").assertDoesNotExist()
        rule.onNodeWithText("Topics").performScrollTo().performClick()
        rule.onNodeWithText("Accessible list").performScrollTo().performClick()
        rule.onNodeWithTag("grammar-list").performScrollTo()
        rule.onNodeWithTag("grammar-node:q").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("grammar-node:p").assertDoesNotExist()
    }

    private fun partialSnapshot(
        sets: List<GrammarSet> = listOf(grammarFixture("p"), grammarFixture("q", requires = listOf("p"))),
    ): GrammarRoadmapSnapshot {
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
