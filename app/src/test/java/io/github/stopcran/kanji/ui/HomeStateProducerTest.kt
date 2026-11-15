package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.PacingNote
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.WeekProgress
import io.github.stopcran.kanji.core.unlock.UnlockRule
import io.github.stopcran.kanji.data.HomeInventory
import io.github.stopcran.kanji.data.HomeRepository
import io.github.stopcran.kanji.data.HomeSelection
import io.github.stopcran.kanji.data.HomeSnapshot
import io.github.stopcran.kanji.data.HomeStudyPreferences
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.joinSep
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
            val words = (1..20).map { WordEntity(source.id, "w$it", "w$it", "Word", null, "", "", "", 5) } +
                WordEntity(source.id, "next", "next", "Word", null, "", "", "", 4)
            val solid = SrsState(CardPhase.Review, 30.0, 5.0, now.plusSeconds(86_400), now.minusSeconds(86_400), 3)
            val states = (kanji.map { it.kanji } + words.map { it.word }).associateWith { solid }
            flowOf(snapshot(scope).copy(
                inventory = HomeInventory(scope, kanji, words), states = StudyMode.entries.associateWith { states },
                statesByStack = mapOf("words-n5" to StudyMode.entries.associateWith { states }),
                durableStatesByStack = mapOf("words-n5" to StudyMode.entries.associateWith { states }),
            ))
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

    @Test fun cumulativeKanjiCountsKeepNewCardsOnTheSelectedLevelInAllThreeModes() {
        fun card(id: String, level: Int, on: String, kun: String, position: Int) =
            KanjiEntity(source.id, id, id, level, "jlpt-n$level", 4, null, null, on, kun, "", "", "{}", position)
        val cards = listOf(
            card("白", 5, "ハク", "しろ", 0), card("休", 5, "キュウ", "やす.む", 1),
            card("自", 4, "ジ", "みずか.ら", 2), card("太", 3, "タイ", "ふと.い", 3),
        )
        val due = SrsState(CardPhase.Review, 10.0, 5.0, now.minusSeconds(1), now.minusSeconds(86_400), 3)
        val states = StudyMode.entries.associateWith { emptyMap<String, SrsState>() }.toMutableMap()
        states[StudyMode.Quiz] = mapOf("白" to due, "太" to due)
        states[StudyMode.Draw] = mapOf("白" to due, "太" to due)
        states[StudyMode.KanjiOn] = mapOf(ReadingId.of("白", "はく") to due, ReadingId.of("太", "たい") to due)
        val n4 = selection.copy(stackId = "jlpt-n4")
        val projected = projectHome(snapshot(n4).copy(
            inventory = HomeInventory(n4, cards, emptyList()), states = states, learned = setOf("休", "自"),
            allowance = NewAllowance(5, 5, PacingNote.None, 0, 40),
        ), now)
        assertEquals(ModeState.Start(1, 1), projected.mode(HomeMode.Meaning))
        assertEquals(ModeState.Start(1, 1), projected.mode(HomeMode.Drawing))
        assertEquals(ModeState.Start(1, 2), projected.mode(HomeMode.Readings))
        assertEquals(setOf("白", "休", "自"), projected.kanji.filter {
            io.github.stopcran.kanji.core.srs.Stacks.includes(projected.stack, it.tags.split('\u001F'))
        }.map { it.kanji }.toSet())
    }

    @Test fun levelProgressUsesExactInventoriesAndIndependentSkillStates() {
        val cards = listOf(
            KanjiEntity(source.id, "k5", "N5", 5, "jlpt-n5", 4, null, null, "オン", "くん", "", "", "{}", 0),
            KanjiEntity(source.id, "k4", "N4", 4, "jlpt-n4", 4, null, null, "オン", "くん", "", "", "{}", 1),
        )
        val words = listOf(
            WordEntity(source.id, "w5", "w5", "N5", null, "", "", "", 5),
            WordEntity(source.id, "w4", "w4", "N4 fallback", null, listOf("k4").joinSep(), "", ""),
            WordEntity(source.id, "unclassified", "unclassified", "Unclassified", null, "", "", ""),
        )
        val state = SrsState(CardPhase.Review, 20.0, 5.0, now, now, 3)
        val statesByStack = mapOf(
            Stacks.JLPT_STATE to mapOf(
                StudyMode.Quiz to mapOf("k5" to state),
                StudyMode.Draw to mapOf("k4" to state),
                StudyMode.KanjiOn to mapOf(ReadingId.of("k5", "おん") to state),
                StudyMode.KanjiKun to mapOf(ReadingId.of("k4", "くん") to state),
            ),
            "words-n5" to mapOf(StudyMode.WordJpEn to mapOf("w5" to state)),
            "words-n4" to mapOf(StudyMode.WordJpEn to mapOf("w4" to state)),
            "words-all" to mapOf(StudyMode.WordJpEn to mapOf("w5" to state, "w4" to state)),
        )
        val selected = selection.copy(stackId = "jlpt-n4", wordStackId = "words-n4", n4Unlocked = true)
        val selectedStates = StudyMode.entries.associateWith { emptyMap<String, SrsState>() }
        val projected = projectHome(
            snapshot(selected).copy(
                inventory = HomeInventory(selected, cards, words),
                states = selectedStates,
                statesByStack = statesByStack,
            ),
            now,
        )

        assertEquals(1, projected.kanjiProgress.getValue("jlpt-n5").skills.getValue("meaning").solid)
        assertEquals(1, projected.kanjiProgress.getValue("jlpt-n5").skills.getValue("meaning").total)
        assertEquals(0, projected.kanjiProgress.getValue("jlpt-n4").skills.getValue("meaning").solid)
        assertEquals(1, projected.kanjiProgress.getValue("jlpt-n4").skills.getValue("meaning").total)
        assertEquals(1, projected.kanjiProgress.getValue("jlpt-n5").skills.getValue("onReading").solid)
        assertEquals(1, projected.kanjiProgress.getValue("jlpt-n4").skills.getValue("kunReading").solid)
        assertEquals(1, projected.wordProgress.getValue("words-n5").skills.getValue("JpToEn").total)
        assertEquals(1, projected.wordProgress.getValue("words-n4").skills.getValue("JpToEn").total)
        assertEquals(3, projected.wordProgress.getValue("words-all").skills.getValue("JpToEn").total)
    }

    @Test fun kanjiProgressUsesEachStackStateAndUniqueLevelTags() {
        val cards = listOf(
            KanjiEntity(source.id, "n5", "N5", 5, "jlpt-n5", null, null, null, "", "", "", "", null, 0),
            KanjiEntity(source.id, "tag-n4", "N4 tag only", null, "jlpt-n4", null, null, null, "", "", "", "", null, 1),
            KanjiEntity(source.id, "ambiguous", "Conflicting tags", null, "jlpt-n4\u001Fjlpt-n3", null, null, null, "", "", "", "", null, 2),
            KanjiEntity(source.id, "starter", "Starter", null, "starter", null, null, null, "", "", "", "", null, 3),
        )
        val solid = SrsState(CardPhase.Review, 20.0, 5.0, now, now, 3)
        val selected = selection.copy(stackId = "jlpt-n5")
        val statesByStack = mapOf(
            Stacks.JLPT_STATE to mapOf(StudyMode.Quiz to mapOf("tag-n4" to solid)),
            "all" to mapOf(StudyMode.Quiz to mapOf("n5" to solid, "starter" to solid)),
            "starter" to mapOf(StudyMode.Quiz to mapOf("starter" to solid)),
        )
        val projected = projectHome(
            snapshot(selected).copy(
                inventory = HomeInventory(selected, cards, emptyList()),
                states = StudyMode.entries.associateWith { emptyMap() },
                statesByStack = statesByStack,
            ),
            now,
        )

        assertEquals(1, projected.kanjiProgress.getValue("jlpt-n4").total.total)
        assertEquals(1, projected.kanjiProgress.getValue("jlpt-n4").total.solid)
        assertEquals(0, projected.kanjiProgress.getValue("jlpt-n3").total.total)
        assertEquals(4, projected.kanjiProgress.getValue("all").total.total)
        assertEquals(2, projected.kanjiProgress.getValue("all").total.solid)
        assertEquals(1, projected.kanjiProgress.getValue("starter").total.solid)
    }

    @Test fun sectionRecommendationsUseOnlyTheirOwnSpacedSkillEvidence() {
        val cards = (1..20).map {
            KanjiEntity(source.id, "k$it", "N5", 5, "jlpt-n5", null, null, null, "", "", "", "", null, it)
        } + KanjiEntity(source.id, "next", "N4", 4, "jlpt-n4", null, null, null, "", "", "", "", null, 21)
        val words = (1..20).map { WordEntity(source.id, "w$it", "w$it", "N5", null, "", "", "", 5) } +
            WordEntity(source.id, "next-word", "next-word", "N4", null, "", "", "", 4)
        val solid = SrsState(CardPhase.Review, 20.0, 5.0, now, now, 3)
        val kanjiState = mapOf(StudyMode.Quiz to cards.take(20).associate { it.kanji to solid })
        val wordState = listOf(StudyMode.WordJpEn, StudyMode.WordEnJp, StudyMode.WordType)
            .associateWith { words.take(20).associate { it.word to solid } }
        val selected = selection.copy(stackId = "jlpt-n5")
        val base = snapshot(selected).copy(
            inventory = HomeInventory(selected, cards, words),
            statesByStack = mapOf(Stacks.JLPT_STATE to kanjiState, "words-n5" to wordState),
        )
        val unspaced = projectHome(base, now)
        assertEquals(null, unspaced.kanjiOffer)
        assertFalse(unspaced.n4Offer)

        val kanjiOnly = projectHome(base.copy(durableStatesByStack = mapOf(Stacks.JLPT_STATE to kanjiState)), now)
        assertEquals("jlpt-n4", kanjiOnly.kanjiOffer)
        assertFalse(kanjiOnly.n4Offer)

        val wordsOnly = projectHome(base.copy(durableStatesByStack = mapOf("words-n5" to wordState)), now)
        assertEquals(null, wordsOnly.kanjiOffer)
        assertTrue(wordsOnly.n4Offer)
    }

    @Test fun cumulativeHomeDoesNotOfferEmptyExtraPracticeForIneligibleLowerSkills() {
        val cards = listOf(
            KanjiEntity(source.id, "休", "rest", 5, "jlpt-n5", 4, null, null, "キュウ", "やす.む", "", "", "{}", 0),
            KanjiEntity(source.id, "白", "white", 5, "jlpt-n5", 4, null, null, "ハク", "しろ", "", "", "{}", 1),
            KanjiEntity(source.id, "体", "body", 4, "jlpt-n4", 4, null, null, "タイ", "からだ", "", "", "{}", 2),
        )
        val n4 = selection.copy(stackId = "jlpt-n4")
        val projected = projectHome(snapshot(n4).copy(
            inventory = HomeInventory(n4, cards, emptyList()), learned = setOf("休"),
            focus = io.github.stopcran.kanji.data.FocusStatus(size = 5, occupancy = 5),
        ), now)
        assertEquals(ModeState.Locked("Kanji focus full"), projected.mode(HomeMode.Meaning))
        assertEquals(ModeState.Locked(UnlockRule.NextDay.hint), projected.mode(HomeMode.Drawing))
        assertEquals(ModeState.Locked(UnlockRule.NextDay.hint), projected.mode(HomeMode.Readings))
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
