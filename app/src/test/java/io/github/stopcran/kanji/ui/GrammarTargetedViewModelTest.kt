package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarReadiness
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.core.grammarFixture
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarEntity
import io.github.stopcran.kanji.data.GrammarResponseEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncMetaEntity
import io.github.stopcran.kanji.data.changeGrammarFocus
import io.github.stopcran.kanji.data.changeGrammarGuided
import io.github.stopcran.kanji.data.newAllowance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class GrammarTargetedViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val sourceId get() = services.settings.source.value.id

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        services.settings.setWeekPlan("FFFFFFF")
    }

    @After
    fun tearDown() {
        runBlocking { db.withTransaction {} }
        db.close()
        Dispatchers.resetMain()
    }

    private fun installTopics(vararg sets: GrammarSet): GrammarPracticeTarget {
        services.settings.setGrammarParserEpoch(sourceId, ContentParser.GRAMMAR_PARSER_EPOCH)
        services.settings.setGrammarNewScope(sourceId, GrammarScope.All)
        runBlocking {
            db.content().insertGrammar(sets.mapIndexed { index, set ->
                GrammarEntity(sourceId, set.id, Json.encodeToString(GrammarSet.serializer(), set), index)
            })
            db.content().putMeta(SyncMetaEntity(sourceId, "accepted-version", Instant.now().toEpochMilli(), 0))
        }
        return GrammarPracticeTarget(sourceId, services.settings.source.value.branch, GrammarTargetKind.Group, sets.first().introductionGroups.first().id)
    }

    private fun awaitLoaded(vm: GrammarQuizViewModel) {
        val end = System.currentTimeMillis() + 10_000
        while (vm.ui is GrammarUi.Loading && System.currentTimeMillis() < end) Thread.sleep(10)
        assertFalse("Session did not finish loading", vm.ui is GrammarUi.Loading)
    }

    private fun targeted(target: GrammarPracticeTarget, interrupted: Boolean = false): GrammarQuizViewModel =
        GrammarQuizViewModel(services, target = target).also {
            it.ensureStarted(extra = true, interrupted = interrupted)
            awaitLoaded(it)
        }

    private fun seedTopicReview(exercise: GrammarExercise, at: Instant, due: Instant, typed: Boolean = false) = runBlocking {
        db.reviews().recordGrammar(
            ReviewStateEntity(sourceId, GRAMMAR_STACK, exercise.id, "Grammar", "Review", 3.0, 5.0, due.toEpochMilli(), at.toEpochMilli(), 1, 0),
            ReviewLogEntity(0, sourceId, GRAMMAR_STACK, exercise.id, "Grammar", 3, at.toEpochMilli()),
            GrammarResponseEntity(
                0, sourceId, GRAMMAR_STACK, exercise.id, exercise.target, exercise.relationship,
                if (typed) "type" else "choose", exercise.target, true,
            ),
        )
    }

    private fun answerCorrect(vm: GrammarQuizViewModel) {
        when (val question = vm.ui) {
            is GrammarUi.Choose -> vm.pick(question.exercise.answer)
            is GrammarUi.Type -> {
                vm.onDraft(question.exercise.acceptedAnswers.last())
                vm.check()
            }
            else -> error("Expected a question, got $question")
        }
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
    }

    private fun advance(vm: GrammarQuizViewModel) {
        vm.next()
        runBlocking { withTimeout(10_000) { db.withTransaction {} } }
    }

    private fun logs() = runBlocking { db.reviews().allLog() }
    private fun details() = runBlocking { db.reviews().allGrammarResponses() }
    private fun sessions() = runBlocking { db.reviews().allSessions() }
    private fun states() = runBlocking { db.reviews().allStates() }

    @Test
    fun targetedPracticeAndContinuationKeepTheSmallTargetAndOrdinaryFocusAccounting() {
        val alpha = grammarFixture("alpha", examples = 2)
        val beta = grammarFixture("beta")
        val target = installTopics(alpha, beta)
        services.settings.setDailyNewCards(10)
        val now = Instant.now()
        beta.exercises.take(2).forEach { seedTopicReview(it, now.minusSeconds(86400), now.minusSeconds(60)) }
        val focusBefore = runBlocking {
            db.changeGrammarGuided(services.settings, sourceId, true)
            db.changeGrammarFocus(services.settings, sourceId, "beta", now)
            db.reviews().grammarFocus(sourceId)
        }
        val allowanceBefore = runBlocking { db.newAllowance(services.settings, sourceId, now).remaining }
        val vm = targeted(target)
        val preview = vm.ui as GrammarUi.Preview
        assertEquals(4, preview.preview.initialCount(false))
        assertTrue(sessions().isEmpty())
        assertEquals(focusBefore, runBlocking { db.reviews().grammarFocus(sourceId) })
        vm.startTargeted(false, preview)
        vm.startTargeted(false, preview)
        awaitLoaded(vm)
        val session = sessions().single()
        assertTrue(session.extra)
        val asked = mutableSetOf<String>()
        repeat(4) {
            asked += vm.currentExercise!!.id
            answerCorrect(vm)
        }
        assertEquals(alpha.exercises.map { it.id }.toSet(), asked)
        assertEquals(GrammarUi.Done(4, 4), vm.ui)
        assertEquals(4, logs().count { it.sessionId == session.id })
        assertEquals(4, details().count { it.exerciseId.startsWith("alpha-") })
        assertTrue(states().filter { it.kanji.startsWith("alpha-") }.all { it.reps == 1 && it.phase == "Review" })
        assertEquals(allowanceBefore - 4, runBlocking { db.newAllowance(services.settings, sourceId, Instant.now()).remaining })
        assertEquals(focusBefore, runBlocking { db.reviews().grammarFocus(sourceId) })
        val again = targeted(target)
        assertFalse(again.practiceOverride)
        assertEquals(1, sessions().size)
        val nextPreview = again.ui as GrammarUi.Preview
        assertEquals(4, nextPreview.preview.members.count { it.started })
        again.startTargeted(false, nextPreview)
        awaitLoaded(again)
        assertEquals(2, sessions().map { it.id }.toSet().size)
        assertTrue(again.currentExercise!!.id in asked)
        again.leaveSession()
    }

    @Test
    fun staleMembershipScopeAndPrerequisitesRefreshThePreviewWithoutCarryingApproval() {
        val alpha = grammarFixture("alpha", examples = 2)
        val foundation = grammarFixture("foundation", examples = 2)
        val target = installTopics(alpha, foundation)
        val vm = targeted(target)
        val old = vm.ui as GrammarUi.Preview
        val changed = grammarFixture("alpha", examples = 3, requires = listOf("foundation"))
        runBlocking {
            db.content().insertGrammar(listOf(GrammarEntity(sourceId, changed.id, Json.encodeToString(GrammarSet.serializer(), changed), 0)))
        }
        services.settings.setGrammarNewScope(sourceId, GrammarScope.N5)
        vm.startTargeted(true, old)
        awaitLoaded(vm)
        val fresh = vm.ui as GrammarUi.Preview
        assertTrue(fresh.message!!.contains("changed"))
        assertEquals(6, fresh.preview.members.size)
        assertTrue(fresh.preview.members.all { it.outOfScope && it.missingPrerequisites == listOf("foundation") })
        assertTrue(sessions().isEmpty())
        vm.startTargeted(true, old)
        assertEquals(fresh, vm.ui)
        vm.startTargeted(false, fresh)
        awaitLoaded(vm)
        assertTrue(sessions().isEmpty())
        vm.startTargeted(true, vm.ui as GrammarUi.Preview)
        awaitLoaded(vm)
        assertTrue(vm.practiceOverride)
        assertEquals(GrammarScope.N5, services.settings.grammarFor(sourceId).newScope)
        assertTrue(runBlocking { db.reviews().grammarReadiness(sourceId, GrammarReadiness.POLICY_VERSION).isEmpty() })
        vm.leaveSession()
        val again = targeted(target)
        assertTrue(again.ui is GrammarUi.Preview)
        assertFalse(again.practiceOverride)
        assertEquals(1, sessions().size)
    }

    @Test
    fun activeTargetPinsQuestionsAndSourceButANewBatchRejectsChangedSourceOrBranch() {
        val alpha = grammarFixture("alpha", examples = 2)
        val target = installTopics(alpha)
        val originalSource = sourceId
        val vm = targeted(target)
        vm.startTargeted(false, vm.ui as GrammarUi.Preview)
        awaitLoaded(vm)
        val question = vm.ui
        val changed = alpha.copy(exercises = alpha.exercises.map { it.copy(translation = "Changed snapshot.") })
        runBlocking {
            db.content().insertGrammar(listOf(GrammarEntity(sourceId, changed.id, Json.encodeToString(GrammarSet.serializer(), changed), 0)))
        }
        services.studySessions.register("target-route", vm::leaveSession)
        services.studySessions.leave("source-doc:article")
        vm.ensureStarted(extra = true)
        assertEquals(question, vm.ui)
        assertNull(sessions().single().endReason)
        assertTrue(services.settings.setSource("https://github.com/$originalSource", "other-branch"))
        assertTrue((targeted(target).ui as GrammarUi.Empty).message.contains("branch"))
        assertTrue(services.settings.setSource("https://github.com/example/fork", "main"))
        assertTrue((targeted(target).ui as GrammarUi.Empty).message.contains("source"))
        repeat(4) {
            assertEquals("Synthetic meaning.", vm.currentExercise!!.translation)
            answerCorrect(vm)
        }
        assertEquals(GrammarUi.Done(4, 4), vm.ui)
        assertEquals(setOf(originalSource), logs().map { it.sourceId }.toSet())
        assertEquals(1, sessions().size)
    }

    @Test
    fun targetedRetriesKeepSuccessfulConfirmationsUnrecorded() {
        val target = installTopics(grammarFixture("alpha", examples = 3))
        val vm = targeted(target, interrupted = true)
        assertTrue((vm.ui as GrammarUi.Preview).message!!.contains("interrupted"))
        assertTrue(sessions().isEmpty())
        vm.startTargeted(false, vm.ui as GrammarUi.Preview)
        awaitLoaded(vm)
        val first = vm.ui as GrammarUi.Choose
        vm.pick(first.options.first { it.id != first.exercise.answer }.id)
        advance(vm)
        repeat(7) { answerCorrect(vm) }
        assertEquals(GrammarUi.Done(8, 7), vm.ui)
        assertEquals(7, logs().size)
        assertEquals(7, details().size)
        assertEquals(2, states().single { it.kanji == first.exercise.id }.reps)
        assertTrue(runBlocking { db.reviews().grammarReadiness(sourceId, GrammarReadiness.POLICY_VERSION).isEmpty() })
    }

    @Test
    fun aRecordedTargetedAnswerCanEarnRealSpacedReadinessWithoutAFocusTicket() {
        val alpha = grammarFixture("alpha", examples = 2)
        val target = installTopics(alpha, grammarFixture("dependent", requires = listOf("alpha")))
        val now = Instant.now()
        alpha.exercises.filterNot { it.id == "alpha-b-1" }.forEach {
            seedTopicReview(it, now.minusSeconds(if (it.id.endsWith("-0")) 172800 else 86400), now.plusSeconds(86400), typed = true)
        }
        val vm = targeted(target)
        vm.startTargeted(false, vm.ui as GrammarUi.Preview)
        awaitLoaded(vm)
        assertEquals("alpha-b-1", vm.currentExercise!!.id)
        assertTrue(runBlocking { db.reviews().grammarReadiness(sourceId, GrammarReadiness.POLICY_VERSION).isEmpty() })
        answerCorrect(vm)
        val ready = runBlocking { db.reviews().grammarReadiness(sourceId, GrammarReadiness.POLICY_VERSION) }
        assertEquals(setOf("a", "b"), ready.filter { it.groupId == "alpha" }.map { it.facetId }.toSet())
        assertNull(runBlocking { db.reviews().grammarFocus(sourceId) })
        vm.leaveSession()
    }
}
