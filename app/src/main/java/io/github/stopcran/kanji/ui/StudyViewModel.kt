package io.github.stopcran.kanji.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.data.StudySessionEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Route removal, unlike leaving composition for an article or rotation, ends a session. */
class StudySessionRegistry {
    private val exits = mutableMapOf<String, () -> Unit>()

    fun register(route: String, onExit: () -> Unit) {
        check(exits.putIfAbsent(route, onExit) == null) { "Study route is already registered" }
    }

    fun leave(route: String) {
        exits.remove(route)?.invoke()
    }
}

abstract class StudyViewModel(private val services: Services) : ViewModel() {
    private var id: String? = null
    private var counts: () -> Pair<Int, Int> = { 0 to 0 }
    private var ended = false
    private var writeJob: Job? = null
    protected val reviewSessionId: String get() = checkNotNull(id) { "Study session has not started" }

    protected suspend fun <T : Any> trackSession(
        session: QuizSession<T>,
        sourceId: String,
        stack: String,
        mode: String,
        extra: Boolean,
    ): QuizSession<T> {
        currentCoroutineContext().ensureActive()
        check(id == null && !ended) { "Study session cannot be restarted" }
        val row = StudySessionEntity(UUID.randomUUID().toString(), sourceId, stack, mode, extra, System.currentTimeMillis())
        id = row.id
        counts = { session.answered to session.correct }
        val start = persist { services.db.reviews().insertSession(row) }
        start.join()
        check(!start.isCancelled) { "Could not persist study session" }
        return session
    }

    protected fun recordSession(block: suspend () -> Unit) {
        check(id != null && !ended) { "Cannot record outside a study session" }
        persist(block)
    }

    protected fun finishSession() = endSession("completed")

    fun leaveSession() {
        endSession("left")
        viewModelScope.cancel()
    }

    private fun endSession(reason: String) {
        if (ended) return
        ended = true
        val sessionId = id ?: return
        val atMs = System.currentTimeMillis()
        val (answered, correct) = counts()
        persist {
            check(services.db.reviews().endSession(sessionId, atMs, reason, answered, correct) == 1) {
                "Could not close study session"
            }
        }
    }

    /** Keep the end marker behind every review, including writes still pending when the route is removed. */
    private fun persist(block: suspend () -> Unit): Job {
        val previous = writeJob
        return viewModelScope.launch(NonCancellable) {
            previous?.join()
            check(previous?.isCancelled != true) { "Previous study-session write failed" }
            block()
        }.also { writeJob = it }
    }

    override fun onCleared() {
        endSession("left")
    }
}
