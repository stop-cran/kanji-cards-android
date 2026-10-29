package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.WordEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WordAnswerFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun edgeToEdge() {
        rule.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun failedAnswerRepeatsLaterAndSuccessfulConfirmationIsNotAnotherReview() = session(6) { vm, db ->
        val seen = mutableListOf<String>()
        listOf(6, 6, 5, 4, 3, 3, 2, 1).forEachIndexed { index, count ->
            val question = question(vm)
            seen += question.word.word
            assertEquals(count, question.remaining)
            rule.onNodeWithText("Attempts left: $count").performScrollTo().assertIsDisplayed()
            if (index == 1) assertTrue(seen[0] != seen[1])
            if (index == 4 || index == 7) assertEquals(seen[0], question.word.word)
            rule.onNodeWithText(if (index == 0) "I don't know" else question.word.title)
                .performScrollTo().performClick()
            if (index == 0) {
                rule.onNodeWithText("Read the article").performScrollTo().performClick()
                rule.onNodeWithText("Fixture word reference").performScrollTo().assertIsDisplayed()
            }
            assertEquals(index, recorded(db))
            rule.onNodeWithText("Next").performScrollTo().performClick()
            rule.waitUntil(10_000) {
                vm.ui !is WordQuizUi.Answer && recorded(db) == minOf(index + 1, 7)
            }
        }
        rule.onNodeWithText("Session complete").assertIsDisplayed()
        finished(db)
        assertEquals(6, seen.distinct().size)
        assertEquals(7, recorded(db))
    }

    @Test fun ordinaryWordGuessDoesNotPromiseOrInsertARetry() = session(2) { vm, db ->
        val first = question(vm)
        rule.onNodeWithText("Extra practice: saved answers update review history and scheduling.").assertDoesNotExist()
        rule.onNodeWithText(first.word.title).performScrollTo().performClick()
        assertEquals(0, recorded(db))
        rule.onNodeWithText("I guessed").performScrollTo().performClick()
        rule.waitUntil(10_000) { vm.ui is WordQuizUi.Question && recorded(db) == 1 }
        val last = question(vm)
        assertTrue(first.word.word != last.word.word)
        assertEquals(1, last.remaining)
        rule.onNodeWithText("Attempts left: 1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(last.word.title).performScrollTo().performClick()
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.onNodeWithText("Session complete").assertIsDisplayed()
        finished(db)
        assertEquals(listOf(2, 3), runBlocking { db.reviews().allLog().sortedBy { it.id }.map { it.grade } })
    }

    @Test fun extraPracticeRetainsContextWithoutSavingOnFeedback() = session(2, extra = true) { vm, db ->
        question(vm)
        val notice = rule.onNodeWithText("Extra practice: saved answers update review history and scheduling.")
        notice.assertIsDisplayed()
        rule.onNodeWithText("I don't know").performScrollTo().performClick()
        assertTrue(vm.ui is WordQuizUi.Answer)
        notice.performScrollTo().assertIsDisplayed()
        assertEquals(0, recorded(db))
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.waitUntil(10_000) { recorded(db) == 1 }
        assertTrue(runBlocking { db.reviews().allSessions().single().extra })
    }

    @Test fun typingDisclosesPartialRecallWithoutRequiringKanjiForKanaWords() = session(2, typing = true) { vm, db ->
        val grades = mutableListOf<Int>()
        repeat(2) { index ->
            rule.waitUntil(10_000) { vm.ui is WordQuizUi.TypeQuestion }
            val question = rule.runOnIdle { vm.ui as WordQuizUi.TypeQuestion }
            val partial = question.word.word != question.word.reading
            rule.onNodeWithText("Type the word's Japanese spelling.").performScrollTo().assertIsDisplayed()
            val guidance = rule.onNodeWithText("Reading alone counts as partial recall.")
            if (partial) guidance.performScrollTo().assertIsDisplayed() else guidance.assertDoesNotExist()
            rule.onNodeWithText(question.word.word).assertDoesNotExist()
            rule.onNode(hasSetTextAction()).performScrollTo().performTextInput(question.word.reading)
            rule.onNodeWithText("Check").performScrollTo().performClick()
            rule.onNodeWithText(question.word.word).assertIsDisplayed()
            if (partial) {
                rule.onNodeWithText("Reading recalled — spelling needs practice").performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("Review the spelling above, then choose Next.").performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("Correct!").assertDoesNotExist()
            } else {
                rule.onNodeWithText("Correct!").performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("Review the spelling above, then choose Next.").assertDoesNotExist()
            }
            rule.onNode(hasSetTextAction()).assertDoesNotExist()
            assertEquals(index, recorded(db))
            grades += if (partial) 2 else 3
            rule.onNodeWithText("Next").performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.ui !is WordQuizUi.TypeAnswer && recorded(db) == index + 1 }
        }
        assertEquals(setOf(2, 3), grades.toSet())
        assertEquals(grades, runBlocking { db.reviews().allLog().sortedBy { it.id }.map { it.grade } })
    }

    @Test fun typingKeepsBothActionsReachableAboveKeyboardForLongCue() = session(2, typing = true, longCue = true) { vm, db ->
        rule.waitUntil(10_000) { vm.ui is WordQuizUi.TypeQuestion }
        val imeAnimations = AtomicInteger()
        rule.runOnIdle {
            ViewCompat.setWindowInsetsAnimationCallback(rule.activity.window.decorView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) imeAnimations.incrementAndGet()
                    }
                    override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>) = insets
                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) imeAnimations.decrementAndGet()
                    }
                })
        }
        rule.onNode(hasSetTextAction()).performScrollTo().performClick()
        try {
            // IME visibility precedes animation completion, which Compose idleness does not await.
            rule.waitUntil(10_000) {
                ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
                    imeAnimations.get() == 0
            }
        } catch (error: Throwable) {
            captureTypingKeyboard()
            throw error
        }
        val check = rule.onNodeWithText("Check").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        val unsure = rule.onNodeWithText("I don't know").assertIsDisplayed().assertIsEnabled()
        captureTypingKeyboard()
        val keyboardTop = rule.runOnIdle {
            val decor = rule.activity.window.decorView
            decor.height - requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(WindowInsetsCompat.Type.ime()).bottom
        }
        val checkBounds = check.fetchSemanticsNode().boundsInWindow
        val unsureBounds = unsure.fetchSemanticsNode().boundsInWindow
        assertEquals(checkBounds.center.y, unsureBounds.center.y, 1f)
        assertTrue("Check is covered by the keyboard", checkBounds.bottom <= keyboardTop)
        assertTrue("Uncertainty is covered by the keyboard", unsureBounds.bottom <= keyboardTop)
        unsure.performClick()
        rule.onNodeWithText("No problem — here it is").performScrollTo().assertIsDisplayed()
        assertTrue((vm.ui as WordQuizUi.TypeAnswer).gaveUp)
        assertEquals(0, recorded(db))
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.waitUntil(10_000) { recorded(db) == 1 }
        assertEquals(1, runBlocking { db.reviews().allLog().single().grade })
    }

    private fun captureTypingKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), "word-typing-keyboard.png")
                .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
    }

    private fun question(vm: WordQuizViewModel): WordQuizUi.Question {
        rule.waitUntil(10_000) { vm.ui is WordQuizUi.Question }
        return rule.runOnIdle { vm.ui as WordQuizUi.Question }
    }

    private fun recorded(db: AppDatabase) = runBlocking { db.reviews().allLog().size }

    private fun finished(db: AppDatabase) {
        rule.waitUntil(10_000) {
            runBlocking { db.reviews().allSessions().singleOrNull()?.endedAtMs != null }
        }
    }

    private fun session(
        limit: Int, typing: Boolean = false, longCue: Boolean = false, extra: Boolean = false,
        exercise: (WordQuizViewModel, AppDatabase) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "word-answer-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = Settings(isolated).also {
            it.setSource("https://github.com/fixture/words", "main")
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(limit)
            it.setWordStack(WordStacks.n5.id)
        }
        val models = ViewModelStore()
        val route = "wquiz:${UUID.randomUUID()}:${if (typing) "ty" else "jp"}" + if (extra) ":extra" else ""
        val vm = WordQuizViewModel(Services(db, settings, context)).also { models.put(route, it) }
        try {
            runBlocking {
                val words = if (typing) listOf(
                    Triple("猫", "ねこ", "cat"), Triple("すぐ", "すぐ", "immediately"),
                ) else listOf(
                    Triple("猫", "ねこ", "cat"), Triple("犬", "いぬ", "dog"), Triple("鳥", "とり", "bird"),
                    Triple("魚", "さかな", "fish"), Triple("馬", "うま", "horse"), Triple("牛", "うし", "cow"),
                )
                db.content().insertWords(words.map { (word, reading, title) ->
                    val cue = if (longCue) "$title, described by a longer English cue that wraps across several lines while the answer keyboard is open" else title
                    WordEntity(settings.source.value.id, word, reading, cue, "wago", word, "", "Fixture word reference", 5)
                })
                if (typing) {
                    val now = System.currentTimeMillis()
                    for ((word) in words) db.reviews().putState(ReviewStateEntity(
                        settings.source.value.id, WordStacks.n5.id, word, StudyMode.WordEnJp.name, CardPhase.Review.name,
                        2.0, 5.0, now - 60_000, now - 86_400_000, 2, 0,
                    ))
                }
            }
            rule.setContent { MaterialTheme { WordQuizScreen(route, {}, {}, {}, vm) } }
            exercise(vm, db)
        } finally {
            rule.runOnIdle { models.clear() }
            runBlocking {
                withTimeout(10_000) {
                    while (db.reviews().allSessions().any { it.endedAtMs == null }) delay(10)
                }
            }
            db.close()
            context.deleteSharedPreferences(preferences)
        }
    }
}
