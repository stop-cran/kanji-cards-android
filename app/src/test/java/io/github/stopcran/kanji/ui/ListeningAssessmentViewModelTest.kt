package io.github.stopcran.kanji.ui

import android.media.AudioManager
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.*
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningAssessmentViewModelTest {
    private lateinit var services: Services
    private lateinit var study: ListeningAssessmentStudy
    private val models = ViewModelStore()
    private val time = AtomicLong(1_000)

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        study = ListeningAssessmentStudy(db, services.settings, services.listeningTasks, elapsed = time::get)
        val assets = listOf(taskTone(), assessmentAsset("listening-short-tone.ogg"), assessmentAsset("listening-test-picture.webp"))
            .associateBy(ContentParser::rawSha256)
        db.withTransaction {
            val problems = db.importListeningTasks(ParsedContent(Manifest(1, "fixture-vm", emptyMap()), emptyList(), emptyList(), emptyList(),
                emptyMap(), emptyList(), listeningTasks = ListeningTaskCatalog(
                    records = ListeningAssessmentProfile.entries.flatMap { profile -> assessmentPool(profile = profile).map { it.record } }, assets = assets)),
                services.settings.source.value, "a".repeat(40)).problems
            assertTrue(problems.toString(), problems.isEmpty())
        }
    }

    @After fun cleanup() { models.clear(); services.db.close(); Dispatchers.resetMain() }
    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(10) }

    private suspend fun model(player: HeldAssessmentPlayer): Pair<String, ListeningAssessmentViewModel> {
        val id = study.start(study.preview(42))
        val vm = ListeningAssessmentViewModel(services, id, player, study)
        models.put(id, vm)
        vm.load()
        waitFor { !vm.busy && vm.ready }
        assertNull(vm.error)
        return id to vm
    }

    private suspend fun complete(vm: ListeningAssessmentViewModel, player: HeldAssessmentPlayer) {
        val before = requireNotNull(vm.snapshot)
        waitFor {
            val row = services.db.listeningTasks().encounter(before.encounter.id)!!
            player.playing && services.listeningTasks.attempt(row).started
        }
        player.complete()
        waitFor { vm.snapshot!!.state.version > before.state.version || vm.error != null }
        assertNull(vm.error)
    }

    private suspend fun expire(id: String, vm: ListeningAssessmentViewModel) {
        val before = requireNotNull(vm.snapshot)
        if (before.state.deadlineMs == null) {
            vm.evidenceVisible(before.encounter.id, before.state.generation, before.state.phase,
                textHelp = before.state.phase == AssessmentPhase.ExampleFeedback,
                explanation = before.state.phase == AssessmentPhase.ExampleFeedback)
            waitFor { vm.snapshot!!.state.deadlineMs != null || vm.error != null }
        }
        assertNull(vm.error)
        val timed = requireNotNull(vm.snapshot)
        time.set(requireNotNull(timed.state.deadlineMs))
        val after = study.pulse(id, timed.state.generation)
        waitFor { vm.snapshot!!.state.version >= after.state.version }
    }

    @Test fun profileChangeRebuildsPreviewAndDoesNotCarryFamiliarConsentAcrossLevels() = runBlocking {
        services.settings.setListeningScope(services.settings.source.value.id, ListeningScope.N4)
        val vm = ListeningAssessmentPreviewModel(services)
        models.put("preview", vm)
        vm.refresh(includeFamiliar = true)
        waitFor { !vm.busy && vm.preview != null }
        assertTrue(vm.familiar)
        assertEquals(ListeningAssessmentProfile.N5, vm.preview!!.profile)
        vm.refresh(profile = ListeningAssessmentProfile.N4)
        waitFor { !vm.busy && vm.preview?.profile == ListeningAssessmentProfile.N4 }
        assertNull(vm.error)
        assertFalse(vm.familiar)
        assertFalse(vm.preview!!.includeFamiliar)
        assertTrue(vm.preview!!.selection.available)
        assertEquals(32, vm.preview!!.selection.items.size)
        assertTrue(vm.preview!!.selection.items.all { it.record.task.jlpt == 4 })
        vm.leave()
    }

    @Test fun fullWrapperPlaysExactlyRequiredStagesWithoutGuidedGradingOrInstructionCredit() = runBlocking {
        val player = HeldAssessmentPlayer()
        val (id, vm) = model(player)
        vm.begin()
        waitFor { !vm.busy && vm.snapshot!!.state.phase != AssessmentPhase.Ready }
        withTimeout(60_000) {
            while (vm.snapshot!!.result == null) {
                waitFor { !vm.busy && vm.ready }
                when (vm.snapshot!!.state.phase) {
                    AssessmentPhase.Instruction, AssessmentPhase.Audio -> complete(vm, player)
                    AssessmentPhase.Preview, AssessmentPhase.ExampleFeedback -> expire(id, vm)
                    AssessmentPhase.Answer -> {
                        vm.answer(vm.snapshot!!.record.task.answer)
                        waitFor { !vm.busy }
                        expire(id, vm)
                    }
                    else -> error("Unexpected wrapper phase")
                }
            }
        }
        assertEquals(101, player.starts)
        assertEquals(24, vm.snapshot!!.result!!.correct)
        assertTrue(vm.snapshot!!.result!!.completedFullForm)
        assertTrue(services.db.reviews().allLog().isEmpty())
        assertTrue(services.db.reviews().allStates().isEmpty())
        vm.close({})
        waitFor { !vm.busy }
    }

    @Test fun pendingPreparationPauseNeverStartsLateAndLoadDoesNotAutoResume() = runBlocking {
        val player = HeldAssessmentPlayer(holdPreparation = true)
        val (id, vm) = model(player)
        val before = requireNotNull(vm.snapshot)
        vm.begin()
        player.preparing.await()
        vm.pause("App backgrounded", leave = true)
        waitFor { !vm.busy && !vm.playing }
        assertTrue(study.snapshot(id).state.interrupted)
        assertEquals(0, player.starts)
        assertTrue(services.db.listeningTasks().allExposures().isEmpty())
        player.preparation.complete(Unit)
        vm.load()
        waitFor { !vm.busy && vm.ready }
        assertTrue(vm.snapshot!!.state.paused)
        assertEquals(0, player.starts)
        vm.continuePractice()
        waitFor { player.starts == 1 }
        complete(vm, player)
        assertEquals(AssessmentPhase.Preview, vm.snapshot!!.state.phase)
        assertTrue(services.db.listeningTasks().allExposures().isEmpty())
        vm.evidenceVisible(before.encounter.id, before.state.generation, before.state.phase)
        delay(100)
        assertTrue(services.db.listeningTasks().allExposures().isEmpty())
        val rendered = requireNotNull(vm.snapshot)
        vm.evidenceVisible(rendered.encounter.id, rendered.state.generation, rendered.state.phase)
        waitFor { services.db.listeningTasks().allExposures().size == 1 }
        vm.end()
        waitFor { !vm.busy && vm.snapshot!!.result != null }
        assertEquals(24, vm.snapshot!!.result!!.notAssessed)
        vm.close({})
        waitFor { !vm.busy }
    }

    @Test fun focusLossInSilentAnswerWindowFreezesTimeAndRequiresExplicitInterruptedContinuation() = runBlocking {
        val player = HeldAssessmentPlayer()
        val (id, vm) = model(player)
        vm.begin()
        waitFor { !vm.busy }
        complete(vm, player)
        expire(id, vm)
        repeat(3) { complete(vm, player) }
        assertEquals(AssessmentPhase.Answer, vm.snapshot!!.state.phase)
        val deadline = vm.snapshot!!.state.deadlineMs!!
        time.addAndGet(2_000)
        val manager = services.context.getSystemService(AudioManager::class.java)
        val listener = shadowOf(manager).lastAudioFocusRequest.listener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        waitFor { !vm.busy && vm.snapshot!!.state.paused }
        assertEquals(deadline - time.get(), vm.snapshot!!.state.remainingMs)
        val starts = player.starts
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(vm.snapshot!!.state.paused)
        assertEquals(starts, player.starts)
        vm.continuePractice()
        waitFor { !vm.busy }
        assertTrue(vm.snapshot!!.state.interrupted)
        assertEquals(deadline, vm.snapshot!!.state.deadlineMs)
        vm.end()
        waitFor { !vm.busy && vm.snapshot!!.result != null }
        assertEquals(0, vm.snapshot!!.result!!.assessed)
        assertEquals(24, vm.snapshot!!.result!!.notAssessed)
        vm.close({})
        waitFor { !vm.busy }
    }

    private class HeldAssessmentPlayer(holdPreparation: Boolean = false) : ListeningPlayer {
        val preparing = CompletableDeferred<Unit>()
        val preparation = CompletableDeferred<Unit>().also { if (!holdPreparation) it.complete(Unit) }
        var starts = 0
        private var completion: () -> Unit = {}
        override var playing = false
        override val positionMs get() = 0L
        override suspend fun prepare(bytes: ByteArray) { preparing.complete(Unit); preparation.await() }
        override suspend fun play(positionMs: Long, speed: Float, completed: () -> Unit,
            interrupted: () -> Unit, failed: (IOException) -> Unit) {
            starts++
            playing = true
            completion = completed
        }
        fun complete() { playing = false; completion() }
        override fun pause() { playing = false }
        override fun close() { playing = false }
    }
}
