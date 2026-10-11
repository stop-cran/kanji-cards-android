package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.PadCandidate
import io.github.stopcran.kanji.core.srs.PadContext
import io.github.stopcran.kanji.core.srs.PracticePadding
import io.github.stopcran.kanji.core.srs.SimilarityIndex
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class PracticePaddingTest {
    private val now = Instant.parse("2025-03-01T00:00:00Z")

    private fun state(daysAgo: Long, stability: Double = 5.0, phase: CardPhase = CardPhase.Review) =
        SrsState(phase, stability, 5.0, now.plusSeconds(86_400), now.minusSeconds(daysAgo * 86_400))

    private fun cand(kanji: String, owner: String, s: SrsState, id: String = kanji) = PadCandidate(id, kanji, owner, s)

    private fun fill(c: List<PadCandidate>, need: Int, exclude: Set<String> = emptySet(), anchors: List<String> = emptyList(), sim: SimilarityIndex? = null) =
        PracticePadding.fill(c, PadContext(exclude, anchors, sim), need, now, Random(1))

    @Test
    fun weakestFirstAndLimitedToNeed() {
        val r = fill(listOf(cand("A", "n5", state(1)), cand("B", "n5", state(30)), cand("C", "n5", state(10))), 2)
        assertEquals(listOf("B", "C"), r.map { it.kanji })
    }

    @Test
    fun neverPadsNewOrExcludedCards() {
        val c = listOf(cand("A", "n5", SrsState()), cand("B", "n5", state(5)), cand("C", "n5", state(9)))
        assertEquals(listOf("C"), fill(c, 5, exclude = setOf("B")).map { it.kanji })
    }

    @Test
    fun fewerCandidatesThanNeededReturnsAll() {
        assertEquals(1, fill(listOf(cand("A", "n5", state(3))), 9).size)
        assertTrue(fill(listOf(cand("A", "n5", state(3))), 0).isEmpty())
    }

    @Test
    fun oneOwnerPerIdWithLowestRetrievabilityThenStackOrder() {
        val c = listOf(cand("A", "starter", state(2)), cand("A", "jlpt-n5", state(20)), cand("B", "x", state(4)), cand("B", "a", state(4)))
        val r = fill(c, 5)
        assertEquals(2, r.size)
        assertEquals("jlpt-n5", r.first { it.kanji == "A" }.owner)
        assertEquals("a", r.first { it.kanji == "B" }.owner)
    }

    @Test
    fun confusableKanjiRankAheadOfSlightlyWeakerOnes() {
        val sim = SimilarityIndex(mapOf("A" to setOf("r:木"), "B" to setOf("r:木"), "C" to setOf("r:水")))
        val c = listOf(cand("B", "n5", state(10)), cand("C", "n5", state(11)))
        assertEquals("B", fill(c, 1, anchors = listOf("A"), sim = sim).single().kanji)
        assertTrue(!sim.similar("A", "A"))
    }
}
