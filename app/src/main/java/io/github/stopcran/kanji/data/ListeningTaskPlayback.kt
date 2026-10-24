package io.github.stopcran.kanji.data

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface TaskPlaybackOutcome {
    data object Completed : TaskPlaybackOutcome
    data class Interrupted(val positionMs: Long) : TaskPlaybackOutcome
    data class Failed(val message: String) : TaskPlaybackOutcome
}

/** One native stage at a time. C/D/F own presentation/sequence decisions, never completion credit. */
class ListeningTaskPlayback(private val study: ListeningTaskStudy, private val player: ListeningPlayer) : AutoCloseable {
    private var active: CompletableDeferred<TaskPlaybackOutcome>? = null
    private var closed = false

    suspend fun play(
        id: String, stage: Int? = null, wholeReplay: Boolean = false, slower: Boolean = false, recoveryReplay: Boolean = false,
    ): TaskPlaybackOutcome = withContext(Dispatchers.Main.immediate) {
        check(!closed && active == null) { "Listening player is closed or already active" }
        val outcome = CompletableDeferred<TaskPlaybackOutcome>()
        active = outcome
        try {
            val requested = study.request(id, stage, wholeReplay, slower, recoveryReplay)
            val state = study.attempt(requested)
            try {
                val result = runStage(id, state.generation, state.positionMs, slower, outcome)
                when (result) {
                    TaskPlaybackOutcome.Completed -> study.completed(id, state.generation)
                    is TaskPlaybackOutcome.Interrupted -> study.interrupt(id, state.generation, result.positionMs)
                    is TaskPlaybackOutcome.Failed -> study.failed(id, state.generation, result.message)
                }
                result
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val position = player.positionMs
                    player.pause()
                    study.interrupt(id, state.generation, position)
                }
                throw e
            } catch (e: IOException) {
                fail(id, state.generation, e)
            } catch (e: IllegalArgumentException) {
                fail(id, state.generation, e)
            } catch (e: IllegalStateException) {
                fail(id, state.generation, e)
            }
        } finally {
            player.close()
            active = null
        }
    }

    private suspend fun runStage(
        id: String, generation: Long, position: Long, slower: Boolean, outcome: CompletableDeferred<TaskPlaybackOutcome>,
    ): TaskPlaybackOutcome = coroutineScope {
        val operation = async {
            if (!outcome.isCompleted) player.prepare(study.stageAudio(id, generation))
            if (!outcome.isCompleted) {
                player.play(position, if (slower) 0.8f else 1f,
                    completed = { outcome.complete(TaskPlaybackOutcome.Completed) },
                    interrupted = { outcome.complete(TaskPlaybackOutcome.Interrupted(player.positionMs)) },
                    failed = { outcome.complete(TaskPlaybackOutcome.Failed(it.message ?: "Task audio playback failed")) })
                // play returns only after the native start, including any pending seek.
                withContext(NonCancellable) { study.started(id, generation) }
            }
            outcome.await()
        }
        val result = select {
            operation.onAwait { it }
            outcome.onAwait {
                if (it == TaskPlaybackOutcome.Completed) operation.await() else it
            }
        }
        operation.cancelAndJoin()
        result
    }

    private suspend fun fail(id: String, generation: Long, error: Exception): TaskPlaybackOutcome.Failed {
        val reason = error.message ?: "Task audio playback failed"
        study.failed(id, generation, reason)
        return TaskPlaybackOutcome.Failed(reason)
    }

    suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        val position = player.positionMs
        player.pause()
        active?.complete(TaskPlaybackOutcome.Interrupted(position))
    }

    @MainThread
    override fun close() {
        val position = player.positionMs
        player.pause()
        active?.complete(TaskPlaybackOutcome.Interrupted(position))
        closed = true
        player.close()
    }
}
