package io.github.stopcran.kanji.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import io.github.stopcran.kanji.core.approvedListening
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.Grade
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ListeningStudyTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private lateinit var study: ListeningStudy
    private var now = Instant.parse("2026-10-22T12:00:00Z")
    private val source = "owner/repo"
    private val zone = ZoneId.of("UTC")
    private val fixture = approvedListening()

    @Before fun setup() = runBlocking {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context).also { it.setSource("https://github.com/$source", "main"); it.setWeekPlan("FFFFFFF") }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        study = ListeningStudy(db, settings, { now }, { zone })
        install(fixture)
    }

    @After fun cleanup() { db.close() }

    private suspend fun install(record: ListeningRecord) {
        val audio = requireNotNull(record.passage.audio)
        val bytes = requireNotNull(javaClass.getResourceAsStream("/listening-test-tone.ogg")).use { it.readBytes() }
        db.content().insertListening(listOf(ListeningEntity(source, record.passage.id, record.json, record.contentHash,
            audio.sha256, audio.durationMs, record.ready, record.reason, 0)))
        db.content().insertListeningAudio(listOf(ListeningAudioEntity(source, audio.sha256, bytes)))
    }

    private suspend fun heard(id: String) {
        study.playbackStarted(id, restart = true, slower = false)
        study.playbackCompleted(id)
    }

    private suspend fun answers(id: String) {
        fixture.passage.questions.forEach { study.answer(id, it.id, it.answer) }
    }

    private suspend fun rejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected explicit rejection")
        } catch (_: IllegalArgumentException) {
            // The service reports invalid operations instead of recording a failure grade.
        }
    }

    @Test fun concurrentSubmissionIsOneSourceBoundReviewAndImmutableExportedResponse() = runBlocking {
        settings.setDailyNewCards(1)
        val id = study.start(source, "example")
        assertEquals(0, db.newAllowance(settings, source, now, zone).remaining)
        heard(id)
        answers(id)
        settings.setSource("https://github.com/other/repo", "main")
        List(3) { async { study.submit(id) } }.awaitAll()
        val row = requireNotNull(db.listening().encounter(id))
        assertEquals(Grade.Good.value, study.response(row)!!.grade)
        assertTrue(row.firstEncounter && row.introduced)
        assertEquals(source, db.reviews().allLog().single().sourceId)
        assertEquals(row.reviewLogId, db.reviews().allLog().single().id)
        assertEquals(1, db.reviews().allStates().single().reps)
        now = now.plusSeconds(60)
        study.playbackStarted(id, restart = true, slower = true)
        study.position(id, 1_000)
        val afterReplay = requireNotNull(db.listening().encounter(id))
        assertEquals(row.responseJson, afterReplay.responseJson)
        assertTrue(study.response(afterReplay)!!.assistance.isEmpty())
        study.close(id)
        study.submit(id)
        assertEquals(1, db.reviews().allLog().size)
        val output = ByteArrayOutputStream()
        assertEquals(1, ProgressExport.write(db, settings, output))
        val entries = ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().decodeToString()) } }
        }
        val exported = Json.parseToJsonElement(entries.getValue("listening_encounters.json")).jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement(row.responseJson!!), exported.getValue("response"))
        assertEquals(source, exported.getValue("sourceId").jsonPrimitive.content)
        assertFalse(exported.containsKey("imageBytes"))
    }

    @Test fun studyAndDiscardPreserveExposureButNeverInventReviewsOrFirstEncounters() = runBlocking {
        val studyId = study.start(source, "example", study = true)
        assertFalse(db.listening().hasExposure(source, "example"))
        study.revealStudy(studyId)
        study.close(studyId)
        assertTrue(db.reviews().allLog().isEmpty())
        assertEquals(0, db.listening().reservedNew(source))
        val cooling = study.overview(source)
        assertTrue(cooling.fresh.isEmpty())
        assertTrue(cooling.blocked.isEmpty())
        assertEquals(mapOf("example" to now.plusSeconds(86_400)), cooling.cooldowns)
        assertNull(cooling.minimumNewMs)
        rejected { study.start(source, "example") }
        now = now.plusSeconds(86_400)
        val id = study.start(source, "example")
        heard(id)
        assertFalse(db.listening().encounter(id)!!.firstEncounter)
        study.close(id, discard = true)
        rejected { study.submit(id, giveUp = true) }
        assertTrue(db.reviews().allStates().isEmpty())
        now = now.plusSeconds(86_399)
        assertTrue(study.overview(source).fresh.isEmpty())
        now = now.plusSeconds(1)
        assertEquals(listOf("example"), study.overview(source).fresh)
        assertTrue(study.overview(source).cooldowns.isEmpty())
    }

    @Test fun extraScoredReviewWaitsForCooldownThenReviewsEarlyWithoutAnotherIntroduction() = runBlocking {
        val first = study.start(source, "example")
        heard(first)
        answers(first)
        study.submit(first)
        study.close(first)
        val previous = db.reviews().allStates().single()
        db.reviews().putState(previous.copy(dueMs = now.plusSeconds(7 * 86_400).toEpochMilli()))
        assertTrue(study.overview(source).extra.isEmpty())
        rejected { study.start(source, "example", extra = true) }
        now = now.plusMillis(ListeningPolicy.COOLDOWN_MS - 1)
        assertTrue(study.overview(source).extra.isEmpty())
        rejected { study.start(source, "example", extra = true) }
        now = now.plusMillis(1)
        val home = study.overview(source)
        assertTrue(home.due.isEmpty() && home.fresh.isEmpty())
        assertEquals(listOf("example"), home.extra)
        rejected { study.start(source, "example") }
        val id = study.start(source, "example", extra = true)
        assertFalse(db.listening().encounter(id)!!.newReservation)
        assertTrue(db.reviews().allSessions().single { it.id == id }.extra)
        heard(id)
        answers(id)
        val result = study.submit(id)
        assertEquals("graded", result.kind)
        assertFalse(result.firstEncounter || result.introduced)
        assertEquals(Grade.Good.value, study.response(result)!!.grade)
        assertEquals(previous.reps + 1, db.reviews().allStates().single().reps)
        assertEquals(2, db.reviews().allLog().size)
        val today = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(ListeningIntroductions(0, 0), db.listening().introductions(source, today))
        study.close(id)
        assertTrue(study.overview(source).extra.isEmpty())
    }

    @Test fun extraIntroductionsExceedDailyPlansButStillReserveAndDebitActualScoredWork() = runBlocking {
        settings.setDailyNewCards(0)
        settings.setWeekPlan("OOOOOOO")
        install(approvedListening("second"))
        install(approvedListening("third"))
        val home = study.overview(source)
        assertEquals(ListeningAllowance(0, 0), home.allowance)
        assertEquals(0, home.sharedAllowance.remaining)
        assertTrue(home.due.isEmpty() && home.fresh.isEmpty())
        assertEquals(home.extra, study.overview(source).extra)
        rejected { study.start(source, "example") }
        rejected { study.start(source, "example", study = true, extra = true) }
        for (passage in listOf("example", "second", "third")) {
            val id = study.start(source, passage, extra = true)
            assertEquals(1, db.listening().reservedNew(source))
            assertTrue(db.listening().encounter(id)!!.newReservation)
            assertTrue(db.reviews().allSessions().single { it.id == id }.extra)
            rejected { study.revealStudy(id) }
            rejected { study.submit(id) }
            heard(id)
            answers(id)
            val result = study.submit(id)
            assertTrue(result.introduced && result.firstEncounter)
            assertEquals("graded", result.kind)
            assertEquals(Grade.Good.value, study.response(result)!!.grade)
            assertEquals(0, db.listening().reservedNew(source))
            assertEquals(result, study.submit(id))
            study.close(id)
        }
        val today = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(ListeningIntroductions(3, 390_000), db.listening().introductions(source, today))
        assertEquals(3, db.reviews().allLog().size)
        assertEquals(3, db.reviews().allStates().size)
        assertEquals(0, db.newAllowance(settings, source, now, zone).remaining)
        assertTrue(study.overview(source).extra.isEmpty())
    }

    @Test fun extraCandidatesKeepSharedQueueOrderAndExcludeCoolingAndBlockedPassages() = runBlocking {
        for (id in listOf("overdue", "upcoming", "cooling", "blocked")) install(approvedListening(id))
        for ((id, days) in listOf("overdue" to -1L, "upcoming" to 3L)) {
            db.reviews().putState(ReviewStateEntity(source, LISTENING_STACK, id, LISTENING_MODE, "Review", 5.0, 5.0,
                now.plusSeconds(days * 86_400).toEpochMilli(), now.minusSeconds(2 * 86_400).toEpochMilli(), 3, 0))
        }
        val cooling = study.start(source, "cooling", study = true)
        study.revealStudy(cooling)
        study.close(cooling)
        val blocked = db.listening().passage(source, "blocked")!!
        db.content().insertListening(listOf(blocked.copy(ready = false, reason = "Recording is not approved")))
        val home = study.overview(source)
        assertEquals(listOf("overdue", "example", "upcoming"), home.extra)
        assertEquals(home.extra, study.overview(source).extra)
        rejected { study.start(source, "blocked", extra = true) }
        rejected { study.start(source, "cooling", extra = true) }
        assertTrue(db.reviews().allLog().isEmpty())
    }

    @Test fun extraEntryRevalidatesSourceActiveEncounterAndMediaInsteadOfTrustingOverview() = runBlocking {
        install(approvedListening("other"))
        val candidate = study.overview(source).extra.first()
        settings.setSource("https://github.com/other/source", "main")
        rejected { study.start(source, candidate, extra = true) }
        settings.setSource("https://github.com/$source", "main")
        val id = study.start(source, candidate, extra = true)
        val other = study.overview(source).passages.first { it.id != candidate }.id
        rejected { study.start(source, other, extra = true) }
        study = ListeningStudy(db, settings, { now }, { zone })
        assertEquals(id, study.start(source, candidate, extra = true))
        heard(id)
        study.close(id, discard = true)
        rejected { study.start(source, candidate, extra = true) }
        db.content().insertListeningAudio(listOf(ListeningAudioEntity(source, fixture.passage.audio!!.sha256, byteArrayOf(1))))
        rejected { study.start(source, other, extra = true) }
        assertNull(db.listening().active(source))
        assertEquals(0, db.listening().reservedNew(source))
        assertTrue(study.overview(source).extra.isEmpty())
        assertTrue(db.reviews().allLog().isEmpty())
        assertTrue(db.reviews().allStates().isEmpty())
    }

    @Test fun sharedIntroductionsExplainZeroNewListeningDespiteAnUnusedTimeBudget() = runBlocking {
        settings.setDailyNewCards(1)
        db.reviews().insertLog(ReviewLogEntity(
            sourceId = source, stack = "all", kanji = "other-mode-item", mode = "Quiz", grade = Grade.Good.value,
            atMs = now.toEpochMilli(),
        ))
        val home = study.overview(source)
        assertEquals(1, home.sharedAllowance.total)
        assertEquals(0, home.sharedAllowance.remaining)
        assertEquals(ListeningAllowance(0, 300_000), home.allowance)
        assertEquals(130_000L, home.minimumNewMs)
        assertTrue(home.fresh.isEmpty())
        assertTrue(home.blocked.isEmpty() && home.cooldowns.isEmpty())
        rejected { study.start(source, "example") }
        val studyId = study.start(source, "example", study = true)
        study.revealStudy(studyId)
        assertEquals(1, db.reviews().allLog().size)
        assertEquals(0, db.listening().reservedNew(source))
    }

    @Test fun unplayedDiscardDoesNotCreateExposureAndInterruptedAssistanceSurvivesServiceRecreation() = runBlocking {
        val unused = study.start(source, "example")
        study.close(unused, discard = true)
        assertFalse(db.listening().hasExposure(source, "example"))
        val id = study.start(source, "example")
        heard(id)
        heard(id)
        study.playbackStarted(id, restart = true, slower = false)
        study.position(id, 1_234)
        study = ListeningStudy(db, settings, { now }, { zone })
        assertEquals(id, study.start(source, "example"))
        assertEquals(1_234L, study.attempt(db.listening().encounter(id)!!).positionMs)
        study.playbackStarted(id, restart = false, slower = false)
        answers(id)
        val row = study.submit(id)
        assertTrue(row.firstEncounter)
        assertEquals(Grade.Hard.value, study.response(row)!!.grade)
        assertEquals(setOf("extra-replay"), study.response(row)!!.assistance)
        assertEquals(3, study.response(row)!!.starts)
    }

    @Test fun anAllowedAttemptCanFinishAfterMidnightButStillDebitsItsCapturedTimeOnce() = runBlocking {
        val id = study.start(source, "example")
        heard(id)
        now = now.plusSeconds(86_400)
        settings.setDailyNewCards(0)
        val row = study.submit(id, giveUp = true)
        assertEquals(Grade.Again.value, study.response(row)!!.grade)
        assertEquals(now.plusSeconds(86_400).toEpochMilli(), db.reviews().allStates().single().dueMs)
        val introductions = db.listening().introductions(source, now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli())
        assertEquals(ListeningIntroductions(1, 130_000), introductions)
        assertEquals(0, db.listening().reservedNew(source))
    }

    @Test fun capturedPackageSurvivesContentReplacementAndMediaFailureDoesNotGrade() = runBlocking {
        val id = study.start(source, "example")
        heard(id)
        val next = approvedListening("replacement")
        db.content().clearListening(source)
        install(next)
        db.listening().pruneAudio(source)
        assertEquals(fixture.contentHash, study.record(db.listening().encounter(id)!!).contentHash)
        assertTrue(study.audio(id).isNotEmpty())
        answers(id)
        study.submit(id)
        study.close(id)
        db.content().insertListeningAudio(listOf(ListeningAudioEntity(source, next.passage.audio!!.sha256, byteArrayOf(1))))
        rejected { study.start(source, "replacement") }
        assertEquals(1, db.reviews().allLog().size)
        assertNull(db.listening().active(source))
        val unavailable = study.overview(source)
        assertFalse(unavailable.passages.single().ready)
        assertEquals("Cached audio is invalid; sync again", unavailable.blocked["replacement"])
        assertFalse("replacement" in unavailable.cooldowns)
        assertTrue(unavailable.fresh.isEmpty())
        assertTrue(unavailable.due.isEmpty())
    }

    @Test fun playbackRecoveryRestoresSharedAudioButNeverApprovesAReplacementDraft() = runBlocking {
        val id = study.start(source, "example")
        install(approvedListening("other"))
        study.playbackFailed(id)
        assertTrue(db.listening().passages(source).none { it.ready })
        heard(id)
        assertTrue(db.listening().passages(source).all { it.ready })
        val draft = ListeningContent.parse("listening/example.json",
            kotlinx.serialization.json.JsonObject(Json.parseToJsonElement(fixture.json).jsonObject - "audioReview").toString())
        install(draft)
        study.playbackFailed(id)
        heard(id)
        assertFalse(db.listening().passage(source, "example")!!.ready)
        assertTrue(db.listening().passage(source, "other")!!.ready)
    }

    @Test fun version12UpgradePreservesProgressAndReopensAnInterruptedListeningEncounter() = runBlocking {
        val name = "listening-migration-test.db"
        try {
            createMigrationSchema(context, name, 12) { sqlite ->
                sqlite.execSQL("INSERT INTO review_log VALUES (7, ?, 'all', 'x', 'Quiz', 3, 500, '', NULL)", arrayOf(source))
                sqlite.execSQL("INSERT INTO review_state VALUES (?, 'all', 'x', 'Quiz', 'Review', 12.5, 5.0, 999, 500, 3, 1, '')",
                    arrayOf(source))
            }
            val captured = ListeningEncounterEntity(
                "interrupted-listening", source, "example", "{}", "content-hash", "audio-hash", null,
                """{"assistance":["extra-replay"],"positionMs":1234}""", "graded", 1000, 130_000, true,
                exposedAtMs = 1001, lastExposureAtMs = 1002, firstEncounter = true,
            )
            repeat(2) { index ->
                val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16).allowMainThreadQueries().build()
                try {
                    if (index == 0) {
                        assertTrue(migrated.listening().allEncounters().isEmpty())
                        migrated.listening().putEncounter(captured)
                    }
                    assertEquals(captured, migrated.listening().active(source))
                    assertEquals(1, migrated.listening().reservedNew(source))
                    assertEquals(1, migrated.reviews().allLog().size)
                    assertEquals(3, migrated.reviews().allStates().single().reps)
                } finally { migrated.close() }
            }
        } finally { context.deleteDatabase(name) }
    }
}
