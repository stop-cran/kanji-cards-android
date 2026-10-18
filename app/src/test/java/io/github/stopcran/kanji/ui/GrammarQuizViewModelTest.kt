package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.GrammarConstruction
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarRelationship
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarEntity
import io.github.stopcran.kanji.data.Settings
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
class GrammarQuizViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val sourceId get() = services.settings.source.value.id

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        services.settings.setWeekPlan("FFFFFFF")
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun start(count: Int = 3, answers: List<String> = listOf("model", "alternative")): GrammarQuizViewModel {
        val constructions = (0 until count).map { GrammarConstruction("target-$it", "Target $it") }
        val exercises = constructions.mapIndexed { index, target ->
            GrammarExercise(
                "exercise-$index", target.id, "contrast", "Complete ____.", "Synthetic grading fixture.",
                "Evidence.", "Explanation.", target.id, answers,
                listOf(
                    GrammarOption(target.id, answers.first(), "Fits."),
                    GrammarOption(constructions[(index + 1) % count].id, "wrong", "Does not fit."),
                ),
            )
        }
        val set = GrammarSet(
            1, "fixture", "Fixture", "articles/fixture.md", constructions,
            listOf(GrammarRelationship("contrast", "Contrast", constructions.map { it.id })), exercises,
        )
        runBlocking {
            db.content().insertGrammar(listOf(GrammarEntity(sourceId, set.id, Json.encodeToString(GrammarSet.serializer(), set), 0)))
        }
        val vm = GrammarQuizViewModel(services)
        vm.ensureStarted(false)
        val end = System.currentTimeMillis() + 10_000
        while (vm.ui is GrammarUi.Loading && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("Expected a first choice question, got ${vm.ui}", vm.ui is GrammarUi.Choose)
        return vm
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

    private fun advance(vm: GrammarQuizViewModel, guessed: Boolean = false) {
        vm.next(guessed)
        // Drain Room's serial transaction queue even when this answer should not have recorded anything.
        runBlocking { withTimeout(10_000) { db.withTransaction {} } }
    }

    private fun logs() = runBlocking { db.reviews().allLog() }
    private fun details() = runBlocking { db.reviews().allGrammarResponses() }
    private fun states() = runBlocking { db.reviews().allStates() }

    @Test
    fun choiceAndTypingRecordGoodAndGuessedChoiceRecordsHard() {
        val vm = start()
        val first = vm.ui as GrammarUi.Choose
        vm.pick(first.exercise.answer)
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        assertTrue(logs().isEmpty())
        assertEquals("articles/fixture.md", vm.articlePath(first.exercise.id))
        advance(vm)
        answerCorrect(vm)
        val last = vm.ui as GrammarUi.Choose
        vm.pick(last.exercise.answer)
        advance(vm, guessed = true)
        assertTrue(vm.ui is GrammarUi.Done)
        assertEquals(listOf(3, 3, 2), logs().map { it.grade })
        assertEquals(listOf("choose", "type", "choose"), details().map { it.promptType })
        assertEquals(listOf(first.exercise.answer, "alternative", last.exercise.answer), details().map { it.response })
        assertTrue(details().all { it.correct })
        val byId = logs().associateBy { it.id }
        details().forEach {
            val log = byId.getValue(it.reviewLogId)
            assertEquals(it.exerciseId, log.kanji)
            assertEquals(it.sourceId, log.sourceId)
            assertEquals(GRAMMAR_STACK, log.stack)
            assertEquals("Grammar", log.mode)
            assertEquals("", log.reading)
        }
        assertEquals(3, states().size)
    }

    @Test
    fun typingTrimsAndNfkcNormalizesBothInputAndAcceptedAlternatives() {
        val vm = start(2, listOf("ＭＯＤＥＬ", "  ＡＬＴＥＲＮＡＴＥ  "))
        answerCorrect(vm)
        vm.onDraft(" \u3000 ")
        vm.check()
        assertTrue(vm.ui is GrammarUi.Type)
        assertEquals(1, logs().size)
        vm.onDraft(" \u3000ＡLＴＥＲＮＡＴＥ\u3000 ")
        vm.check()
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        assertEquals("ALTERNATE", details().last().response)
        assertEquals(3, logs().last().grade)
    }

    @Test
    fun typingDoesNotAcceptAnUnlistedExtension() {
        val vm = start(2)
        answerCorrect(vm)
        vm.onDraft("model with extra words")
        vm.check()
        assertFalse((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        assertEquals(1, logs().last().grade)
        assertFalse(details().last().correct)
        assertEquals("model with extra words", details().last().response)
    }

    @Test
    fun givingUpInEitherPresentationRecordsAnEmptyAgainNotTheDraft() {
        val vm = start(2)
        vm.pick(null)
        assertFalse((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        vm.onDraft("alternative")
        vm.check(giveUp = true)
        assertFalse((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        assertEquals(listOf(1, 1), logs().map { it.grade })
        assertEquals(listOf("choose", "type"), details().map { it.promptType })
        assertTrue(details().all { !it.correct && it.response.isEmpty() })
    }

    @Test
    fun wrongChoiceCanReturnAsTypingWithoutCreatingAnotherSchedule() {
        val vm = start()
        val first = vm.ui as GrammarUi.Choose
        vm.pick(first.options.first { it.id != first.exercise.answer }.id)
        advance(vm)
        repeat(2) { answerCorrect(vm) }
        val repeat = vm.ui as GrammarUi.Type
        assertEquals(first.exercise.id, repeat.exercise.id)
        answerCorrect(vm)
        assertTrue(vm.ui is GrammarUi.Done)
        val repeated = details().filter { it.exerciseId == first.exercise.id }
        assertEquals(listOf("choose", "type"), repeated.map { it.promptType })
        assertEquals(listOf(false, true), repeated.map { it.correct })
        assertTrue(repeated.all { it.constructionId == first.exercise.target && it.relationshipId == first.exercise.relationship })
        assertEquals(4, logs().size)
        assertEquals(3, states().size)
        assertEquals(2, states().single { it.kanji == first.exercise.id }.reps)
    }

    @Test
    fun successfulPracticeConfirmationAddsNeitherLogNorResponseNorSrsRepetition() {
        val vm = start(5)
        val first = vm.ui as GrammarUi.Choose
        vm.pick(first.options.first { it.id != first.exercise.answer }.id)
        advance(vm)
        repeat(6) { answerCorrect(vm) }
        val done = vm.ui as GrammarUi.Done
        assertEquals(7, done.answered)
        assertEquals(6, done.correct)
        assertEquals(6, logs().size)
        assertEquals(6, details().size)
        assertEquals(2, details().count { it.exerciseId == first.exercise.id })
        assertEquals(2, states().single { it.kanji == first.exercise.id }.reps)
    }
}
