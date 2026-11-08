package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.srs.Confusion
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KanjiMeaningAnswerFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun unknownAnswerFormatsEnglish() = exercise("en", unknown = true)
    @Test fun wrongAnswerFormatsEnglish() = exercise("en", unknown = false)
    @Test fun unknownAnswerFormatsRussian() = exercise("ru", unknown = true)
    @Test fun wrongAnswerFormatsRussian() = exercise("ru", unknown = false)

    private fun exercise(language: String, unknown: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "kanji-feedback-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val settings = Settings(isolated).also {
            it.setSource("https://github.com/fixture/kanji", "main")
            it.setStack("jlpt-n4")
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(0)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val models = ViewModelStore()
        val route = "quiz:${UUID.randomUUID()}"
        val services = Services(db, settings, context)
        val vm = QuizViewModel(services).also { models.put(route, it) }
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(configuration)
        val now = System.currentTimeMillis()
        val distractor = ReviewStateEntity(
            settings.source.value.id, Stacks.JLPT_STATE, "体", StudyMode.Quiz.name, "Review", 15.0, 4.0,
            now + 90L * 86_400_000, now - 86_400_000, 4, 0,
        )
        try {
            runBlocking {
                val source = settings.source.value.id
                db.content().insertKanji(listOf(
                    KanjiEntity(source, "休", "rest", 5, "jlpt-n5", null, null, null, "", "", "", "", null, 0),
                    KanjiEntity(source, "体", "body", 4, "jlpt-n4", null, null, null, "", "", "", "", null, 1),
                ))
                db.reviews().putState(ReviewStateEntity(
                    source, Stacks.JLPT_STATE, "休", StudyMode.Quiz.name, "Review", 10.0, 5.0,
                    now - 60_000, now - 86_400_000, 2, 0,
                ))
                db.reviews().putState(distractor)
            }
            rule.setContent {
                CompositionLocalProvider(
                    LocalContext provides localized,
                    LocalConfiguration provides localized.resources.configuration,
                    LocalActivityResultRegistryOwner provides rule.activity,
                ) {
                    MaterialTheme { QuizScreen(route, {}, {}, {}, vm) }
                }
            }
            rule.waitUntil(20_000) { vm.ui is QuizUi.Question }
            val question = rule.runOnIdle { vm.ui as QuizUi.Question }
            assertEquals("休", question.card.kanji)
            val choice = if (unknown) localized.getString(R.string.i_don_t_know)
                else question.options.first { it.kanji != question.card.kanji }.title
            rule.onNodeWithText(choice).performScrollTo().performClick()
            val resource = if (unknown) R.string.kanji_no_problem_answer else R.string.kanji_not_quite_answer
            rule.onNodeWithText(localized.getString(resource, "休", "rest")).performScrollTo().assertIsDisplayed()
            assertEquals(0, runBlocking { db.reviews().allLog().size })
            val confirmedAfter = System.currentTimeMillis()
            rule.onNodeWithText(localized.getString(R.string.next)).performScrollTo().performClick()
            rule.waitUntil(10_000) { runBlocking { db.reviews().allLog().size == 1 } }
            runBlocking {
                services.kanjiWrites.awaitPending()
                val confirmedBefore = System.currentTimeMillis()
                val log = db.reviews().allLog().single()
                assertEquals("休", log.kanji)
                assertEquals(1, log.grade)
                assertEquals(Stacks.JLPT_STATE, log.stack)
                val states = db.reviews().states(settings.source.value.id, Stacks.JLPT_STATE, "Quiz").associateBy { it.kanji }
                assertEquals(3, states.getValue("休").reps)
                val other = states.getValue("体")
                if (unknown) {
                    assertEquals(distractor, other)
                } else {
                    assertEquals(distractor, other.copy(dueMs = distractor.dueMs))
                    val delay = Confusion.NUDGE.toMillis()
                    assertTrue(other.dueMs in (confirmedAfter + delay)..(confirmedBefore + delay))
                }
            }
        } finally {
            try {
                rule.runOnIdle { models.clear() }
                runBlocking {
                    withTimeout(10_000) {
                        while (db.reviews().allSessions().any { it.endedAtMs == null }) delay(10)
                    }
                }
            } finally {
                db.close()
                context.deleteSharedPreferences(preferences)
            }
        }
    }
}
