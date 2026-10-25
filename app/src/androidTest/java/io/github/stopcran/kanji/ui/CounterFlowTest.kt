package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.CounterFile
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Optional real-corpus acceptance: push counter-acceptance.zip to the target's external files directory.
 * Uses only an in-memory database and uniquely named preferences, never learner history.
 */
class CounterFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun realReadingAndArticleBack() = exercise(CounterMode.Reading)
    @Test fun realWritingAndExtraContinuation() = exercise(CounterMode.Expression)
    @Test fun realN4EverydayReading() = exercise(CounterMode.Reading, CounterScope.N4, CounterGroup.Everyday)
    @Test fun realN4TimeWriting() = exercise(CounterMode.Expression, CounterScope.N4)

    private fun exercise(mode: CounterMode, scope: CounterScope = CounterScope.N5, group: CounterGroup = CounterGroup.Time) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.getExternalFilesDir(null), "counter-acceptance.zip")
        assumeTrue("Provide the real content archive for native acceptance", archive.isFile)
        val parsed = archive.inputStream().use { ContentParser.parse(SafeZip.read(it)) }
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertEquals(123, parsed.counters.filter { it.level == 5 }.sumOf { it.expressions.size })
        if (scope == CounterScope.N4) {
            assumeTrue("Provide published or candidate N4 content",
                parsed.counters.any { it.level == 4 && it.group == group.code && it.expressions.isNotEmpty() })
        }
        val preferenceName = "counter-flow-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferenceName, mode)
        }
        val settings = Settings(isolated).apply { setDailyNewCards(2); setWeekPlan("FFFFFFF") }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val services = Services(db, settings, context)
        val vm = mutableStateOf(CounterQuizViewModel(services))
        val route = mutableStateOf(CounterRoutes.create(mode, group, scope))
        val document = mutableStateOf<String?>(null)
        val source = settings.source.value.id
        try {
            runBlocking {
                db.content().insertCounters(parsed.counters.mapIndexed { i, file ->
                    CounterEntity(source, file.id, Json.encodeToString(CounterFile.serializer(), file), i)
                })
                if (scope == CounterScope.N4) {
                    val known = SrsState(CardPhase.Review, 30.0, 5.0, Instant.now().plus(30, ChronoUnit.DAYS),
                        Instant.now().minus(2, ChronoUnit.DAYS), 3, 0)
                    db.withTransaction {
                        CounterIndex(parsed.counters).inScope(scope = CounterScope.N5).ids(mode).forEach { id ->
                            db.reviews().putState(known.toEntity(source, COUNTER_STACK, id, mode.mode))
                        }
                    }
                }
            }
            rule.setContent {
                FixtureTheme {
                    val holder = rememberSaveableStateHolder()
                    holder.SaveableStateProvider(if (document.value == null) route.value else "article") {
                        if (document.value != null) {
                            Page("Reference", onBack = { document.value = null }) {
                                MarkdownView(parsed.articles.single { "articles/${it.slug}.md" == document.value }.body,
                                    requireNotNull(document.value), { document.value = it })
                            }
                        } else {
                            CounterQuizScreen(route.value, {}, {
                                vm.value.leaveSession()
                                route.value = CounterRoutes.extra(route.value)
                                vm.value = CounterQuizViewModel(services)
                            }, { owner, path -> assertEquals(source, owner); document.value = path }, vm.value)
                        }
                    }
                }
            }
            rule.waitUntil(10_000) { vm.value.ui is CounterUi.ExpressionQuestion }
            assertQuestionScope(vm.value, scope, group)
            rule.onNode(hasSetTextAction()).performScrollTo().performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement(if (mode == CounterMode.Reading) "さい" else "時")
            rule.onNodeWithText("Check").performScrollTo().performClick()
            assertFalse((vm.value.ui as CounterUi.ExpressionAnswer).correct)
            rule.onNodeWithText("Not quite").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Next (will repeat)").performScrollTo().performClick()
            rule.onNodeWithText("I don't know").performScrollTo().performClick()
            assertTrue((vm.value.ui as CounterUi.ExpressionAnswer).gaveUp)
            rule.onNodeWithText("Next (will repeat)").performScrollTo().performClick()
            var answered = 0
            var openedArticle = false
            while (vm.value.ui is CounterUi.ExpressionQuestion && answered++ < 12) {
                val question = vm.value.ui as CounterUi.ExpressionQuestion
                assertQuestionScope(vm.value, scope, group)
                rule.onNode(hasSetTextAction()).performScrollTo()
                    .performTextReplacement(if (question.reading) question.item.reading else question.item.written)
                rule.onNodeWithText("Check").performScrollTo().performClick()
                assertTrue((vm.value.ui as CounterUi.ExpressionAnswer).correct)
                rule.onNodeWithText("Correct!").performScrollTo().assertIsDisplayed()
                if (!openedArticle) {
                    rule.onNodeWithText("Read the explanation").performScrollTo().performClick()
                    rule.onNodeWithText("Numbers, counters, time and dates").assertIsDisplayed()
                    rule.onNodeWithContentDescription("Back").performClick()
                    assertTrue(vm.value.ui is CounterUi.ExpressionAnswer)
                    openedArticle = true
                }
                rule.onNodeWithText("Next").performScrollTo().performClick()
            }
            assertTrue(openedArticle)
            assertTrue(vm.value.ui is CounterUi.Done)
            rule.onNodeWithText("Practice more").performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.value.ui is CounterUi.ExpressionQuestion }
            assertEquals(group, CounterGroup.fromRoute(route.value))
            assertEquals(scope, CounterScope.fromRoute(route.value))
            assertTrue(route.value.endsWith(":extra"))
            assertQuestionScope(vm.value, scope, group)
            runBlocking {
                db.withTransaction {}
                val log = db.reviews().allLog()
                assertTrue(log.any { it.grade == 1 })
                assertTrue(log.any { it.grade == 3 })
                assertEquals(setOf(mode.mode.name), log.map { it.mode }.toSet())
            }
        } finally {
            rule.runOnIdle { vm.value.leaveSession() }
            runBlocking { db.withTransaction {} }
            db.close()
            context.deleteSharedPreferences(preferenceName)
        }
    }

    private fun assertQuestionScope(vm: CounterQuizViewModel, scope: CounterScope, group: CounterGroup) {
        val id = (vm.ui as CounterUi.ExpressionQuestion).item.id
        val owner = vm.index.files.single { f -> f.cards.any { it.id == id } || f.expressions.any { it.id == id } }
        assertEquals(if (scope == CounterScope.N4) 4 else 5, owner.level)
        assertEquals(setOf(group.code), vm.index.files.map { it.group }.toSet())
        assertEquals(if (scope == CounterScope.N4) setOf(4, 5) else setOf(5), vm.index.files.map { it.level }.toSet())
    }

    @Composable
    private fun FixtureTheme(content: @Composable () -> Unit) {
        val dark = InstrumentationRegistry.getArguments().getString("counterTheme") == "dark"
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
