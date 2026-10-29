package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterRoutes
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.CounterEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.newAllowance
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CounterHomeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun preferenceOnlyRefreshKeepsGroupAllowanceAndCapturedN3Route() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context).apply { setDailyNewCards(2); setWeekPlan("FFFFFFF") }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val services = Services(db, settings, context)
        val source = settings.source.value.id
        var route: String? = null
        val visible = mutableStateOf(true)
        val restoration = StateRestorationTester(rule)
        try {
            insertCounters(db, source)
            val now = Instant.now()
            val allowance = runBlocking { db.newAllowance(settings, source, now) }
            restoration.setContent {
                MaterialTheme {
                    if (visible.value) {
                        CountersPage(services, {}, { mode, extra, group, scope ->
                            route = CounterRoutes.create(mode, group, scope, extra)
                        }, {})
                    }
                }
            }
            awaitCount(2)
            rule.onNodeWithText("N5").assertIsSelected()
            rule.onNodeWithText("Includes easier levels.").assertDoesNotExist()
            rule.onNodeWithText(CounterGroup.Time.label).performScrollTo().performClick()
            awaitCount(1)
            for ((scope, count) in listOf(CounterScope.N4 to 2, CounterScope.N3 to 3, CounterScope.All to 5)) {
                rule.runOnIdle { settings.setCounterScope(source, scope) }
                awaitCount(count)
                rule.onNodeWithText(CounterGroup.Time.label).assertIsSelected()
            }
            rule.onNodeWithText("N3").performScrollTo().performClick()
            awaitCount(3)
            rule.onNodeWithText("Includes easier levels.").assertExists()
            restoration.emulateSavedInstanceStateRestore()
            awaitCount(3)
            rule.onNodeWithText("N3").assertIsSelected()
            rule.onNodeWithText(CounterGroup.Time.label).assertIsSelected()
            rule.onNode(hasText("Start") and hasAnyAncestor(hasText(CounterMode.Expression.label)))
                .performScrollTo().performClick()
            val captured = requireNotNull(route)
            assertEquals(CounterScope.N3, CounterScope.fromRoute(captured))
            assertEquals(CounterGroup.Time, CounterGroup.fromRoute(captured))
            rule.runOnIdle { settings.setCounterScope(source, CounterScope.N5) }
            awaitCount(1)
            assertEquals(CounterScope.N3, CounterScope.fromRoute(CounterRoutes.extra(captured)))
            runBlocking {
                assertEquals(allowance, db.newAllowance(settings, source, now))
                assertTrue(db.reviews().allStates().isEmpty())
                assertTrue(db.reviews().allLog().isEmpty())
            }
        } finally {
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            db.close()
        }
    }

    private fun insertCounters(db: AppDatabase, source: String) = runBlocking {
        val files = (1..5).flatMap { level ->
            listOf("time", "everyday").map { group ->
                val id = "level-$level-$group"
                CounterFile(2, id, "日", "にち", "Days", level = level, group = group,
                    expressions = listOf(CounterExpression("$id-expression", "One day.", "一日",
                        listOf("一日"), "いちにち", listOf("いちにち"), "A duration.")))
            }
        }
        db.content().insertCounters(files.mapIndexed { i, file ->
            CounterEntity(source, file.id, Json.encodeToString(CounterFile.serializer(), file), i)
        })
    }

    private fun awaitCount(count: Int) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("$count prompts in this selection").fetchSemanticsNodes().size == 1
        }
    }
}
