package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.toEntity
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CumulativeKanjiSessionTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private lateinit var source: String
    private lateinit var preferenceName: String

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val app = RuntimeEnvironment.getApplication()
        preferenceName = "cumulative-sessions-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(preferenceName, mode)
        }
        val settings = Settings(context).also {
            it.setStack("jlpt-n4")
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(0)
        }
        source = settings.source.value.id
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, settings, context)
        val now = Instant.now()
        val due = SrsState(CardPhase.Review, 10.0, 5.0, now.minusSeconds(60), now.minusSeconds(86_400), 3)
        runBlocking {
            db.content().insertKanji(listOf(
                card("休", 5, "キュウ", 0), card("白", 5, "ハク", 1),
                card("体", 4, "タイ", 2), card("太", 3, "サン", 3),
            ))
            for (kanji in listOf("休", "太")) {
                db.reviews().putState(due.toEntity(source, Stacks.JLPT_STATE, kanji, StudyMode.Draw))
            }
            db.reviews().putState(due.toEntity(source, Stacks.JLPT_STATE, "休", StudyMode.KanjiOn, "きゅう"))
            db.reviews().putState(due.toEntity(source, Stacks.JLPT_STATE, "太", StudyMode.KanjiOn, "さん"))
            db.reviews().putState(due.toEntity("other/content", Stacks.JLPT_STATE, "休", StudyMode.Draw))
            db.reviews().putState(due.toEntity("other/content", Stacks.JLPT_STATE, "休", StudyMode.KanjiOn, "きゅう"))
        }
    }

    @After fun tearDown() {
        db.close()
        RuntimeEnvironment.getApplication().deleteSharedPreferences(preferenceName)
        Dispatchers.resetMain()
    }

    private fun card(id: String, level: Int, on: String, position: Int): KanjiEntity {
        val strokes = """{"schemaVersion":1,"kanji":"$id","viewBox":[0,0,100,100],"strokeCount":1,"strokes":[{"id":1,"points":[[10,10],[90,90]]}]}"""
        return KanjiEntity(source, id, id, level, "jlpt-n$level", 4, null, null, on, "", "", "", strokes, position)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for the study session", condition())
    }

    private fun finish(vararg viewModels: StudyViewModel) {
        viewModels.forEach { it.leaveSession() }
        runBlocking {
            withTimeout(10_000) {
                while (db.reviews().allSessions().any { it.endedAtMs == null }) delay(10)
            }
        }
    }

    private suspend fun makeDue(mode: StudyMode, reading: String = "") {
        val row = db.reviews().states(source, Stacks.JLPT_STATE, mode.name).single { it.kanji == "休" && it.reading == reading }
        db.reviews().putState(row.copy(dueMs = System.currentTimeMillis() - 1))
    }

    private fun recognizer() = object : InkRecognition {
        override suspend fun prepare() = ModelState.Unavailable
        override suspend fun candidates(strokes: List<List<TimedPt>>): List<String>? = null
        override fun close() = Unit
    }

    @Test fun fullFocusStillAllowsExtraPracticeOfStartedCards() {
        services.settings.setFocusEnabled(true)
        services.settings.setFocusSize(5)
        val now = Instant.now()
        services.settings.focusSince(source, "jlpt-n4", now.minusSeconds(30).toEpochMilli())
        val started = listOf("肉", "魚", "茶", "紙", "赤")
        val fresh = (1..15).map { "new-$it" }
        val learning = SrsState(CardPhase.Learning, 1.0, 5.0, now.plusSeconds(86_400), now.minusSeconds(10), 1)
        runBlocking {
            db.content().insertKanji((started + fresh).mapIndexed { i, id -> card(id, 4, "タイ", 10 + i) })
            for (id in started) {
                db.reviews().putState(learning.toEntity(source, Stacks.JLPT_STATE, id, StudyMode.Quiz))
                db.reviews().putLog(ReviewLogEntity(
                    0, source, Stacks.JLPT_STATE, id, StudyMode.Quiz.name, 3, now.minusSeconds(10).toEpochMilli(),
                ))
            }
        }
        val vm = QuizViewModel(services)
        try {
            vm.ensureStarted(true)
            await { vm.ui !is QuizUi.Loading }
            assertTrue((vm.ui as QuizUi.Question).card.kanji in started)
        } finally {
            finish(vm)
        }
    }

    @Test fun drawingUsesAndRecordsTheSameLowerLevelStateAcrossSelections() {
        repeat(2) { iteration ->
            if (iteration == 1) {
                services.settings.setStack("jlpt-n5")
                runBlocking { makeDue(StudyMode.Draw) }
            }
            val vm = DrawViewModel(services, recognizer())
            try {
                vm.ensureStarted(false)
                await { vm.ui !is DrawUi.Loading }
                assertEquals("休", (vm.ui as DrawUi.Question).card.kanji)
                vm.check(emptyList(), 100f)
                await { vm.ui is DrawUi.Answer }
                vm.next()
                runBlocking {
                    withTimeout(10_000) { while (db.reviews().allLog().size < iteration + 1) delay(10) }
                }
            } finally {
                finish(vm)
            }
        }
        runBlocking {
            val rows = db.reviews().states(source, Stacks.JLPT_STATE, StudyMode.Draw.name)
            assertEquals(5, rows.single { it.kanji == "休" }.reps)
            assertEquals(3, rows.single { it.kanji == "太" }.reps)
            assertEquals(3, db.reviews().states("other/content", Stacks.JLPT_STATE, StudyMode.Draw.name).single().reps)
            assertTrue(db.reviews().allLog().all { it.kanji == "休" && it.stack == Stacks.JLPT_STATE })
            assertEquals(listOf("jlpt-n4", "jlpt-n5"), db.reviews().allSessions().map { it.stack })
        }
    }

    @Test fun readingsKeepTheirIdentityAndExcludeHigherLevelOptionsAcrossSelections() {
        repeat(2) { iteration ->
            if (iteration == 1) {
                services.settings.setStack("jlpt-n5")
                runBlocking { makeDue(StudyMode.KanjiOn, "きゅう") }
            }
            val vm = KanjiReadingViewModel(services)
            try {
                vm.ensureStarted(false)
                await { vm.ui !is ReadingUi.Loading }
                val q = vm.ui as ReadingUi.Question
                assertEquals("休", q.card.kanji)
                assertEquals("きゅう", q.question.correctKey)
                assertFalse(q.question.options.any { it.key == "さん" })
                vm.pick(q.question.correctKey)
                vm.next()
                runBlocking {
                    withTimeout(10_000) { while (db.reviews().allLog().size < iteration + 1) delay(10) }
                }
            } finally {
                finish(vm)
            }
        }
        runBlocking {
            val rows = db.reviews().states(source, Stacks.JLPT_STATE, StudyMode.KanjiOn.name)
            assertEquals(5, rows.single { it.kanji == "休" && it.reading == "きゅう" }.reps)
            assertEquals(3, rows.single { it.kanji == "太" }.reps)
            assertEquals(3, db.reviews().states("other/content", Stacks.JLPT_STATE, StudyMode.KanjiOn.name).single().reps)
            assertTrue(db.reviews().allLog().all { it.kanji == "休" && it.reading == "きゅう" && it.stack == Stacks.JLPT_STATE })
            assertEquals(listOf("jlpt-n4", "jlpt-n5"), db.reviews().allSessions().map { it.stack })
        }
    }

    @Test fun overlappingDrawingSessionsBothUpdateTheSharedSchedule() {
        val first = DrawViewModel(services, recognizer())
        val second = DrawViewModel(services, recognizer())
        try {
            first.ensureStarted(false)
            await { first.ui is DrawUi.Question }
            services.settings.setStack("jlpt-n5")
            second.ensureStarted(false)
            await { second.ui is DrawUi.Question }
            assertEquals("休", (first.ui as DrawUi.Question).card.kanji)
            assertEquals("休", (second.ui as DrawUi.Question).card.kanji)
            first.check(emptyList(), 100f)
            second.check(emptyList(), 100f)
            await { first.ui is DrawUi.Answer && second.ui is DrawUi.Answer }
            first.next()
            second.next()
            runBlocking {
                withTimeout(10_000) { while (db.reviews().allLog().size < 2) delay(10) }
                val state = db.reviews().states(source, Stacks.JLPT_STATE, "Draw").single { it.kanji == "休" }
                assertEquals(5, state.reps)
                assertEquals(1, state.lapses)
            }
        } finally {
            finish(first, second)
        }
    }

    @Test fun overlappingReadingSessionsBothUpdateTheSharedReadingSchedule() {
        val first = KanjiReadingViewModel(services)
        val second = KanjiReadingViewModel(services)
        try {
            first.ensureStarted(false)
            await { first.ui is ReadingUi.Question }
            services.settings.setStack("jlpt-n5")
            second.ensureStarted(false)
            await { second.ui is ReadingUi.Question }
            val one = first.ui as ReadingUi.Question
            val two = second.ui as ReadingUi.Question
            assertEquals("きゅう", one.question.correctKey)
            assertEquals("きゅう", two.question.correctKey)
            first.pick(one.question.correctKey)
            second.pick(two.question.correctKey)
            first.next()
            second.next()
            runBlocking {
                withTimeout(10_000) { while (db.reviews().allLog().size < 2) delay(10) }
                val state = db.reviews().states(source, Stacks.JLPT_STATE, "KanjiOn").single { it.kanji == "休" }
                assertEquals(5, state.reps)
                assertEquals(listOf(true, true), db.reviews().allLog().map { it.previousReadingStarted })
            }
        } finally {
            finish(first, second)
        }
    }
}
