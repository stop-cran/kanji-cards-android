package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.RepoSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncCoordinatorTest {
    private val source = requireNotNull(RepoSource.parse("https://github.com/fixture/content", "main"))

    @Test fun cancelledScreenWaiterDoesNotCancelWorkAndForcedFollowUpCannotBeCoalescedWithANoop() = runTest {
        val engine = Engine()
        val owner = SyncCoordinator(backgroundScope, engine.activity, execute = engine::execute)
        val ordinary = owner.request(source)
        assertEquals(SyncActivity.Running, owner.activity.value[source])
        assertSame(ordinary, owner.request(source))
        val running = engine.started.receive()
        val screen = launch { ordinary.await() }
        runCurrent()
        screen.cancel()
        assertFalse(ordinary.isCancelled)
        val forced = owner.request(source, SyncKind.Forced)
        assertSame(forced, owner.request(source, SyncKind.Forced))
        val launch = owner.request(source, SyncKind.Launch)
        running.result.complete(SyncResult.UpToDate)
        assertEquals(SyncResult.UpToDate, ordinary.await())
        val followUp = engine.started.receive()
        assertEquals(SyncKind.Forced, followUp.request.kind)
        assertFalse(forced.isCompleted)
        assertEquals(SyncActivity.Running, owner.activity.value[source])
        val failure = SyncResult.Failed("Keeping accepted catalog", false)
        followUp.result.complete(failure)
        assertEquals(failure, forced.await())
        val launched = engine.started.receive()
        assertEquals(SyncKind.Launch, launched.request.kind)
        launched.result.complete(SyncResult.UpToDate)
        launch.await()
        assertEquals(SyncActivity.Finished(SyncResult.UpToDate), owner.activity.value[source])
    }

    @Test fun branchReplacementIsOrderedAndCannotCoalesceAcrossAnInterveningRequest() = runTest {
        val engine = Engine()
        val owner = SyncCoordinator(backgroundScope, engine.activity, execute = engine::execute)
        val other = source.copy(branch = "other")
        val first = owner.request(source)
        val startedFirst = engine.started.receive()
        val second = owner.request(other)
        val third = owner.request(source)
        assertNotSame(first, third)
        startedFirst.result.complete(SyncResult.UpToDate)
        first.await()
        val startedSecond = engine.started.receive()
        assertEquals(other, startedSecond.request.source)
        val rejected = SyncResult.Failed("Branch unavailable", true)
        startedSecond.result.complete(rejected)
        assertEquals(rejected, second.await())
        val startedThird = engine.started.receive()
        assertEquals(source, startedThird.request.source)
        assertEquals(SyncActivity.Finished(rejected), owner.activity.value[other])
        startedThird.result.complete(SyncResult.Updated(1, 2, emptyList()))
        third.await()
        assertEquals(listOf(source, other, source), engine.calls.map { it.source })
        assertEquals(source, engine.lastAccepted)
    }

    @Test fun ownerShutdownCancelsOutstandingTicketsAndANewProcessStartsWithoutInventedActivity() = runTest {
        val engine = Engine()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val owner = SyncCoordinator(scope, engine.activity, execute = engine::execute)
        val active = owner.request(source)
        engine.started.receive()
        val queued = owner.request(source, SyncKind.Forced)
        scope.cancel()
        runCurrent()
        assertTrue(active.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(SyncActivity.Interrupted, owner.activity.value[source])
        assertTrue(owner.request(source).isCancelled)
        val freshEngine = Engine()
        val fresh = SyncCoordinator(backgroundScope, freshEngine.activity, execute = freshEngine::execute)
        assertTrue(fresh.activity.value.isEmpty())
    }

    @Test fun periodicEngineResultsRemainVisibleWithoutAnOwnedRequest() = runTest {
        val engine = Engine()
        val owner = SyncCoordinator(backgroundScope, engine.activity, execute = engine::execute)
        val result = SyncResult.Updated(2, 3, listOf("Retained older task catalog"), grammar = 4, listening = 5)
        engine.activity.record(source, SyncActivity.Finished(result))
        runCurrent()
        assertEquals(SyncActivity.Finished(result), owner.activity.value[source])
    }

    @Test fun thrownRequestDoesNotCancelQueuedOrLaterRequestsAndKeepsFailureSourceScoped() = runTest {
        val engine = Engine()
        val errors = mutableListOf<Exception>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = SyncCoordinator(backgroundScope, engine.activity, reportFailure = { errors += it }) { request ->
            if (request.kind == SyncKind.Launch) {
                entered.complete(Unit)
                release.await()
                error("Launch admission failed")
            }
            engine.execute(request)
        }
        val failed = owner.request(source, SyncKind.Launch)
        entered.await()
        val other = source.copy(branch = "other")
        val queued = owner.request(other, SyncKind.Forced)
        release.complete(Unit)
        val failure = failed.await() as SyncResult.Failed
        assertEquals("Launch admission failed", failure.message)
        assertFalse(failure.retryable)
        assertEquals(listOf("Launch admission failed"), errors.map { it.message })
        val running = engine.started.receive()
        assertEquals(SyncRequest(other, SyncKind.Forced), running.request)
        assertEquals(SyncActivity.Finished(failure), owner.activity.value[source])
        running.result.complete(SyncResult.UpToDate)
        assertEquals(SyncResult.UpToDate, queued.await())
        val later = owner.request(source)
        engine.started.receive().result.complete(SyncResult.UpToDate)
        assertEquals(SyncResult.UpToDate, later.await())
        assertEquals(SyncActivity.Finished(SyncResult.UpToDate), owner.activity.value[source])
    }

    @Test fun fallbackFailureYieldsToPeriodicWorkEvenWhenItsFinalResultEqualsThePreviousResult() = runTest {
        val engine = Engine()
        engine.activity.record(source, SyncActivity.Finished(SyncResult.UpToDate))
        val owner = SyncCoordinator(backgroundScope, engine.activity) { throw java.io.IOException("offline") }
        val failure = owner.request(source).await() as SyncResult.Failed
        assertTrue(failure.retryable)
        runCurrent()
        assertEquals(SyncActivity.Finished(failure), owner.activity.value[source])
        val other = source.copy(branch = "other")
        engine.activity.record(other, SyncActivity.Finished(SyncResult.UpToDate))
        runCurrent()
        assertEquals(SyncActivity.Finished(failure), owner.activity.value[source])
        // Conflate a complete periodic attempt before the observer runs: equal results are still a new attempt.
        engine.activity.record(source, SyncActivity.Running)
        engine.activity.record(source, SyncActivity.Finished(SyncResult.UpToDate))
        runCurrent()
        assertEquals(SyncActivity.Finished(SyncResult.UpToDate), owner.activity.value[source])
    }

    private class Running(val request: SyncRequest, val result: CompletableDeferred<SyncResult> = CompletableDeferred())

    private class Engine {
        val activity = MutableStateFlow<Map<RepoSource, SyncActivityEvent>>(emptyMap())
        val started = Channel<Running>(Channel.UNLIMITED)
        val calls = mutableListOf<SyncRequest>()
        var lastAccepted: RepoSource? = null

        suspend fun execute(request: SyncRequest): SyncResult {
            calls += request
            activity.record(request.source, SyncActivity.Running)
            val running = Running(request)
            started.send(running)
            val result = running.result.await()
            if (result !is SyncResult.Failed) lastAccepted = request.source
            activity.record(request.source, SyncActivity.Finished(result))
            return result
        }
    }
}
