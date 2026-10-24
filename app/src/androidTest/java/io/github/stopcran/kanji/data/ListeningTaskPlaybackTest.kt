package io.github.stopcran.kanji.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.MainActivity
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.Grade
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

/** Synthetic tones and isolated storage exercise the real codec, not Japanese listening approval. */
class ListeningTaskPlaybackTest {
    @Test fun offlineNativeStagesAndNormalWholeReplayUnlockExactlyOneReview() = runBlocking {
        withFixture { fixture ->
            val player = AndroidListeningPlayer(fixture.context)
            val playback = ListeningTaskPlayback(fixture.study, player)
            try {
                val id = fixture.study.start(fixture.source, fixture.record.task.id, extra = true)
                repeat(4) { index ->
                    assertEquals(TaskPlaybackOutcome.Completed, withTimeout(10_000) { playback.play(id) })
                    assertEquals(index + 1, fixture.state(id).completedStages.size)
                    if (index == 0) assertNull(fixture.row(id).exposedAtMs)
                }
                assertEquals("answer", fixture.state(id).phase)
                assertTrue(fixture.db.reviews().allLog().isEmpty())
                assertEquals(TaskPlaybackOutcome.Completed, playback.play(id, wholeReplay = true))
                repeat(2) { assertEquals(TaskPlaybackOutcome.Completed, playback.play(id)) }
                assertTrue(fixture.state(id).assistance.isEmpty())
                fixture.study.answer(id, fixture.record.task.answer)
                fixture.study.submit(id)
                assertEquals(Grade.Good.value, fixture.state(id).grade)
                assertEquals(1, fixture.db.reviews().allLog().size)
            } finally { withContext(Dispatchers.Main) { playback.close() } }
        }
    }

    @Test fun interruptionCancelsPendingPreparationAndSeekWithoutLateStartOrCredit() = runBlocking {
        withFixture { fixture ->
            val player = ControlledPlayer()
            val playback = ListeningTaskPlayback(fixture.study, player)
            try {
                val id = fixture.study.start(fixture.source, fixture.record.task.id, extra = true)
                val preparing = async { playback.play(id) }
                withTimeout(10_000) { player.preparing.await() }
                playback.pause()
                assertTrue(preparing.await() is TaskPlaybackOutcome.Interrupted)
                player.allowPrepare.complete(Unit)
                assertEquals(0, player.starts)
                assertNull(fixture.row(id).exposedAtMs)
                val instruction = async { playback.play(id) }
                withTimeout(10_000) { player.playingSignal.await() }
                player.completion()
                assertEquals(TaskPlaybackOutcome.Completed, instruction.await())
                player.playingSignal = CompletableDeferred()
                val focus = async { playback.play(id) }
                withTimeout(10_000) { player.playingSignal.await() }
                player.position = 50
                val staleCompletion = player.completion
                playback.pause()
                assertTrue(focus.await() is TaskPlaybackOutcome.Interrupted)
                assertNotNull(fixture.row(id).exposedAtMs)
                val seeking = async { playback.play(id) }
                withTimeout(10_000) { player.seeking.await() }
                playback.pause()
                assertTrue(seeking.await() is TaskPlaybackOutcome.Interrupted)
                player.allowSeek.complete(Unit)
                staleCompletion()
                assertEquals(2, player.starts)
                assertEquals(1, fixture.state(id).completedStages.size)
                assertEquals(50L, fixture.state(id).positionMs)
                assertTrue(fixture.db.reviews().allLog().isEmpty())
            } finally { withContext(Dispatchers.Main) { playback.close() } }
        }
    }

    private class Fixture(
        val context: Context, val db: AppDatabase, val study: ListeningTaskStudy, val source: String, val record: ListeningTaskRecord,
    ) {
        suspend fun row(id: String) = requireNotNull(db.listeningTasks().encounter(id))
        suspend fun state(id: String) = study.attempt(row(id))
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = "listening-native-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val settings = Settings(isolated).also { it.setWeekPlan("FFFFFFF") }
        val source = settings.source.value
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            val bytes = instrumentation.context.assets.open("listening-tasks/tone.ogg").use { it.readBytes() }
            val raw = instrumentation.context.assets.open("listening-tasks/task.json").bufferedReader().use { it.readText() }
            val draft = ListeningTaskContent.parse("listening-tasks/n5-task-open-window.json", raw)
            val media = TaskMedia(draft.contentHash, draft.task.stages.associate { stage ->
                stage.id to TaskAudio("task-audio/tone.ogg", ContentParser.rawSha256(bytes), 250,
                    stage.turns.mapIndexed { index, turn ->
                        ListeningTiming(turn, index * 250L / stage.turns.size, (index + 1) * 250L / stage.turns.size)
                    })
            })
            val task = draft.task.copy(media = media, approval = TaskApproval("approved", draft.contentHash,
                ListeningTaskContent.mediaHash(media), "2026-10-24", "Synthetic test declaration, not actual listening approval."))
            val record = ListeningTaskContent.parse("listening-tasks/${task.id}.json", Json.encodeToString(ListeningTaskPackage.serializer(), task))
            db.withTransaction {
                db.importListeningTasks(ParsedContent(Manifest(1, "native-fixture", emptyMap()), emptyList(), emptyList(),
                    emptyList(), emptyMap(), emptyList(), listeningTasks = ListeningTaskCatalog(records = listOf(record),
                        assets = mapOf(ContentParser.rawSha256(bytes) to bytes))), source, null)
            }
            block(Fixture(context, db, ListeningTaskStudy(db, settings, supportedTypes = setOf(ListeningTaskType.TaskBased)), source.id, record))
        } finally {
            activity.close()
            db.close()
            context.deleteSharedPreferences(preferences)
        }
    }

    private class ControlledPlayer : ListeningPlayer {
        val preparing = CompletableDeferred<Unit>()
        val allowPrepare = CompletableDeferred<Unit>()
        val seeking = CompletableDeferred<Unit>()
        val allowSeek = CompletableDeferred<Unit>()
        var playingSignal = CompletableDeferred<Unit>()
        var completion: () -> Unit = {}
        var starts = 0
        var position = 0L
        override var playing = false
        override val positionMs get() = position
        override suspend fun prepare(bytes: ByteArray) {
            position = 0
            preparing.complete(Unit)
            allowPrepare.await()
        }
        override suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit, interrupted: () -> Unit, failed: (IOException) -> Unit) {
            completion = completed
            if (positionMs > 0) { seeking.complete(Unit); allowSeek.await() }
            starts++
            playing = true
            playingSignal.complete(Unit)
        }
        override fun pause() { playing = false }
        override fun close() { playing = false }
    }
}
