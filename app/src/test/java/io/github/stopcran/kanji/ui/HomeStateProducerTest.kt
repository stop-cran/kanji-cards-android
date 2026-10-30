package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.PacingNote
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.srs.WeekProgress
import io.github.stopcran.kanji.core.unlock.UnlockRule
import io.github.stopcran.kanji.data.HomeInventory
import io.github.stopcran.kanji.data.HomeRepository
import io.github.stopcran.kanji.data.HomeSelection
import io.github.stopcran.kanji.data.HomeSnapshot
import io.github.stopcran.kanji.data.HomeStudyPreferences
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.SyncActivity
import io.github.stopcran.kanji.data.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeStateProducerTest {
    private val now = Instant.parse("2026-01-05T15:01:00Z")
    private val zone = ZoneId.of("Asia/Tokyo")
    private val source = requireNotNull(RepoSource.parse("https://github.com/fixture/content", "main"))
    private val selection = HomeSelection(source, "all", "words-n5", false, 0, HomeStudyPreferences(3, "FFFFFFF", UnlockRule.NextDay, false, 5))

    @Test fun initialLoadingEmptyCacheAndFailedRefreshRemainDistinct() = runTest {
        val repository = FakeHome()
        val loaded = CompletableDeferred<Unit>()
        repository.loader = { scope, _, _ -> flow { loaded.await(); emit(snapshot(scope)) } }
        val activity = MutableStateFlow<Map<RepoSource, SyncActivity>>(emptyMap())
        val events = Channel<HomeState>(Channel.UNLIMITED)
        val job = backgroundScope.launch { producer(repository, activity).states.collect { events.send(it) } }
        assertTrue(events.receive().data.loading)
        loaded.complete(Unit)
        val empty = events.nextReady()
        assertTrue(empty.data.kanji.isEmpty())
        assertTrue(empty.content.usable)
        activity.value = mapOf(source to SyncActivity.Finished(SyncResult.Failed("Offline", true)))
        val failed = events.receive()
        assertFalse(failed.data.loading)
        assertTrue(failed.content.usable)
        assertEquals("Offline", failed.content.failure?.message)
        activity.value = mapOf(source to SyncActivity.Interrupted)
        assertTrue(events.receive().content.interrupted)
        job.cancel()
    }

    @Test fun obsoleteNonCooperativeLoadsAndErrorsCannotOverwriteANewSourceOrStack() = runTest {
        for (lateFailure in listOf(false, true)) {
            val repository = FakeHome()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            repository.loader = { scope, _, _ ->
                flow {
                    if (scope == selection) {
                        started.complete(Unit)
                        withContext(NonCancellable) {
                            release.await()
                            if (lateFailure) error("Obsolete failure")
                        }
                    }
                    emit(snapshot(scope))
                }
            }
            val events = Channel<HomeState>(Channel.UNLIMITED)
            val job = backgroundScope.launch { producer(repository).states.collect { events.send(it) } }
            started.await()
            val next = selection.copy(source = source.copy(branch = "other"), stackId = "other-stack", wordStackId = "other-words")
            repository.selection.value = next
            runCurrent()
            release.complete(Unit)
            assertEquals(next, events.nextReady().selection)
            assertTrue(repository.loaded.contains(next))
            job.cancel()
        }
    }

    @Test fun aCurrentProgrammingFailureIsNotConvertedIntoARecoverableEmptyHome() = runTest {
        val repository = FakeHome()
        val failure = IllegalStateException("Broken projection input")
        repository.loader = { _, _, _ -> flow { throw failure } }
        try {
            producer(repository).states.first { !it.data.loading }
            fail("The programming failure must remain observable")
        } catch (error: IllegalStateException) {
            assertEquals(failure.message, error.message)
        }
    }

    @Test fun aTickExpiresTheN4DismissalAndPassesTheSameInstantAndZoneThroughLoading() = runTest {
        val repository = FakeHome()
        repository.selection.value = selection.copy(dismissedMs = now.minusSeconds(7 * 86_400 - 30).toEpochMilli())
        val times = mutableListOf<Pair<Instant, ZoneId>>()
        repository.loader = { scope, at, inZone ->
            times += at to inZone
            val kanji = (1..20).map { KanjiEntity(source.id, "k$it", "Kanji", 5, "jlpt-n5", null, null, null, "", "", "", "", null, it) }
            val words = (1..20).map { WordEntity(source.id, "w$it", "w$it", "Word", null, "", "", "", 5) }
            val solid = SrsState(CardPhase.Review, 30.0, 5.0, now.plusSeconds(86_400), now.minusSeconds(86_400), 3)
            val states = (kanji.map { it.kanji } + words.map { it.word }).associateWith { solid }
            flowOf(snapshot(scope).copy(inventory = HomeInventory(scope, kanji, words), states = StudyMode.entries.associateWith { states }))
        }
        val ticks = MutableStateFlow(now)
        val events = Channel<HomeState>(Channel.UNLIMITED)
        val job = backgroundScope.launch {
            HomeStateProducer(repository, flowOf(emptyMap()), Clock.fixed(now, zone), ticks).states.collect { events.send(it) }
        }
        assertFalse(events.nextReady().data.n4Offer)
        ticks.value = now.plusSeconds(60)
        assertTrue(events.nextReady().data.n4Offer)
        assertEquals(listOf(now to zone, now.plusSeconds(60) to zone), times)
        job.cancel()
    }

    private fun producer(repository: HomeRepository, activity: Flow<Map<RepoSource, SyncActivity>> = flowOf(emptyMap())) =
        HomeStateProducer(repository, activity, Clock.fixed(now, zone), flowOf(now))

    private suspend fun Channel<HomeState>.nextReady(): HomeState {
        while (true) {
            val state = receive()
            if (!state.data.loading && state.content.ready != null) return state
        }
    }

    private fun snapshot(scope: HomeSelection) = HomeSnapshot(
        HomeInventory(scope, emptyList(), emptyList()), StudyMode.entries.associateWith { emptyMap() },
        NewAllowance(3, 3, PacingNote.None, 0, 40), WeekProgress(0, 5), emptySet(), 0, emptySet(), null,
    )

    private inner class FakeHome : HomeRepository {
        override val selection = MutableStateFlow(this@HomeStateProducerTest.selection)
        override val currentSource get() = selection.value.source
        val loaded = mutableListOf<HomeSelection>()
        var loader: (HomeSelection, Instant, ZoneId) -> Flow<HomeSnapshot> = { scope, _, _ -> flowOf(snapshot(scope)) }
        override fun ready(source: RepoSource) = flowOf(true)
        override fun observe(selection: HomeSelection, now: Instant, zone: ZoneId): Flow<HomeSnapshot> {
            loaded += selection
            return loader(selection, now, zone)
        }
    }
}
