package io.github.stopcran.kanji.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.core.*
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningAssessmentStudyTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private lateinit var tasks: ListeningTaskStudy
    private lateinit var study: ListeningAssessmentStudy
    private val source = "fixture/assessment"
    private var wall = Instant.parse("2026-10-25T12:00:00Z")
    private var elapsed = 100L
    private val pool = assessmentPool()
    private val assets = listOf(taskTone(), assessmentAsset("listening-short-tone.ogg"), assessmentAsset("listening-test-picture.webp"))
        .associateBy(ContentParser::rawSha256)

    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context).also {
            it.setSource("https://github.com/$source", "main")
            it.setDailyNewCards(0)
            it.setWeekPlan("OOOOOOO")
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        tasks = ListeningTaskStudy(db, settings, { wall })
        study = newService()
        install()
    }

    @After fun cleanup() { db.close() }
    private fun newService() = ListeningAssessmentStudy(db, settings, tasks, { wall }, { elapsed })

    private suspend fun install(records: List<ListeningTaskRecord> = pool.map { it.record }) {
        db.withTransaction {
            val problems = db.importListeningTasks(ParsedContent(Manifest(1, "fixture-v1", emptyMap()), emptyList(), emptyList(),
                emptyList(), emptyMap(), emptyList(), listeningTasks = ListeningTaskCatalog(records = records, assets = assets)),
                settings.source.value, "a".repeat(40)).problems
            assertTrue(problems.toString(), problems.isEmpty())
        }
    }

    private suspend fun start(profile: ListeningAssessmentProfile = ListeningAssessmentProfile.N5): String {
        val preview = study.preview(42, profile = profile)
        assertTrue(preview.selection.problems.toString(), preview.selection.available)
        return study.start(preview).also { study.restore(it); study.begin(it) }
    }

    private suspend fun audio(id: String) {
        val before = study.snapshot(id)
        val requested = tasks.request(before.encounter.id)
        val generation = tasks.attempt(requested).generation
        tasks.started(requested.id, generation)
        tasks.completed(requested.id, generation)
        study.audioCompleted(id, before.state.generation, before.state.stageIndex)
    }

    private suspend fun untilAnswer(id: String): ListeningAssessmentSnapshot {
        repeat(10) {
            val current = study.snapshot(id)
            when (current.state.phase) {
                AssessmentPhase.Instruction, AssessmentPhase.Audio -> audio(id)
                AssessmentPhase.Preview -> {
                    val visible = study.visible(id, current.state.generation)
                    elapsed = requireNotNull(visible.state.deadlineMs)
                    study.pulse(id, visible.state.generation)
                }
                AssessmentPhase.Answer -> return current
                else -> error("Unexpected fixture phase ${current.state.phase}")
            }
        }
        error("Fixture did not reach answer")
    }

    @Test fun fullTimelineIsDiagnosticOnlyAndScoredInstructionsAreNotInvented() = runBlocking {
        verifyFullTimeline(ListeningAssessmentProfile.N5)
    }

    @Test fun n4FullTimelineAndRawExportRetainTheirCapturedProfileAndDenominators() = runBlocking {
        install(pool.map { it.record } + assessmentPool(profile = ListeningAssessmentProfile.N4).map { it.record })
        verifyFullTimeline(ListeningAssessmentProfile.N4)
        val row = db.listeningTasks().allAssessments().single()
        assertEquals("n4-section-v1", row.blueprint)
        val form = Json.decodeFromString<AssessmentForm>(row.contentJson)
        assertEquals(4, form.jlpt)
        assertEquals(32, form.items.size)
        val result = Json.decodeFromString<AssessmentResult>(row.responseJson!!)
        assertEquals(28, result.planned)
        assertEquals(4, result.jlpt)
        assertEquals(form.blueprint, result.blueprint)
        val bytes = ByteArrayOutputStream()
        assertEquals(0, ProgressExport.write(db, settings, bytes))
        var exported = false
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "listening_assessments.json") {
                    val parent = Json.parseToJsonElement(zip.readBytes().decodeToString()).jsonArray.single().jsonObject
                    assertEquals("n4-section-v1", parent.getValue("blueprint").jsonPrimitive.content)
                    val response = parent.getValue("response").jsonObject
                    assertEquals("28", response.getValue("planned").jsonPrimitive.content)
                    assertEquals("4", response.getValue("jlpt").jsonPrimitive.content)
                    assertEquals(32, response.getValue("responses").jsonArray.size)
                    exported = true
                }
            }
        }
        assertTrue(exported)
    }

    private suspend fun verifyFullTimeline(profile: ListeningAssessmentProfile) {
        val id = start(profile)
        settings.setListeningScope(source, ListeningScope.N5)
        repeat(profile.itemCount) { index ->
            val answer = untilAnswer(id)
            assertEquals(index, answer.state.index)
            study.answer(id, answer.state.generation, answer.record.task.answer)
            elapsed = requireNotNull(answer.state.deadlineMs)
            var next = study.pulse(id, answer.state.generation)
            if (next.state.phase == AssessmentPhase.ExampleFeedback) {
                next = study.visible(id, next.state.generation, help = true, explanation = true)
                elapsed = requireNotNull(next.state.deadlineMs)
                next = study.pulse(id, next.state.generation)
            }
            if (index == profile.itemCount - 1) assertEquals(AssessmentPhase.Results, next.state.phase)
        }
        val completed = study.snapshot(id)
        assertEquals(profile.scoredCount, completed.result!!.correct)
        assertEquals(profile.scoredCount, completed.result.assessed)
        assertTrue(completed.result.completedFullForm)
        assertEquals(4, completed.result.examples)
        assertEquals(profile.itemCount, db.listeningTasks().allExposures().size)
        assertTrue(db.reviews().allLog().isEmpty())
        assertTrue(db.reviews().allStates().isEmpty())
        assertEquals(0, db.listeningTasks().reservedNew(source))
        db.listeningTasks().assessmentEncounters(id).forEach { child ->
            val task = tasks.record(child).task
            assertEquals(task.usage == TaskUsage.Example, task.stages.first().id in tasks.attempt(child).completedStages)
            assertNull(child.reviewLogId)
            assertFalse(child.introduced)
            assertFalse(child.newReservation)
        }
        val response = completed.row.responseJson
        study.end(id)
        study.close(id)
        study.close(id)
        assertEquals(response, db.listeningTasks().assessment(id)!!.responseJson)
        assertNull(db.listeningTasks().activeAssessment(source))
    }

    @Test fun skippedResponseSurvivesCancellationButDoesNotAdvanceClock() = runBlocking {
        val id = start()
        val example = untilAnswer(id)
        elapsed = example.state.deadlineMs!!
        val pendingFeedback = study.pulse(id, example.state.generation)
        val feedback = study.visible(id, pendingFeedback.state.generation, help = true, explanation = true)
        elapsed = feedback.state.deadlineMs!!
        study.pulse(id, feedback.state.generation)
        val scored = untilAnswer(id)
        val skipped = study.answer(id, scored.state.generation, null)
        assertEquals(scored.state.index, skipped.state.index)
        assertEquals(scored.state.deadlineMs, skipped.state.deadlineMs)
        assertNotNull(skipped.encounter.responseJson)
        assertEquals(skipped.encounter.responseJson, study.answer(id, scored.state.generation, null).encounter.responseJson)
        val result = study.end(id).result!!
        assertEquals(1, result.assessed)
        assertEquals(1, result.unanswered)
        assertEquals(23, result.notAssessed)
        assertFalse(result.completedFullForm)
        assertEquals(2, db.listeningTasks().allExposures().size)
    }

    @Test fun lateAnswersCannotChangeFinalizedWindowAndExportKeepsDiagnosticShape() = runBlocking {
        val id = start()
        val example = untilAnswer(id)
        elapsed = example.state.deadlineMs!!
        val feedback = study.pulse(id, example.state.generation)
        val shown = study.visible(id, feedback.state.generation, help = true, explanation = true)
        elapsed = shown.state.deadlineMs!!
        study.pulse(id, shown.state.generation)
        val scored = untilAnswer(id)
        elapsed = scored.state.deadlineMs!!
        val late = study.answer(id, scored.state.generation, scored.record.task.answer)
        assertEquals(scored.state.index + 1, late.state.index)
        assertEquals(AssessmentOutcome.Unanswered, Json.decodeFromString<AssessmentResponse>(
            db.listeningTasks().encounter(scored.encounter.id)!!.responseJson!!).outcome)
        val ended = study.end(id)
        val bytes = ByteArrayOutputStream()
        assertEquals(0, ProgressExport.write(db, settings, bytes))
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        val parent = Json.parseToJsonElement(entries.getValue("listening_assessments.json")).jsonArray.single().jsonObject
        assertEquals(28, parent.getValue("response").jsonObject.getValue("responses").jsonArray.size)
        val children = Json.parseToJsonElement(entries.getValue("listening_task_encounters.json")).jsonArray
        assertTrue(children.all { it.jsonObject.getValue("kind").jsonPrimitive.content == "assessment" })
        assertTrue(children.all { "grade" !in it.jsonObject.getValue("response").jsonObject })
        val response = children.single { it.jsonObject.getValue("id").jsonPrimitive.content == scored.encounter.id }
            .jsonObject.getValue("response").jsonObject
        assertEquals("unanswered", response.getValue("outcome").jsonPrimitive.content)
        assertEquals(23, ended.result!!.notAssessed)
    }

    @Test fun discardFinalizesReadableCaptureButPreservesUninterpretableFuturePolicy() = runBlocking {
        val id = start()
        study.close(id, discardUnreadable = true)
        val closed = db.listeningTasks().assessment(id)!!
        assertEquals(24, Json.decodeFromString<AssessmentResult>(closed.responseJson!!).notAssessed)
        assertTrue(db.listeningTasks().allExposures().isEmpty())
        val next = study.start(study.preview(99))
        val row = db.listeningTasks().assessment(next)!!
        db.listeningTasks().putAssessment(row.copy(policyVersion = 99))
        study.close(next, discardUnreadable = true)
        val discarded = db.listeningTasks().assessment(next)!!
        assertNotNull(discarded.closedAtMs)
        assertEquals(row.stateJson, discarded.stateJson)
        assertNull(discarded.responseJson)
        assertTrue(db.reviews().allLog().isEmpty())
    }

    @Test fun pictureExposureCannotStartTheWorkedExplanationWindow() = runBlocking {
        val id = start()
        val example = untilAnswer(id)
        elapsed = example.state.deadlineMs!!
        val feedback = study.pulse(id, example.state.generation)
        assertEquals(AssessmentPhase.ExampleFeedback, feedback.state.phase)
        assertNull(study.visible(id, feedback.state.generation).state.deadlineMs)
        assertNull(study.visible(id, feedback.state.generation, help = true).state.deadlineMs)
        elapsed += 30_000
        assertEquals(AssessmentPhase.ExampleFeedback, study.pulse(id, feedback.state.generation).state.phase)
        val shown = study.visible(id, feedback.state.generation, help = true, explanation = true)
        assertEquals(elapsed + 10_000, shown.state.deadlineMs)
        elapsed += 5_000
        assertEquals(shown.state.deadlineMs, study.visible(id, shown.state.generation).state.deadlineMs)
        study.end(id)
        study.close(id)
    }

    @Test fun completedFeedbackPlaybackRecoversWithoutReinterpretingTheFrozenResult() = runBlocking {
        val id = start()
        untilAnswer(id)
        val ended = study.end(id)
        val child = study.feedbackItem(id, 0)
        val requested = tasks.request(child.id, stage = 1)
        tasks.started(child.id, tasks.attempt(requested).generation)
        assertTrue(tasks.attempt(db.listeningTasks().encounter(child.id)!!).started)
        study = newService()
        study.restore(id)
        assertFalse(tasks.attempt(db.listeningTasks().encounter(child.id)!!).started)
        assertEquals(ended.row.responseJson, db.listeningTasks().assessment(id)!!.responseJson)
        tasks.request(child.id, stage = 1)
        study.close(id)
    }

    @Test fun captureIsAtomicAndStaleConsentOrMissingAssetsNeverAcquireOwnership() = runBlocking {
        val preview = study.preview(42)
        db.listeningTasks().putExposure(ListeningExposureEntity(source, pool[1].record.task.exposureId,
            wall.toEpochMilli(), wall.toEpochMilli()))
        try { study.start(preview); fail("Stale consent accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(db.listeningTasks().allEncounters().isEmpty())
        assertTrue(db.listeningTasks().allAssessments().isEmpty())
        val refreshed = study.preview(42)
        assertTrue(refreshed.selection.available)
        val starts = (1..2).map { async {
            try { study.start(refreshed) } catch (_: IllegalArgumentException) { null }
        } }.awaitAll()
        assertEquals(1, starts.count { it != null })
        assertEquals(28, db.listeningTasks().allEncounters().size)
        val id = starts.filterNotNull().single()
        study.end(id)
        study.close(id)
        val hash = ContentParser.rawSha256(taskTone())
        db.listeningTasks().putAssets(listOf(ListeningTaskAssetEntity(source, hash, byteArrayOf(1))))
        val missing = study.preview(42, familiar = true)
        assertFalse(missing.selection.available)
        assertTrue(missing.selection.groups.sumOf { it.unavailable } > 0)
        assertNull(db.listeningTasks().activeAssessment(source))
    }

    @Test fun restorationSourceReplacementAndTechnicalFailureRetainCaptureWithoutGrading() = runBlocking {
        verifyRestoration(ListeningAssessmentProfile.N5)
    }

    @Test fun n4RestorationIgnoresCurrentSourceAndRetainsPartialTwentyEightItemResult() = runBlocking {
        verifyRestoration(ListeningAssessmentProfile.N4)
    }

    private suspend fun verifyRestoration(profile: ListeningAssessmentProfile) {
        val capturedPool = assessmentPool(profile = profile)
        install(capturedPool.map { it.record })
        val id = start(profile)
        val first = untilAnswer(id)
        elapsed += 2_000
        study.pulse(id, first.state.generation)
        val original = study.snapshot(id)
        install(emptyList())
        capturedPool.flatMap { it.record.assetHashes }.distinct().forEach { assertNotNull(db.listeningTasks().asset(source, it)) }
        settings.setSource("https://github.com/another/source", "different")
        wall = wall.plusSeconds(100_000)
        elapsed = 1
        study = newService()
        val restored = study.restore(id)
        assertTrue(restored.state.paused)
        assertTrue(restored.state.interrupted)
        assertEquals(original.form, restored.form)
        assertEquals(original.state.remainingMs, restored.state.remainingMs)
        val continued = study.continuePractice(id)
        assertEquals(1 + restored.state.remainingMs!!, continued.state.deadlineMs)
        study.interrupt(id, "Decoder failure")
        val ended = study.end(id)
        assertEquals(0, ended.result!!.assessed)
        assertEquals(profile.scoredCount, ended.result.notAssessed)
        assertEquals(profile.id, ended.result.blueprint)
        assertEquals(profile.jlpt, ended.result.jlpt)
        assertTrue(db.reviews().allLog().isEmpty())
        assertEquals(1, db.listeningTasks().allExposures().size)
        assertEquals(source, ended.row.sourceId)
        study.close(id)
        capturedPool.flatMap { it.record.assetHashes }.distinct().forEach { assertNull(db.listeningTasks().asset(source, it)) }
        assertTrue(Json.decodeFromString<AssessmentResult>(ended.row.responseJson!!).interrupted)
    }

    @Test fun n4StaleProfileBranchAndMalformedCapturesNeverFallBackToN5() = runBlocking {
        install(pool.map { it.record } + assessmentPool(profile = ListeningAssessmentProfile.N4).map { it.record })
        val preview = study.preview(42, profile = ListeningAssessmentProfile.N4)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { study.start(preview.copy(profile = ListeningAssessmentProfile.N5)) }
        }
        settings.setSource("https://github.com/$source", "different")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { study.start(preview) } }
        assertTrue(db.listeningTasks().allEncounters().isEmpty())
        assertTrue(db.listeningTasks().allAssessments().isEmpty())
        settings.setSource("https://github.com/$source", "main")
        val id = study.start(preview)
        val row = db.listeningTasks().assessment(id)!!
        val form = Json.decodeFromString<AssessmentForm>(row.contentJson)
        val malformed = listOf(
            row.copy(blueprint = ListeningAssessmentBlueprint.ID),
            row.copy(blueprint = "n4-section-v2"),
            row.copy(contentJson = Json.encodeToString(form.copy(jlpt = 5))),
            row.copy(contentJson = Json.encodeToString(form.copy(items = form.items.dropLast(1)))),
        )
        malformed.forEach {
            db.listeningTasks().putAssessment(it)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { study.snapshot(id) } }
        }
        db.listeningTasks().putAssessment(row)
        val ended = study.end(id)
        db.listeningTasks().putAssessment(ended.row.copy(responseJson = Json.encodeToString(ended.result!!.copy(planned = 24))))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { study.snapshot(id) } }
        db.listeningTasks().putAssessment(ended.row)
        study.close(id)
    }

    @Test fun unheardSourceScopedLegacyIdentityBlocksN4AndExpiresAnEarlierPreview() = runBlocking {
        val profile = ListeningAssessmentProfile.N4
        val n4 = assessmentPool(profile = profile).map { it.record }.toMutableList()
        n4[1] = approvedTask(n4[1].task.copy(exposureId = "example"))
        install(pool.map { it.record } + n4)
        val legacy = approvedListening()
        val audio = requireNotNull(legacy.passage.audio)
        val foreign = ListeningEntity("other/source", "example", legacy.json, legacy.contentHash,
            audio.sha256, audio.durationMs, true, null, 0)
        db.content().insertListening(listOf(foreign))
        val before = study.preview(42, profile = profile)
        assertTrue(before.selection.problems.toString(), before.selection.available)
        db.content().insertListening(listOf(foreign.copy(sourceId = source)))
        assertTrue(db.listeningTasks().allExposures().isEmpty())
        val blocked = study.preview(42, profile = profile)
        assertFalse(blocked.selection.available)
        assertTrue(blocked.selection.problems.any { "another level" in it })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { study.start(before) } }
        assertTrue(db.listeningTasks().allAssessments().isEmpty())
        assertTrue(db.listeningTasks().allEncounters().isEmpty())
        assertTrue(study.preview(42).selection.available)
    }
}
