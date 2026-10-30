package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.kana.KANA_SOURCE_ID
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.ui.KanaOverview
import io.github.stopcran.kanji.ui.KanaStateKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KanaPacingTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private val now = Instant.parse("2026-01-06T12:00:00Z")
    private val zone = ZoneId.of("UTC")

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        settings = Settings(context).also { it.setWeekPlan("FFFFFFF"); it.setDailyNewCards(3) }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun cleanup() { db.close() }

    @Test fun firstPracticeSpendsOneSharedSlotPerModeAndScriptButRepeatsAndOldSkillsDoNot() = runBlocking {
        val forward = KanaMode.QuizKanaToRomaji.name
        val reverse = KanaMode.QuizRomajiToKana.name
        record(log("hiragana", "あ", forward, now.minusSeconds(86_400)))
        record(log("katakana", "ア", forward, now.minusSeconds(86_400)))
        record(log("hiragana", "あ", reverse).copy(grade = Grade.Again.value))
        assertEquals(2, remaining())
        record(log("hiragana", "あ", reverse, now.plusSeconds(1)))
        assertEquals(2, remaining())
        record(log("hiragana", "あ", KanaMode.Draw.name))
        assertEquals(1, remaining())
        record(log("katakana", "ア", reverse))
        assertEquals(0, remaining())

        val tomorrow = now.plusSeconds(86_400)
        assertEquals(3, remaining(tomorrow))
        record(log("hiragana", "あ", reverse, tomorrow))
        assertEquals(3, remaining(tomorrow))
        record(log("hiragana", "あ", KanaMode.TypeKanaToRomaji.name, tomorrow))
        assertEquals(2, remaining(tomorrow))
        assertEquals(8, db.reviews().allLog().size)
    }

    @Test fun exhaustedAllowanceStopsRefillingNewKanaWithoutHidingDueReviews() = runBlocking {
        settings.setDailyNewCards(1)
        val reverse = KanaMode.QuizRomajiToKana.name
        record(log("katakana", "ア", reverse, now.minusSeconds(7 * 86_400)))
        record(log("katakana", "カ", KanaMode.QuizKanaToRomaji.name, now.minusSeconds(86_400)))
        assertEquals(ModeState.Start(1, 1), reverseMode())

        record(log("katakana", "カ", reverse))
        assertEquals(ModeState.Start(1, 0), reverseMode())
        record(log("katakana", "キ", reverse))
        assertEquals(0, remaining())
        assertEquals(ModeState.Start(1, 0), reverseMode())
    }

    @Test fun nonKanaSourcesKeepCrossModeDeduplicationAndSeparateReadingCards() = runBlocking {
        val source = "fixture/content"
        record(log("words-n5", "語", "WordJpEn", now.minusSeconds(86_400)).copy(sourceId = source))
        record(log("words-n5", "語", "WordReading").copy(sourceId = source))
        assertEquals(3, remaining(source = source))
        record(log("jlpt-n5", "日", "Quiz").copy(sourceId = source))
        record(log("jlpt-n5", "日", "Draw").copy(sourceId = source))
        assertEquals(2, remaining(source = source))
        for (mode in listOf("KanjiOn", "KanjiKun")) {
            record(log("jlpt-n5", "日", mode).copy(sourceId = source, reading = "same-reading"))
        }
        assertEquals(0, remaining(source = source))
        assertEquals(3, remaining())
    }

    @Test fun weeklyStudyDatesCombineKanaAndSelectedContentWithoutMergingDailyPools() = runBlocking {
        val source = "fixture/content"
        assertEquals(0, db.observeWeekProgress(settings, source, now, zone).first().studied)
        record(log("hiragana", "あ", KanaMode.QuizKanaToRomaji.name))
        assertEquals(1, db.observeWeekProgress(settings, source, now, zone).first().studied)
        record(log("words-n5", "語", "WordJpEn").copy(sourceId = source))
        record(log("katakana", "ア", KanaMode.QuizRomajiToKana.name))
        assertEquals(1, db.observeWeekProgress(settings, source, now, zone).first().studied)
        record(log("words-n5", "日", "WordJpEn", now.minusSeconds(86_400)).copy(sourceId = source))
        assertEquals(2, db.observeWeekProgress(settings, source, now, zone).first().studied)
        assertEquals(1, remaining())
        assertEquals(2, remaining(source = source))
        assertEquals(4, db.reviews().allLog().size)
    }

    @Test fun weeklyDatesUseLocalMondayAndExcludeUnrelatedSourcesAndAdjacentWeeks() = runBlocking {
        val source = "fixture/content"
        val tokyo = ZoneId.of("Asia/Tokyo")
        val current = Instant.parse("2026-01-07T03:00:00Z")
        for (at in listOf("2026-01-04T14:59:59Z", "2026-01-04T15:00:00Z", "2026-01-05T15:00:00Z", "2026-01-11T15:00:00Z")) {
            record(log("hiragana", "あ", KanaMode.QuizKanaToRomaji.name, Instant.parse(at)))
        }
        record(log("words-n5", "語", "WordJpEn", Instant.parse("2026-01-04T16:00:00Z")).copy(sourceId = source))
        record(log("words-n5", "日", "WordJpEn", current).copy(sourceId = "fixture/other"))
        assertEquals(2, db.observeWeekProgress(settings, source, current, tokyo).first().studied)
        assertEquals(3, db.observeWeekProgress(settings, "fixture/other", current, tokyo).first().studied)
        assertEquals(1, db.observeWeekProgress(settings, source, current.plusSeconds(7 * 86_400), tokyo).first().studied)
    }

    @Test fun homeSnapshotUsesCapturedPreferencesAndLocalDatesForAllowanceUnlockAndFocus() = runBlocking {
        settings.setSource("https://github.com/fixture/content", "main")
        settings.setStack("jlpt-n5")
        settings.setFocusEnabled(true)
        settings.setFocusSize(5)
        val source = settings.source.value.id
        val current = Instant.parse("2026-01-05T15:01:00Z")
        val tokyo = ZoneId.of("Asia/Tokyo")
        db.content().insertKanji(listOf("日", "月").mapIndexed { index, kanji ->
            KanjiEntity(source, kanji, kanji, 5, "jlpt-n5", null, null, null, "", "", "", "Large body", "{}", index)
        })
        record(log("jlpt-n5", "日", "Quiz", Instant.parse("2026-01-05T14:59:00Z")).copy(sourceId = source))
        record(log("jlpt-n5", "日", "Quiz", Instant.parse("2026-01-05T15:00:00Z")).copy(sourceId = source))
        record(log("jlpt-n5", "月", "Quiz", current).copy(sourceId = source))
        record(log("jlpt-n5", "火", "Quiz", current).copy(sourceId = "fixture/unrelated"))
        val repository = RoomHomeRepository(db, settings)
        val captured = repository.selection.first()
        settings.setDailyNewCards(9)
        settings.setWeekPlan("OOOOOOO")
        settings.setFocusSize(9)
        val snapshot = repository.observe(captured, current, tokyo).first()
        assertEquals(3, snapshot.allowance.total)
        assertEquals(2, snapshot.allowance.remaining)
        assertEquals(setOf("日"), snapshot.learned)
        assertEquals(2, snapshot.week.studied)
        assertEquals(5, snapshot.week.goal)
        assertEquals(5, snapshot.focus?.size)
        assertEquals(current.toEpochMilli(), settings.focusSince(source, "jlpt-n5", 0))
        assertEquals(listOf("", ""), snapshot.inventory.kanji.map { it.body })
        assertEquals(3, repository.observe(captured, current.plusSeconds(86_400), tokyo).first().allowance.remaining)
    }

    private fun log(stack: String, kana: String, mode: String, at: Instant = now) =
        ReviewLogEntity(0, KANA_SOURCE_ID, stack, kana, mode, Grade.Good.value, at.toEpochMilli())

    private suspend fun record(log: ReviewLogEntity) {
        db.reviews().record(
            ReviewStateEntity(log.sourceId, log.stack, log.kanji, log.mode,
                if (log.grade == Grade.Again.value) "Learning" else "Review", 10.0, 5.0,
                log.atMs + 7 * 86_400_000L, log.atMs, 1, 0, reading = log.reading),
            log,
        )
    }

    private suspend fun remaining(at: Instant = now, source: String = KANA_SOURCE_ID) =
        db.newAllowance(settings, source, at, zone).remaining

    private suspend fun reverseMode(): ModeState {
        val script = KanaScript.Katakana
        val mode = KanaMode.QuizRomajiToKana
        val states = mapOf(KanaStateKey(script, mode) to db.reviews().states(KANA_SOURCE_ID, script.id, mode.name))
        return KanaOverview(states, db.newAllowance(settings, KANA_SOURCE_ID, now, zone), now).modeState(script, mode)
    }
}
