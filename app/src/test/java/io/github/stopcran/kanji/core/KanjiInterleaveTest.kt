package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.reading.KanjiReadingQueue
import io.github.stopcran.kanji.core.reading.ReadingCard
import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.core.reading.ReadingItem
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class KanjiInterleaveTest {
    private val now = Instant.parse("2026-01-10T10:00:00Z")
    private val day = 86400L

    private fun due(k: String, r: String) = ReadingItem(k, ReadingKind.On, r, false)
    private fun fresh(k: String, r: String) = ReadingItem(k, ReadingKind.On, r, true)
    private fun state(overdueDays: Long, intervalDays: Long = 10, stability: Double = 10.0) = SrsState(
        phase = CardPhase.Review, stability = stability, difficulty = 5.0,
        due = now.minusSeconds(overdueDays * day), lastReview = now.minusSeconds((overdueDays + intervalDays) * day), reps = 3,
    )

    @Test
    fun siblingsAreSpreadApartAndNothingIsLostOrReordered() {
        val items = listOf(due("a", "1"), due("a", "2"), due("a", "3"), due("b", "1"), due("c", "1"), due("d", "1"))
        val out = KanjiReadingQueue.interleave(items)
        assertEquals(items.toSet(), out.toSet())
        assertEquals(items.size, out.size)
        assertEquals(listOf("1", "2", "3"), out.filter { it.kanji == "a" }.map { it.reading })
        // Gap 2: the same kanji never recurs within two positions while others remain.
        assertEquals(listOf("a", "b", "c", "a", "d", "a"), out.map { it.kanji })
    }

    @Test
    fun oneKanjiAloneKeepsItsOrder() {
        val items = listOf(due("a", "1"), due("a", "2"), due("a", "3"))
        assertEquals(items, KanjiReadingQueue.interleave(items))
    }

    @Test
    fun aNewCardNeverJumpsAheadOfADueCardSkippedForSpacing() {
        val items = listOf(due("a", "1"), due("a", "2"), fresh("b", "1"), due("c", "1"))
        val out = KanjiReadingQueue.interleave(items, gap = 1)
        // a1 first; a2 is skipped for spacing, so the new b1 may not pass it; c1 takes the slot.
        assertEquals(listOf("a1", "c1", "a2", "b1"), out.map { it.kanji + it.reading })
    }

    @Test
    fun deterministicForTheSameSeed() {
        val items = (0 until 12).map { due("k${it % 4}", "r$it") }
        val states = items.map { it to state(it.reading.drop(1).toLong() % 5) }
        val a = KanjiReadingQueue.rankDue(states, now, 0.3, Random(7))
        val b = KanjiReadingQueue.rankDue(states, now, 0.3, Random(7))
        assertEquals(a, b)
        assertEquals(items.toSet(), a.toSet())
    }

    @Test
    fun rankingIsGlobalAcrossKinds() {
        val on = ReadingItem("a", ReadingKind.On, "1", false) to state(overdueDays = 1)
        val kun = ReadingItem("b", ReadingKind.Kun, "2", false) to state(overdueDays = 9)
        assertEquals(listOf(kun.first, on.first), KanjiReadingQueue.rankDue(listOf(on, kun), now, 0.0, Random(0)))
    }

    @Test
    fun retrievabilityBreaksTiesOnlyInsideTheEpsilon() {
        val weak = ReadingItem("a", ReadingKind.On, "1", false) to state(overdueDays = 2, stability = 2.0)
        val strong = ReadingItem("b", ReadingKind.On, "2", false) to state(overdueDays = 2, stability = 200.0)
        // Same urgency, so the less retrievable (obscure) card goes first even when listed second.
        assertEquals(listOf(weak.first, strong.first), KanjiReadingQueue.rankDue(listOf(strong, weak), now, 0.0, Random(0)))
        // Clearly more urgent beats lower retrievability.
        val moreUrgent = ReadingItem("c", ReadingKind.On, "3", false) to state(overdueDays = 8, stability = 200.0)
        assertEquals(moreUrgent.first, KanjiReadingQueue.rankDue(listOf(weak, moreUrgent), now, 0.0, Random(0)).first())
    }

    @Test
    fun queueAndExtraKeepLimitsAndCards() {
        val cards = listOf(ReadingCard("a", listOf("イ", "ロ", "ハ"), listOf("あ.う")), ReadingCard("b", listOf("ニ"), listOf("い.く")))
        val states = mapOf(
            ReadingKind.On to mapOf(ReadingId.of("a", "い") to state(2), ReadingId.of("a", "ろ") to state(3), ReadingId.of("b", "に") to state(1)),
            ReadingKind.Kun to mapOf(ReadingId.of("b", "いく") to state(9)),
        )
        val q = KanjiReadingQueue.build(cards, states, emptySet(), 0, 0, now, noise = 0.0)
        assertEquals(4, q.size)
        assertEquals("b", q.first().kanji)
        assertTrue(KanjiReadingQueue.build(cards, states, emptySet(), 0, 0, now, extra = true, noise = 0.0).size <= KanjiReadingQueue.EXTRA_LIMIT)
    }
}
