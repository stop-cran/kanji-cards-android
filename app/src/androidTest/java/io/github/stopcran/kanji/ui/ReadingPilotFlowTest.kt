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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
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
    private var renderedFontScale = Float.NaN

    @Test fun reviewedArchiveQuizSurvivesProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("readingPhase")
        assumeTrue("Supply the local unpublished archive and prepare/restore phase", phase in listOf("prepare", "restore"))
        val minimumFontScale = args.getString("readingMinimumFontScale")?.toFloat() ?: 1f
        require(minimumFontScale in 1f..2f) { "Expected font scale must be between 1 and 2" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ReadingNativeProcessEvidence.capture(requireNotNull(phase))
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
            var readerVm: TextReadingViewModel? = null
            lateinit var vm: ReadingQuizViewModel
            rule.setContent {
                val fontScale = LocalDensity.current.fontScale
                SideEffect { renderedFontScale = fontScale }
                MaterialTheme {
                    if (mounted) {
                        vm = remember(activeId) { ReadingQuizViewModel(store, activeId) }
                        if (reading) {
                            val currentReader = remember {
                                TextReadingViewModel(readers, source.id, TEXT, initial.saved.captureId)
                            }
                            SideEffect { readerVm = currentReader }
                            TextReadingScreen(currentReader, { reading = false }, {})
                        } else ReadingQuizHost(vm, db, store, {}, { activeId = it }, { reading = true })
                    }
                }
            }
            awaitQuizReady { vm }
            rule.runOnIdle {
                val configurationScale = context.resources.configuration.fontScale
                assertTrue("Composed font scale $renderedFontScale is below $minimumFontScale", renderedFontScale >= minimumFontScale)
                assertTrue("Configuration font scale $configurationScale is below $minimumFontScale", configurationScale >= minimumFontScale)
                File(context.getExternalFilesDir(null), "reading-font-$phase.json").writeText(
                    org.json.JSONObject()
                        .put("phase", phase)
                        .put("minimumFontScale", minimumFontScale.toDouble())
                        .put("renderedFontScale", renderedFontScale.toDouble())
                        .put("configurationFontScale", configurationScale.toDouble())
                        .toString(),
                )
            }
            if (phase == "prepare") {
                touch(context.getString(R.string.reading_quiz_read_text))
                rule.waitForIdle()
                await {
                    val state = rule.runOnIdle { readerVm?.ui }
                    assertFalse("Captured Reading text became unavailable after Read text", state is TextReadingUi.Unavailable)
                    state is TextReadingUi.Ready
                }
                rule.waitForIdle()
                rule.onNodeWithTag("reading-sections").performScrollToIndex(8)
                screenshot(context, "reading-pilot-long-passage")
                rule.runOnIdle { reading = false }
                awaitQuizReady { vm }
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
                awaitQuizReady(closed.saved.id) { vm }
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
        val ready = awaitQuizReady { vm }
        val question = ready.quiz.plan.questions[ready.quiz.answers.size].question
        rule.onNodeWithText(question.explanation).assertDoesNotExist()
        if (correct) {
            touch(question.options.single { it.id == question.correctOptionId }.text)
            touch(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.reading_quiz_save_answer))
        } else touch(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.reading_quiz_unknown))
        await {
            rule.runOnIdle {
                (vm.ui as? ReadingQuizUi.Ready)?.quiz?.answers?.size == ready.quiz.answers.size + 1 && !vm.busy
            }
        }
        rule.waitForIdle()
    }

    private fun completeWithOneHighestTierMiss(context: Context, vm: ReadingQuizViewModel) {
        val initial = awaitQuizReady { vm }.quiz
        var correctRemaining = 3 - initial.answers.count { answer ->
            initial.plan.questions.single { it.question.id == answer.questionId }.question.let {
                it.tier == initial.plan.highestTier && answer.optionId == it.correctOptionId
            }
        }
        while (true) {
            val quiz = awaitQuizReady { vm }.quiz
            val question = quiz.plan.questions.getOrNull(quiz.answers.size)?.question ?: break
            val highest = question.tier == quiz.plan.highestTier
            answer(vm, !highest || correctRemaining-- > 0)
        }
        awaitQuizReady { vm }
        touch(context.getString(R.string.reading_quiz_submit))
        await { rule.runOnIdle { (vm.ui as? ReadingQuizUi.Ready)?.quiz?.result != null && !vm.busy } }
        assertEquals(true, awaitQuizReady { vm }.quiz.result?.passed)
    }

    private fun awaitQuizReady(expectedId: String? = null, current: () -> ReadingQuizViewModel): ReadingQuizUi.Ready {
        rule.waitForIdle()
        await {
            rule.runOnIdle {
                val vm = current()
                val state = vm.ui
                assertFalse("Captured Reading quiz became unavailable", state is ReadingQuizUi.Unavailable)
                state is ReadingQuizUi.Ready && !vm.busy && (expectedId == null || state.quiz.saved.id == expectedId)
            }
        }
        rule.waitForIdle()
        return rule.runOnIdle {
            val vm = current()
            check(!vm.busy) { "Reading quiz reloaded before the next interaction" }
            vm.ui as ReadingQuizUi.Ready
        }
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
        const val REVISION = "db72eaedf6f122b5a54ce33381d987b3fd8ba9bc"
        const val PACKAGE_HASH = "61394c7245746ebd077a6939a70f80a4d48bd282ba97ea4f84e3179f5f62f13c"
        const val ARCHIVE_HASH = "93a6a976503de723f6ab84b3fb2da6f3bfa702d42455ff54c81356a2a886fb22"
    }
}
