package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.grammar.GRAMMAR_LEVEL_SYNC_NEEDED
import io.github.stopcran.kanji.core.grammar.GrammarEligibility
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GrammarScopeTest {
    private fun exercise(id: String, level: Int?) = GrammarExercise(
        id, "a", "contrast", "Complete ____.", "Synthetic fixture.", "Evidence.", "Explanation.", "a", listOf("a"),
        listOf(GrammarOption("a", "a", "Fits."), GrammarOption("b", "b", "Does not fit.")), level,
    )

    @Test
    fun cumulativeIntakeAndExactBrowsingHaveDifferentLevelMatrices() {
        val levels = listOf(null, 1, 2, 3, 4, 5)
        val expected = mapOf(
            GrammarScope.N5 to listOf(5), GrammarScope.N4 to listOf(4, 5), GrammarScope.N3 to listOf(3, 4, 5),
            GrammarScope.N2 to listOf(2, 3, 4, 5), GrammarScope.N1 to listOf(1, 2, 3, 4, 5),
        )
        expected.forEach { (scope, included) ->
            assertEquals(included, levels.filter { scope.matches(it, cumulative = true) })
            assertEquals(listOf(included.first()), levels.filter { scope.matches(it, cumulative = false) })
        }
        for (cumulative in listOf(false, true)) {
            assertEquals(levels, levels.filter { GrammarScope.All.matches(it, cumulative) })
            assertEquals(listOf<Int?>(null), levels.filter { GrammarScope.Unclassified.matches(it, cumulative) })
        }
    }

    @Test
    fun onlyUnseenCandidatesAreFilteredInNormalAndExtraQueues() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val exercises = listOf(
            exercise("n4", 4), exercise("n5", 5), exercise("n3-new-state", 3), exercise("unclassified", null),
            exercise("due-n1", 1), exercise("early-n2", 2),
        )
        val states = mapOf(
            "n3-new-state" to SrsState(),
            "due-n1" to SrsState(CardPhase.Review, due = now.minusSeconds(60)),
            "early-n2" to SrsState(CardPhase.Learning, due = now.plusSeconds(3600)),
            "removed" to SrsState(CardPhase.Review),
        )
        val pool = GrammarEligibility.pool(exercises, states, GrammarScope.N4, levelsReady = true)
        assertEquals(listOf("n4", "n5", "due-n1", "early-n2"), pool.ids)
        assertEquals(listOf("due-n1", "n4"), QueueBuilder.build(pool.ids, states, now, 1, noise = 0.0).map { it.kanji })
        assertEquals(listOf("due-n1"), QueueBuilder.build(pool.ids, states, now, 0, noise = 0.0).map { it.kanji })
        assertEquals(
            listOf("due-n1", "n4", "n5", "early-n2"),
            QueueBuilder.extra(pool.ids, states, now, noise = 0.0).map { it.kanji },
        )
    }

    @Test
    fun legacyCacheCannotMasqueradeAsUnclassifiedButAllAndStartedReviewsRemainSafe() {
        val exercises = listOf(exercise("unseen", null), exercise("started", null))
        val states = mapOf("started" to SrsState(CardPhase.Review))
        GrammarScope.entries.filter { it != GrammarScope.All }.forEach { scope ->
            val pool = GrammarEligibility.pool(exercises, states, scope, levelsReady = false)
            assertEquals(listOf("started"), pool.ids)
            assertEquals(GRAMMAR_LEVEL_SYNC_NEEDED, pool.newItemMessage)
        }
        assertEquals(exercises.map { it.id }, GrammarEligibility.pool(exercises, states, GrammarScope.All, false).ids)
        assertEquals(
            exercises.map { it.id },
            GrammarEligibility.pool(exercises, states, GrammarScope.Unclassified, true).ids,
        )
        assertTrue(GrammarEligibility.pool(emptyList(), states, GrammarScope.All, true).ids.isEmpty())
    }
}
