package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.COUNTER_STACK
import io.github.stopcran.kanji.data.CounterEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.counterIndex
import io.github.stopcran.kanji.data.counterModeStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CounterQuizViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val source get() = services.settings.source.value.id
    private val item = CounterExpression("day-duration-1", "Duration: one day.", "一日",
        listOf("一日", "1日"), "いちにち", listOf("いちにち"), "Not the first day of a month.")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        services.settings.setWeekPlan("FFFFFFF")
        val time = CounterFile(2, "days", "日", "にち", "Days", group = "time",
            article = "articles/counts.md", expressions = listOf(item))
        val other = time.copy(id = "age", group = "everyday",
            expressions = listOf(item.copy(id = "age-expression-1", cue = "Age.")))
        runBlocking {
            db.content().insertCounters(listOf(time, other).mapIndexed { i, file ->
                CounterEntity(source, file.id, Json.encodeToString(CounterFile.serializer(), file), i)
            })
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun start(mode: CounterMode, extra: Boolean = false): CounterQuizViewModel {
        val vm = CounterQuizViewModel(services)
        vm.ensureStarted(mode, extra, CounterGroup.Time)
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.ui is CounterUi.Loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue((vm.ui as CounterUi.ExpressionQuestion).item.id.startsWith("day-duration-"))
        assertEquals(setOf("time"), vm.index.files.map { it.group }.toSet())
        return vm
    }

    private fun next(vm: CounterQuizViewModel) {
        vm.next()
        runBlocking { db.withTransaction {} }
    }

    @Test
    fun readingAndWritingRecordSeparateGoodEvidenceButOneSharedIntroducedItem() {
        for ((mode, answer) in listOf(CounterMode.Expression to "１日", CounterMode.Reading to "イチニチ")) {
            val vm = start(mode)
            vm.onDraft(answer)
            vm.submit()
            assertTrue((vm.ui as CounterUi.ExpressionAnswer).correct)
            assertEquals("articles/counts.md", vm.index.article(item.id))
            next(vm)
            assertTrue(vm.ui is CounterUi.Done)
        }
        runBlocking {
            assertEquals(setOf("CounterExpression", "CounterReading"), db.reviews().allStates().map { it.mode }.toSet())
            assertEquals(listOf(3, 3), db.reviews().allLog().map { it.grade })
            assertEquals(1, db.reviews().itemsIntroducedSince(source, 0))
            assertTrue(db.reviews().states(source, COUNTER_STACK, CounterMode.Choose.mode.name).isEmpty())
        }
    }

    @Test
    fun wrongAndGiveUpRelearnWithoutLeavingTheSelectedGroup() {
        val file = CounterFile(2, "days", "日", "にち", "Days", group = "time",
            article = "articles/counts.md", expressions = (1..4).map { item.copy(id = "day-duration-$it") })
        runBlocking { db.content().insertCounters(listOf(CounterEntity(source, file.id,
            Json.encodeToString(CounterFile.serializer(), file), 0))) }
        val vm = start(CounterMode.Reading, extra = true)
        vm.onDraft("ついたち")
        vm.submit()
        assertFalse((vm.ui as CounterUi.ExpressionAnswer).correct)
        next(vm)
        assertTrue(vm.ui is CounterUi.ExpressionQuestion)
        vm.submit(giveUp = true)
        assertTrue((vm.ui as CounterUi.ExpressionAnswer).gaveUp)
        next(vm)
        var repeats = 0
        while (vm.ui is CounterUi.ExpressionQuestion && repeats++ < 12) {
            vm.onDraft("いちにち")
            vm.submit()
            next(vm)
        }
        assertTrue(vm.ui is CounterUi.Done)
        runBlocking {
            assertEquals(listOf(1, 1, 3, 3, 3, 3), db.reviews().allLog().map { it.grade })
            assertEquals((1..4).map { "day-duration-$it" }.toSet(), db.reviews().allStates().map { it.kanji }.toSet())
        }
    }

    @Test
    fun homeAndQueuesShareFilteredAvailabilityWithoutAddingModeAllowances() = runBlocking {
        val index = db.counterIndex(source).inGroup(CounterGroup.Time)
        val modes = db.counterModeStates(source, index, 1, Instant.now())
        assertEquals(setOf(CounterMode.Expression, CounterMode.Reading), modes.keys)
        assertTrue(modes.values.all { it == ModeState.Start(0, 1) })
        assertEquals("0 due; 1 new-item allowance", CounterHome(1, modes, 1).summary())
        assertTrue(db.counterModeStates(source, index, 0, Instant.now()).values.all { it == ModeState.PracticeMore })
    }
}
