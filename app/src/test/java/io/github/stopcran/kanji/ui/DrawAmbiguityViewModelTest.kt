package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.DrawingAmbiguityFixtures
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.IssueType
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.DrawingLog
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.SavedDrawing
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.toEntity
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
class DrawAmbiguityViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val models = mutableListOf<DrawViewModel>()
    private val source get() = services.settings.source.value.id

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        services.settings.setSource("https://github.com/fixture/drawing", "main")
        services.settings.setStack(Stacks.ALL)
        services.settings.setTraceNew(false)
        services.settings.setWeekPlan("FFFFFFF")
    }

    @After
    fun tearDown() = runBlocking {
        models.forEach { it.leaveSession() }
        services.kanjiWrites.awaitPending()
        db.close()
        Dispatchers.resetMain()
    }

    private fun install(target: String) = runBlocking {
        services.kanjiWrites.awaitPending()
        db.clearAllTables()
        db.content().insertKanji(DrawingAmbiguityFixtures.references.map { (kanji, strokes) ->
            KanjiEntity(
                source, kanji, "Meaning $kanji", 5, "", strokes.size, null, null, "", "", "", "",
                DrawingAmbiguityFixtures.strokesJson(kanji), 0,
            )
        })
        val now = Instant.now()
        db.reviews().putState(
            SrsState(CardPhase.Review, 10.0, 5.0, now.minusSeconds(60), now.minusSeconds(86_400), 3)
                .toEntity(source, Stacks.ALL, target, StudyMode.Draw),
        )
    }

    private fun model(recognizer: InkRecognition = FakeRecognizer()): DrawViewModel =
        DrawViewModel(services, recognizer).also {
            models += it
            it.ensureStarted(false)
            await { it.ui is DrawUi.Question }
        }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    private fun drain() = runBlocking {
        services.kanjiWrites.awaitPending()
        db.withTransaction {}
    }

    private fun ink(case: DrawingAmbiguityFixtures.Case) = case.drawn.map { stroke ->
        stroke.mapIndexed { index, point -> TimedPt(point.x.toFloat(), point.y.toFloat(), index.toLong()) }
    }

    private fun check(vm: DrawViewModel, case: DrawingAmbiguityFixtures.Case): DrawUi.Ambiguous {
        vm.check(ink(case), 109f, vm.ui as DrawUi.Question)
        await { vm.ui !is DrawUi.Checking }
        return vm.ui as DrawUi.Ambiguous
    }

    @Test
    fun bothNoisyIntendedTargetsAndWrongGlyphsRequireExplicitResolutionBeforeOneReview() = runBlocking {
        for (case in DrawingAmbiguityFixtures.cases) for (choice in DrawResolution.entries) {
            install(case.target)
            val vm = model()
            val before = db.reviews().allStates()
            val ambiguity = check(vm, case)
            assertEquals(case.id, case.alternative, ambiguity.alternative.kanji)
            vm.next()
            drain()
            assertTrue(db.reviews().allLog().isEmpty())
            assertEquals(before, db.reviews().allStates())

            vm.resolve(ambiguity, choice)
            val answer = vm.ui as DrawUi.Answer
            assertEquals(
                if (choice == DrawResolution.TargetWithMistakes) DrawOutcome.Mistakes else DrawOutcome.NotRecognized,
                answer.outcome,
            )
            if (case.target == "日") assertEquals(listOf(IssueType.Missing), answer.match.issues.map { it.type })
            vm.resolve(ambiguity, choice)
            vm.retry(ambiguity)
            assertSame(answer, vm.ui)
            drain()
            assertTrue(db.reviews().allLog().isEmpty())

            vm.next(answer)
            vm.next(answer)
            vm.resolve(ambiguity, choice)
            drain()
            assertEquals(case.id, 1, db.reviews().allLog().size)
            assertEquals(if (choice == DrawResolution.TargetWithMistakes) 2 else 1, db.reviews().allLog().single().grade)
            assertEquals(4, db.reviews().allStates().single().reps)
            assertEquals(1, db.reviews().allSessions().single().answered)
            vm.leaveSession()
            drain()
        }
    }

    @Test
    fun aPartialTargetStillRequiresAChoiceWhenTheCleanCompetitorCostsMore() = runBlocking {
        val sun = DrawingAmbiguityFixtures.references.getValue("日")
        val case = DrawingAmbiguityFixtures.cases.last().copy(drawn = listOf(sun[0], sun[1], sun[3]))
        install(case.target)
        val vm = model()
        val ambiguity = check(vm, case)
        assertEquals("口", ambiguity.alternative.kanji)
        assertEquals(listOf(IssueType.Missing), ambiguity.answer.match.issues.map { it.type })
        vm.next()
        drain()
        assertTrue(db.reviews().allLog().isEmpty())
        vm.resolve(ambiguity, DrawResolution.TargetWithMistakes)
        val answer = vm.ui as DrawUi.Answer
        assertEquals(DrawOutcome.Mistakes, answer.outcome)
        vm.next(answer)
        drain()
        assertEquals(2, db.reviews().allLog().single().grade)
    }

    @Test
    fun theAdvisoryPoolPreservesCompetitorAuthoredOrderVariants() = runBlocking {
        val mouth = DrawingAmbiguityFixtures.references.getValue("口")
        val case = DrawingAmbiguityFixtures.cases.last().copy(drawn = listOf(mouth[1], mouth[0], mouth[2]))
        install(case.target)
        val competitor = db.content().kanji(source).single { it.kanji == "口" }
        db.content().insertKanji(listOf(competitor.copy(
            strokesJson = DrawingAmbiguityFixtures.strokesJson("口", listOf(listOf(2, 1, 3))),
        )))
        val vm = model()
        val ambiguity = check(vm, case)
        assertEquals("口", ambiguity.alternative.kanji)
        assertTrue(ambiguity.answer.match.issues.any { it.type == IssueType.WrongOrder })
        assertTrue(ambiguity.answer.match.issues.any { it.type == IssueType.Missing })
        vm.resolve(ambiguity, DrawResolution.TargetWithMistakes)
        assertEquals(DrawOutcome.Mistakes, (vm.ui as DrawUi.Answer).outcome)
        drain()
        assertTrue(db.reviews().allLog().isEmpty())
    }

    @Test
    fun optionalDrawingLogSavesAtResolutionButTheLearningScheduleWaitsForNext() = runBlocking {
        val case = DrawingAmbiguityFixtures.cases.last()
        install(case.target)
        services.settings.setSaveDrawings(true)
        assertEquals(0, DrawingLog.count(services.context))
        val vm = model()
        val first = check(vm, case)
        assertEquals(0, DrawingLog.count(services.context))
        vm.retry(first)
        assertEquals(0, DrawingLog.count(services.context))
        val second = check(vm, case)
        vm.resolve(second, DrawResolution.TargetWithMistakes)
        val answer = vm.ui as DrawUi.Answer
        vm.resolve(second, DrawResolution.OtherCharacter)
        assertEquals(1, DrawingLog.count(services.context))
        val file = checkNotNull(DrawingLog.dir(services.context).listFiles()).single()
        assertEquals("Mistakes", Json.decodeFromString(SavedDrawing.serializer(), file.readText()).outcome)
        drain()
        assertTrue(db.reviews().allLog().isEmpty())
        vm.next(answer)
        vm.next(answer)
        drain()
        assertEquals(1, DrawingLog.count(services.context))
        assertEquals(1, db.reviews().allLog().size)
        assertEquals(2, db.reviews().allLog().single().grade)
    }

    @Test
    fun retryRetainsTheTakenQuestionAndRejectsCallbacksFromTheOldAttempt() = runBlocking {
        val case = DrawingAmbiguityFixtures.cases.last()
        install(case.target)
        val vm = model()
        val original = vm.ui as DrawUi.Question
        vm.ink.addAll(ink(case))
        val first = check(vm, case)
        vm.retry(first)
        val retry = vm.ui as DrawUi.Question
        assertNotSame(original, retry)
        assertSame(original.card, retry.card)
        assertEquals(original.remaining, retry.remaining)
        assertTrue(vm.ink.isEmpty())
        vm.check(ink(case), 109f, original)
        vm.resolve(first, DrawResolution.OtherCharacter)
        vm.retry(first)
        vm.next(first.answer)
        assertSame(retry, vm.ui)
        drain()
        assertTrue(db.reviews().allLog().isEmpty())

        val second = check(vm, case)
        vm.resolve(first, DrawResolution.OtherCharacter)
        vm.retry(first)
        assertSame(second, vm.ui)
        vm.resolve(second, DrawResolution.TargetWithMistakes)
        val answer = vm.ui as DrawUi.Answer
        vm.next(answer)
        vm.next(answer)
        drain()
        assertEquals(1, db.reviews().allLog().size)
        assertEquals(2, db.reviews().allLog().single().grade)
    }

    @Test
    fun routeCoverageAndRotationRetainTheDecisionButRemovalCancelsWithoutGrading() = runBlocking {
        val case = DrawingAmbiguityFixtures.cases.last()
        install(case.target)
        val owners = RouteOwners()
        val owner = owners.owner("draw")
        val vm = DrawViewModel(services, FakeRecognizer())
        models += vm
        owner.viewModelStore.put("draw", vm)
        vm.attachSessionRegistration("draw", services.studySessions)
        services.studySessions.register("draw", vm::leaveSession)
        vm.ensureStarted(false)
        await { vm.ui is DrawUi.Question }
        val ambiguity = check(vm, case)
        owners.show("draw")
        owners.hostState(Lifecycle.State.RESUMED)
        owners.show("article")
        owners.hostState(Lifecycle.State.CREATED)
        owners.hostState(Lifecycle.State.RESUMED)
        owners.show("draw")
        assertSame(owner, owners.owner("draw"))
        assertSame(ambiguity, vm.ui)
        drain()
        assertTrue(db.reviews().allLog().isEmpty())
        services.studySessions.leave("draw")
        owners.remove("draw")
        vm.resolve(ambiguity, DrawResolution.TargetWithMistakes)
        vm.retry(ambiguity)
        vm.next(ambiguity.answer)
        drain()
        assertTrue(db.reviews().allLog().isEmpty())
        assertEquals(0, db.reviews().allSessions().single().answered)
        assertEquals("left", db.reviews().allSessions().single().endReason)
    }

    @Test
    fun leavingDuringRecognitionCannotPublishOrGradeALateResult() = runBlocking {
        val case = DrawingAmbiguityFixtures.cases.last()
        install(case.target)
        val pending = CompletableDeferred<List<String>?>()
        val vm = model(FakeRecognizer(pending = pending))
        vm.check(ink(case), 109f, vm.ui as DrawUi.Question)
        assertTrue(vm.ui is DrawUi.Checking)
        vm.leaveSession()
        pending.complete(null)
        drain()
        assertTrue(vm.ui is DrawUi.Checking)
        assertTrue(db.reviews().allLog().isEmpty())
        assertEquals(0, db.reviews().allSessions().single().answered)
    }

    @Test
    fun availableRecognitionCleanDrawingAndGiveUpKeepOrdinaryOutcomes() = runBlocking {
        val case = DrawingAmbiguityFixtures.cases.last()
        for ((candidates, expected) in listOf(
            listOf(case.alternative, case.target) to DrawOutcome.Mistakes,
            emptyList<String>() to DrawOutcome.NotRecognized,
        )) {
            install(case.target)
            val vm = model(FakeRecognizer(candidates))
            vm.check(ink(case), 109f, vm.ui as DrawUi.Question)
            await { vm.ui is DrawUi.Answer }
            assertEquals(expected, (vm.ui as DrawUi.Answer).outcome)
            vm.leaveSession()
            drain()
        }
        install(case.target)
        val vm = model()
        val clean = case.copy(drawn = DrawingAmbiguityFixtures.references.getValue(case.target))
        vm.check(ink(clean), 109f, vm.ui as DrawUi.Question)
        await { vm.ui is DrawUi.Answer }
        assertEquals(DrawOutcome.Clean, (vm.ui as DrawUi.Answer).outcome)
        vm.leaveSession()
        drain()

        install(case.target)
        val giveUp = model()
        giveUp.check(emptyList(), 109f, giveUp.ui as DrawUi.Question)
        await { giveUp.ui is DrawUi.Answer }
        assertEquals(DrawOutcome.NotRecognized, (giveUp.ui as DrawUi.Answer).outcome)
        drain()
        assertTrue(db.reviews().allLog().isEmpty())
    }

    private class FakeRecognizer(
        private val result: List<String>? = null,
        private val pending: CompletableDeferred<List<String>?>? = null,
    ) : InkRecognition {
        override suspend fun prepare() = ModelState.Unavailable
        override suspend fun candidates(strokes: List<List<TimedPt>>): List<String>? =
            pending?.await() ?: result
        override fun close() = Unit
    }
}
