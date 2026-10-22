package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.approvedListening
import io.github.stopcran.kanji.core.listening.ListeningAllowance
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.PacingNote
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
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

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

    @Test fun summarySeparatesCooldownsFromContentProblems() = runBlocking {
        services.listening.close(id, discard = true)
        val home = ListeningHomeState(services.listening.overview(source).copy(
            fresh = emptyList(),
            cooldowns = mapOf("one" to Instant.EPOCH, "two" to Instant.EPOCH),
            blocked = mapOf("broken" to "Cached audio is invalid; sync again"),
        ))
        assertEquals("0 due, 0 new available, 2 on cooldown, 1 content/audio problems", home.summary())
        val ready = home.copy(overview = home.overview!!.copy(cooldowns = emptyMap(), blocked = emptyMap()))
        assertEquals("0 due, 0 new available", ready.summary())
    }

    @Test fun hintsDistinguishSharedAllowanceFromListeningCountAndTimeLimits() = runBlocking {
        services.listening.close(id, discard = true)
        val base = services.listening.overview(source).copy(fresh = emptyList())
        val shared = ListeningHomeState(base.copy(
            sharedAllowance = NewAllowance(10, 0, PacingNote.None, 0, 40),
            allowance = ListeningAllowance(0, 300_000),
        ))
        assertTrue(shared.newPassageHint()!!.contains("No shared new-item allowance"))
        val backlog = base.sharedAllowance.copy(total = 0, remaining = 0, note = PacingNote.BacklogPaused, due = 80)
        assertTrue(pacingExplanation(backlog)!!.contains("80 reviews are waiting, so new items are paused"))
        assertTrue(ListeningHomeState(base.copy(sharedAllowance = backlog)).newPassageHint()!!.contains("shared"))
        val count = ListeningHomeState(base.copy(allowance = ListeningAllowance(0, 40_000)))
        assertTrue(count.newPassageHint()!!.contains("passage limit"))
        val time = ListeningHomeState(base.copy(allowance = ListeningAllowance(1, 129_999)))
        assertTrue(time.newPassageHint()!!.contains("130 estimated seconds"))
        val waiting = ListeningHomeState(base.copy(minimumNewMs = null))
        assertTrue(waiting.newPassageHint()!!.contains("No eligible new passages"))
    }

    @Test fun cooldownLabelsRoundUpInLocalTimeAcrossDateAndDaylightSavingBoundaries() {
        val cases = listOf(
            Triple("2026-10-22T09:59:59.999Z", "Pacific/Kiritimati", "23 Oct 2026, 00:00"),
            Triple("2026-10-23T00:15:00Z", "America/Los_Angeles", "22 Oct 2026, 17:15"),
            Triple("2026-10-25T00:59:59Z", "Europe/Berlin", "25 Oct 2026, 02:00 CET"),
        )
        cases.forEach { (instant, zone, expected) ->
            val text = listeningCooldownText(Instant.parse(instant), ZoneId.of(zone), Locale.UK)
            assertTrue(text, text.startsWith("Practice only until $expected"))
            assertTrue(text, text.endsWith("(local time)"))
            assertFalse(text, Regex("\\d{2}:\\d{2}:\\d{2}").containsMatchIn(text))
        }
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
