package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterIndex
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.data.COUNTER_STACK
import io.github.stopcran.kanji.data.CounterRepository
import io.github.stopcran.kanji.core.srs.ReviewRecord
import io.github.stopcran.kanji.data.StudyEnd
import io.github.stopcran.kanji.data.StudySessionStore
import io.github.stopcran.kanji.data.StudyStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CounterBoundaryTest {
    @Test
    fun capturedSourceAndScopeSurviveLoadingAndExitWaitsForThePendingReview() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val data = FakeCounterData()
        val vm = CounterQuizViewModel(data, data)
        try {
            vm.ensureStarted(CounterMode.Expression, false, CounterGroup.Time, CounterScope.N3)
            runCurrent()
            data.sourceId = "second/repo"
            data.loaded.complete(Unit)
            runCurrent()
            assertEquals("first/repo", vm.sourceId)
            assertEquals("day-1", (vm.ui as CounterUi.ExpressionQuestion).item.id)
            vm.onDraft("一日")
            vm.submit()
            vm.next()
            runCurrent()
            vm.leaveSession()
            assertEquals(listOf("start", "pending"), data.events)
            data.allowReview.complete(Unit)
            runCurrent()
            assertEquals(listOf("start", "pending", "review", "end"), data.events)
            val record = data.records.single()
            assertEquals("first/repo", record.sourceId)
            assertEquals(COUNTER_STACK, record.stack)
            assertEquals(CounterMode.Expression.mode, record.mode)
            assertEquals(data.started.single().id, record.sessionId)
            assertEquals(StudyEnd(record.sessionId, data.ended.single().atMs, "completed", 1, 1), data.ended.single())
            assertTrue(data.requestedSources.all { it == "first/repo" })
        } finally {
            data.loaded.complete(Unit)
            data.allowReview.complete(Unit)
            vm.leaveSession()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun leavingDuringLoadDoesNotStartASessionAfterTheLoadCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val data = FakeCounterData()
        val vm = CounterQuizViewModel(data, data)
        try {
            vm.ensureStarted(CounterMode.Expression, false)
            runCurrent()
            vm.leaveSession()
            data.loaded.complete(Unit)
            runCurrent()
            assertTrue(data.started.isEmpty())
            assertTrue(data.records.isEmpty())
            assertTrue(data.ended.isEmpty())
        } finally {
            data.loaded.complete(Unit)
            data.allowReview.complete(Unit)
            vm.leaveSession()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class FakeCounterData : CounterRepository, StudySessionStore {
        override var sourceId = "first/repo"
        val loaded = CompletableDeferred<Unit>()
        val allowReview = CompletableDeferred<Unit>()
        val requestedSources = mutableListOf<String>()
        val events = mutableListOf<String>()
        val started = mutableListOf<StudyStart>()
        val ended = mutableListOf<StudyEnd>()
        val records = mutableListOf<ReviewRecord>()
        private val item = CounterExpression("day-1", "One day.", "一日", listOf("一日"), "いちにち", listOf("いちにち"), "Duration.")
        private val file = CounterFile(2, "days", "日", "にち", "Days", group = "time", level = 3, expressions = listOf(item))

        override fun practiceMinimum() = 10
        override suspend fun index(sourceId: String): CounterIndex {
            requestedSources += sourceId
            loaded.await()
            return CounterIndex(listOf(file, file.copy(id = "harder", level = 2, expressions = listOf(item.copy(id = "harder-1")))))
        }
        override suspend fun states(sourceId: String, mode: CounterMode): Map<String, SrsState> {
            requestedSources += sourceId
            return emptyMap()
        }
        override suspend fun newBudget(sourceId: String, now: Instant): Int {
            requestedSources += sourceId
            return 10
        }
        override suspend fun record(value: ReviewRecord) {
            events += "pending"
            allowReview.await()
            records += value
            events += "review"
        }
        override suspend fun start(value: StudyStart) {
            started += value
            events += "start"
        }
        override suspend fun end(value: StudyEnd) {
            ended += value
            events += "end"
        }
    }
}
