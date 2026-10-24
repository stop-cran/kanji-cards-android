package io.github.stopcran.kanji.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.io.IOException

class ListeningTaskViewModel(
    private val services: Services,
    private val id: String,
    private val player: ListeningPlayer = AndroidListeningPlayer(services.context),
    private val study: ListeningTaskStudy = services.listeningTasks,
) : ViewModel() {
    var encounter: ListeningTaskEncounterEntity? by mutableStateOf(null)
        private set
    var task: ListeningTaskPackage? by mutableStateOf(null)
        private set
    var attempt: ListeningTaskAttempt? by mutableStateOf(null)
        private set
    var response: ListeningTaskAttempt? by mutableStateOf(null)
        private set
    var picture: ImageBitmap? by mutableStateOf(null)
        private set
    var vocabularyBodies: Map<String, String> by mutableStateOf(emptyMap())
        private set
    var error: String? by mutableStateOf(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    private var pending by mutableStateOf(0)
    val busy get() = pending > 0
    private val actions = Mutex()
    private var loadJob: Job? = null
    private var watcher: Job? = null
    private var playback: ListeningTaskPlayback? = null
    private var playToken = 0
    private var visibleExposureRequested = false

    fun load() {
        if (loadJob?.isActive == true || ready) return
        loadJob = action {
            error = null
            val row = requireNotNull(services.db.listeningTasks().encounter(id)) { "Listening task no longer exists" }
            encounter = row
            val captured = study.record(row).task
            require(captured.taskType in ListeningTaskPolicy.supportedTypes) { "Update the app to present this captured task type" }
            task = captured
            vocabularyBodies = Json.decodeFromString(row.vocabularyJson)
            if (row.closedAtMs != null) { accept(row); return@action }
            study.recover(id)
            picture = study.image(id)?.let { bytes ->
                when (val result = withContext(Dispatchers.Default) { SceneImages.decode(bytes) }) {
                    is SceneImageResult.Ready -> result.bitmap.asImageBitmap()
                    is SceneImageResult.Unavailable -> throw IOException(result.reason)
                }
            }
            accept(requireNotNull(services.db.listeningTasks().encounter(id)))
            ready = true
            watcher?.cancel()
            watcher = viewModelScope.launch {
                listeningAction(::failed) {
                    services.db.listeningTasks().observeEncounter(id).collect { updated ->
                        accept(requireNotNull(updated) { "Listening task no longer exists" })
                    }
                }
            }
        }
    }

    private fun accept(row: ListeningTaskEncounterEntity) {
        encounter = row
        attempt = study.attempt(row)
        response = row.responseJson?.let { Json.decodeFromString(ListeningTaskAttempt.serializer(), it) }
    }

    fun evidenceVisible() {
        if (visibleExposureRequested || !ready || encounter?.closedAtMs != null) return
        visibleExposureRequested = true
        viewModelScope.launch { listeningAction(::failed) { study.revealEvidence(id) } }
    }

    fun help() {
        pause()
        action { accept(study.revealEvidence(id, textHelp = true)) }
    }

    fun play(wholeReplay: Boolean = false, stage: Int? = null, slower: Boolean = false) {
        if (busy || playing) return
        val token = ++playToken
        playing = true
        action {
            check(ready && error == null) { "Reload the verified captured task before playing" }
            val engine = ListeningTaskPlayback(study, player)
            playback = engine
            try {
                playStages(engine, token, stage, wholeReplay, slower)
            } finally {
                engine.close()
                if (playback === engine) playback = null
                playing = false
            }
        }
    }

    private suspend fun playStages(engine: ListeningTaskPlayback, token: Int, stage: Int?, wholeReplay: Boolean, slower: Boolean) {
        var first = true
        while (token == playToken) {
            val current = requireNotNull(attempt)
            val requestedStage = stage.takeIf { first }
            val replay = first && wholeReplay
            val index = if (replay) 1 else requestedStage ?: current.stageIndex
            val outcome = engine.play(id, requestedStage, replay, slower)
            accept(requireNotNull(services.db.listeningTasks().encounter(id)))
            if (outcome is TaskPlaybackOutcome.Failed) failed(outcome.message)
            val end = stage != null || index == requireNotNull(task).stages.lastIndex
            if (outcome != TaskPlaybackOutcome.Completed || end) break
            first = false
        }
    }

    fun pause(release: Boolean = false) {
        ++playToken
        val active = playback
        if (active != null) viewModelScope.launch(Dispatchers.Main.immediate) { active.pause() }
        if (release) {
            loadJob?.cancel()
            watcher?.cancel()
            watcher = null
            picture = null
            ready = false
            visibleExposureRequested = false
        }
    }

    fun answer(option: String) {
        action { accept(study.answer(id, option)) }
    }

    fun submit(guessed: Boolean = false, giveUp: Boolean = false) {
        pause()
        action {
            check(ready && error == null) { "Resolve the technical problem before submitting" }
            accept(study.submit(id, guessed, giveUp))
        }
    }

    fun close(discard: Boolean, done: () -> Unit) {
        pause(release = true)
        action { study.close(id, discard); done() }
    }

    fun retry() {
        pause(release = true)
        load()
    }

    private fun failed(message: String) {
        pause()
        error = message
        playing = false
    }

    private fun action(block: suspend () -> Unit): Job {
        pending++
        return viewModelScope.launch {
            try { actions.withLock { listeningAction(::failed, block) } }
            finally { pending-- }
        }
    }

    override fun onCleared() {
        if (playback == null) player.close() else player.pause()
    }
}
