package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ImageEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.SceneImageResult
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.WordSceneEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.sceneImage
import io.github.stopcran.kanji.data.sceneWebp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WordPictureViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val source get() = services.settings.source.value.id
    private val stack = WordStacks.onomatopoeia.id

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        services.settings.setWeekPlan("FFFFFFF")
        services.settings.setWordStack(stack)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun install(count: Int = 4, variants: Int = 1, gate: Boolean = true) = runBlocking {
        for (index in 0 until count) {
            val word = "word-$index"
            val image = "images/$word--0.webp"
            db.content().insertWords(listOf(
                WordEntity(source, word, word, "Meaning $index", "wago", "", "onomatopoeia", "Body", 5, image = image, imageScene = "Scene"),
            ))
            for (variant in 0 until variants) {
                val path = "images/$word--$variant.webp"
                val bytes = sceneWebp(index * variants + variant)
                db.content().insertImages(listOf(ImageEntity(source, path.removePrefix("images/"), bytes)))
                db.content().insertScenes(listOf(
                    WordSceneEntity(
                        source, word, "scene-$variant", "Sense $variant", path, "Scene $word $variant",
                        true, ContentParser.rawSha256(bytes), variant,
                    ),
                ))
            }
            if (gate) db.reviews().putState(ReviewStateEntity(source, stack, word, "WordJpEn", "Review", 3.0, 5.0, 999, 500, 2, 0))
        }
    }

    private fun awaitQuestionOrEnd(vm: WordQuizViewModel) {
        val end = System.currentTimeMillis() + 10_000
        while (vm.ui is WordQuizUi.Loading && System.currentTimeMillis() < end) Thread.sleep(10)
        assertFalse("Session did not finish loading", vm.ui is WordQuizUi.Loading)
    }

    private fun advance(vm: WordQuizViewModel, guessed: Boolean = false) {
        vm.next(guessed)
        runBlocking { withTimeout(10_000) { db.withTransaction {} } }
        awaitQuestionOrEnd(vm)
    }

    @Test
    fun inverseQuestionWaitsForEveryImageAndRecordsOnlyItsWordDirection() = runBlocking {
        install()
        val old = ReviewStateEntity(source, stack, "word-0", "WordPicture", "Review", 15.0, 5.0, 999, 500, 4, 0)
        db.reviews().putState(old)
        val lastRequested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val vm = WordQuizViewModel(services) { owner, scene ->
            if (++requests == 4) {
                lastRequested.complete(Unit)
                release.await()
            }
            db.content().sceneImage(owner, scene.scene.image, scene.imageHash)
        }
        vm.ensureStarted(WordDirection.WordToPicture, extra = false)
        withTimeout(10_000) { lastRequested.await() }
        assertSame(WordQuizUi.Loading, vm.ui)
        vm.pick("word-0")
        vm.next()
        assertTrue(db.reviews().allLog().isEmpty())
        release.complete(Unit)
        awaitQuestionOrEnd(vm)
        val question = vm.ui as WordQuizUi.Question
        assertEquals(4, question.picture!!.images.size)
        vm.pick(question.word.word)
        assertSame(question.picture, (vm.ui as WordQuizUi.Answer).picture)
        advance(vm, guessed = true)
        val log = db.reviews().allLog().single()
        assertEquals(question.word.word, log.kanji)
        assertEquals("WordToPicture", log.mode)
        assertEquals(stack, log.stack)
        assertEquals("", log.reading)
        assertEquals(2, log.grade)
        assertEquals(old, db.reviews().states(source, stack, "WordPicture").single())
    }

    @Test
    fun unavailablePicturesNeverBecomeAnswersOrSpendTheNewItemBudget() = runBlocking {
        install()
        val before = db.reviews().allStates()
        val budget = db.newAllowance(services.settings, source, Instant.now()).remaining
        for (direction in listOf(WordDirection.Picture, WordDirection.WordToPicture)) {
            val vm = WordQuizViewModel(services) { _, _ -> SceneImageResult.Unavailable("Fixture picture unavailable") }
            vm.ensureStarted(direction, extra = false)
            awaitQuestionOrEnd(vm)
            assertTrue(vm.ui is WordQuizUi.Empty)
            assertNotNull(vm.pictureNotice)
            vm.pick("word-0")
            vm.next()
            db.withTransaction {}
            assertTrue(db.reviews().allLog().isEmpty())
            assertEquals(before, db.reviews().allStates())
            assertEquals(budget, db.newAllowance(services.settings, source, Instant.now()).remaining)
        }
    }

    @Test
    fun pictureReaskKeepsItsNamedVariantInsteadOfCreatingASceneSchedule() {
        install(count = 5, variants = 2)
        val vm = WordQuizViewModel(services)
        vm.ensureStarted(WordDirection.Picture, extra = false)
        awaitQuestionOrEnd(vm)
        val first = vm.ui as WordQuizUi.Question
        vm.pick(NO_ANSWER)
        advance(vm)
        repeat(3) {
            val next = vm.ui as WordQuizUi.Question
            assertTrue(next.word.word != first.word.word)
            vm.pick(next.word.word)
            advance(vm)
        }
        val retry = vm.ui as WordQuizUi.Question
        assertEquals(first.word.word, retry.word.word)
        assertEquals(first.picture!!.question.target, retry.picture!!.question.target)
        assertEquals(1, retry.picture.images.size)
        val logs = runBlocking { db.reviews().allLog() }
        assertEquals(4, logs.size)
        assertTrue(logs.all { it.mode == "WordPicture" && it.kanji.startsWith("word-") && it.reading.isEmpty() })
    }

    private fun installSkippedInterveningWords(direction: WordDirection, laterTarget: Boolean) = runBlocking {
        install(count = if (laterTarget) 6 else 5, gate = false)
        val all = db.content().words(source)
        val now = Instant.now().toEpochMilli()
        for (index in 0 until if (laterTarget) 5 else 4) {
            val word = "word-$index"
            val due = when (index) { 0 -> 500L; 4 -> now - 60_000; else -> now - 3 * 86_400_000L }
            db.reviews().putState(ReviewStateEntity(source, stack, word, "WordJpEn", "Review", 3.0, 5.0, 999, 500, 2, 0))
            db.reviews().putState(
                ReviewStateEntity(source, stack, word, direction.mode.name, "Review", 3.0, 5.0, due, due - 86_400_000L, 2, 0),
            )
            if (index in 1..3) {
                val entity = all.single { it.word == word }
                db.content().insertWords(listOf(entity.copy(quizExclusions = all.filter { it.word != word }.joinToString("\u001f") { it.word })))
            }
        }
    }

    @Test
    fun skippedInterveningTargetsCannotTurnAReaskIntoAnImmediateRecordedSuccess() = runBlocking {
        for (direction in listOf(WordDirection.Picture, WordDirection.WordToPicture)) {
            installSkippedInterveningWords(direction, laterTarget = false)
            val before = db.reviews().allLog().size
            val vm = WordQuizViewModel(services)
            vm.ensureStarted(direction, extra = false)
            awaitQuestionOrEnd(vm)
            assertEquals("word-0", (vm.ui as WordQuizUi.Question).word.word)
            vm.pick(NO_ANSWER)
            advance(vm)
            assertTrue(vm.ui is WordQuizUi.Done)
            vm.pick("word-0")
            advance(vm)
            val logs = db.reviews().allLog().drop(before)
            assertEquals(1, logs.size)
            assertEquals(1, logs.single().grade)
            assertEquals("word-0", logs.single().kanji)
        }
    }

    @Test
    fun aReaskCanMovePastSkippedTargetsUntilAnotherPictureHasActuallyBeenAsked() {
        installSkippedInterveningWords(WordDirection.Picture, laterTarget = true)
        val vm = WordQuizViewModel(services)
        vm.ensureStarted(WordDirection.Picture, extra = false)
        awaitQuestionOrEnd(vm)
        val first = vm.ui as WordQuizUi.Question
        assertEquals("word-0", first.word.word)
        vm.pick(NO_ANSWER)
        advance(vm)
        assertEquals("word-4", (vm.ui as WordQuizUi.Question).word.word)
        vm.pick("word-4")
        advance(vm)
        val retry = vm.ui as WordQuizUi.Question
        assertEquals(first.word.word, retry.word.word)
        assertEquals(first.picture!!.question.target, retry.picture!!.question.target)
    }

    @Test
    fun inversePracticeRespectsTheRecognitionGateAndPreservesPaddingOwnership() = runBlocking {
        install(gate = false)
        val locked = WordQuizViewModel(services)
        locked.ensureStarted(WordDirection.WordToPicture, extra = false)
        awaitQuestionOrEnd(locked)
        assertTrue(locked.ui is WordQuizUi.Empty)
        db.reviews().putState(ReviewStateEntity(source, WordStacks.n5.id, "word-0", "WordToPicture", "Review", 3.0, 5.0, 999, 500, 2, 0))
        val padded = WordQuizViewModel(services)
        padded.ensureStarted(WordDirection.WordToPicture, extra = true)
        awaitQuestionOrEnd(padded)
        val question = padded.ui as WordQuizUi.Question
        assertEquals("word-0", question.word.word)
        padded.pick(question.word.word)
        advance(padded)
        assertEquals(WordStacks.n5.id, db.reviews().allLog().single().stack)
        assertTrue(db.reviews().states(source, stack, "WordToPicture").isEmpty())
    }
}
