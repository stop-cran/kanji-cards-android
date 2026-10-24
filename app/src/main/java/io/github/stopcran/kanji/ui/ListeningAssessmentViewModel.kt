package io.github.stopcran.kanji.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException

internal class ListeningAssessmentViewModel(
    private val services: Services, private val id: String,
    player: ListeningPlayer? = null,
    private val study: ListeningAssessmentStudy = services.listeningAssessments,
) : ViewModel() {
    private val focus = ListeningAudioFocus(services.context)
    private val player = player ?: AndroidListeningPlayer(services.context, focus)
    var snapshot: ListeningAssessmentSnapshot? by mutableStateOf(null)
        private set
    var picture: ImageBitmap? by mutableStateOf(null)
        private set
    var detail: ListeningTaskEncounterEntity? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var help by mutableStateOf(false)
        private set
    private var controls by mutableStateOf(0)
    val busy get() = controls > 0
    val displayRow get() = detail ?: snapshot?.encounter
    val displayTask get() = detail?.let { services.listeningTasks.record(it).task } ?: snapshot?.record?.task
    val words: Map<String, String> get() = displayRow?.let { Json.decodeFromString<Map<String, String>>(it.vocabularyJson) }.orEmpty()
    private val actions = Mutex()
    private var foreground = false
    private var restored = false
    private var imageId: String? = null
    private var detailIndex: Int? = null
    private var visibleKey: String? = null
    private var imageJob: Job? = null
    private var watcher: Job? = null
    private var runner: Job? = null
    private var ticker: Job? = null
    private var playback: ListeningTaskPlayback? = null
    private var sequence = 0

    fun load() {
        foreground = true
        control {
            if (!restored) { accept(study.restore(id)); restored = true } else accept(study.snapshot(id))
            if (watcher == null) watcher = viewModelScope.launch {
                listeningAction(::fail) {
                    services.db.listeningTasks().observeAssessment(id).collect { accept(study.snapshot(id)) }
                }
            }
        }
    }

    private fun accept(value: ListeningAssessmentSnapshot) {
        if (value.state.version < (snapshot?.state?.version ?: -1)) return
        if (snapshot?.encounter?.id != value.encounter.id) help = false
        if (value.result != null) focus.endSection()
        snapshot = value
        if (value.row.closedAtMs != null || value.result != null && detail == null) {
            imageJob?.cancel()
            imageId = null
            picture = null
            ready = true
            return
        }
        loadPicture()
        kick()
    }

    private fun loadPicture() {
        val row = displayRow ?: return
        if (!foreground || imageId == row.id) return
        imageJob?.cancel()
        imageId = row.id
        ready = false
        picture = null
        imageJob = viewModelScope.launch {
            listeningAction(::fail) {
                val decoded = services.listeningTasks.image(row.id)?.let { bytes ->
                    when (val result = withContext(Dispatchers.Default) { SceneImages.decode(bytes) }) {
                        is SceneImageResult.Ready -> result.bitmap.asImageBitmap()
                        is SceneImageResult.Unavailable -> throw IOException(result.reason)
                    }
                }
                if (foreground && imageId == row.id) { picture = decoded; ready = true; kick() }
            }
        }
    }

    private fun kick() {
        val current = snapshot ?: return
        if (!foreground || busy || !ready) return
        if (error != null || detail != null) return
        if (current.row.closedAtMs != null || current.state.paused) return
        if (current.state.phase in setOf(AssessmentPhase.Instruction, AssessmentPhase.Audio) && !playing) playRequired(current)
        if (current.state.deadlineMs != null && ticker?.isActive != true) startTimer()
    }

    private fun hasTimer(): Boolean = snapshot?.state?.let { !it.paused && it.deadlineMs != null } == true

    private fun startTimer() {
        ticker = viewModelScope.launch {
            listeningAction(::fail) {
                while (foreground && hasTimer()) {
                    val state = requireNotNull(snapshot).state
                    delay(minOf(250L, (state.remainingMs ?: 250L).coerceAtLeast(1)))
                    accept(study.pulse(id, state.generation))
                }
            }
        }
    }

    private fun playRequired(current: ListeningAssessmentSnapshot) {
        val token = sequence
        play(current.encounter.id) { engine ->
            val outcome = engine.play(current.encounter.id)
            if (token != sequence) return@play
            when (outcome) {
                TaskPlaybackOutcome.Completed -> accept(study.audioCompleted(id, current.state.generation, current.state.stageIndex))
                is TaskPlaybackOutcome.Interrupted -> accept(study.interrupt(id, "Audio focus, headphones or playback interruption"))
                is TaskPlaybackOutcome.Failed -> fail(outcome.message)
            }
        }
    }

    private fun play(encounterId: String, operation: suspend (ListeningTaskPlayback) -> Unit) {
        check(displayRow?.id == encounterId) { "Captured playback target changed" }
        playing = true
        runner = viewModelScope.launch {
            val engine = ListeningTaskPlayback(services.listeningTasks, player)
            playback = engine
            try { listeningAction(::fail) { operation(engine) } }
            finally { engine.close(); playback = null; playing = false; kick() }
        }
    }

    fun evidenceVisible(
        encounterId: String, generation: Long, phase: AssessmentPhase, textHelp: Boolean = false, explanation: Boolean = false,
    ) {
        val current = snapshot ?: return
        if (!foreground || !ready || current.row.closedAtMs != null) return
        if (displayRow?.id != encounterId || current.state.generation != generation || current.state.phase != phase) return
        val key = "$encounterId:$generation:$phase:$textHelp:$explanation"
        if (visibleKey == key) return
        visibleKey = key
        viewModelScope.launch {
            listeningAction(::fail) {
                val index = detailIndex
                if (index != null) study.feedbackItem(id, index, visible = true)
                else accept(study.visible(id, current.state.generation, textHelp, explanation))
            }
        }
    }

    private fun acquireFocus() { focus.startSection { pause("Audio focus loss or call") } }
    fun begin() {
        if (busy) return
        control { acquireFocus(); accept(study.begin(id)) }
    }
    fun answer(option: String?) {
        val generation = snapshot?.state?.generation ?: return
        control { accept(study.answer(id, generation, option)) }
    }
    fun continuePractice() {
        if (busy) return
        control { error = null; help = false; acquireFocus(); accept(study.continuePractice(id)) }
    }
    fun showHelp() = stop("Text help") {
        help = true
        snapshot?.let { accept(study.visible(id, it.state.generation, help = true)) }
    }
    fun pause(reason: String = "User pause", leave: Boolean = false) {
        if (leave) foreground = false
        stop(reason) {
            if (leave) {
                watcher?.cancelAndJoin()
                watcher = null
                imageJob?.cancelAndJoin()
                imageId = null
                picture = null
                ready = false
            }
        }
    }
    fun retry() = stop("Evidence recovery") {
        error = null
        imageJob?.cancelAndJoin()
        imageId = null
        accept(study.snapshot(id))
    }
    fun replay(stage: Int, slower: Boolean = false) = stop("Manual replay or slower playback") {
        val row = requireNotNull(displayRow)
        play(row.id) { engine ->
            val outcome = engine.play(row.id, stage = stage, slower = slower, recoveryReplay = true)
            if (outcome is TaskPlaybackOutcome.Failed) fail(outcome.message)
        }
    }
    fun showDetail(index: Int) = stop("Opening feedback") {
        error = null
        detail = study.feedbackItem(id, index)
        detailIndex = index
        loadPicture()
    }
    fun hideDetail() = stop("Leaving feedback") {
        detail = null
        detailIndex = null
        loadPicture()
    }
    fun end() = stop("Explicit partial ending") { accept(study.end(id)) }
    fun close(done: () -> Unit, discardUnreadable: Boolean = false) = stop(if (discardUnreadable) null else "Closing assessment") {
        study.close(id, discardUnreadable)
        done()
    }

    private fun stop(reason: String?, operation: suspend () -> Unit) {
        ++sequence
        val previous = runner
        playback?.let { engine -> viewModelScope.launch(Dispatchers.Main.immediate) { engine.pause() } }
        previous?.cancel()
        ticker?.cancel()
        control {
            previous?.join()
            focus.endSection()
            if (snapshot != null && reason != null) accept(study.interrupt(id, reason))
            operation()
        }
    }

    private fun fail(message: String) {
        error = message
        stop("Technical failure: $message") { }
    }

    private fun control(operation: suspend () -> Unit) {
        controls++
        viewModelScope.launch {
            try { actions.withLock { listeningAction(::controlFailed, operation) } }
            finally { controls--; kick() }
        }
    }

    private fun controlFailed(message: String) {
        error = message
        ++sequence
        runner?.cancel()
        ticker?.cancel()
        focus.endSection()
        viewModelScope.launch {
            listeningAction({ error = it }) { if (snapshot != null) accept(study.interrupt(id, "Operation failed: $message")) }
        }
    }

    override fun onCleared() {
        if (playback == null) player.close() else player.pause()
        focus.endSection()
    }
}
