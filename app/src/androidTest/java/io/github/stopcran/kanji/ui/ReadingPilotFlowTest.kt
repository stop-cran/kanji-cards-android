package io.github.stopcran.kanji.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.readingtext.ReadingBookMode
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentHttp
import io.github.stopcran.kanji.data.ContentResponse
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.ReadingQuizStore
import io.github.stopcran.kanji.data.RoomTextReadingRepository
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReadingPilotFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun reviewedArchiveQuizSurvivesProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("readingPhase")
        assumeTrue("Supply the local unpublished archive and prepare/restore phase", phase in listOf("prepare", "restore"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.getExternalFilesDir(null), "reading-pilot-acceptance.zip")
        assertTrue("Push the pinned, unpublished archive to the target's external files directory", archive.isFile)
        assertEquals(ARCHIVE_HASH, ContentParser.rawSha256(archive.readBytes()))
        val settings = Settings(object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("reading-native-$name", mode)
        })
        settings.setSource("https://github.com/stop-cran/learning-japanese", "romanko-ms-reading-issues-review")
        settings.setWeekPlan("FFFFFFF")
        settings.setDailyNewCards(1)
        val source = settings.source.value
        if (phase == "prepare") context.deleteDatabase(DATABASE)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE).build()
        var mounted by mutableStateOf(true)
        try {
            val readers = RoomTextReadingRepository(db, settings)
            val store = ReadingQuizStore(db, readers, settings)
            val initial = runBlocking {
                if (phase == "prepare") {
                    val http = ContentHttp { url, _, _, _ ->
                        object : ContentResponse {
                            override val code = 200
                            override val body = if (url.startsWith("https://api.github.com/"))
                                REVISION.byteInputStream() else archive.inputStream()
                            override fun header(name: String): String? = null
                            override fun close() = body.close()
                        }
                    }
                    val imported = ContentSync(db, settings.sync, http, { settings.source.value }).sync(source, force = true)
                    assertTrue(imported.toString(), imported is SyncResult.Updated && imported.reading == 1)
                    assertNull(db.content().meta(source.id)?.readingProblem)
                    assertEquals(REVISION, db.content().meta(source.id)?.contentRevision)
                    val reader = readers.open(source.id, TEXT)
                    assertEquals(PACKAGE_HASH, reader.saved.contentHash)
                    assertEquals(1_154, reader.content.sections.sumOf { it.text.codePointCount(0, it.text.length) })
                    readers.complete(reader)
                    store.create(reader, ReadingBookMode.OpenBook)
                } else {
                    val pending = requireNotNull(db.reading().openQuiz(source.id, TEXT))
                    store.load(pending.id).also {
                        assertEquals(1, it.answers.size)
                        assertEquals(ReadingBookMode.OpenBook, it.plan.mode)
                        assertEquals(PACKAGE_HASH, it.reader.saved.contentHash)
                    }
                }
            }
            var activeId by mutableStateOf(initial.saved.id)
            var reading by mutableStateOf(false)
            lateinit var vm: ReadingQuizViewModel
            rule.setContent {
                MaterialTheme {
                    if (mounted) {
                        vm = remember(activeId) { ReadingQuizViewModel(store, activeId) }
                        if (reading) {
                            val readerVm = remember {
                                TextReadingViewModel(readers, source.id, TEXT, initial.saved.captureId)
                            }
                            TextReadingScreen(readerVm, { reading = false }, {})
                        } else ReadingQuizHost(vm, db, store, {}, { activeId = it }, { reading = true })
                    }
                }
            }
            await { vm.ui is ReadingQuizUi.Ready && !vm.busy }
            if (phase == "prepare") {
                touch(context.getString(R.string.reading_quiz_read_text))
                rule.onNodeWithTag("reading-sections").performScrollToIndex(8)
                screenshot(context, "reading-pilot-long-passage")
                rule.runOnIdle { reading = false }
                await { !vm.busy }
                answer(vm, correct = true)
                screenshot(context, "reading-pilot-before-restart")
            } else {
                screenshot(context, "reading-pilot-after-restart")
                completeWithOneHighestTierMiss(context, vm)
                val completed = (vm.ui as ReadingQuizUi.Ready).quiz
                assertEquals(3, completed.result?.highestCorrect)
                assertEquals(2, runBlocking { db.reading().progress(source.id, TEXT)?.unlockedTier })
                val closed = runBlocking { store.create(completed.reader, ReadingBookMode.ClosedBook) }
                await { rule.onAllNodesWithText(context.getString(R.string.reading_quiz_resume)).fetchSemanticsNodes().size == 1 }
                rule.onNodeWithText(completed.plan.questions.first().question.explanation).assertDoesNotExist()
                touch(context.getString(R.string.reading_quiz_resume))
                await { (vm.ui as? ReadingQuizUi.Ready)?.quiz?.saved?.id == closed.saved.id && !vm.busy }
                rule.onNodeWithText(context.getString(R.string.reading_quiz_read_text)).assertDoesNotExist()
                completeWithOneHighestTierMiss(context, vm)
                assertEquals(3, runBlocking { db.reading().progress(source.id, TEXT)?.unlockedTier })
                assertEquals(setOf("ReadingOpenBook", "ReadingClosedBook"), runBlocking { db.reviews().allLog() }.map { it.mode }.toSet())
                assertEquals(2, runBlocking { db.reviews().allLog().size })
                screenshot(context, "reading-pilot-two-mode-results")
            }
        } finally {
            rule.runOnIdle { mounted = false }
            rule.waitForIdle()
            db.close()
        }
    }

    private fun answer(vm: ReadingQuizViewModel, correct: Boolean) {
        val ready = vm.ui as ReadingQuizUi.Ready
        val question = ready.quiz.plan.questions[ready.quiz.answers.size].question
        rule.onNodeWithText(question.explanation).assertDoesNotExist()
        if (correct) {
            touch(question.options.single { it.id == question.correctOptionId }.text)
            touch(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.reading_quiz_save_answer))
        } else touch(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.reading_quiz_unknown))
        await { (vm.ui as? ReadingQuizUi.Ready)?.quiz?.answers?.size == ready.quiz.answers.size + 1 && !vm.busy }
    }

    private fun completeWithOneHighestTierMiss(context: Context, vm: ReadingQuizViewModel) {
        val initial = (vm.ui as ReadingQuizUi.Ready).quiz
        var correctRemaining = 3 - initial.answers.count { answer ->
            initial.plan.questions.single { it.question.id == answer.questionId }.question.let {
                it.tier == initial.plan.highestTier && answer.optionId == it.correctOptionId
            }
        }
        while (true) {
            val quiz = (vm.ui as ReadingQuizUi.Ready).quiz
            val question = quiz.plan.questions.getOrNull(quiz.answers.size)?.question ?: break
            val highest = question.tier == quiz.plan.highestTier
            answer(vm, !highest || correctRemaining-- > 0)
        }
        touch(context.getString(R.string.reading_quiz_submit))
        await { (vm.ui as? ReadingQuizUi.Ready)?.quiz?.result != null && !vm.busy }
        assertEquals(true, (vm.ui as ReadingQuizUi.Ready).quiz.result?.passed)
    }

    private fun touch(text: String) {
        rule.onNodeWithText(text).performScrollTo().performTouchInput { click() }
    }

    private fun await(condition: () -> Boolean) = rule.waitUntil(20_000, condition)

    private fun screenshot(context: Context, name: String) {
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private companion object {
        const val DATABASE = "reading-native-acceptance.db"
        const val TEXT = "momotaro-n5"
        const val REVISION = "fa5884e9d7257af95bed2b2a3735b0a38fb7cb9b"
        const val PACKAGE_HASH = "87e30b125f176f4a7d9a7b5e0b200787033c0edf9283e009931c887aa884adef"
        const val ARCHIVE_HASH = "2fd2e1411049050a3d0163f94a911b1891355d8cda15c997b180489d2074aeb7"
    }
}
