package io.github.stopcran.kanji.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.core.srs.KanjiTransitionChecks
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CumulativeJlptDataTest {
    private val source = "owner/content"
    private val other = "other/content"
    private val now = Instant.parse("2026-01-05T10:00:00Z").toEpochMilli()
    private val day = KanjiTransitionChecks.DAY_MS
    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var preferences: String

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        preferences = "cumulative-jlpt-${UUID.randomUUID()}"
        context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(preferences, mode)
        }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            for (repo in listOf(source, other)) {
                db.content().insertKanji(listOf(
                    card(repo, "休", 5, "キュウ", "やす.む", 0), card(repo, "白", 5, "ハク", "しろ", 1),
                    card(repo, "上", 5, "ジョウ", "うえ", 2), card(repo, "体", 4, "タイ", "からだ", 3),
                    card(repo, "自", 4, "ジ", "みずか.ら", 4), card(repo, "止", 4, "シ", "と.まる", 5),
                    card(repo, "太", 3, "タイ", "ふと.い", 6),
                ))
            }
        }
    }

    @After fun tearDown() {
        db.close()
        RuntimeEnvironment.getApplication().deleteSharedPreferences(preferences)
    }

    @Suppress("LongParameterList")
    private fun card(repo: String, id: String, level: Int, on: String, kun: String, position: Int) =
        KanjiEntity(repo, id, id, level, "jlpt-n$level", 4, null, null, on, kun, "", "", "{}", position)

    private fun state(kanji: String, mode: StudyMode = StudyMode.Quiz, reading: String = "", repo: String = source, due: Long = now + 90 * day) =
        ReviewStateEntity(repo, Stacks.JLPT_STATE, kanji, mode.name, "Review", 30.0, 4.0, due, now - day, 8, 1, reading)

    private suspend fun introduce(kanji: String, mode: StudyMode = StudyMode.Quiz, reading: String = "", repo: String = source) {
        val row = ReviewStateEntity(repo, Stacks.JLPT_STATE, kanji, mode.name, "Learning", 1.0, 5.0, now + 600_000, now, 1, 0, reading)
        db.recordKanjiReview(row, ReviewLogEntity(0, repo, Stacks.JLPT_STATE, kanji, mode.name, 3, now, reading))
    }

    @Test fun rangeAndIntroductionFiltersDoNotAffectOtherStacks() = runBlocking {
        val all = db.content().kanji(source)
        assertEquals(setOf("休", "白", "上", "体", "自", "止"), all.inStack("jlpt-n4").map { it.kanji }.toSet())
        assertEquals(setOf("体", "自", "止"), all.inStack("jlpt-n4").newKanjiInStack("jlpt-n4"))
        assertEquals(setOf("休", "白", "上"), all.inStack("jlpt-n5").map { it.kanji }.toSet())
        assertEquals(all, all.inStack("all"))
        assertEquals(null, all.newKanjiInStack("all"))
    }

    @Test fun introductionSchedulesOnlyACuratedStartedLowerCardAndNeverChangesItsScore() = runBlocking {
        val target = state("休")
        val unrelated = state("白")
        val otherSource = state("休", repo = other)
        db.reviews().putState(target)
        db.reviews().putState(unrelated)
        db.reviews().putState(otherSource)
        introduce("体")
        val check = db.reviews().allTransitionChecks().single()
        assertEquals("体", check.triggerKanji)
        assertEquals("休", check.kanji)
        assertEquals(now + 2 * day, check.dueMs)
        val stored = db.reviews().states(source, Stacks.JLPT_STATE, StudyMode.Quiz.name).associateBy { it.kanji }
        assertEquals(target.copy(dueMs = check.dueMs), stored.getValue("休"))
        assertEquals(unrelated, stored.getValue("白"))
        assertEquals(otherSource, db.reviews().states(other, Stacks.JLPT_STATE, StudyMode.Quiz.name).single())
        assertEquals(listOf("体"), db.reviews().allLog().map { it.kanji })

        introduce("体")
        assertEquals(1, db.reviews().allTransitionChecks().size)
        val reviewed = target.copy(stability = 12.0, dueMs = now + 30 * day, lastReviewMs = now + day, reps = 9)
        db.recordKanjiReview(reviewed, ReviewLogEntity(0, source, Stacks.JLPT_STATE, "休", "Quiz", 1, now + day))
        assertEquals(now + day, db.reviews().allTransitionChecks().single().completedAtMs)
        val after = db.reviews().states(source, Stacks.JLPT_STATE, "Quiz").single { it.kanji == "休" }
        assertEquals(9, after.reps)
        assertEquals(now + day, after.lastReviewMs)
        assertEquals("Learning", after.phase)
    }

    @Test fun existingEarlierDueAndUnstartedCardsDoNotGetDuplicateChecks() = runBlocking {
        val soon = state("休", due = now + day)
        db.reviews().putState(soon)
        introduce("体")
        introduce("自")
        assertTrue(db.reviews().allTransitionChecks().isEmpty())
        assertEquals(soon, db.reviews().states(source, Stacks.JLPT_STATE, "Quiz").single { it.kanji == "休" })
    }

    @Test fun concurrentAndRelatedIntroductionsDoNotDuplicatePendingChecks() = runBlocking {
        db.content().insertKanji(listOf(
            card(source, "本", 5, "ホン", "もと", 7), card(source, "末", 3, "マツ", "すえ", 8),
            card(source, "未", 3, "ミ", "いま.だ", 9),
        ))
        db.reviews().putState(state("休"))
        db.reviews().putState(state("本"))
        coroutineScope { repeat(4) { launch(Dispatchers.IO) { introduce("体") } } }
        introduce("末")
        introduce("未")
        val checks = db.reviews().allTransitionChecks()
        assertEquals(1, checks.count { it.kanji == "休" && it.mode == StudyMode.Quiz.name })
        assertEquals(1, checks.count { it.kanji == "本" && it.mode == StudyMode.Quiz.name })
        assertEquals(2, checks.size)
    }

    @Test fun checksAreBoundedAcrossModesAndEarlyCompletionKeepsTheDayReservation() = runBlocking {
        val rest = state("休")
        listOf(rest, state("白"), state("上"), state("休", StudyMode.Draw)).forEach { db.reviews().putState(it) }
        introduce("体")
        introduce("自")
        db.recordKanjiReview(rest.copy(lastReviewMs = now + 1000, reps = 9),
            ReviewLogEntity(0, source, Stacks.JLPT_STATE, "休", "Quiz", 3, now + 1000))
        introduce("止")
        introduce("体", StudyMode.Draw)
        val checks = db.reviews().allTransitionChecks()
        assertEquals(listOf(now + 2 * day, now + 2 * day, now + 3 * day, now + 3 * day), checks.map { it.dueMs })
        assertEquals(now + 1000, checks.first().completedAtMs)
        assertTrue(checks.drop(1).all { it.completedAtMs == null })
    }

    @Test fun readingsKeepTheirOwnIdentityAndOnlyTheFirstReadingOfAKindTriggers() = runBlocking {
        val target = state("休", StudyMode.KanjiOn, "きゅう")
        db.reviews().putState(target)
        introduce("体", StudyMode.KanjiOn, "たい")
        assertEquals("きゅう", db.reviews().allTransitionChecks().single().reading)
        db.recordKanjiReview(target.copy(reps = 9, lastReviewMs = now + 1000),
            ReviewLogEntity(0, source, Stacks.JLPT_STATE, "休", "KanjiOn", 3, now + 1000, "きゅう"))
        introduce("体", StudyMode.KanjiOn, "てい")
        introduce("体", StudyMode.KanjiKun, "からだ")
        assertEquals(1, db.reviews().allTransitionChecks().size)
        assertEquals(2, db.reviews().states(source, Stacks.JLPT_STATE, "KanjiOn").count { it.kanji == "体" })
    }

    @Test fun readingIntroductionsUseTheFirstRecordedPriorStateIncludingSameTimestampTies() = runBlocking {
        db.reviews().putLog(ReviewLogEntity(0, source, "jlpt-n5", "休", "KanjiOn", 3, now - day))
        db.reviews().putState(state("休", StudyMode.KanjiOn, "きゅう"))
        introduce("休", StudyMode.KanjiOn, "きゅう")
        assertEquals(0, db.reviews().itemsIntroducedSince(source, now))
        assertEquals(0, db.reviews().readingCardsIntroducedSince(source, Stacks.JLPT_STATE, now))
        db.content().insertKanji(listOf(card(source, "休", 5, "キュウ\u001Fヤスミ", "やす.む", 0)))
        introduce("休", StudyMode.KanjiOn, "やすみ")
        introduce("休", StudyMode.KanjiOn, "やすみ")
        assertEquals(listOf(null, true, false, true), db.reviews().allLog().map { it.previousReadingStarted })
        assertEquals(1, db.reviews().itemsIntroducedSince(source, now))
        assertEquals(1, db.reviews().readingCardsIntroducedSince(source, Stacks.JLPT_STATE, now))
    }

    @Test fun historicalReadingLogsWithoutProvenanceKeepTheirPriorCountingBehavior() = runBlocking {
        db.reviews().putLog(ReviewLogEntity(0, source, "jlpt-n5", "休", "KanjiOn", 3, now - day))
        db.reviews().putLog(ReviewLogEntity(0, source, "jlpt-n5", "休", "KanjiOn", 3, now, "きゅう"))
        db.reviews().putState(state("休", StudyMode.KanjiOn, "きゅう"))
        introduce("休", StudyMode.KanjiOn, "きゅう")
        assertEquals(listOf(null, null, true), db.reviews().allLog().map { it.previousReadingStarted })
        assertEquals(1, db.reviews().itemsIntroducedSince(source, now))
        assertEquals(1, db.reviews().readingCardsIntroducedSince(source, Stacks.JLPT_STATE, now))
    }

    @Test fun unavailableDrawingAndRemovedOrUnanswerableReadingsAreNotScheduled() = runBlocking {
        val drawing = state("休", StudyMode.Draw)
        val reading = state("休", StudyMode.KanjiOn, "きゅう")
        db.reviews().putState(drawing)
        db.reviews().putState(reading)
        db.content().insertKanji(listOf(card(source, "休", 5, "キュウ", "やす.む", 0).copy(strokesJson = null, quizOnyomi = "")))
        introduce("体", StudyMode.Draw)
        introduce("体", StudyMode.KanjiOn, "たい")
        assertTrue(db.reviews().allTransitionChecks().isEmpty())
        assertEquals(drawing, db.reviews().states(source, Stacks.JLPT_STATE, "Draw").single { it.kanji == "休" })
        assertEquals(reading, db.reviews().states(source, Stacks.JLPT_STATE, "KanjiOn").single { it.kanji == "休" })

        db.content().insertKanji(listOf(
            card(source, "白", 5, "タイ\u001Fキュウ", "しろ", 1).copy(quizOnyomi = "タイ"),
            card(source, "上", 5, "タイ", "うえ", 2),
            card(source, "休", 5, "キュウ\u001Fタイ", "やす.む", 0).copy(quizOnyomi = "キュウ"),
            card(source, "体", 4, "タイ", "からだ", 3),
            card(source, "自", 4, "タイ", "みずか.ら", 4),
            card(source, "止", 4, "タイ", "と.まる", 5),
        ))
        db.reviews().putState(state("白", StudyMode.KanjiOn, "たい"))
        introduce("自", StudyMode.KanjiOn, "たい")
        assertTrue(db.reviews().allTransitionChecks().isEmpty())
    }

    @Test fun cancelledReservationsRestoreOnlyTheirOwnDueDateAndReleaseTheirSlot() = runBlocking {
        val rest = state("休")
        val white = state("白")
        val up = state("上")
        listOf(rest, white, up).forEach { db.reviews().putState(it) }
        introduce("体")
        introduce("自")
        val before = db.reviews().allTransitionChecks()
        assertEquals(listOf(now + 2 * day, now + 2 * day), before.map { it.dueMs })
        val changedWhiteDue = now + 4 * day
        db.reviews().putState(white.copy(dueMs = changedWhiteDue))
        db.content().insertKanji(listOf(
            card(source, "休", 5, "キュウ", "やす.む", 0).copy(tags = ""),
            card(source, "白", 5, "ハク", "しろ", 1).copy(tags = ""),
        ))
        db.withTransaction { db.cancelUnavailableTransitionChecks(source, now + 1000) }
        val after = db.reviews().allTransitionChecks()
        assertTrue(after.all { it.cancelledAtMs == now + 1000 })
        val stored = db.reviews().states(source, Stacks.JLPT_STATE, "Quiz").associateBy { it.kanji }
        assertEquals(rest.dueMs, stored.getValue("休").dueMs)
        assertEquals(changedWhiteDue, stored.getValue("白").dueMs)
        introduce("止")
        assertEquals(now + 2 * day, db.reviews().allTransitionChecks().single { it.kanji == "上" }.dueMs)
    }

    @Test fun focusMigratesLegacyCutoffsWithoutBlockingOnIneligibleLowerLevelSkills() = runBlocking {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putLong("focusSince:$source|jlpt-n5", now - 2 * day)
            .putLong("focusSince:$source|jlpt-n4", now - day).apply()
        val settings = Settings(context)
        assertEquals(now - 2 * day, settings.focusSince(source, "jlpt-n4", now))
        assertEquals(now - 2 * day, settings.focusSince(source, "jlpt-n5", now))
        db.reviews().putState(state("休"))
        db.reviews().putLog(ReviewLogEntity(0, source, "jlpt-n5", "休", "Quiz", 3, now - day))
        val cards = db.content().kanji(source).inStack("jlpt-n4")
        assertEquals(0, db.focusStatus(source, "jlpt-n4", cards, 5, now - 2 * day).occupancy)
        db.reviews().putState(state("休", StudyMode.Draw).copy(phase = "Learning"))
        assertEquals(1, db.focusStatus(source, "jlpt-n4", cards, 5, now - 2 * day).occupancy)
        assertFalse(db.padCandidates(source, "jlpt-n4", StudyMode.Quiz, cards, emptySet()).isNotEmpty())
    }

    @Test fun transitionChecksSurviveReopenAndAreIncludedInProgressExport() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val name = "cumulative-reopen-${UUID.randomUUID()}.db"
        var persisted = Room.databaseBuilder(app, AppDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            persisted.content().insertKanji(listOf(
                card(source, "休", 5, "キュウ", "やす.む", 0), card(source, "体", 4, "タイ", "からだ", 1),
            ))
            persisted.reviews().putState(state("休"))
            val introduced = ReviewStateEntity(source, Stacks.JLPT_STATE, "体", "Quiz", "Learning", 1.0, 5.0, now + 600_000, now, 1, 0)
            persisted.recordKanjiReview(introduced, ReviewLogEntity(0, source, Stacks.JLPT_STATE, "体", "Quiz", 3, now))
            val expected = persisted.reviews().allTransitionChecks().single()
            persisted.close()
            persisted = Room.databaseBuilder(app, AppDatabase::class.java, name).allowMainThreadQueries().build()
            assertEquals(expected, persisted.reviews().allTransitionChecks().single())
            assertEquals(expected.dueMs, persisted.reviews().states(source, Stacks.JLPT_STATE, "Quiz").single { it.kanji == "休" }.dueMs)
            val reading = state("休", StudyMode.KanjiOn, "きゅう")
            repeat(2) {
                persisted.recordKanjiReview(reading, ReviewLogEntity(0, source, Stacks.JLPT_STATE, "休", "KanjiOn", 3, now + 1000, "きゅう"))
            }

            val output = ByteArrayOutputStream()
            assertEquals(3, ProgressExport.write(persisted, Settings(context), output))
            val entries = mutableMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            val json = Json
            assertEquals("7", json.parseToJsonElement(entries.getValue("export_manifest.json")).jsonObject.getValue("formatVersion").jsonPrimitive.content)
            val exported = json.parseToJsonElement(entries.getValue("jlpt_transition_checks.json")).jsonArray.single().jsonObject
            assertEquals(source, exported.getValue("sourceId").jsonPrimitive.content)
            assertEquals("体", exported.getValue("triggerKanji").jsonPrimitive.content)
            assertEquals("休", exported.getValue("kanji").jsonPrimitive.content)
            assertEquals(expected.dueMs.toString(), exported.getValue("dueMs").jsonPrimitive.content)
            assertEquals(expected.originalDueMs.toString(), exported.getValue("originalDueMs").jsonPrimitive.content)
            assertEquals(JsonNull, exported.getValue("completedAtMs"))
            assertEquals(JsonNull, exported.getValue("cancelledAtMs"))
            val logs = json.parseToJsonElement(entries.getValue("review_log.json")).jsonArray
            assertEquals(JsonNull, logs[0].jsonObject.getValue("previousReadingStarted"))
            assertEquals("false", logs[1].jsonObject.getValue("previousReadingStarted").jsonPrimitive.content)
            assertEquals("true", logs[2].jsonObject.getValue("previousReadingStarted").jsonPrimitive.content)
        } finally {
            persisted.close()
            app.deleteDatabase(name)
        }
    }
}
