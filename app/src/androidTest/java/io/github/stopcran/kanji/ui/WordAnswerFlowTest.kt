package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.WordEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WordAnswerFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

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

    private fun session(limit: Int, exercise: (WordQuizViewModel, AppDatabase) -> Unit) {
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
        val route = "wquiz:${UUID.randomUUID()}:jp"
        val vm = WordQuizViewModel(Services(db, settings, context)).also { models.put(route, it) }
        try {
            runBlocking {
                val words = listOf(
                    Triple("猫", "ねこ", "cat"), Triple("犬", "いぬ", "dog"), Triple("鳥", "とり", "bird"),
                    Triple("魚", "さかな", "fish"), Triple("馬", "うま", "horse"), Triple("牛", "うし", "cow"),
                )
                db.content().insertWords(words.map { (word, reading, title) ->
                    WordEntity(settings.source.value.id, word, reading, title, "wago", word, "", "Fixture word reference", 5)
                })
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
