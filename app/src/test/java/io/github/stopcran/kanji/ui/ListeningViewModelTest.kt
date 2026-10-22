package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.approvedListening
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
import org.robolectric.annotation.SQLiteMode
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ListeningViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val store = ViewModelStore()
    private val fixture = approvedListening()
    private lateinit var id: String
    private val source get() = services.settings.source.value.id

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        services.settings.setWeekPlan("FFFFFFF")
        val audio = fixture.passage.audio!!
        db.content().insertListening(listOf(ListeningEntity(source, "example", fixture.json, fixture.contentHash,
            audio.sha256, audio.durationMs, true, null, 0)))
        val bytes = requireNotNull(javaClass.getResourceAsStream("/listening-test-tone.ogg")).use { it.readBytes() }
        db.content().insertListeningAudio(listOf(ListeningAudioEntity(source, audio.sha256, bytes)))
        id = services.listening.start(source, "example")
    }

    @After fun cleanup() { store.clear(); db.close(); Dispatchers.resetMain() }

    private suspend fun waitUntil(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

    private suspend fun model(player: FakePlayer): ListeningViewModel {
        val vm = ListeningViewModel(services, id, player)
        store.put(id, vm)
        vm.load()
        waitUntil { vm.encounter != null && !vm.busy }
        assertNull(vm.error)
        return vm
    }

    @Test fun interruptionAndRecreationResumeTheSameUnrevealedAttemptWithoutAnExtraReplay() = runBlocking {
        val player = FakePlayer()
        val vm = model(player)
        assertEquals("listen", vm.attempt!!.phase)
        vm.play()
        waitUntil { vm.playing && !vm.busy }
        val staleCompletion = player.completed
        player.positionMs = 4_321
        vm.pause(release = true)
        waitUntil { !vm.busy }
        staleCompletion()
        assertEquals("listen", vm.attempt!!.phase)
        val oldOrder = vm.attempt!!.optionOrder
        val replacement = FakePlayer()
        val resumed = model(replacement)
        assertEquals(4_321L, resumed.positionMs)
        assertEquals(oldOrder, resumed.attempt!!.optionOrder)
        resumed.play()
        waitUntil { resumed.playing && !resumed.busy }
        assertEquals(4_321L, replacement.positionMs)
        assertEquals(1, resumed.attempt!!.starts)
        replacement.finish()
        waitUntil { resumed.attempt?.phase == "questions" && !resumed.busy }
        for (question in fixture.passage.questions) {
            resumed.answer(question.id, question.answer)
            waitUntil { !resumed.busy }
        }
        resumed.submit()
        waitUntil { resumed.response != null && !resumed.busy }
        assertEquals(Grade.Good.value, resumed.response!!.grade)
        assertEquals(1, db.reviews().allLog().size)
    }

    @Test fun decodeFailureCannotUnlockQuestionsOrProduceAnAgain() = runBlocking {
        val player = FakePlayer().also { it.prepareError = true }
        val vm = model(player)
        vm.play()
        waitUntil { vm.error != null && !vm.busy }
        waitUntil { db.listening().passage(source, "example")?.ready == false }
        assertEquals("listen", vm.attempt!!.phase)
        assertFalse(db.listening().hasExposure(source, "example"))
        vm.submit(giveUp = true)
        waitUntil { !vm.busy }
        assertTrue(db.reviews().allLog().isEmpty())
        player.prepareError = false
        vm.retry()
        waitUntil { vm.error == null && !vm.busy }
        vm.play(slower = true)
        waitUntil { vm.playing && !vm.busy }
        player.finish()
        waitUntil { vm.attempt?.phase == "questions" && !vm.busy }
        assertTrue(db.listening().passage(source, "example")!!.ready)
        for (question in fixture.passage.questions) {
            vm.answer(question.id, question.answer)
            waitUntil { !vm.busy }
        }
        vm.submit()
        waitUntil { vm.response != null && !vm.busy }
        assertEquals(Grade.Hard.value, vm.response!!.grade)
        services.listening.close(id)
        assertNotEquals(id, services.listening.start(source, "example", study = true))
    }

    private class FakePlayer : ListeningPlayer {
        override var playing = false
        override var positionMs = 0L
        var prepareError = false
        var completed: () -> Unit = {}
        override suspend fun prepare(bytes: ByteArray) { if (prepareError) throw IOException("Synthetic decode failure") }
        override suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit, interrupted: () -> Unit, failed: (IOException) -> Unit) {
            this.positionMs = positionMs
            this.completed = completed
            playing = true
        }
        fun finish() { playing = false; positionMs = 20_000; completed() }
        override fun pause() { playing = false }
        override fun close() { playing = false }
    }
}
