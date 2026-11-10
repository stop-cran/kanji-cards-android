package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterRoutes
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.ReviewRecord
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.COUNTER_STACK
import io.github.stopcran.kanji.data.CounterEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.counterIndex
import io.github.stopcran.kanji.data.counterModeStates
import io.github.stopcran.kanji.data.newAllowance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
class ModePracticeBatchTest {
    private lateinit var db: AppDatabase
    private lateinit var app: Services
    private val source get() = app.settings.source.value.id
    private val ids = (1..23).map { "counter-$it" }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        app = Services(db, Settings(context), context)
        app.settings.setWeekPlan("FFFFFFF")
        app.settings.setDailyNewCards(0)
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    private fun installCounters(expressions: Boolean = false) = runBlocking {
        val file = if (expressions) CounterFile(2, "days", "日", "にち", "Days", group = "time",
            expressions = ids.map { CounterExpression(it, "One day.", "一日", listOf("一日"), "いちにち", listOf("いちにち"), "Duration.") })
        else CounterFile(1, "pens", "本", "ほん", "Pens", group = "time", cards = ids.map {
            CounterCard(it, text = "ペンが三{}あります。", noun = "ペン", phrase = "三本", reading = "さんぼん",
                good = listOf("本"), distractors = listOf("匹", "冊", "回"))
        })
        db.content().insertCounters(listOf(CounterEntity(source, file.id, Json.encodeToString(CounterFile.serializer(), file), 0)))
    }

    private fun introduce(stack: String, items: List<String>, mode: io.github.stopcran.kanji.core.srs.StudyMode) = runBlocking {
        val yesterday = Instant.now().minusSeconds(86400)
        val solid = SrsState(CardPhase.Review, stability = 3.0, due = yesterday.plusSeconds(86400 * 10), reps = 3)
        for (id in items) app.reviewStore.record(ReviewRecord(solid, source, stack, id, mode, Grade.Good, yesterday, "fixture"))
    }

    private fun counterBatch(route: String): CounterQuizViewModel {
        val vm = CounterQuizViewModel(app.counters, app.sessionStore)
        vm.ensureStarted(CounterMode.fromCode(route.split(':')[2]), false, CounterGroup.fromRoute(route), CounterScope.fromRoute(route))
        await { vm.ui !is CounterUi.Loading }
        var answers = 0
        while (vm.ui is CounterUi.TypeQuestion || vm.ui is CounterUi.ExpressionQuestion) {
            assertTrue(++answers <= 10)
            vm.onDraft(if (vm.ui is CounterUi.TypeQuestion) "本" else "いちにち")
            vm.submit()
            vm.next()
        }
        assertTrue(vm.ui is CounterUi.Done)
        await { runBlocking { db.reviews().allSessions().all { it.endedAtMs != null } } }
        return vm
    }

    @Test fun counterTypeContinuesInTenTenThreeWithoutSpendingAnotherIntroduction() {
        installCounters()
        introduce(COUNTER_STACK, ids, CounterMode.Choose.mode)
        for (budget in listOf(0, 1)) {
            app.settings.setDailyNewCards(budget)
            val modes = runBlocking { db.counterModeStates(source, db.counterIndex(source), budget, Instant.now()) }
            assertEquals(ModeState.Start(0, 10), modes[CounterMode.Type])
        }
        app.settings.setDailyNewCards(0)
        val before = runBlocking { db.reviews().states(source, COUNTER_STACK, CounterMode.Choose.mode.name) }
        var route = CounterRoutes.create(CounterMode.Type, CounterGroup.Time, CounterScope.N5)
        var total = 0
        for (size in listOf(10, 10, 3)) {
            val vm = counterBatch(route)
            total += size
            assertEquals(size, (vm.ui as CounterUi.Done).answered)
            if (total < 23) await { (vm.ui as? CounterUi.Done)?.canContinue == true }
            assertEquals(total, runBlocking { db.reviews().states(source, COUNTER_STACK, CounterMode.Type.mode.name).size })
            assertEquals(before, runBlocking { db.reviews().states(source, COUNTER_STACK, CounterMode.Choose.mode.name) })
            route = CounterRoutes.nextBatch(route)
            assertFalse(route.endsWith(":extra"))
            vm.leaveSession()
        }
        assertEquals(0, runBlocking { db.reviews().itemsIntroducedSince(source, Instant.now().minusSeconds(3600).toEpochMilli()) })
    }

    @Test fun wholeExpressionReadingReusesWritingIntroductionsWithNoBudget() {
        installCounters(expressions = true)
        introduce(COUNTER_STACK, ids, CounterMode.Expression.mode)
        val route = CounterRoutes.create(CounterMode.Reading, CounterGroup.Time, CounterScope.N5)
        val vm = counterBatch(route)
        assertEquals(10, (vm.ui as CounterUi.Done).answered)
        await { (vm.ui as? CounterUi.Done)?.canContinue == true }
        assertEquals(0, runBlocking { db.newAllowance(app.settings, source, Instant.now()).remaining })
        assertEquals(23, runBlocking { db.reviews().itemsIntroducedSince(source, 0) })
        vm.leaveSession()
    }

    @Test fun unseenCounterContentStopsAtTheSharedLimitAndCannotContinue() {
        installCounters(expressions = true)
        app.settings.setDailyNewCards(1)
        val route = CounterRoutes.create(CounterMode.Reading, CounterGroup.Time, CounterScope.N5)
        val vm = counterBatch(route)
        assertEquals(1, (vm.ui as CounterUi.Done).answered)
        await { runBlocking { db.reviews().allSessions().all { it.endedAtMs != null } } }
        assertFalse((vm.ui as CounterUi.Done).canContinue)
        assertEquals(1, runBlocking { db.reviews().itemsIntroducedSince(source, 0) })
        assertEquals(0, runBlocking { db.newAllowance(app.settings, source, Instant.now()).remaining })
        vm.leaveSession()
    }

    @Test fun wordReverseReadingAndTypeBatchPreviouslyIntroducedWordsAtZeroBudget() {
        val stack = WordStacks.n5.id
        val words = (1..23).map { "本$it" }
        runBlocking {
            db.content().insertWords(words.mapIndexed { i, word ->
                WordEntity(source, word, "ほん$i", "Book $i", "noun", "", "", "", 5)
            })
        }
        introduce(stack, words, WordDirection.JpToEn.mode)
        for (direction in listOf(WordDirection.EnToJp, WordDirection.Reading, WordDirection.Type)) {
            if (direction == WordDirection.Type) introduce(stack, words, WordDirection.EnToJp.mode)
            val vm = WordQuizViewModel(app)
            vm.ensureStarted(direction, false)
            await { vm.ui !is WordQuizUi.Loading }
            var count = 0
            while (vm.ui is WordQuizUi.Question || vm.ui is WordQuizUi.TypeQuestion) {
                assertTrue(++count <= 10)
                when (val q = vm.ui) {
                    is WordQuizUi.Question -> vm.pick(if (direction == WordDirection.Reading) q.word.reading else q.word.word)
                    is WordQuizUi.TypeQuestion -> { vm.onDraft(q.word.word); vm.submit() }
                    else -> error("Expected question")
                }
                vm.next()
            }
            assertEquals(10, (vm.ui as WordQuizUi.Done).answered)
            await { (vm.ui as? WordQuizUi.Done)?.canContinue == true }
            assertEquals(10, runBlocking { db.reviews().states(source, stack, direction.mode.name).size })
            vm.leaveSession()
        }
        assertTrue(runBlocking { db.reviews().introducedItems("other/source", stack).isEmpty() })
        assertTrue(runBlocking { db.reviews().introducedItems(source, WordStacks.all.id).isEmpty() })
        assertEquals(23, runBlocking { db.reviews().itemsIntroducedSince(source, 0) })
    }
}
