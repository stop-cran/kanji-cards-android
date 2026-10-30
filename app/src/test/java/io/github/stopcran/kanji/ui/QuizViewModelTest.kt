package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.ReviewRecording
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.ReviewStore
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QuizViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val sourceId = "stop-cran/learning-japanese"

    private fun card(k: String, title: String, pos: Int) =
        KanjiEntity(sourceId, k, title, 5, "jlpt-n5", 4, null, null, "ニチ", "ひ", "", "# $k", null, pos)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun seed(vararg cards: KanjiEntity) = runBlocking { db.content().insertKanji(cards.toList()) }

    private fun <T> QuizViewModel.await(predicate: (QuizUi) -> Boolean, then: () -> T): T {
        val end = System.currentTimeMillis() + 10_000
        while (!predicate(ui) && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out in state $ui", predicate(ui))
        return then()
    }

    private fun states() = runBlocking { db.reviews().states(sourceId, services.settings.stack.value, StudyMode.Quiz.name) }

    private fun awaitStates(n: Int): List<io.github.stopcran.kanji.data.ReviewStateEntity> {
        val end = System.currentTimeMillis() + 10_000
        while (states().size < n && System.currentTimeMillis() < end) Thread.sleep(10)
        return states()
    }

    @Test
    fun tooFewCardsShowsEmptyState() {
        seed(card("日", "sun", 0))
        val vm = QuizViewModel(services)
        vm.ensureStarted(false)
        vm.await({ it !is QuizUi.Loading }) { assertTrue(vm.ui is QuizUi.Empty) }
    }

    @Test
    fun newCardIsStudiedThenQuestionedAndCorrectAnswerIsRecorded() {
        seed(card("日", "sun", 0), card("月", "moon", 1))
        val vm = QuizViewModel(services)
        vm.ensureStarted(false)
        val studied = vm.await({ it is QuizUi.Study }) { vm.ui as QuizUi.Study }
        assertTrue(states().isEmpty())
        vm.startQuestion()
        val q = vm.ui as QuizUi.Question
        assertEquals(studied.card.kanji, q.card.kanji)
        assertTrue(q.options.any { it.kanji == q.card.kanji })
        vm.pick(q.card.kanji)
        assertTrue((vm.ui as QuizUi.Answer).correct)
        vm.next()
        assertEquals(1, awaitStates(1).size)
    }

    @Test
    fun wrongAnswerIsAskedAgainAndSessionFinishesWithCounts() {
        seed(card("日", "sun", 0), card("月", "moon", 1))
        val vm = QuizViewModel(services)
        vm.ensureStarted(false)
        var guard = 0
        var wrongGiven = false
        while (vm.ui !is QuizUi.Done && guard++ < 40) {
            when (val s = vm.ui) {
                is QuizUi.Study -> vm.startQuestion()
                is QuizUi.Question -> {
                    if (!wrongGiven) {
                        wrongGiven = true
                        vm.pick(s.options.first { it.kanji != s.card.kanji }.kanji)
                    } else vm.pick(s.card.kanji)
                }
                is QuizUi.Answer -> vm.next()
                QuizUi.Loading -> Thread.sleep(10)
                else -> break
            }
        }
        val done = vm.ui as QuizUi.Done
        assertTrue("the wrong card is re-asked, so more answers than cards", done.answered > 2)
        assertTrue(done.correct < done.answered)
    }

    @Test
    fun delayedWritesKeepConfirmationTimesAndImmediateStateForReasks() {
        seed(card("日", "sun", 0), card("月", "moon", 1))
        val clock = object : Clock() {
            var now = Instant.ofEpochMilli(System.currentTimeMillis())
            override fun instant() = now
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId) = Clock.fixed(now, zone)
        }
        val allowWrite = CompletableDeferred<Unit>()
        val vm = QuizViewModel(services, ReviewRecording(clock), ReviewStore {
            allowWrite.await()
            services.reviewStore.record(it)
        })
        try {
            vm.ensureStarted(false)
            val first = vm.await({ it is QuizUi.Study }) { (vm.ui as QuizUi.Study).card.kanji }
            val confirmations = mutableListOf<Pair<String, Instant>>()
            var failed = false
            repeat(20) {
                when (val state = vm.ui) {
                    is QuizUi.Study -> vm.startQuestion()
                    is QuizUi.Question -> {
                        vm.pick(if (!failed) NO_ANSWER else state.card.kanji)
                        failed = true
                    }
                    is QuizUi.Answer -> {
                        confirmations += state.card.kanji to clock.now
                        vm.next()
                        clock.now = clock.now.plusSeconds(60)
                    }
                    else -> Unit
                }
            }
            assertTrue(vm.ui is QuizUi.Done)
            assertTrue(runBlocking { db.reviews().allLog().isEmpty() })
            clock.now = clock.now.plusSeconds(86_400)
            allowWrite.complete(Unit)
            val logs = runBlocking {
                withTimeout(10_000) { while (db.reviews().allLog().size < 3) delay(10) }
                db.reviews().allLog().filter { it.kanji == first }
            }
            val times = confirmations.filter { it.first == first }.take(2).map { it.second }
            assertEquals(times.map { it.toEpochMilli() }, logs.map { it.atMs })
            assertEquals(listOf(1, 3), logs.map { it.grade })
            var expected = SrsState()
            for ((grade, at) in listOf(Grade.Again, Grade.Good).zip(times)) {
                expected = Fsrs().review(expected, grade, at, Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS,
                    Fsrs.seed(first, StudyMode.Quiz.name, expected.reps))
            }
            assertEquals(expected, states().single { it.kanji == first }.toSrs())
        } finally {
            allowWrite.complete(Unit)
            vm.leaveSession()
        }
    }

    @Test
    fun voiceMispickCanBeTakenBackWithoutRecording() {
        seed(card("日", "sun", 0), card("月", "moon", 1))
        val vm = QuizViewModel(services)
        vm.ensureStarted(false)
        vm.await({ it is QuizUi.Study }) { vm.startQuestion() }
        val q = vm.ui as QuizUi.Question
        vm.pick(q.options.first { it.kanji != q.card.kanji }.kanji, heard = "moon")
        vm.retry()
        assertTrue(vm.ui is QuizUi.Question)
        assertTrue(states().isEmpty())
    }
}
