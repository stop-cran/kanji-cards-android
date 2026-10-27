package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.WordEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WordUnlockFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun helpSeparatesUnlockedTargetsFromCandidatesAndRetainsStartedModes() =
        fixture(HomeMode.WordReading, WordDirection.Reading) { fixture, home ->
            await { home()?.wordReadiness?.get(WordDirection.Reading) == WordReadiness(2, 0) }
            rule.onNodeWithText("Start").assertIsNotEnabled()
            rule.onNodeWithText("First practise Japanese → English.").assertIsDisplayed()
            rule.onNodeWithText("How do word modes unlock?").performClick()
            rule.onNodeWithText("For each word, the earlier mode must be in Review", substring = true)
                .performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Stability estimates how long", substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Hide word-mode help").performScrollTo().performClick()

            runBlocking { fixture.put(StudyMode.WordJpEn) }
            await { home()?.mode(HomeMode.WordReading) == ModeState.Start(0, 1) }
            rule.onNodeWithText("Unlocked words: 1 of 2 in this selection").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Start").performScrollTo().assertIsEnabled().performClick()
            assertEquals(listOf(false), fixture.starts)

            runBlocking {
                fixture.db.withTransaction {
                    fixture.put(StudyMode.WordJpEn, CardPhase.Learning, stability = 0.1, reps = 1)
                    fixture.put(StudyMode.WordReading, CardPhase.Learning, stability = 0.1, reps = 1)
                }
            }
            await { home()?.mode(HomeMode.WordReading) == ModeState.Start(1, 0) }
            rule.onNodeWithText("Unlocked words: 1 of 2 in this selection").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Start").assertIsEnabled()

            runBlocking {
                fixture.db.withTransaction {
                    fixture.db.content().clearWords(fixture.source)
                    fixture.db.content().insertWords(listOf(fixture.word("猫", "ねこ", "cat"), fixture.word("ネコ", "ねこ", "cat in kana")))
                }
            }
            await { home()?.wordReadiness?.get(WordDirection.Reading) == WordReadiness(1, 1) }
            rule.onNodeWithText("Needs at least two words with kanji").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Start").assertIsNotEnabled()
            assertEquals(0, runBlocking { fixture.db.reviews().allLog().size })
        }

    @Test fun typingUsesReverseProgressAndExtraPracticeSurvivesAnEmptyAllowance() =
        fixture(HomeMode.WordType, WordDirection.Type) { fixture, home ->
            runBlocking { fixture.put(StudyMode.WordJpEn) }
            await { home()?.mode(HomeMode.WordEn) == ModeState.Start(0, 1) }
            rule.onNodeWithText("First practise English → Japanese.").assertIsDisplayed()
            rule.onNodeWithText("Start").assertIsNotEnabled()
            runBlocking { fixture.put(StudyMode.WordEnJp) }
            await { home()?.mode(HomeMode.WordType) == ModeState.Start(0, 1) }
            rule.onNodeWithText("Unlocked words: 1 of 2 in this selection").assertIsDisplayed()
            rule.onNodeWithText("Start").assertIsEnabled()
            rule.runOnIdle { fixture.settings.setDailyNewCards(0) }
            await { home()?.pacing?.remaining == 0 && home()?.mode(HomeMode.WordType) == ModeState.PracticeMore }
            rule.onNodeWithText("First practise English → Japanese.").assertDoesNotExist()
            rule.onNodeWithText("Unlocked words: 1 of 2 in this selection").assertIsDisplayed()
            rule.onNodeWithText("Practice more").assertIsEnabled().performClick()
            assertEquals(listOf(true), fixture.starts)
            rule.onNodeWithText("How do word modes unlock?").performClick()
            rule.onNodeWithText("Hide word-mode help").assertIsDisplayed()
        }

    private fun await(condition: () -> Boolean) = rule.waitUntil(10_000, condition)

    private fun fixture(mode: HomeMode, direction: WordDirection, exercise: (Fixture, () -> HomeData?) -> Unit) {
        Fixture().use { fixture ->
            val home = mutableStateOf<HomeData?>(null)
            val visible = mutableStateOf(true)
            rule.setContent {
                if (visible.value) {
                    val data = rememberHomeData(fixture.services)
                    SideEffect { home.value = data }
                    MaterialTheme {
                        Page("Words") {
                            WordUnlockHelp()
                            WordModeRow(data, direction.label, mode, direction) { fixture.starts += it }
                        }
                    }
                }
            }
            try {
                await { home.value?.loading == false }
                exercise(fixture) { home.value }
            } finally {
                rule.runOnIdle { visible.value = false }
                rule.waitForIdle()
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferences = "word-unlock-${UUID.randomUUID()}"
        private val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = Settings(isolated).also {
            it.setSource("https://github.com/fixture/words", "main")
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(10)
            it.setWordStack(WordStacks.n5.id)
        }
        val services = Services(db, settings, context)
        val source get() = settings.source.value.id
        val starts = mutableListOf<Boolean>()

        init {
            runBlocking { db.content().insertWords(listOf(word("猫", "ねこ", "cat"), word("犬", "いぬ", "dog"))) }
        }

        // Kana-only spelling deliberately has nonempty kanji metadata; that is not reading eligibility.
        fun word(word: String, reading: String, title: String) = WordEntity(source, word, reading, title, "wago", "猫", "", "", 5)

        suspend fun put(mode: StudyMode, phase: CardPhase = CardPhase.Review, stability: Double = 2.0, reps: Int = 2) {
            val now = System.currentTimeMillis()
            db.reviews().putState(ReviewStateEntity(
                source, WordStacks.n5.id, "猫", mode.name, phase.name, stability, 5.0, now - 60_000, now - 86_400_000, reps, 0,
            ))
        }

        override fun close() {
            db.close()
            context.deleteSharedPreferences(preferences)
        }
    }
}
