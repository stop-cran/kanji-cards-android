package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.content.SafeZip
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterIndex
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterRoutes
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.CounterEntity
import io.github.stopcran.kanji.data.COUNTER_STACK
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.toEntity
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Push counter-acceptance.zip to the target's external files directory.
 * N3 entry cases require a real candidate and fail rather than skip if it is missing.
 * Uses only an in-memory database and uniquely named preferences, never learner history.
 */
class CounterFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val imeAnimations = AtomicInteger()
    private lateinit var keyboardEvidence: File
    private lateinit var keyboardPrefix: String
    private var keyboardStep = 0

    @Before fun prepareWindow() {
        rule.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            ViewCompat.setWindowInsetsAnimationCallback(it.window.decorView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) imeAnimations.incrementAndGet()
                    }
                    override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>) = insets
                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) imeAnimations.decrementAndGet()
                    }
                })
        }
    }

    @Test fun realReadingAndArticleBack() = exercise(CounterMode.Reading)
    @Test fun realWritingAndExtraContinuation() = exercise(CounterMode.Expression)
    @Test fun realLegacyTypingActionsStayTogether() = exercise(CounterMode.Type, group = CounterGroup.Everyday)
    @Test fun realN4EverydayReading() = exercise(CounterMode.Reading, CounterScope.N4, CounterGroup.Everyday)
    @Test fun realN4TimeWriting() = exercise(CounterMode.Expression, CounterScope.N4)
    @Test fun realN4PracticalReading() = exercise(CounterMode.Reading, CounterScope.N4,
        CounterGroup.Everyday, setOf("soku", "chaku", "ken", "mei", "do"))
    @Test fun realN4OvernightWriting() = exercise(CounterMode.Expression, CounterScope.N4,
        CounterGroup.Time, setOf("hakuexpressions"))
    @Test fun realN3TimeWritingFromHome() = exercise(CounterMode.Expression, CounterScope.N3,
        CounterGroup.Time, viaHome = true, expressionsOnly = true)
    @Test fun realN3EverydayReadingFromHome() = exercise(CounterMode.Reading, CounterScope.N3,
        CounterGroup.Everyday, viaHome = true, expressionsOnly = true)
    @Test fun realN3ChooseNewFamilyFromHome() = exercise(CounterMode.Choose, CounterScope.N3,
        CounterGroup.Everyday, setOf("tou"), viaHome = true)
    @Test fun realN3ReverseNewFamilyFromHome() = exercise(CounterMode.Noun, CounterScope.N3,
        CounterGroup.Everyday, setOf("tou"), viaHome = true)

    private fun exercise(mode: CounterMode, scope: CounterScope = CounterScope.N5,
                         group: CounterGroup = CounterGroup.Time, focusFiles: Set<String> = emptySet(),
                         viaHome: Boolean = false, expressionsOnly: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.getExternalFilesDir(null), "counter-acceptance.zip")
        requireFixture("Provide the real content archive for native acceptance", archive.isFile, viaHome)
        val parsed = archive.inputStream().use { ContentParser.parse(SafeZip.read(it)) }
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        val targetLevel = (1..5).first(scope::includes)
        requireFixture("Retain the complete easier corpus", (targetLevel..5).all { level ->
            parsed.counters.any { it.level == level }
        }, viaHome)
        val targetFiles = parsed.counters.filter {
            it.level == targetLevel && (group == CounterGroup.All || it.group == group.code) &&
                (focusFiles.isEmpty() || it.id in focusFiles) && (!expressionsOnly || it.schemaVersion == 2)
        }
        requireFixture("Provide level-$targetLevel ${group.code} content for ${mode.name}",
            CounterIndex(targetFiles).ids(mode).isNotEmpty(), viaHome)
        requireFixture("Provide the practical-family content",
            focusFiles.all { id -> targetFiles.any { it.id == id } }, viaHome)
        val targetIds = CounterIndex(targetFiles).ids(mode).toSet()
        val theme = if (InstrumentationRegistry.getArguments().getString("counterTheme") == "dark") "dark" else "light"
        keyboardPrefix = "counter-${scope.code}-${group.code}-${mode.name}-$theme-font${context.resources.configuration.fontScale}"
        keyboardEvidence = File(context.getExternalFilesDir(null), "$keyboardPrefix-controls.jsonl").apply { writeText("") }
        val preferenceName = "counter-flow-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferenceName, mode)
        }
        val settings = Settings(isolated).apply { setDailyNewCards(2); setWeekPlan("FFFFFFF") }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val services = Services(db, settings, context)
        val vm = mutableStateOf(CounterQuizViewModel(services.counters, services.sessionStore))
        val route = mutableStateOf(if (viaHome) null else CounterRoutes.create(mode, group, scope))
        val document = mutableStateOf<String?>(null)
        val visible = mutableStateOf(true)
        val restoration = StateRestorationTester(rule)
        val source = settings.source.value.id
        try {
            runBlocking {
                db.content().insertCounters(parsed.counters.mapIndexed { i, file ->
                    CounterEntity(source, file.id, Json.encodeToString(CounterFile.serializer(), file), i)
                })
                prepareDueStates(db, source, parsed.counters, mode, targetIds)
            }
            restoration.setContent {
                FixtureTheme {
                    if (visible.value) {
                        val holder = rememberSaveableStateHolder()
                        holder.SaveableStateProvider(if (document.value == null) route.value ?: "home" else "article") {
                            when {
                                document.value != null -> {
                                    Page("Reference", onBack = { document.value = null }) {
                                        MarkdownView(parsed.articles.single { "articles/${it.slug}.md" == document.value }.body,
                                            requireNotNull(document.value), { document.value = it })
                                    }
                                }
                                route.value == null -> CountersPage(services, {}, { selectedMode, extra, selectedGroup, selectedScope ->
                                    assertEquals(mode, selectedMode)
                                    assertFalse(extra)
                                    route.value = CounterRoutes.create(selectedMode, selectedGroup, selectedScope, extra)
                                }, { document.value = it })
                                else -> CounterQuizScreen(requireNotNull(route.value), {}, {
                                    vm.value.leaveSession()
                                    route.value = CounterRoutes.extra(requireNotNull(route.value))
                                    vm.value = CounterQuizViewModel(services.counters, services.sessionStore)
                                }, { owner, path -> assertEquals(source, owner); document.value = path }, vm.value)
                            }
                        }
                    }
                }
            }
            if (viaHome) {
                rule.waitUntil(10_000) {
                    rule.onAllNodes(hasText("Loading…")).fetchSemanticsNodes().isEmpty()
                }
                rule.onNodeWithText(group.label).performScrollTo().performClick()
                rule.onNodeWithText(scope.label).performScrollTo().performClick()
                rule.onNodeWithText(group.label).assertIsSelected()
                rule.onNodeWithText(scope.label).assertIsSelected()
                val selected = CounterIndex(parsed.counters).inScope(group, scope)
                val count = selected.cards.size + selected.expressions.size
                rule.waitUntil(10_000) {
                    rule.onAllNodes(hasText("$count prompts in this selection")).fetchSemanticsNodes().size == 1
                }
                val start = hasText("Start") and hasAnyAncestor(hasText(mode.label))
                rule.waitUntil(10_000) {
                    rule.onAllNodes(start).fetchSemanticsNodes().size == 1
                }
                rule.onNode(start).performScrollTo().assertIsEnabled().performClick()
            }
            rule.waitUntil(10_000) { vm.value.ui !is CounterUi.Loading }
            assertEquals(scope, CounterScope.fromRoute(requireNotNull(route.value)))
            assertEquals(group, CounterGroup.fromRoute(requireNotNull(route.value)))
            rule.onNodeWithText("Extra practice updates review history and scheduling.").assertDoesNotExist()
            assertQuestionScope(vm.value, parsed, scope, group, targetIds)
            if (mode == CounterMode.Choose || mode == CounterMode.Noun) {
                exerciseChoices(vm.value, parsed, scope, group, targetIds)
                prepareContinuation(db, source, mode, targetIds)
                rule.runOnIdle { settings.setCounterScope(source, CounterScope.N5) }
                rule.onNodeWithText("Practice more").performScrollTo().performClick()
                rule.waitUntil(10_000) { vm.value.ui !is CounterUi.Loading }
                assertQuestionScope(vm.value, parsed, scope, group, targetIds)
                assertEquals(scope, CounterScope.fromRoute(requireNotNull(route.value)))
                assertTrue(requireNotNull(route.value).endsWith(":extra"))
                runBlocking {
                    db.withTransaction {}
                    val log = db.reviews().allLog()
                    assertTrue(log.isNotEmpty())
                    assertTrue(log.all { it.grade == 3 && it.kanji in targetIds && it.sourceId == source && it.stack == COUNTER_STACK })
                    assertEquals(setOf(mode.mode.name), log.map { it.mode }.toSet())
                }
                return
            }
            rule.onNodeWithText("Check").assertIsNotEnabled()
            rule.onNode(hasSetTextAction()).performScrollTo().performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement(if (mode == CounterMode.Reading) "さい" else "時")
            if (viaHome) {
                restoration.emulateSavedInstanceStateRestore()
                assertEquals(if (mode == CounterMode.Reading) "さい" else "時", vm.value.draft)
                assertQuestionScope(vm.value, parsed, scope, group, targetIds)
            }
            assertTypedActionsTogether(vm.value, "initial")
            if (mode == CounterMode.Type) {
                rule.onNodeWithText("I don't know").performTouchInput { click() }
                rule.waitUntil(10_000) { vm.value.ui is CounterUi.TypeAnswer }
                assertTrue(rule.runOnIdle { (vm.value.ui as CounterUi.TypeAnswer).gaveUp })
                assertEquals(0, runBlocking { db.reviews().allLog().size })
                return
            }
            // A one-question pilot cannot retry immediately; still exercise success, article Back and continuation.
            val exerciseRetries = targetIds.size >= 2
            if (exerciseRetries) {
                assertFalse(answerExpression(vm.value, "Check").correct)
                rule.onNodeWithText("Not quite").performScrollTo().assertIsDisplayed()
                advanceExpression(vm.value)
                assertTrue(answerExpression(vm.value, "I don't know").gaveUp)
                advanceExpression(vm.value)
            }
            var answered = 0
            var openedArticle = false
            while (vm.value.ui is CounterUi.ExpressionQuestion && answered++ < 12) {
                val question = vm.value.ui as CounterUi.ExpressionQuestion
                assertQuestionScope(vm.value, parsed, scope, group, targetIds)
                rule.onNode(hasSetTextAction()).performScrollTo()
                    .performTextReplacement(if (question.reading) question.item.reading else question.item.written)
                assertTrue(answerExpression(vm.value, "Check").correct)
                rule.onNodeWithText("Correct!").performScrollTo().assertIsDisplayed()
                if (!openedArticle) {
                    rule.onNodeWithText("Read the explanation").performScrollTo().performClick()
                    rule.onNodeWithText("Reference").assertIsDisplayed()
                    rule.onNodeWithContentDescription("Back").performClick()
                    assertTrue(vm.value.ui is CounterUi.ExpressionAnswer)
                    openedArticle = true
                }
                advanceExpression(vm.value)
            }
            assertTrue(openedArticle)
            assertTrue(vm.value.ui is CounterUi.Done)
            prepareContinuation(db, source, mode, targetIds)
            rule.runOnIdle { settings.setCounterScope(source, CounterScope.N5) }
            rule.onNodeWithText("Practice more").performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.value.ui is CounterUi.ExpressionQuestion }
            assertEquals(group, CounterGroup.fromRoute(requireNotNull(route.value)))
            assertEquals(scope, CounterScope.fromRoute(requireNotNull(route.value)))
            assertTrue(requireNotNull(route.value).endsWith(":extra"))
            rule.onNodeWithText("Extra practice updates review history and scheduling.").performScrollTo().assertIsDisplayed()
            assertQuestionScope(vm.value, parsed, scope, group, targetIds)
            runBlocking {
                db.withTransaction {}
                val log = db.reviews().allLog()
                if (exerciseRetries) assertTrue(log.any { it.grade == 1 })
                assertTrue(log.any { it.grade == 3 })
                assertTrue(log.all { it.kanji in targetIds && it.sourceId == source && it.stack == COUNTER_STACK })
                assertEquals(setOf(mode.mode.name), log.map { it.mode }.toSet())
            }
        } finally {
            rule.runOnIdle { visible.value = false; vm.value.leaveSession() }
            rule.waitForIdle()
            runBlocking { db.withTransaction {} }
            db.close()
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun requireFixture(message: String, available: Boolean, required: Boolean) {
        if (required) assertTrue(message, available) else assumeTrue(message, available)
    }

    private fun prepareContinuation(db: AppDatabase, source: String, mode: CounterMode, targetIds: Set<String>) {
        // The end marker follows every queued review write; a transaction barrier alone may precede later chained writes.
        rule.waitUntil(10_000) {
            runBlocking { db.reviews().allSessions().singleOrNull()?.endReason == "completed" }
        }
        runBlocking {
            db.withTransaction {
                val reviewed = db.reviews().states(source, COUNTER_STACK, mode.mode.name).first { it.kanji in targetIds }
                // Upcoming urgency is relative and noisy, so postponing easier cards cannot guarantee a target first.
                db.reviews().putState(reviewed.copy(dueMs = Instant.now().minusSeconds(1).toEpochMilli()))
            }
        }
    }

    private suspend fun prepareDueStates(db: AppDatabase, source: String, files: List<CounterFile>,
                                         mode: CounterMode, targetIds: Set<String>) {
        val all = CounterIndex(files)
        val known = SrsState(CardPhase.Review, 30.0, 5.0, Instant.now().plus(30, ChronoUnit.DAYS),
            Instant.now().minus(2, ChronoUnit.DAYS), 3, 0)
        db.withTransaction {
            for (id in all.ids(mode).filterNot { it in targetIds }) {
                db.reviews().putState(known.toEntity(source, COUNTER_STACK, id, mode.mode))
            }
            if (mode == CounterMode.Type || mode == CounterMode.Noun) {
                all.cards.keys.forEach { id ->
                    db.reviews().putState(known.toEntity(source, COUNTER_STACK, id, CounterMode.Choose.mode))
                }
            }
        }
    }

    private fun exerciseChoices(vm: CounterQuizViewModel, parsed: ParsedContent, scope: CounterScope,
                                group: CounterGroup, targetIds: Set<String>) {
        var answered = 0
        while ((vm.ui is CounterUi.Choose || vm.ui is CounterUi.NounQuestion) && answered++ < 10) {
            assertQuestionScope(vm, parsed, scope, group, targetIds)
            val label = when (val q = vm.ui) {
                is CounterUi.Choose -> vm.index.counterLabel(q.file.counter)
                is CounterUi.NounQuestion -> {
                    q.options.forEach { option ->
                        assertEquals(vm.index.nounLabel(vm.index.referenceCard(option.id)), option.label)
                    }
                    assertTrue("Real reverse case must retain easier-family references", q.options.any { option ->
                        parsed.counters.any { file -> file.level > q.file.level && file.cards.any { it.id == option.id } }
                    })
                    q.options.single { it.id == vm.index.reverse.getValue(q.reverseId).answer }.label
                }
                else -> error("Expected a selection question")
            }
            rule.onNodeWithText(label).performScrollTo().performClick()
            rule.onNodeWithText("Correct!").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Next").performScrollTo().performClick()
        }
        assertTrue(vm.ui is CounterUi.Done)
    }

    private fun assertQuestionScope(vm: CounterQuizViewModel, parsed: ParsedContent, scope: CounterScope,
                                    group: CounterGroup, targetIds: Set<String>) {
        val id = when (val question = vm.ui) {
            is CounterUi.ExpressionQuestion -> question.item.id
            is CounterUi.TypeQuestion -> question.card.id
            is CounterUi.Choose -> question.card.id
            is CounterUi.NounQuestion -> question.reverseId
            else -> error("Expected a question, got $question")
        }
        val owner = vm.index.files.single { f ->
            f.cards.any { it.id == id } || f.expressions.any { it.id == id } || f.reverse.any { it.id == id }
        }
        assertEquals((1..5).first(scope::includes), owner.level)
        assertTrue("Expected a target-level family, got ${owner.id}", id in targetIds)
        val expected = parsed.counters.filter {
            scope.includes(it.level) && (group == CounterGroup.All || it.group == group.code)
        }
        assertEquals(expected, vm.index.files)
    }

    private fun assertTypedActionsTogether(vm: CounterQuizViewModel, action: String) {
        rule.onNode(hasSetTextAction()).performScrollTo().performClick()
        rule.waitUntil(10_000) {
            val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
            insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0 && imeAnimations.get() == 0
        }
        val check = rule.onNodeWithText("Check").performScrollTo().assertIsDisplayed()
        val checkEnabled = rule.runOnIdle { vm.draft.isNotBlank() }
        if (checkEnabled) check.assertIsEnabled() else check.assertIsNotEnabled()
        val unsure = rule.onNodeWithText("I don't know").assertIsDisplayed().assertIsEnabled()
        val (imeVisible, imeBottom, keyboardTop) = rule.runOnIdle {
            val decor = rule.activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
            val bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            Triple(insets.isVisible(WindowInsetsCompat.Type.ime()), bottom, decor.height - bottom)
        }
        val animations = imeAnimations.get()
        assertTrue("A docked keyboard must be visible", imeVisible && imeBottom > 0)
        assertEquals("IME animation must finish before measuring controls", 0, animations)
        val checkBounds = check.fetchSemanticsNode().boundsInWindow
        val unsureBounds = unsure.fetchSemanticsNode().boundsInWindow
        assertEquals(checkBounds.center.y, unsureBounds.center.y, 1f)
        assertTrue("Check is covered by the keyboard", checkBounds.bottom <= keyboardTop)
        assertTrue("Uncertainty is covered by the keyboard", unsureBounds.bottom <= keyboardTop)
        val image = "$keyboardPrefix-${keyboardStep++}.png"
        captureKeyboard(image)
        keyboardEvidence.appendText(buildJsonObject {
            put("action", action)
            put("question", when (val q = vm.ui) {
                is CounterUi.ExpressionQuestion -> q.item.id
                is CounterUi.TypeQuestion -> q.card.id
                else -> error("Expected a typed question")
            })
            put("imeVisible", imeVisible)
            put("imeAnimations", animations)
            put("imeBottomPx", imeBottom)
            put("keyboardTopPx", keyboardTop)
            put("fontScale", rule.activity.resources.configuration.fontScale)
            put("checkBounds", checkBounds.toString())
            put("unsureBounds", unsureBounds.toString())
            put("checkEnabled", checkEnabled)
            put("image", image)
        }.toString() + "\n")
    }

    private fun answerExpression(vm: CounterQuizViewModel, action: String): CounterUi.ExpressionAnswer {
        val question = rule.runOnIdle { vm.ui as CounterUi.ExpressionQuestion }
        assertTypedActionsTogether(vm, action)
        rule.onNodeWithText(action).performTouchInput { click() }
        rule.waitUntil(10_000) { (vm.ui as? CounterUi.ExpressionAnswer)?.item?.id == question.item.id }
        return rule.runOnIdle { vm.ui as CounterUi.ExpressionAnswer }.also { answer ->
            keyboardEvidence.appendText(buildJsonObject {
                put("actionCompleted", action)
                put("question", answer.item.id)
                put("correct", answer.correct)
                put("gaveUp", answer.gaveUp)
            }.toString() + "\n")
        }
    }

    private fun advanceExpression(vm: CounterQuizViewModel) {
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.waitUntil(10_000) { vm.ui is CounterUi.ExpressionQuestion || vm.ui is CounterUi.Done }
    }

    private fun captureKeyboard(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), name)
                .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val dark = InstrumentationRegistry.getArguments().getString("counterTheme") == "dark"
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
