package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterReverse
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterIndex
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterRoutes
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.COUNTER_STACK
import io.github.stopcran.kanji.data.CounterEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.counterIndex
import io.github.stopcran.kanji.data.counterModeStates
import io.github.stopcran.kanji.data.counterWaitingCount
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.toEntity
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
        await(vm)
        assertTrue((vm.ui as CounterUi.ExpressionQuestion).item.id.startsWith("day-duration-"))
        assertEquals(setOf("time"), vm.index.files.map { it.group }.toSet())
        return vm
    }

    private fun await(vm: CounterQuizViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.ui is CounterUi.Loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(vm.ui is CounterUi.Loading)
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
        val index = db.counterIndex(source).inScope(CounterGroup.Time)
        val modes = db.counterModeStates(source, index, 1, Instant.now())
        assertEquals(setOf(CounterMode.Expression, CounterMode.Reading), modes.keys)
        assertTrue(modes.values.all { it == ModeState.Start(0, 1) })
        assertEquals("0 due; 1 new-item allowance", CounterHome(1, modes, 1).summary())
        assertTrue(db.counterModeStates(source, index, 0, Instant.now()).values.all { it == ModeState.PracticeMore })
    }

    @Test
    fun changingThePreferenceDoesNotBroadenTheCapturedSessionOrResetEarlierEvidence() = runBlocking {
        val advanced = CounterFile(2, "years", "年", "ねん", "Years", level = 3, group = "time",
            article = "articles/counts.md", expressions = listOf(item.copy(id = "year-calendar-1")))
        db.content().insertCounters(listOf(CounterEntity(source, advanced.id,
            Json.encodeToString(CounterFile.serializer(), advanced), 2)))
        val beginner = CounterQuizViewModel(services)
        beginner.ensureStarted(CounterMode.Expression, false, CounterGroup.Time, CounterScope.N5)
        await(beginner)
        services.settings.setCounterScope(source, CounterScope.N3)
        assertEquals(listOf(item.id), beginner.index.ids(CounterMode.Expression))
        beginner.onDraft(item.written)
        beginner.submit()
        next(beginner)
        assertTrue(beginner.ui is CounterUi.Done)
        val before = db.reviews().allStates()
        val route = CounterRoutes.create(CounterMode.Expression, CounterGroup.Time, services.settings.counterScopeFor(source))
        val n3 = CounterQuizViewModel(services)
        n3.ensureStarted(CounterMode.Expression, false, CounterGroup.fromRoute(route), CounterScope.fromRoute(route))
        await(n3)
        assertEquals("year-calendar-1", (n3.ui as CounterUi.ExpressionQuestion).item.id)
        services.settings.setCounterScope(source, CounterScope.N5)
        n3.onDraft(item.written)
        n3.submit()
        next(n3)
        assertTrue(db.reviews().allStates().containsAll(before))
        assertEquals(setOf(item.id, "year-calendar-1"), db.reviews().allStates().map { it.kanji }.toSet())
        assertEquals(2, db.reviews().itemsIntroducedSince(source, 0))
        val n5 = db.counterIndex(source).inScope(CounterGroup.Time, CounterScope.N5)
        assertEquals(ModeState.PracticeMore, db.counterModeStates(source, n5, 0, Instant.now())[CounterMode.Expression])
        val reviewedN3 = db.reviews().states(source, COUNTER_STACK, CounterMode.Expression.mode.name)
            .single { it.kanji == "year-calendar-1" }
        db.reviews().putState(reviewedN3.copy(dueMs = Instant.now().minusSeconds(1).toEpochMilli()))
        val extra = CounterRoutes.extra(route)
        val continued = CounterQuizViewModel(services)
        continued.ensureStarted(CounterMode.Expression, true, CounterGroup.fromRoute(extra), CounterScope.fromRoute(extra))
        await(continued)
        assertEquals(setOf(item.id, "year-calendar-1"), continued.index.ids(CounterMode.Expression).toSet())
        assertEquals("year-calendar-1", (continued.ui as CounterUi.ExpressionQuestion).item.id)
        assertEquals(CounterScope.N5, services.settings.counterScopeFor(source))
        continued.leaveSession()
    }

    @Test
    fun n3HomeAllModeQueuesAndReminderCountsExcludeStartedHarderLevelsWithoutChangingProgress() = runBlocking {
        val files = (1..5).flatMap { level ->
            val card = CounterCard("level-$level-card", text = "ペンが三{}あります。", en = "Three pens.",
                noun = "ペン", phrase = "三本", reading = "さんぼん", good = listOf("本"),
                kana = listOf("さんぼん"), distractors = listOf("匹", "冊", "回"))
            listOf(
                CounterFile(1, "level-$level-legacy", "本", "ほん", "Pens", level = level, group = "time",
                    cards = listOf(card), reverse = listOf(CounterReverse("level-$level-noun", card.id, listOf(card.id)))),
                CounterFile(2, "level-$level-expression", "日", "にち", "Days", level = level, group = "time",
                    expressions = listOf(item.copy(id = "level-$level-expression"))),
            )
        }
        db.content().insertCounters(files.mapIndexed { i, file ->
            CounterEntity(source, file.id, Json.encodeToString(CounterFile.serializer(), file), i + 2)
        })
        val all = db.counterIndex(source)
        val now = Instant.now()
        assertFreshCounts(now)
        val due = SrsState(CardPhase.Review, 30.0, 5.0, now.minusSeconds(60), now.minusSeconds(86400), 3, 0)
        for (mode in CounterMode.entries) for (id in all.ids(mode)) {
            db.reviews().putState(due.toEntity(source, COUNTER_STACK, id, mode.mode))
        }
        val before = db.reviews().allStates()
        val allowance = db.newAllowance(services.settings, source, now)
        for (scope in CounterScope.entries) {
            services.settings.setCounterScope(source, scope)
            val expected = CounterIndex(all.files.filter { scope.includes(it.level) })
            val home = db.counterModeStates(source, all.inScope(scope = scope), 0, now)
            assertEquals(expected.ids(CounterMode.Expression).size + expected.cards.size,
                all.inScope(scope = scope).expressions.size + all.inScope(scope = scope).cards.size)
            for (mode in CounterMode.entries) {
                assertEquals(ModeState.Start(expected.ids(mode).size, 0), home[mode])
            }
            val waiting = expected.let { index -> CounterMode.entries.sumOf { index.ids(it).size } }
            assertEquals(waiting, db.counterWaitingCount(services.settings, source, now, 0))
            assertEquals("$waiting due; 0 new-item allowance", CounterHome(expected.cards.size, home).summary())
            assertEquals(allowance, db.newAllowance(services.settings, source, now))
        }
        for (mode in CounterMode.entries) for (extra in listOf(false, true)) {
            val vm = CounterQuizViewModel(services)
            vm.ensureStarted(mode, extra, CounterGroup.Time, CounterScope.N3)
            await(vm)
            assertEquals(setOf(3, 4, 5), vm.index.files.map { it.level }.toSet())
            val id = when (val q = vm.ui) {
                is CounterUi.Choose -> q.card.id
                is CounterUi.TypeQuestion -> q.card.id
                is CounterUi.NounQuestion -> q.reverseId
                is CounterUi.ExpressionQuestion -> q.item.id
                else -> error("Expected a question, got $q")
            }
            assertTrue(id in vm.index.ids(mode))
            vm.leaveSession()
        }
        assertEquals(0, db.counterWaitingCount(services.settings, "example/empty", now, 10))
        assertEquals(before, db.reviews().allStates())
        assertTrue(db.reviews().allLog().isEmpty())
    }

    private suspend fun assertFreshCounts(now: Instant) {
        services.settings.setCounterScope(source, CounterScope.N3)
        val index = db.counterIndex(source).inScope(scope = CounterScope.N3)
        val home = db.counterModeStates(source, index, 2, now)
        for (mode in listOf(CounterMode.Choose, CounterMode.Expression, CounterMode.Reading)) {
            assertEquals(ModeState.Start(0, 2), home[mode])
        }
        assertEquals(setOf(CounterMode.Type, CounterMode.Noun), home.filterValues { it is ModeState.Locked }.keys)
        assertEquals(6, db.counterWaitingCount(services.settings, source, now, 2))
        assertEquals(0, db.counterWaitingCount(services.settings, source, now, 0))
        assertEquals("0 due; 2 new-item allowance", CounterHome(index.cards.size, home, 2).summary())
    }
}
