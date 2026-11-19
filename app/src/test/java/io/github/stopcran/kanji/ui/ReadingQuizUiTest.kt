package io.github.stopcran.kanji.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.readingtext.ReadingBookMode
import io.github.stopcran.kanji.core.readingtext.ReadingContent
import io.github.stopcran.kanji.core.readingtext.ReadingPackage
import io.github.stopcran.kanji.core.readingtext.ReadingPicture
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentRows
import io.github.stopcran.kanji.data.ReadingEntity
import io.github.stopcran.kanji.data.ReadingImageEntity
import io.github.stopcran.kanji.data.ReadingQuizStore
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.RoomTextReadingRepository
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncMetaEntity
import io.github.stopcran.kanji.data.sceneWebp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReadingQuizUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun closedBookKeepsQuestionPicturesButHidesPassageAndAllFeedbackUntilSubmission() = verify(ReadingBookMode.ClosedBook)

    @Test fun openBookOffersTheCapturedPassageAndKeepsTheSameEndOnlyFeedbackBoundary() = verify(ReadingBookMode.OpenBook)

    @Test fun returningToTheOriginalQuizHostReloadsAnswersSavedThroughAnotherRoute() {
        val (db, settings) = database()
        try {
            val store = ReadingQuizStore(db, RoomTextReadingRepository(db, settings), settings)
            val id = runBlocking { install(db, settings, store, ReadingBookMode.OpenBook) }
            lateinit var vm: ReadingQuizViewModel
            var visible by mutableStateOf(true)
            rule.setContent {
                MaterialTheme {
                    vm = remember { ReadingQuizViewModel(store, id) }
                    if (visible) ReadingQuizHost(vm, db, store, {}, {}, {})
                    else Text("Another route")
                }
            }
            await { vm.ui is ReadingQuizUi.Ready && !vm.busy }
            val question = (vm.ui as ReadingQuizUi.Ready).quiz.plan.questions.first().question
            rule.runOnIdle { visible = false }
            await { rule.onAllNodesWithText("Another route").fetchSemanticsNodes().size == 1 }
            runBlocking { store.answer(id, question.id, question.correctOptionId) }
            assertTrue((vm.ui as ReadingQuizUi.Ready).quiz.answers.isEmpty())
            rule.runOnIdle { visible = true }
            await {
                rule.onAllNodesWithText("Question 2 of 4").fetchSemanticsNodes().size == 1 &&
                    (vm.ui as? ReadingQuizUi.Ready)?.quiz?.answers?.size == 1 && !vm.busy
            }
            rule.onNodeWithText("Question 2 of 4").assertExists()
        } finally {
            db.close()
        }
    }

    @Test fun olderResultEvidenceIsBlockedWhileANewerClosedBookQuizIsUnfinished() {
        val (db, settings) = database()
        try {
            val store = ReadingQuizStore(db, RoomTextReadingRepository(db, settings), settings)
            val first = runBlocking {
                val id = install(db, settings, store, ReadingBookMode.OpenBook)
                val quiz = store.load(id)
                quiz.plan.questions.forEach { store.answer(id, it.question.id, it.question.correctOptionId) }
                store.submit(id)
            }
            val pending = runBlocking { store.create(first.reader, ReadingBookMode.ClosedBook) }
            var resumed: String? = null
            rule.setContent {
                MaterialTheme {
                    val vm = remember { ReadingQuizViewModel(store, first.saved.id) }
                    ReadingQuizHost(vm, db, store, {}, { resumed = it }, {})
                }
            }
            await { rule.onAllNodesWithText("Resume quiz").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithText(first.plan.questions.first().question.explanation).assertDoesNotExist()
            rule.onNodeWithText("Read the captured text").assertDoesNotExist()
            rule.onNodeWithText("Resume quiz").performClick()
            assertEquals(pending.saved.id, resumed)
        } finally {
            db.close()
        }
    }

    @Test fun readerQuizActionAgreesWithModeAvailabilityAndKeepsAnUnfinishedQuizResumable() {
        val (db, settings) = database()
        try {
            settings.setDailyNewCards(0)
            val store = ReadingQuizStore(db, RoomTextReadingRepository(db, settings), settings)
            val reader = runBlocking { installCompleted(db, settings) }
            var opened = 0
            rule.setContent {
                MaterialTheme {
                    val vm = remember { TextReadingViewModel(RoomTextReadingRepository(db, settings), "owner/repo", reader.saved.textId) }
                    TextReadingScreen(vm, {}, {}, onQuiz = { opened++ },
                        quiz = rememberReadingQuizAvailability(store, "owner/repo", reader.saved.textId))
                }
            }
            val noAllowance = "No shared daily-new allowance remains for introducing this text. " +
                "Reading and word lookup are still available."
            await { rule.onAllNodesWithText(noAllowance).fetchSemanticsNodes().size == 1 }
            rule.onNodeWithText("Reading quiz").performScrollTo().assertIsNotEnabled()

            rule.runOnIdle { settings.setDailyNewCards(1) }
            await { rule.onAllNodesWithText(noAllowance).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("Reading quiz").performScrollTo().assertIsEnabled().performClick()
            assertEquals(1, opened)

            // Another mode introducing an item spends the shared allowance while the reader stays open.
            runBlocking {
                db.reviews().insertLog(ReviewLogEntity(sourceId = "owner/repo", stack = "other", kanji = "日",
                    mode = "Quiz", grade = 3, atMs = System.currentTimeMillis()))
            }
            await { rule.onAllNodesWithText(noAllowance).fetchSemanticsNodes().size == 1 }
            rule.onNodeWithText("Reading quiz").performScrollTo().assertIsNotEnabled()

            rule.runOnIdle { settings.setDailyNewCards(2) }
            await { rule.onAllNodesWithText(noAllowance).fetchSemanticsNodes().isEmpty() }
            runBlocking { store.create(reader, ReadingBookMode.OpenBook) }
            rule.runOnIdle { settings.setDailyNewCards(0) }
            await { rule.onAllNodesWithText("Resume quiz").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithText(noAllowance).assertDoesNotExist()
            rule.onNodeWithText("Reading quiz").assertDoesNotExist()
            rule.onNodeWithText("Resume quiz").performScrollTo().assertIsEnabled().performClick()
            assertEquals(2, opened)
        } finally {
            db.close()
        }
    }

    @Test fun readerReportsAFailedAvailabilityCheckAndRecoversOnRetry() {
        val (db, settings) = database()
        try {
            settings.setDailyNewCards(1)
            val store = ReadingQuizStore(db, RoomTextReadingRepository(db, settings), settings)
            val reader = runBlocking { installCompleted(db, settings) }
            db.openHelper.writableDatabase.execSQL("ALTER TABLE reading_quizzes RENAME TO reading_quizzes_hidden")
            rule.setContent {
                MaterialTheme {
                    val vm = remember { TextReadingViewModel(RoomTextReadingRepository(db, settings), "owner/repo", reader.saved.textId) }
                    TextReadingScreen(vm, {}, {}, onQuiz = {},
                        quiz = rememberReadingQuizAvailability(store, "owner/repo", reader.saved.textId))
                }
            }
            val failure = "Could not check whether this quiz can start. Retry, or reopen the text."
            await { rule.onAllNodesWithText(failure).fetchSemanticsNodes().size == 1 }
            rule.onNodeWithText("Reading quiz").performScrollTo().assertIsNotEnabled()
            db.openHelper.writableDatabase.execSQL("ALTER TABLE reading_quizzes_hidden RENAME TO reading_quizzes")
            rule.onNodeWithText("Retry").performScrollTo().performClick()
            await { rule.onAllNodesWithText(failure).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("Reading quiz").performScrollTo().assertIsEnabled()
        } finally {
            db.close()
        }
    }

    private fun database(): Pair<AppDatabase, Settings> {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        settings.setSource("https://github.com/owner/repo", "main")
        settings.setWeekPlan("FFFFFFF")
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        return db to settings
    }

    private fun verify(mode: ReadingBookMode) {
        val (db, settings) = database()
        try {
            val store = ReadingQuizStore(db, RoomTextReadingRepository(db, settings), settings)
            val id = runBlocking { install(db, settings, store, mode) }
            lateinit var vm: ReadingQuizViewModel
            var opens = 0
            rule.setContent {
                MaterialTheme {
                    vm = remember { ReadingQuizViewModel(store, id) }
                    ReadingQuizHost(vm, db, store, {}, {}, { opens++ })
                }
            }
            await { vm.ui is ReadingQuizUi.Ready && !vm.busy }
            rule.onNodeWithTag("reading-question-image").assertExists()
            val read = rule.onNodeWithText("Read the captured text")
            if (mode == ReadingBookMode.ClosedBook) read.assertDoesNotExist()
            else {
                read.performScrollTo().performClick()
                assertEquals(1, opens)
            }
            answerAll(vm)
            rule.onNodeWithText("Finish quiz").performScrollTo().performClick()
            await { (vm.ui as? ReadingQuizUi.Ready)?.quiz?.result != null }
            val result = (vm.ui as ReadingQuizUi.Ready).quiz
            await { rule.onAllNodesWithText(result.plan.questions.first().question.explanation).fetchSemanticsNodes().size == 4 }
            rule.onAllNodesWithText(result.plan.questions.first().question.explanation).assertCountEquals(4)
            assertEquals(mode, requireNotNull(result.result).mode)
            assertEquals(1, runBlocking { db.reviews().allLog().size })
        } finally {
            db.close()
        }
    }

    private fun answerAll(vm: ReadingQuizViewModel) {
        repeat(4) { index ->
            val current = (vm.ui as ReadingQuizUi.Ready).quiz
            val question = current.plan.questions[index].question
            val evidence = question.evidence.first()
            val section = current.reader.content.sections.single { it.id == evidence.sectionId }
            rule.onNodeWithText(question.explanation).assertDoesNotExist()
            rule.onNodeWithText(ReadingContent.slice(section.text, evidence.start, evidence.end), substring = true).assertDoesNotExist()
            rule.onNodeWithText(question.options.single { it.id == question.correctOptionId }.text).performScrollTo().performClick()
            rule.onNodeWithText("Save answer and continue").performScrollTo().performClick()
            await { (vm.ui as? ReadingQuizUi.Ready)?.quiz?.answers?.size == index + 1 && !vm.busy }
        }
    }

    private fun await(condition: () -> Boolean) {
        rule.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }

    private suspend fun install(db: AppDatabase, settings: Settings, store: ReadingQuizStore, mode: ReadingBookMode): String =
        store.create(installCompleted(db, settings), mode).saved.id

    private suspend fun installCompleted(db: AppDatabase, settings: Settings): io.github.stopcran.kanji.data.ReadingReader {
        val original = ReadingContent.parse("reading/reading-fixture-peach.json",
            requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()).content
        val bytes = sceneWebp(0)
        val picture = ReadingPicture("reading-images/fixture.webp", ContentParser.rawSha256(bytes), "A fixture illustration.")
        val content = original.copy(questions = (1..4).map {
            original.questions.single().copy(id = "question-$it", image = picture)
        })
        val raw = Json.encodeToString(ReadingPackage.serializer(), content)
        db.content().replaceContent("owner/repo", ContentRows(emptyList(), emptyList(), emptyList(),
            reading = listOf(ReadingEntity("owner/repo", content.id, raw, ContentParser.sha256(raw.toByteArray()), content.title, 5, 0)),
            readingImages = listOf(ReadingImageEntity("owner/repo", picture.path, picture.sha256, bytes))),
            SyncMetaEntity("owner/repo", "fixture", 1, 0, readingParserEpoch = 1))
        val readers = RoomTextReadingRepository(db, settings)
        val reader = readers.open("owner/repo", content.id)
        readers.complete(reader)
        return reader
    }
}
