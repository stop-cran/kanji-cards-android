package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.RoomHomeRepository
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.toEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CumulativeKanjiHomeUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var application: Application
    private lateinit var preferenceName: String

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        if (::application.isInitialized && ::preferenceName.isInitialized) application.deleteSharedPreferences(preferenceName)
    }

    @Test fun changingLevelsShowsSharedDueCardsAndOnlySelectedLevelIntroductions() {
        application = ApplicationProvider.getApplicationContext()
        preferenceName = "cumulative-home-${UUID.randomUUID()}"
        val context = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String, mode: Int) = application.getSharedPreferences(preferenceName, mode)
        }
        val settings = Settings(context)
        settings.setStack("jlpt-n5")
        settings.setWeekPlan("FFFFFFF")
        settings.setDailyNewCards(10)
        val source = settings.source.value.id
        val now = Instant.now()
        val review = SrsState(CardPhase.Review, 10.0, 5.0, now.minusSeconds(60), now.minusSeconds(5 * 86_400), 3)
        fun card(id: String, level: Int, on: String, kun: String, position: Int) =
            KanjiEntity(source, id, id, level, "jlpt-n$level", 4, null, null, on, kun, "", "", "{}", position)
        db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            db.content().insertKanji(listOf(
                card("休", 5, "キュウ", "やす.む", 0), card("白", 5, "ハク", "しろ", 1),
                card("体", 4, "タイ", "からだ", 2), card("自", 4, "ジ", "みずか.ら", 3),
                card("太", 3, "タイ", "ふと.い", 4),
            ))
            for (kanji in listOf("休", "太")) {
                db.reviews().putState(review.toEntity(source, Stacks.JLPT_STATE, kanji, StudyMode.Quiz))
                db.reviews().putState(review.toEntity(source, Stacks.JLPT_STATE, kanji, StudyMode.Draw))
            }
            db.reviews().putState(review.toEntity(source, Stacks.JLPT_STATE, "休", StudyMode.KanjiOn, "きゅう"))
            db.reviews().putState(review.toEntity(source, Stacks.JLPT_STATE, "太", StudyMode.KanjiOn, "たい"))
            db.reviews().putState(review.copy(due = now.plusSeconds(86_400)).toEntity(source, Stacks.JLPT_STATE, "体", StudyMode.Quiz))
            for ((kanji, legacyStack) in listOf("休" to "jlpt-n5", "体" to "jlpt-n4")) {
                for (daysAgo in listOf(4, 3)) {
                    db.reviews().putLog(ReviewLogEntity(
                        0, source, legacyStack, kanji, StudyMode.Quiz.name, 3, now.minusSeconds(daysAgo * 86_400L).toEpochMilli(),
                    ))
                }
            }
        }
        val repository = RoomHomeRepository(db, settings)
        val producer = HomeStateProducer(repository, flowOf(emptyMap()), Clock.fixed(now, ZoneId.systemDefault()), flowOf(now))
        var startedQuiz: Boolean? = null
        val actions = HomeActions(
            onSettings = {}, onCards = {}, onWordCards = {}, onKanjiPage = {}, onWordsPage = {},
            onCountersPage = {}, onGrammarPage = {}, onListeningPage = {}, onOpenDoc = {},
            onGrammarDoc = { _, _, _ -> }, onKanaPage = {}, onKanaStudy = { _, _, _ -> },
            onCounters = { _, _, _, _ -> }, onGrammar = {}, onGrammarTarget = {},
            onQuiz = { startedQuiz = it }, onDraw = {}, onReadings = {}, onWords = { _, _ -> },
        )
        rule.setContent {
            val state by producer.states.collectAsState(HomeState.loading(settings.source.value))
            MaterialTheme { KanjiPage(state.data, homePreferences(settings), {}, actions) }
        }

        val n5 = application.getString(R.string.level_n5)
        val n4 = application.getString(R.string.level_n4)
        val n5Count = "$n5 · ${application.getString(R.string.cards, 2)}"
        val n4Count = "$n4 · ${application.getString(R.string.cards, 4)}"
        rule.waitUntil(20_000) { rule.onAllNodesWithText(n5Count).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(n4).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText(n4Count).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(application.getString(R.string.level_filter_kanji_cumulative, n4)).assertIsDisplayed()
        rule.onAllNodesWithText(application.getString(R.string.study_due_new, 1, 1)).assertCountEquals(2)
        rule.onNodeWithText(application.getString(R.string.study_due_new, 1, 2)).assertIsDisplayed()
        rule.onAllNodesWithText(application.getString(R.string.start))[0].performClick()
        assertEquals(false, startedQuiz)

        rule.onNodeWithText(n5).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText(n5Count).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("jlpt-n5", settings.stack.value)
        assertEquals(3, runBlocking {
            db.reviews().states(source, Stacks.JLPT_STATE, StudyMode.Quiz.name).single { it.kanji == "休" }.reps
        })
    }
}
