package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.listening.ListeningAttempt
import io.github.stopcran.kanji.core.listening.ListeningPassage
import io.github.stopcran.kanji.core.listening.ListeningTiming
import io.github.stopcran.kanji.core.srs.PacingNote
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

internal data class ListeningHomeState(val overview: ListeningOverview? = null, val error: String? = null) {
    fun summary(): String = overview?.let {
        if (it.active != null) "Unfinished encounter"
        else if (it.passages.isEmpty()) "No recordings yet"
        else buildList {
            add(if (it.due.isEmpty() && it.fresh.isEmpty()) "All caught up" else "${it.due.size} due, ${it.fresh.size} new")
            if (it.blocked.isNotEmpty()) add("${it.blocked.size} content/audio problems")
        }.joinToString(", ")
    } ?: error ?: "Loading..."

    fun newPassageHint(): String? = overview?.let {
        when {
            it.fresh.isNotEmpty() -> null
            it.sharedAllowance.remaining == 0 -> if (
                it.sharedAllowance.total == 0 &&
                (it.sharedAllowance.note == PacingNote.None || it.sharedAllowance.note == PacingNote.LightDay)
            ) {
                "Scheduled new items are turned off by your daily limit in Settings."
            } else {
                "No shared new-item allowance remains today. New listening shares this limit with other learning modes."
            }
            it.allowance.count == 0 -> "Today's planned new-listening passage limit has been reached."
            it.minimumNewMs == null ->
                "No eligible new passages right now. Started passages return to the scheduled queue when due; cooldowns are listed below."
            it.minimumNewMs > it.allowance.milliseconds ->
                "No new passage fits the remaining listening time. " +
                    "The shortest eligible passage needs ${(it.minimumNewMs + 999) / 1_000} estimated seconds."
            else -> null
        }
    }
}

internal fun listeningCooldownText(
    until: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(),
): String {
    val minute = until.truncatedTo(ChronoUnit.MINUTES)
    // Never advertise an unlock before the exact deadline.
    val rounded = if (minute.isBefore(until)) minute.plus(1, ChronoUnit.MINUTES) else minute
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
    return "Practice only until ${formatter.format(rounded)}"
}

internal suspend fun listeningAction(onError: (String) -> Unit, action: suspend () -> Unit) {
    fun report(e: Exception) {
        Log.e("Listening", "Listening operation failed", e)
        onError(e.message ?: e.javaClass.simpleName)
    }
    try { action() }
    catch (e: CancellationException) { throw e }
    catch (e: IOException) { report(e) }
    catch (e: IllegalArgumentException) { report(e) }
    catch (e: IllegalStateException) { report(e) }
    catch (e: SQLiteException) { report(e) }
}

class ListeningViewModel(
    private val app: Services,
    private val id: String,
    private val player: ListeningPlayer = AndroidListeningPlayer(app.context),
) : ViewModel() {
    var encounter: ListeningEncounterEntity? by mutableStateOf(null)
        private set
    var passage: ListeningPassage? by mutableStateOf(null)
        private set
    var attempt: ListeningAttempt? by mutableStateOf(null)
        private set
    var response: ListeningAttempt? by mutableStateOf(null)
        private set
    var picture: ImageBitmap? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    private var pending by mutableStateOf(0)
    val busy get() = pending > 0
    private var bytes: ByteArray? = null
    private var observer: Job? = null
    private var watcher: Job? = null
    private var ticker: Job? = null
    private var playJob: Job? = null
    private var preparing = false
    private var token = 0
    private val actions = Mutex()

    fun load() {
        if (observer != null) return
        observer = action {
            val row = requireNotNull(app.db.listening().encounter(id)) { "Listening encounter no longer exists" }
            passage = app.listening.record(row).passage
            if (row.closedAtMs != null) { accept(row); return@action }
            bytes = app.listening.audio(id)
            picture = row.imageBytes?.let { image ->
                when (val decoded = withContext(Dispatchers.Default) { SceneImages.decode(image) }) {
                    is SceneImageResult.Ready -> decoded.bitmap.asImageBitmap()
                    is SceneImageResult.Unavailable -> throw IOException(decoded.reason)
                }
            }
            accept(if (row.kind == "study" || row.reviewLogId != null) app.listening.revealStudy(id) else row)
            watcher?.cancel()
            watcher = viewModelScope.launch {
                listeningAction(::failed) {
                    app.db.listening().observeEncounter(id).collect { updated ->
                        accept(requireNotNull(updated) { "Listening encounter no longer exists" })
                    }
                }
            }
        }
    }

    private fun accept(row: ListeningEncounterEntity) {
        encounter = row
        attempt = app.listening.attempt(row)
        response = app.listening.response(row)
        if (!playing) positionMs = requireNotNull(attempt).positionMs
    }

    fun play(restart: Boolean = false, slower: Boolean = false, turnId: String? = null) {
        val currentToken = ++token
        playJob = action {
            val row = requireNotNull(encounter)
            val state = requireNotNull(attempt)
            check(row.closedAtMs == null) { "This encounter is complete" }
            require(turnId == null || row.kind == "study" || row.reviewLogId != null) { "Turn replay is available after answering" }
            val timing = turnId?.let { target -> requireNotNull(passage!!.audio!!.turns.find { it.id == target }) }
            val from = timing?.startMs ?: if (restart) 0 else state.positionMs
            if (!prepareAndPlay(from, slower, timing, currentToken)) return@action
            playing = true
            positionMs = from
            accept(app.listening.playbackStarted(id, restart, slower))
            if (currentToken != token) return@action
            trackPosition(currentToken, timing)
        }
    }

    private suspend fun prepareAndPlay(from: Long, slower: Boolean, timing: ListeningTiming?, currentToken: Int): Boolean {
        preparing = true
        return try {
            player.prepare(requireNotNull(bytes) { "Reload the verified recording first" })
            if (currentToken != token) return false
            player.play(from, if (slower) 0.85f else 1f,
                completed = { playbackCompleted(currentToken, timing) },
                interrupted = { if (currentToken == token) pause() },
                failed = { if (currentToken == token) mediaFailed(it.message ?: "Playback failed") },
            )
            true
        } catch (e: IOException) {
            mediaFailed(e.message ?: "Audio could not be prepared")
            false
        } finally { preparing = false }
    }

    private fun playbackCompleted(currentToken: Int, timing: ListeningTiming?) {
        if (currentToken != token) return
        ticker?.cancel()
        playing = false
        action {
            if (timing == null) accept(app.listening.playbackCompleted(id))
            else accept(app.listening.position(id, timing.endMs))
        }
    }

    private fun trackPosition(currentToken: Int, timing: ListeningTiming?) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            listeningAction(::failed) {
                var ticks = 0
                while (playing && currentToken == token) {
                    positionMs = player.positionMs
                    if (timing != null && positionMs >= timing.endMs) pause()
                    else if (++ticks % 5 == 0) app.listening.position(id, positionMs)
                    delay(200)
                }
            }
        }
    }

    fun pause(release: Boolean = false) {
        ++token
        ticker?.cancel()
        if (preparing) playJob?.cancel()
        try {
            if (player.playing) {
                val position = player.positionMs
                player.pause()
                playing = false
                positionMs = position
                action { accept(app.listening.position(id, position)) }
            }
            if (release) {
                player.close()
                observer?.cancel()
                watcher?.cancel()
                observer = null
                bytes = null
                picture = null
            }
        } catch (e: IllegalStateException) { failed(e.message ?: "Playback interruption failed") }
    }

    fun answer(question: String, option: String) {
        action { accept(app.listening.answer(id, question, option)) }
    }

    fun submit(guessed: Boolean = false, giveUp: Boolean = false) {
        pause()
        action {
            check(error == null && bytes != null) { "Resolve the playback error before submitting" }
            accept(app.listening.submit(id, guessed, giveUp))
        }
    }

    fun close(discard: Boolean, done: () -> Unit) {
        pause(release = true)
        action { app.listening.close(id, discard); done() }
    }

    fun retry() {
        pause(release = true)
        error = null
        observer = null
        load()
    }

    private fun failed(message: String) {
        ++token
        ticker?.cancel()
        player.close()
        playing = false
        error = message
    }

    private fun mediaFailed(message: String) {
        failed(message)
        encounter?.let { row ->
            viewModelScope.launch {
                listeningAction(::failed) {
                    app.listening.playbackFailed(row.id)
                }
            }
        }
    }

    private fun action(block: suspend () -> Unit): Job {
        pending++
        return viewModelScope.launch {
            try { actions.withLock { listeningAction(::failed, block) } }
            finally { pending-- }
        }
    }

    override fun onCleared() { player.close() }
}
