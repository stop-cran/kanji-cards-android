package io.github.stopcran.kanji.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.data.StudySessionStore
import io.github.stopcran.kanji.data.StudyStart
import io.github.stopcran.kanji.data.StudyEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Orders Kanji session writes even when one route is replaced before its previous writes finish. */
class StudyWriteCoordinator {
    private var tail: Job? = null

    fun enqueue(scope: CoroutineScope, block: suspend () -> Unit): Job {
        val next = synchronized(this) {
            val previous = tail
            scope.launch(NonCancellable, start = CoroutineStart.LAZY) {
                previous?.join()
                check(previous?.isCancelled != true) { "Previous kanji write failed" }
                block()
            }.also { tail = it }
        }
        next.start()
        return next
    }

    suspend fun awaitPending() {
        val previous = synchronized(this) { tail }
        previous?.join()
        check(previous?.isCancelled != true) { "Previous kanji write failed" }
    }
}

/** Route removal, unlike leaving composition for an article or rotation, ends a session. */
class StudySessionRegistry {
    private val exits = mutableMapOf<String, () -> Unit>()
    private val captured = mutableMapOf<String, String>()

    fun register(route: String, onExit: () -> Unit) {
        check(exits.putIfAbsent(route, onExit) == null) { "Study route is already registered" }
    }

    fun markCaptured(route: String, sourceId: String) {
        check(route in exits) { "Study route is not registered" }
        captured[route] = sourceId
    }

    fun capturedSourceId(route: String): String? = captured[route]

    fun hasCaptured(route: String): Boolean = route in captured

    fun leave(route: String) {
        captured.remove(route)
        exits.remove(route)?.invoke()
    }
}

abstract class StudyViewModel(
    private val store: StudySessionStore,
    private val coordinator: StudyWriteCoordinator? = null,
) : ViewModel() {
    private var registeredRoute: String? = null
    private var registry: StudySessionRegistry? = null
    private var id: String? = null
    private var counts: () -> Pair<Int, Int> = { 0 to 0 }
    private var ended = false
    private var writeJob: Job? = null
    protected val reviewSessionId: String get() = checkNotNull(id) { "Study session has not started" }
    protected fun registeredCapturedSourceId(): String? =
        registeredRoute?.let { route -> registry?.capturedSourceId(route) }

    fun attachSessionRegistration(route: String, sessionRegistry: StudySessionRegistry) {
        check(registeredRoute == null && registry == null)
        registeredRoute = route
        registry = sessionRegistry
    }

    protected suspend fun <T : Any> trackSession(
        session: QuizSession<T>,
        sourceId: String,
        stack: String,
        mode: String,
        extra: Boolean,
    ): QuizSession<T> {
        currentCoroutineContext().ensureActive()
        check(id == null && !ended) { "Study session cannot be restarted" }
        val row = StudyStart(UUID.randomUUID().toString(), sourceId, stack, mode, extra, System.currentTimeMillis())
        id = row.id
        counts = { session.answered to session.correct }
        val start = persist { store.start(row) }
        start.join()
        check(!start.isCancelled) { "Could not persist study session" }
        registeredRoute?.let { registry?.markCaptured(it, sourceId) }
        return session
    }

    protected fun recordSession(block: suspend () -> Unit) {
        check(id != null && !ended) { "Cannot record outside a study session" }
        persist(block)
    }

    protected fun finishSession() = endSession("completed")

    protected suspend fun awaitSessionWrites() {
        val pending = writeJob
        pending?.join()
        check(pending?.isCancelled != true) { "Study-session write failed" }
    }

    fun leaveSession() {
        endSession("left")
        viewModelScope.cancel()
        onSessionLeft()
    }

    protected open fun onSessionLeft() = Unit

    private fun endSession(reason: String) {
        if (ended) return
        ended = true
        val sessionId = id ?: return
        val atMs = System.currentTimeMillis()
        val (answered, correct) = counts()
        persist { store.end(StudyEnd(sessionId, atMs, reason, answered, correct)) }
    }

    /** Keep the end marker behind every review, including writes still pending when the route is removed. */
    private fun persist(block: suspend () -> Unit): Job {
        val previous = writeJob
        val operation: suspend () -> Unit = {
            previous?.join()
            check(previous?.isCancelled != true) { "Previous study-session write failed" }
            block()
        }
        return (coordinator?.enqueue(viewModelScope, operation) ?: viewModelScope.launch(NonCancellable) { operation() })
            .also { writeJob = it }
    }

    override fun onCleared() {
        endSession("left")
    }
}
