package io.github.stopcran.kanji.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.core.approvedListening
import io.github.stopcran.kanji.core.approvedTask
import io.github.stopcran.kanji.core.taskFixture
import io.github.stopcran.kanji.core.taskTone
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.Grade
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningTaskStudyTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private lateinit var study: ListeningTaskStudy
    private var now = Instant.parse("2026-10-22T12:00:00Z")
    private val source = "owner/repo"
    private val zone = ZoneId.of("UTC")
    private val fixture = approvedTask(taskFixture().task.copy(exposureId = "example"))

    @Before fun setup() = runBlocking {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context).also {
            it.setSource("https://github.com/$source", "main")
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(1)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        study = newStudy()
        install()
        val legacy = approvedListening()
        val audio = requireNotNull(legacy.passage.audio)
        db.content().insertListening(listOf(ListeningEntity(source, "example", legacy.json, legacy.contentHash,
            audio.sha256, audio.durationMs, true, null, 0)))
        db.content().insertListeningAudio(listOf(ListeningAudioEntity(source, audio.sha256, taskTone())))
    }

    @After fun cleanup() { db.close() }

    private fun newStudy() = ListeningTaskStudy(db, settings, { now }, { zone }, ListeningTaskType.entries.toSet())
    private suspend fun row(id: String) = requireNotNull(db.listeningTasks().encounter(id))
    private suspend fun state(id: String) = study.attempt(row(id))
    private suspend fun install(
        records: List<ListeningTaskRecord> = listOf(fixture), errors: List<String> = emptyList(),
        unsupported: List<UnsupportedListeningTask> = emptyList(),
        assets: Map<String, ByteArray> = records.flatMap { it.assetHashes }.associateWith { taskTone() },
    ): List<String> {
        val parsed = ParsedContent(Manifest(1, "fixture", emptyMap()), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(),
            listeningTasks = ListeningTaskCatalog(records = records, assets = assets, problems = errors, unsupported = unsupported))
        return db.withTransaction { db.importListeningTasks(parsed, settings.source.value, "a".repeat(40)) }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected explicit rejection") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun play(id: String, stage: Int? = null, wholeReplay: Boolean = false) {
        val request = study.request(id, stage = stage, wholeReplay = wholeReplay)
        val token = study.attempt(request).generation
        assertArrayEquals(taskTone(), study.stageAudio(id, token))
        study.started(id, token)
        study.completed(id, token)
    }
    private suspend fun hearAll(id: String) { repeat(fixture.task.stages.size) { play(id) } }

    @Test fun familiarUnintroducedTaskReservesSharedAllowanceAndBlocksOtherListeningModes() = runBlocking {
        val unscored = study.start(source, fixture.task.id, study = true)
        assertNull(row(unscored).exposedAtMs)
        study.revealEvidence(unscored, textHelp = true)
        assertTrue(row(unscored).firstEncounter)
        study.close(unscored)
        rejected { study.start(source, fixture.task.id, extra = true) }
        val legacy = ListeningStudy(db, settings, { now }, { zone })
        rejected { legacy.start(source, "example", extra = true) }
        now = now.plusMillis(ListeningPolicy.COOLDOWN_MS + 1)
        val id = study.start(source, fixture.task.id, extra = true)
        assertTrue(row(id).newReservation)
        assertEquals(0, db.newAllowance(settings, source, now, zone).remaining)
        rejected { legacy.start(source, "example", study = true) }
        study.revealEvidence(id)
        assertFalse(row(id).firstEncounter)
        study.close(id, discard = true)
        assertEquals(1, db.newAllowance(settings, source, now, zone).remaining)
        assertTrue(db.reviews().allLog().isEmpty())
    }

    @Test fun sourceTransactionsPreventRacingLegacyTaskAndAssessmentAdmissions() = runBlocking {
        val legacy = ListeningStudy(db, settings, { now }, { zone })
        val attempts = listOf<suspend () -> String>(
            { study.start(source, fixture.task.id, study = true) },
            { legacy.start(source, "example", study = true) },
        ).map { operation -> async { try { operation() } catch (_: IllegalArgumentException) { null } } }.awaitAll()
        assertEquals(1, attempts.count { it != null })
        db.listeningTasks().active(source)?.let { study.close(it.id) }
        db.listening().active(source)?.let { legacy.close(it.id) }
        db.withTransaction {
            db.requireNoOtherListeningActivity(source)
            db.listeningTasks().putAssessment(ListeningAssessmentEntity("assessment", source, "main", null, "fixture",
                ListeningTaskContent.BLUEPRINT, 1, "{}", "{}", now.toEpochMilli()))
        }
        rejected { study.start(source, fixture.task.id, study = true) }
        rejected { legacy.start(source, "example", study = true) }
        assertEquals(1, db.newAllowance(settings, source, now, zone).remaining)
        assertTrue(db.reviews().allLog().isEmpty())
    }

    @Test fun onlyStartedCurrentGenerationCompletesStagesAndRecoveryRetainsAssistance() = runBlocking {
        val id = study.start(source, fixture.task.id, extra = true)
        val pending = study.attempt(study.request(id))
        study.completed(id, pending.generation)
        assertTrue(state(id).completedStages.isEmpty())
        assertNull(row(id).exposedAtMs)
        study.interrupt(id, pending.generation, 0)
        study.started(id, pending.generation)
        study.completed(id, pending.generation)
        assertTrue(state(id).completedStages.isEmpty())
        val first = study.attempt(study.request(id))
        study.started(id, first.generation)
        study.interrupt(id, first.generation, 1_000)
        study = newStudy()
        study.recover(id)
        assertEquals(1_000L, state(id).positionMs)
        assertTrue(state(id).assistance.isEmpty())
        play(id)
        assertNull(row(id).exposedAtMs)
        val story = study.attempt(study.request(id))
        study.started(id, story.generation)
        assertTrue(row(id).firstEncounter)
        study = newStudy()
        study.recover(id)
        assertTrue("recovery-replay" in state(id).assistance)
        assertEquals(0L, state(id).positionMs)
        study.completed(id, story.generation)
        assertEquals(1, state(id).completedStages.size)
        repeat(3) { play(id) }
        study.answer(id, fixture.task.answer)
        study.submit(id)
        assertEquals(Grade.Hard.value, state(id).grade)
    }

    @Test fun failedEvidenceCannotBeBypassedByInstructionCompletionOrGiveUp() = runBlocking {
        val id = study.start(source, fixture.task.id, extra = true)
        play(id)
        val story = study.attempt(study.request(id))
        study.started(id, story.generation)
        study.failed(id, story.generation, "Synthetic decoder failure")
        rejected { study.submit(id, giveUp = true) }
        play(id, stage = 0)
        assertNotNull(state(id).technicalProblem)
        rejected { study.submit(id, giveUp = true) }
        play(id)
        assertNull(state(id).technicalProblem)
        play(id)
        play(id)
        study.answer(id, fixture.task.answer)
        study.submit(id)
        assertEquals(Grade.Hard.value, state(id).grade)
        assertEquals(1, db.reviews().allLog().size)
    }

    @Test fun normalWholeReplayAndConcurrentMidnightSubmitUsePinnedContentAndImmutableResponse() = runBlocking {
        val id = study.start(source, fixture.task.id, extra = true)
        hearAll(id)
        play(id, wholeReplay = true)
        repeat(2) { play(id) }
        assertTrue(state(id).assistance.isEmpty())
        install(emptyList())
        val hash = fixture.assetHashes.single()
        assertNotNull(db.listeningTasks().asset(source, hash))
        assertEquals(fixture.contentHash, row(id).contentHash)
        settings.setSource("https://github.com/other/repo", "next")
        now = now.plusSeconds(86_400)
        settings.setWeekPlan("OOOOOOO")
        study.answer(id, fixture.task.answer)
        listOf(study, newStudy(), newStudy()).map { engine -> async { engine.submit(id) } }.awaitAll()
        val frozen = row(id).responseJson
        assertEquals(Grade.Good.value, state(id).grade)
        assertEquals(1, db.reviews().allLog().size)
        assertEquals(1, db.listeningTasks().introductions(source, now.toEpochMilli()).count)
        assertTrue(row(id).introduced)
        assertEquals("main", row(id).branch)
        play(id, stage = 1)
        assertEquals(frozen, row(id).responseJson)
        study.close(id)
        study.submit(id)
        study.completed(id, state(id).generation)
        assertNull(db.listeningTasks().asset(source, hash))
        assertEquals(frozen, row(id).responseJson)
        val output = ByteArrayOutputStream()
        ProgressExport.write(db, settings, output)
        val files = ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            buildMap { while (true) { val entry = zip.nextEntry ?: break; put(entry.name, Json.parseToJsonElement(zip.readBytes().decodeToString())) } }
        }
        assertEquals(6, files.getValue("export_manifest.json").jsonObject.getValue("formatVersion").jsonPrimitive.int)
        assertEquals(Json.parseToJsonElement(requireNotNull(frozen)),
            files.getValue("listening_task_encounters.json").jsonArray.single().jsonObject.getValue("response"))
        assertTrue(files.getValue("listening_assessments.json").jsonArray.isEmpty())
        assertEquals("example", files.getValue("listening_exposures.json").jsonArray.single().jsonObject.getValue("exposureId").jsonPrimitive.content)
        assertEquals(10, files.size)
    }

    @Test fun submittingAfterOnlyPartOfAnOtherwiseNormalReplayIsAssisted() = runBlocking {
        val id = study.start(source, fixture.task.id, extra = true)
        hearAll(id)
        play(id, wholeReplay = true)
        study.answer(id, fixture.task.answer)
        study.submit(id)
        assertEquals(Grade.Hard.value, state(id).grade)
        assertTrue("partial-replay" in state(id).assistance)
    }

    @Test fun cancelledUnstartedRequestsDoNotSpendTheNormalReplayOrApplyAssistance() = runBlocking {
        val id = study.start(source, fixture.task.id, extra = true)
        hearAll(id)
        val targeted = study.attempt(study.request(id, stage = 1, slower = true))
        study.interrupt(id, targeted.generation, 0)
        val whole = study.attempt(study.request(id, wholeReplay = true))
        study.interrupt(id, whole.generation, 0)
        assertEquals(0, state(id).wholePass)
        assertTrue(state(id).assistance.isEmpty())
        play(id, wholeReplay = true)
        repeat(2) { play(id) }
        assertEquals(1, state(id).wholePass)
        study.answer(id, fixture.task.answer)
        study.submit(id)
        assertEquals(Grade.Good.value, state(id).grade)
    }

    @Test fun aSecondActuallyHeardWholeReplayIsAssistance() = runBlocking {
        val id = study.start(source, fixture.task.id, extra = true)
        hearAll(id)
        repeat(2) {
            play(id, wholeReplay = true)
            repeat(2) { play(id) }
        }
        study.answer(id, fixture.task.answer)
        study.submit(id)
        assertEquals(Grade.Hard.value, state(id).grade)
        assertTrue("extra-replay" in state(id).assistance)
    }

    @Test fun cancelledTargetedRestartOfPartlyHeardStageRetainsReplayAssistance() = runBlocking {
        val id = study.start(source, fixture.task.id, extra = true)
        play(id)
        play(id)
        val passage = study.attempt(study.request(id))
        study.started(id, passage.generation)
        study.interrupt(id, passage.generation, 500)
        val restart = study.attempt(study.request(id, stage = 2, slower = true))
        study.interrupt(id, restart.generation, 0)
        study = newStudy()
        study.recover(id)
        assertTrue(state(id).assistance.isEmpty())
        play(id)
        play(id)
        study.answer(id, fixture.task.answer)
        study.submit(id)
        assertEquals(Grade.Hard.value, state(id).grade)
        assertTrue("targeted-replay" in state(id).assistance)
        assertFalse("slower-playback" in state(id).assistance)
    }

    @Test fun requiredPictureFailurePersistsAndOnlyItsRevalidationClearsIt() = runBlocking {
        val picture = requireNotNull(javaClass.getResourceAsStream("/listening-test-picture.webp")).use { it.readBytes() }
        val visual = approvedTask(fixture.task.copy(choiceMode = TaskChoiceMode.Visual,
            visual = TaskVisual("required", "A synthetic neutral numbered picture.")), image = picture)
        val hash = ContentParser.rawSha256(picture)
        install(listOf(visual), assets = listOf(taskTone(), picture).associateBy(ContentParser::rawSha256))
        val id = study.start(source, visual.task.id, extra = true)
        hearAll(id)
        db.openHelper.writableDatabase.execSQL("DELETE FROM listening_task_assets WHERE sourceId = ? AND sha256 = ?", arrayOf(source, hash))
        rejected { study.image(id) }
        study = newStudy()
        assertNotNull(state(id).pictureProblem)
        rejected { study.submit(id, giveUp = true) }
        play(id, stage = 0)
        assertNotNull(state(id).pictureProblem)
        rejected { study.submit(id, giveUp = true) }
        db.listeningTasks().putAssets(listOf(ListeningTaskAssetEntity(source, hash, byteArrayOf(0))))
        rejected { study.image(id) }
        assertTrue(db.reviews().allLog().isEmpty())
        val pending = study.attempt(study.request(id, stage = 1))
        study.failed(id, pending.generation, "Synthetic audio failure")
        db.listeningTasks().putAssets(listOf(ListeningTaskAssetEntity(source, hash, picture)))
        assertArrayEquals(picture, study.image(id))
        assertNull(state(id).pictureProblem)
        assertNotNull(state(id).technicalProblem)
        rejected { study.submit(id, giveUp = true) }
        play(id, stage = 1)
        study.answer(id, fixture.task.answer)
        study.submit(id)
        assertEquals(Grade.Hard.value, state(id).grade)
        assertEquals(1, db.reviews().allLog().size)
    }

    @Test fun rejectedReplacementPreservesWholeCatalogBindingsAndAssets() = runBlocking {
        assertTrue(install(errors = listOf("one supported package is corrupt")).single().contains("keeping"))
        assertEquals(fixture.json, db.listeningTasks().tasks(source).single().json)
        assertNotNull(db.listeningTasks().sync(source)?.problem)
        assertNotNull(db.listeningTasks().asset(source, fixture.assetHashes.single()))
        val rebound = approvedTask(fixture.task.copy(exposureId = "different-story"))
        assertTrue(install(listOf(rebound)).single().contains("changed its exposure"))
        assertEquals("example", db.listeningTasks().identities(source).single().exposureId)
        assertTrue(install().isEmpty())
        assertNull(db.listeningTasks().sync(source)?.problem)
        assertTrue(study.overview(source).tasks.single().ready)
    }

    @Test fun unsupportedPendingPolicyCanBeDiscardedWithoutInterpretingOrLosingItsState() = runBlocking {
        val id = study.start(source, fixture.task.id, extra = true)
        study.revealEvidence(id)
        val before = row(id)
        db.listeningTasks().putEncounter(before.copy(policyVersion = 2, stateJson = """{"futureState":true}"""))
        rejected { study.request(id) }
        study.close(id, discard = true)
        assertEquals("""{"futureState":true}""", row(id).stateJson)
        assertEquals(before.exposedAtMs, row(id).exposedAtMs)
        assertNotNull(row(id).closedAtMs)
        assertEquals(0, db.listeningTasks().reservedNew(source))
        assertTrue(db.reviews().allLog().isEmpty())
    }

    @Test fun unsupportedReplacementDisablesCurrentAvailabilityEvenWhenAnotherPackageIsCorrupt() = runBlocking {
        install(emptyList(), listOf("Another task is corrupt"), listOf(UnsupportedListeningTask(fixture.task.id, "Requires newer app")))
        val current = db.listeningTasks().tasks(source).single()
        assertEquals(fixture.json, current.json)
        assertFalse(current.ready)
        assertEquals("Requires newer app", current.reason)
        assertNotNull(db.listeningTasks().sync(source)?.problem)
        install()
        assertTrue(db.listeningTasks().tasks(source).single().ready)
    }

    @Test fun removedLegacyHistoryCannotBeReassignedToANewTask() = runBlocking {
        val legacy = ListeningStudy(db, settings, { now }, { zone })
        val id = legacy.start(source, "example", study = true)
        legacy.revealStudy(id)
        legacy.close(id)
        db.content().clearListening(source)
        val reused = approvedTask(fixture.task.copy(id = "example", exposureId = "different-story"))
        assertTrue(install(listOf(reused)).single().contains("reused a legacy scored identity"))
        assertEquals(fixture.task.id, db.listeningTasks().tasks(source).single().id)
    }

    @Test fun everyDistinctAudioAndPictureRemainsPinnedThroughReplacementUntilDiscard() = runBlocking {
        val short = requireNotNull(javaClass.getResourceAsStream("/listening-short-tone.ogg")).use { it.readBytes() }
        val picture = requireNotNull(javaClass.getResourceAsStream("/listening-test-picture.webp")).use { it.readBytes() }
        val draft = fixture.task.copy(media = null, approval = null, visual = TaskVisual("context", "A synthetic neutral test picture."))
        val contentHash = ListeningTaskContent.parse("listening-tasks/${draft.id}.json",
            Json.encodeToString(ListeningTaskPackage.serializer(), draft)).contentHash
        val passage = draft.stages[2]
        val audio = TaskAudio("task-audio/short-tone.ogg", ContentParser.rawSha256(short), 250,
            passage.turns.mapIndexed { index, id -> ListeningTiming(id, index * 250L / passage.turns.size, (index + 1) * 250L / passage.turns.size) })
        val original = requireNotNull(fixture.task.media)
        val media = original.copy(contentSha256 = contentHash,
            audio = original.audio + (passage.id to audio),
            image = ListeningImage("task-images/test-picture.webp", ContentParser.rawSha256(picture)))
        val task = draft.copy(media = media, approval = requireNotNull(fixture.task.approval).copy(contentSha256 = contentHash,
            mediaSha256 = ListeningTaskContent.mediaHash(media)))
        val record = ListeningTaskContent.parse("listening-tasks/${task.id}.json", Json.encodeToString(ListeningTaskPackage.serializer(), task))
        val bytes = listOf(taskTone(), short, picture).associateBy(ContentParser::rawSha256)
        assertTrue(install(listOf(record), assets = bytes).isEmpty())
        val id = study.start(source, task.id, extra = true)
        install(emptyList())
        assertEquals(3, record.assetHashes.size)
        record.assetHashes.forEach { assertArrayEquals(bytes.getValue(it), db.listeningTasks().asset(source, it)) }
        assertArrayEquals(picture, study.image(id))
        study.close(id, discard = true)
        record.assetHashes.forEach { assertNull(db.listeningTasks().asset(source, it)) }
    }
}
