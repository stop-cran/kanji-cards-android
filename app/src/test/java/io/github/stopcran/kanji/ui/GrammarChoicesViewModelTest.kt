package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.GrammarChoiceRandom
import io.github.stopcran.kanji.core.grammarFixture
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GrammarChoiceSnapshot
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.GrammarEntity
import io.github.stopcran.kanji.data.ProgressExport
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.grammarSets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GrammarChoicesViewModelTest {
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
        db.close()
        Dispatchers.resetMain()
    }

    private fun start(count: Int): GrammarQuizViewModel {
        val base = grammarFixture()
        val set = base.copy(
            introductionGroups = emptyList(),
            exercises = base.exercises.sortedBy { it.id.substringAfterLast('-') }.take(count).map {
                it.copy(
                    acceptedAnswers = it.acceptedAnswers + listOf("alternative", "third"),
                    additionalCorrectOptions = listOf(
                        GrammarOption("alternate-realization", "alternative", "Fits too."),
                        GrammarOption("third-realization", "third", "A third framing."),
                    ),
                )
            },
        )
        runBlocking { db.content().insertGrammar(listOf(GrammarEntity(sourceId, set.id, Json.encodeToString(GrammarSet.serializer(), set), 0))) }
        val vm = GrammarQuizViewModel(services, GrammarChoiceRandom())
        vm.ensureStarted(false)
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.ui is GrammarUi.Loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Expected a choice, got ${vm.ui}", vm.ui is GrammarUi.Choose)
        return vm
    }

    private fun advance(vm: GrammarQuizViewModel, guessed: Boolean = false) {
        vm.next(guessed)
        runBlocking { withTimeout(10_000) { db.withTransaction {} } }
    }

    private fun answerCorrect(vm: GrammarQuizViewModel) {
        when (val question = vm.ui) {
            is GrammarUi.Choose -> vm.pick(question.exercise.answer, question)
            is GrammarUi.Type -> { vm.onDraft("third"); vm.check() }
            else -> error("Expected question, got $question")
        }
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
    }

    private fun logs() = runBlocking { db.reviews().allLog() }
    private fun details() = runBlocking { db.reviews().allGrammarResponses() }
    private fun states() = runBlocking { db.reviews().allStates() }

    private fun exportedResponses() = runBlocking {
        val bytes = ByteArrayOutputStream()
        ProgressExport.write(db, services.settings, bytes)
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "grammar_responses.json" }
            Json.parseToJsonElement(zip.readBytes().decodeToString()).jsonArray
        }
    }

    @Test
    fun eitherCorrectChoiceUsesCapturedLabelsAndOneEventWhileHiddenAndStaleChoicesCannotGrade() {
        val vm = start(3)
        val owner = sourceId
        val first = vm.ui as GrammarUi.Choose
        vm.openVocabularyList()
        vm.closeVocabularyList()
        assertSame(first, vm.ui)
        runBlocking {
            val set = db.grammarSets(owner).single()
            val changed = set.copy(exercises = set.exercises.map { it.copy(additionalCorrectOptions = emptyList()) })
            db.content().insertGrammar(listOf(GrammarEntity(owner, set.id, Json.encodeToString(GrammarSet.serializer(), changed), 0)))
        }
        services.settings.setSource("https://github.com/example/fork", "main")
        vm.ensureStarted(false)
        assertSame(first, vm.ui)
        vm.pick("alternate-realization", first)
        val answer = vm.ui as GrammarUi.Answer
        assertTrue(answer.correct)
        assertEquals("alternative", answer.completion)
        assertEquals(first.choices, answer.choices)
        vm.pick(first.exercise.answer, first)
        assertSame(answer, vm.ui)
        advance(vm)
        assertEquals(first.choices, Json.decodeFromString(GrammarChoiceSnapshot.serializer(), details().single().choiceSnapshot!!))
        assertEquals(owner, details().single().sourceId)
        assertEquals(first.exercise.target, details().single().constructionId)
        vm.onDraft("third")
        vm.check()
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        assertNull(details().last().choiceSnapshot)
        val later = vm.ui as GrammarUi.Choose
        vm.pick("alternate-realization", first)
        assertSame(later, vm.ui)
        vm.pick("third-realization", later)
        assertSame(later, vm.ui)
        vm.pick(later.exercise.answer, later)
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        vm.next(origin = answer)
        assertTrue(vm.ui is GrammarUi.Answer)
        advance(vm, guessed = true)
        assertEquals(listOf(3, 3, 2), logs().map { it.grade })
        assertEquals(3, details().size)
        assertEquals(3, states().size)
        val captured = exportedResponses().first().jsonObject.getValue("choiceSnapshot").jsonObject
        assertEquals("choose-any", captured.getValue("interaction").jsonPrimitive.content)
        assertEquals(first.choices.correctOptionIds, captured.getValue("correctOptionIds").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun multiCorrectMistakeRetainsBothAnswersAndSuccessfulConfirmationStillRecordsNothing() {
        val vm = start(5)
        val first = vm.ui as GrammarUi.Choose
        vm.pick(first.options.single { it.id !in first.choices.correctOptionIds }.id, first)
        val missed = vm.ui as GrammarUi.Answer
        assertFalse(missed.correct)
        assertEquals(2, missed.correctOptionIds.size)
        assertEquals(first.choices.options, missed.options)
        advance(vm)
        repeat(5) { answerCorrect(vm) }
        val confirmation = vm.ui as GrammarUi.Choose
        vm.pick("alternate-realization", confirmation)
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        val before = Triple(states(), logs(), details())
        advance(vm)
        assertEquals(before, Triple(states(), logs(), details()))
        assertEquals(7, (vm.ui as GrammarUi.Done).answered)
        assertEquals(6, logs().size)
        assertEquals(2, states().single { it.kanji == first.exercise.id }.reps)
    }

    @Test
    fun multiCorrectGiveUpRetainsBothAnswersAndAnEmptyResponse() {
        val vm = start(3)
        val question = vm.ui as GrammarUi.Choose
        vm.pick(null, question)
        val answer = vm.ui as GrammarUi.Answer
        assertFalse(answer.correct)
        assertEquals("", answer.responseLabel)
        assertEquals(question.choices.correctOptionIds, answer.correctOptionIds)
        assertEquals(2, answer.correctOptionIds.size)
        advance(vm)
        assertEquals(1, logs().single().grade)
        assertEquals("", details().single().response)
        assertEquals(question.choices, Json.decodeFromString(GrammarChoiceSnapshot.serializer(), details().single().choiceSnapshot!!))
    }
}
