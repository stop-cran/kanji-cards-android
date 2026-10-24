package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.restoreStudyRoutes
import io.github.stopcran.kanji.core.approvedTask
import io.github.stopcran.kanji.core.taskFixture
import io.github.stopcran.kanji.core.taskTone
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningTaskViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val store = ViewModelStore()
    private val source get() = services.settings.source.value.id
    private val picture get() = requireNotNull(javaClass.getResourceAsStream("/listening-guided-picture.webp")).use { it.readBytes() }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context).also { it.setWeekPlan("FFFFFFF") }, context)
    }

    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }

    private suspend fun waitUntil(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }
    private suspend fun row(id: String) = requireNotNull(db.listeningTasks().encounter(id))

    private suspend fun install(name: String = "open-window", visual: Boolean = false, word: Boolean = false): ListeningTaskRecord {
        var draft = taskFixture(name).task
        if (visual) draft = draft.copy(choiceMode = TaskChoiceMode.Visual, visual = TaskVisual("required", "Four neutral numbered alternatives."))
        if (word) {
            draft = draft.copy(vocabulary = listOf(ListeningWord("窓", "まど", "window", "窓")))
            db.content().insertWords(listOf(WordEntity(source, "窓", "まど", "Window", null, "窓", "", "# Captured window article")))
        }
        val record = approvedTask(draft, image = if (visual) picture else null)
        val assets = buildMap {
            put(ContentParser.rawSha256(taskTone()), taskTone())
            if (visual) put(ContentParser.rawSha256(picture), picture)
        }
        db.withTransaction {
            db.importListeningTasks(ParsedContent(Manifest(1, "guided-fixture", emptyMap()), emptyList(), emptyList(),
                emptyList(), emptyMap(), emptyList(), listeningTasks = ListeningTaskCatalog(records = listOf(record), assets = assets)),
                services.settings.source.value, "a".repeat(40))
        }
        return record
    }

    private suspend fun model(id: String, player: GuidedPlayer, available: Boolean = true): ListeningTaskViewModel {
        val vm = ListeningTaskViewModel(services, id, player)
        store.put(id, vm)
        vm.load()
        waitUntil { !vm.busy && (vm.ready || vm.error != null) }
        if (available) assertNull(vm.error) else assertNotNull(vm.error)
        return vm
    }

    private suspend fun complete(vm: ListeningTaskViewModel, player: GuidedPlayer, start: Int) {
        waitUntil { player.starts == start && vm.attempt?.started == true }
        player.complete()
    }

    @Test fun bothFocusedProtocolsRunAllStagesAndOneNormalReplayBeforeExactlyOneGood() = runBlocking {
        for (name in listOf("open-window", "rainy-class")) {
            val record = install(name)
            val id = services.listeningTasks.start(source, record.task.id, extra = true)
            val player = GuidedPlayer()
            val vm = model(id, player)
            assertEquals(record.task.taskType, vm.task?.taskType)
            assertNull(row(id).exposedAtMs)
            vm.evidenceVisible()
            waitUntil { row(id).exposedAtMs != null }
            assertNotNull(row(id).exposedAtMs)
            vm.play()
            vm.play()
            for (index in 1..4) {
                assertTrue(db.reviews().allLog().none { it.sessionId == id })
                complete(vm, player, index)
            }
            waitUntil { !vm.busy }
            assertEquals("answer", vm.attempt?.phase)
            vm.play(wholeReplay = true)
            for (index in 5..7) complete(vm, player, index)
            waitUntil { !vm.busy }
            vm.answer(record.task.answer)
            waitUntil { !vm.busy }
            vm.submit()
            vm.submit()
            waitUntil { !vm.busy }
            assertEquals(Grade.Good.value, vm.response?.grade)
            assertEquals(1, db.reviews().allLog().count { it.sessionId == id })
            vm.close(false) {}
            waitUntil { !vm.busy }
        }
    }

    @Test fun visibleChoicesCommitWhileGenericInstructionPlaybackRemainsHeld() = runBlocking {
        val record = install()
        val id = services.listeningTasks.start(source, record.task.id, extra = true)
        val player = GuidedPlayer()
        val vm = model(id, player)
        vm.play()
        waitUntil { player.starts == 1 && vm.attempt?.started == true }
        assertNull(row(id).exposedAtMs)
        vm.evidenceVisible()
        waitUntil { row(id).exposedAtMs != null }
        assertTrue(vm.playing)
        assertEquals(0, services.listeningTasks.attempt(row(id)).stageIndex)
        assertTrue(db.reviews().allLog().isEmpty())
        vm.pause(release = true)
        waitUntil { !vm.busy }
    }

    @Test fun exactPauseAndSourceChangeRestoreCapturedPositionAndWordBodyWithoutHelp() = runBlocking {
        val record = install(word = true, visual = true)
        val originalSource = source
        val id = services.listeningTasks.start(source, record.task.id, extra = true)
        val first = GuidedPlayer()
        val vm = model(id, first)
        assertNotNull(vm.picture)
        vm.play()
        complete(vm, first, 1)
        complete(vm, first, 2)
        waitUntil { first.starts == 3 && vm.attempt?.started == true }
        first.position = 5_000
        vm.pause(release = true)
        waitUntil { !vm.busy }
        assertNull(vm.picture)
        assertEquals(5_000L, services.listeningTasks.attempt(row(id)).positionMs)
        services.settings.setSource("https://github.com/different/source", "other")
        db.openHelper.writableDatabase.execSQL("UPDATE words SET body = ? WHERE sourceId = ?", arrayOf("Changed live article", originalSource))
        val routes = listOf("home", "listening-home", id)
        assertEquals(routes, restoreStudyRoutes(routes, sameProcess = false))
        val second = GuidedPlayer()
        val restored = model(restoreStudyRoutes(routes, sameProcess = false).last(), second)
        assertNotNull(restored.picture)
        assertEquals(originalSource, restored.encounter?.sourceId)
        assertEquals("# Captured window article", restored.vocabularyBodies["窓"])
        restored.play()
        waitUntil { second.starts == 1 && restored.attempt?.started == true }
        assertEquals(5_000L, second.positionMs)
        second.complete()
        complete(restored, second, 2)
        waitUntil { !restored.busy }
        restored.answer(record.task.answer)
        waitUntil { !restored.busy }
        restored.submit()
        waitUntil { !restored.busy }
        assertEquals(Grade.Good.value, restored.response?.grade)
        restored.close(false) {}
        waitUntil { !restored.busy }
    }

    @Test fun requiredPictureFailureCannotTurnAnExposedAttemptIntoAgain() = runBlocking {
        val record = install(visual = true)
        val id = services.listeningTasks.start(source, record.task.id, extra = true)
        services.listeningTasks.revealEvidence(id)
        val hash = requireNotNull(record.task.media?.image).sha256
        db.listeningTasks().putAssets(listOf(ListeningTaskAssetEntity(source, hash, byteArrayOf(0))))
        val vm = model(id, GuidedPlayer(), available = false)
        vm.submit(giveUp = true)
        waitUntil { !vm.busy }
        assertTrue(db.reviews().allLog().isEmpty())
        assertNotNull(services.listeningTasks.attempt(row(id)).pictureProblem)
        vm.close(true) {}
        waitUntil { !vm.busy }
        assertEquals("discarded", row(id).closeReason)
    }

    @Test fun describedStudySurvivesReloadWithoutAReservationOrScore() = runBlocking {
        val record = install(visual = true)
        val id = services.listeningTasks.start(source, record.task.id, study = true, described = true)
        val vm = model(id, GuidedPlayer())
        assertTrue(vm.attempt?.describedStudy == true)
        assertFalse(row(id).newReservation)
        vm.evidenceVisible()
        waitUntil { row(id).exposedAtMs != null }
        vm.pause(release = true)
        val restored = model(id, GuidedPlayer())
        assertTrue(restored.attempt?.describedStudy == true)
        assertEquals("study", restored.attempt?.phase)
        restored.close(false) {}
        waitUntil { !restored.busy }
        assertTrue(db.reviews().allLog().isEmpty())
        assertNotNull(row(id).exposedAtMs)
    }

    private class GuidedPlayer : ListeningPlayer {
        var starts = 0
        var position = 0L
        private var completion: () -> Unit = {}
        override var playing = false
        override val positionMs get() = position
        override suspend fun prepare(bytes: ByteArray) { position = 0 }
        override suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit,
            interrupted: () -> Unit, failed: (IOException) -> Unit) {
            position = positionMs
            completion = completed
            starts++
            playing = true
        }
        fun complete() { playing = false; completion() }
        override fun pause() { playing = false }
        override fun close() { playing = false; position = 0 }
    }
}
