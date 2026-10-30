package io.github.stopcran.kanji.ui

import android.accessibilityservice.AccessibilityService
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.MainActivity
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.content.SafeZip
import io.github.stopcran.kanji.core.counters.*
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in only: imports a complete candidate into a synthetic source on a disposable emulator. */
class N3AppNavAcceptanceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var app: KanjiApp
    private lateinit var parsed: ParsedContent
    private lateinit var source: RepoSource
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var evidence: JSONObject
    private lateinit var name: String
    private var imeAnimations = AtomicInteger()
    private var grammarKind = GrammarTargetKind.Set

    @Test
    fun realCounterRoute() {
        val modeName = InstrumentationRegistry.getArguments().getString("n3Mode")
        assumeTrue("Specify a counter mode for this opt-in acceptance case", modeName != null)
        val mode = CounterMode.valueOf(requireNotNull(modeName))
        prepare("counter-${mode.name}")
        val target = when (mode) {
            CounterMode.Choose -> "tou-cow-1"
            CounterMode.Noun -> "tou-noun-1"
            CounterMode.Type -> "casesken-reservations-1"
            CounterMode.Expression -> "repeatintervals-expression-1"
            CounterMode.Reading -> "roomcounts-expression-3"
        }
        val all = CounterIndex(parsed.counters)
        val known = SrsState(CardPhase.Review, 30.0, 5.0, Instant.now().plus(30, ChronoUnit.DAYS),
            Instant.now().minus(2, ChronoUnit.DAYS), 3, 0)
        runBlocking {
            app.db.withTransaction {
                all.ids(mode).filterNot { it == target }.forEach {
                    app.db.reviews().putState(known.toEntity(source.id, COUNTER_STACK, it, mode.mode))
                }
                if (mode == CounterMode.Type || mode == CounterMode.Noun) {
                    all.cards.keys.forEach {
                        app.db.reviews().putState(known.toEntity(source.id, COUNTER_STACK, it, CounterMode.Choose.mode))
                    }
                }
            }
        }
        evidence.put("fixture", "Other complete-corpus items in this mode postponed; target unseen. Choose prerequisites seeded for Type/Noun.")
        evidence.put("target", target).put("mode", mode.name)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        watchIme()
        try {
            click("Numbers & counters")
            for (scope in CounterScope.entries) {
                click(rule.onAllNodesWithText(scope.label)[0])
                val selected = all.inScope(scope = scope)
                awaitText("${selected.cards.size + selected.expressions.size} prompts in this selection")
            }
            click("N3")
            assertEquals(CounterScope.N3, app.settings.counterScopeFor(source.id))
            click(rule.onNode(hasText("Start") and hasAnyAncestor(hasText(mode.label))))
            rule.waitUntil(20_000) { runCatching { counter().ui !is CounterUi.Loading }.getOrDefault(false) }
            val vm = counter()
            assertEquals(source.id, vm.sourceId)
            when (mode) {
                CounterMode.Choose -> {
                    val question = vm.ui as CounterUi.Choose
                    assertEquals(target, question.card.id)
                    assertEquals(3, question.file.level)
                    click(vm.index.counterLabel(question.file.counter))
                }
                CounterMode.Noun -> {
                    val question = vm.ui as CounterUi.NounQuestion
                    assertEquals(target, question.reverseId)
                    assertEquals(3, question.file.level)
                    assertTrue(question.options.any { all.fileOf.getValue(it.id).level > 3 })
                    val answer = all.reverse.getValue(target).answer
                    click(question.options.single { it.id == answer }.label)
                }
                CounterMode.Type -> {
                    assertEquals(target, (vm.ui as CounterUi.TypeQuestion).card.id)
                    type("いちくみ")
                    checkAnswer()
                    awaitText("Not quite")
                    assertEquals(CounterVerdict.Wrong, (vm.ui as CounterUi.TypeAnswer).result.verdict)
                    capture("wrong-alias")
                }
                CounterMode.Expression, CounterMode.Reading -> {
                    val question = vm.ui as CounterUi.ExpressionQuestion
                    assertEquals(target, question.item.id)
                    val answer = if (mode == CounterMode.Reading) "みへや" else question.item.written
                    type(answer)
                    if (mode == CounterMode.Reading) {
                        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                        assertSame(vm, counter())
                        assertEquals(target, (vm.ui as CounterUi.ExpressionQuestion).item.id)
                        assertEquals(answer, vm.draft)
                        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                        rule.onNode(hasSetTextAction()).assertTextContains(answer)
                        keyboardControls()
                        capture("rotation-restored-draft")
                    }
                    checkAnswer()
                }
            }
            awaitText(if (mode == CounterMode.Type) "Not quite" else "Correct!")
            capture("answer")
            if (mode == CounterMode.Reading) {
                click("Read the explanation")
                awaitText("Numbers, counters, time and dates")
                rule.onNodeWithContentDescription("Back").performClick()
                awaitText("Correct!")
                assertSame(vm, counter())
                assertEquals(target, (vm.ui as CounterUi.ExpressionAnswer).item.id)
                evidence.put("articleBackPreservedAnswer", true)
            }
            click("Next")
            awaitText("Session complete")
            rule.waitUntil(10_000) {
                runBlocking { app.db.reviews().allSessions().any { it.sourceId == source.id && it.endReason == "completed" } }
            }
            runBlocking {
                val state = app.db.reviews().states(source.id, COUNTER_STACK, mode.mode.name).single { it.kanji == target }
                app.db.reviews().putState(state.copy(dueMs = Instant.now().minusSeconds(1).toEpochMilli()))
            }
            app.settings.setCounterScope(source.id, CounterScope.N5)
            click("Practice more")
            rule.waitUntil(20_000) { counter() !== vm && counter().ui !is CounterUi.Loading }
            val extra = counter()
            val actualId = when (val question = extra.ui) {
                is CounterUi.Choose -> question.card.id
                is CounterUi.NounQuestion -> question.reverseId
                is CounterUi.TypeQuestion -> question.card.id
                is CounterUi.ExpressionQuestion -> question.item.id
                else -> error("Expected an extra-practice question: $question")
            }
            assertEquals(target, actualId)
            awaitText("Extra practice updates review history and scheduling.")
            if (mode == CounterMode.Type) {
                type("ひとくみ")
                checkAnswer()
                awaitText("Acceptable, but not the targeted counter")
                assertEquals(CounterVerdict.Yellow, (extra.ui as CounterUi.TypeAnswer).result.verdict)
                click("Next")
                rule.waitUntil(10_000) {
                    runBlocking { app.db.reviews().allLog().any { it.sourceId == source.id && it.kanji == target && it.grade == Grade.Hard.value } }
                }
            }
            evidence.put("capturedN3ContinuationDespiteChangedPreference", true)
            val logs = runBlocking { app.db.reviews().allLog().filter { it.sourceId == source.id } }
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.all { it.kanji == target && it.mode == mode.mode.name && it.stack == COUNTER_STACK })
            if (mode == CounterMode.Type) assertEquals(listOf(Grade.Again.value, Grade.Hard.value), logs.map { it.grade })
            evidence.put("grades", JSONArray(logs.map { it.grade })).put("passed", true)
            capture("continuation")
        } finally {
            finish()
        }
    }

    @Test
    fun realGrammarDiscoveryAndIntervalPractice() {
        prepare("grammar-intervals")
        val set = parsed.grammar.single { it.id == "quantity-scope" }
        val group = set.introductionGroups.single { it.id == "quantity-scope-event-intervals" }
        val newIds = group.members.map { it.exerciseId }.toSet()
        app.settings.setGrammarNewScope(source.id, GrammarScope.N3)
        app.settings.setGrammarBrowseScope(source.id, GrammarScope.All)
        app.settings.setGrammarBrowser(source, GrammarBrowserPreferences(GrammarTargetKind.Set, false))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        watchIme()
        try {
            click("Grammar")
            awaitText("Browse Grammar")
            click("Filters and next topic")
            click(rule.onAllNodesWithText("Unclassified")[1])
            awaitText("Browse: Unclassified")
            assertEquals(GrammarScope.N3, app.settings.grammarFor(source.id).newScope)
            topic(set.title)
            rule.waitUntil(20_000) {
                runCatching { (grammar().ui as? GrammarUi.Preview)?.preview?.target?.kind == GrammarTargetKind.Set }.getOrDefault(false)
            }
            val setPreview = (grammar().ui as GrammarUi.Preview).preview
            assertEquals(GrammarTargetKind.Set, setPreview.target.kind)
            assertEquals(set.exercises.map { it.id }.toSet(), setPreview.members.map { it.exercise.id }.toSet())
            assertEquals(22, setPreview.members.count { it.exercise.jlpt == null })
            assertTrue(setPreview.members.filter { it.exercise.id in newIds }.all { it.outOfScope })
            evidence.put("unclassifiedMembers", 22).put("n2ExcludedFromN3NewIntake", true)
            capture("unclassified-discovery")
            rule.onNodeWithContentDescription("Back").performClick()
            awaitText("Browse Grammar")
            click(rule.onAllNodesWithText("N2")[0])
            rule.waitUntil(10_000) { app.settings.grammarFor(source.id).newScope == GrammarScope.N2 }
            click(rule.onAllNodesWithText("N2")[1])
            awaitText("Browse: N2 only")
            click("Topics")
            rule.onNode(hasSetTextAction()).performTextReplacement(group.label)
            rule.waitUntil(10_000) { imeState().first }
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            rule.waitUntil(10_000) { !imeState().first }
            grammarKind = GrammarTargetKind.Group
            topic(group.label)
            rule.waitUntil(20_000) {
                runCatching { (grammar().ui as? GrammarUi.Preview)?.preview?.target?.kind == GrammarTargetKind.Group }.getOrDefault(false)
            }
            val preview = (grammar().ui as GrammarUi.Preview).preview
            assertEquals(newIds, preview.ids(false).toSet())
            assertTrue(preview.members.all { !it.outOfScope && it.missingPrerequisites.isEmpty() })
            click("Practice")
            val answered = mutableSetOf<String>()
            val forms = JSONArray()
            repeat(2) {
                rule.waitUntil(20_000) { grammar().ui is GrammarUi.Choose || grammar().ui is GrammarUi.Type }
                val vm = grammar()
                val exercise = requireNotNull(vm.currentExercise)
                assertTrue(exercise.id in newIds)
                answered += exercise.id
                val form = when (val question = vm.ui) {
                    is GrammarUi.Choose -> {
                        click(question.options.single { it.id == exercise.answer }.label)
                        "choose"
                    }
                    is GrammarUi.Type -> {
                        type(exercise.acceptedAnswers.single())
                        checkAnswer()
                        "type"
                    }
                    else -> error("Expected an interval question")
                }
                awaitText("Correct!")
                assertTrue((vm.ui as GrammarUi.Answer).correct)
                forms.put(JSONObject().put("id", exercise.id).put("mode", form))
                capture(exercise.id)
                click("Next")
            }
            assertEquals(newIds, answered)
            rule.waitUntil(20_000) { grammar().ui is GrammarUi.Done }
            rule.waitUntil(10_000) {
                runBlocking { app.db.reviews().allLog().count { it.sourceId == source.id } == newIds.size }
            }
            val logs = runBlocking { app.db.reviews().allLog().filter { it.sourceId == source.id } }
            assertEquals(newIds, logs.map { it.kanji }.toSet())
            assertTrue(logs.all { it.grade == Grade.Good.value })
            evidence.put("answers", forms).put("passed", true)
            capture("complete")
        } finally {
            finish()
        }
    }

    private fun prepare(case: String) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit disposable-emulator opt-in", args.getString("n3Disposable") == "true")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KanjiApp
        val archive = File(app.getExternalFilesDir(null), "n3-acceptance.zip")
        assertTrue("Push the complete current candidate archive", archive.isFile)
        parsed = archive.inputStream().use { ContentParser.parse(SafeZip.read(it)) }
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertTrue(parsed.grammarProblems.toString(), parsed.grammarProblems.isEmpty())
        assertTrue((3..5).all { level -> parsed.counters.any { it.level == level } })
        source = RepoSource("acceptance-fixture", "n3-${UUID.randomUUID()}", "main")
        assertTrue(app.settings.setSource("https://github.com/${source.id}", source.branch))
        val head = "1".repeat(40)
        val http = ContentHttp { url, _, _, _ ->
            val input = when (url) {
                "https://api.github.com/repos/${source.id}/commits/main" -> ByteArrayInputStream(head.toByteArray())
                source.commitZipUrl(head) -> archive.inputStream()
                else -> error("Unexpected fixture request: $url")
            }
            object : ContentResponse {
                override val code = 200
                override val body = input
                override fun header(name: String): String? = null
                override fun close() = input.close()
            }
        }
        val synced = runBlocking { ContentSync(app.db, app.settings.sync, http).sync(source, force = true) }
        assertTrue(synced.toString(), synced is SyncResult.Updated)
        assertTrue((synced as SyncResult.Updated).problems.isEmpty())
        app.settings.sync.lastCheckMs = System.currentTimeMillis()
        app.settings.setStartingGuideVisible(false)
        app.settings.setDailyNewCards(50)
        app.settings.setWeekPlan("FFFFFFF")
        name = "n3-appnav-$case"
        evidence = JSONObject().put("case", case).put("contentVersion", parsed.manifest.contentVersion)
            .put("source", source.id).put("host", "Actual MainActivity/AppNav")
            .put("transport", "Local complete candidate through production ContentSync; synthetic HEAD, not public sync")
            .put("fontScale", app.resources.configuration.fontScale)
    }

    private fun counter() = model("cquiz:") as CounterQuizViewModel
    private fun grammar() = model("grammarquiz:", grammarKind) as GrammarQuizViewModel

    private fun model(prefix: String, kind: GrammarTargetKind? = null): ViewModel {
        var result: ViewModel? = null
        scenario.onActivity { activity ->
            val key = activity.viewModelStore.keys().filter {
                it.startsWith(prefix) && (kind == null || GrammarPracticeRoutes.target(it).kind == kind)
            }.maxOrNull()
            result = key?.let { activity.viewModelStore[it] }
        }
        return requireNotNull(result)
    }

    private fun click(text: String) = click(rule.onNodeWithText(text))
    private fun click(node: SemanticsNodeInteraction) {
        rule.waitUntil(20_000) { runCatching { node.fetchSemanticsNode() }.isSuccess }
        rule.waitUntil(20_000) {
            node.isDisplayed() || node.onAncestors().filter(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        }
        val scrollParents = node.onAncestors().filter(hasScrollAction())
        for (index in scrollParents.fetchSemanticsNodes().indices.reversed()) {
            val parent = scrollParents[index]
            if (parent.onAncestors().filter(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
                parent.performScrollTo()
            }
        }
        if (scrollParents.fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
    }
    private fun type(value: String) {
        rule.onNode(hasSetTextAction()).performTextReplacement(value)
        keyboardControls()
    }
    private fun checkAnswer() {
        rule.onNodeWithText("Check").assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
    }
    private fun watchIme() {
        val animations = AtomicInteger().also { imeAnimations = it }
        scenario.onActivity { activity ->
            ViewCompat.setWindowInsetsAnimationCallback(activity.window.decorView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) animations.incrementAndGet()
                    }
                    override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>) = insets
                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) animations.decrementAndGet()
                    }
                })
        }
    }
    private fun imeState(): Triple<Boolean, Int, Int> {
        var state = Triple(false, 0, 0)
        scenario.onActivity {
            val decor = it.window.decorView
            val insets = ViewCompat.getRootWindowInsets(decor)
            val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            state = Triple(insets?.isVisible(WindowInsetsCompat.Type.ime()) == true, bottom, decor.height - bottom)
        }
        return state
    }
    private fun keyboardControls() {
        rule.onNode(hasSetTextAction()).performScrollTo().performClick()
        rule.waitUntil(20_000) {
            val state = imeState()
            state.first && state.second > 0 && imeAnimations.get() == 0
        }
        val unsure = rule.onNodeWithText("I don't know").performScrollTo().assertIsDisplayed().assertIsEnabled()
        val check = rule.onNodeWithText("Check").assertIsDisplayed().assertIsEnabled()
        val checkBounds = check.fetchSemanticsNode().boundsInWindow
        val unsureBounds = unsure.fetchSemanticsNode().boundsInWindow
        val (_, bottom, top) = imeState()
        val records = evidence.optJSONArray("keyboard") ?: JSONArray().also { evidence.put("keyboard", it) }
        records.put(JSONObject().put("imeBottom", bottom).put("keyboardTop", top).put("animations", imeAnimations.get())
            .put("checkBounds", checkBounds.toString()).put("unsureBounds", unsureBounds.toString()))
        capture("keyboard-${records.length()}")
        assertEquals(checkBounds.center.y, unsureBounds.center.y, 1f)
        assertTrue(checkBounds.top >= 0 && unsureBounds.top >= 0)
        assertTrue(checkBounds.bottom <= top && unsureBounds.bottom <= top)
    }
    private fun awaitText(text: String) = rule.waitUntil(30_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun topic(text: String) {
        rule.onNodeWithTag("grammar-list").performScrollTo().performScrollToNode(hasText(text))
        click(rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("grammar-list"))))
    }
    private fun rotate(orientation: Int, expected: Int) {
        scenario.onActivity { it.requestedOrientation = orientation }
        rule.waitUntil(20_000) { app.resources.configuration.orientation == expected }
        rule.waitForIdle()
        watchIme()
    }
    private fun capture(step: String, wait: Boolean = true) {
        if (wait) rule.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(app.getExternalFilesDir(null), "$name-$step.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }
    private fun finish() {
        runCatching {
            File(app.getExternalFilesDir(null), "$name-tree.txt").writeText(rule.onRoot().printToString())
        }.onFailure { evidence.put("treeCaptureError", it.toString()) }
        runCatching { capture("final", wait = false) }.onFailure { evidence.put("screenshotError", it.toString()) }
        File(app.getExternalFilesDir(null), "$name.json").writeText(evidence.toString(2))
        scenario.close()
    }
}
