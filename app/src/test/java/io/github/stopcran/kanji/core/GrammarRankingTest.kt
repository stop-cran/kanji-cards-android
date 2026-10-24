package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.GrammarRecommendation
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.FocusOrigin
import io.github.stopcran.kanji.core.grammar.GrammarActivation
import io.github.stopcran.kanji.core.grammar.GrammarCursor
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarFocusInput
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

internal fun GrammarSet.recommending(vararg links: Pair<String, Int>): GrammarSet = copy(
    introductionGroups = listOf(introductionGroups.single().copy(
        next = links.map { (target, importance) -> GrammarRecommendation(target, importance, "Compare $id with $target.") },
    )),
)

class GrammarRankingTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val related = grammarFixture("beta")
    private val unrelated = grammarFixture("alpha")
    private val foundation = grammarFixture("foundation", examples = 2).recommending("beta" to 3)

    private fun scenario(sets: List<GrammarSet>, learned: List<GrammarSet>, ordinal: Long = 1): GrammarFocusInput {
        val events = learned.flatMapIndexed { index, set ->
            familiarEvents(set, now).map { it.copy(id = index * 1000L + it.id) }
        }
        return GrammarFocusInput(
            "owner/repo", sets, events.associate { it.exerciseId to SrsState(CardPhase.Review) }, events,
            GrammarScope.All, 10, now, GrammarCursor(ordinal),
        )
    }

    @Test
    fun relatedMaterialWinsOrdinarySlotsButUnrelatedMaterialWinsEveryThirdSlot() {
        val current = scenario(listOf(foundation, related, unrelated), listOf(foundation))
        for (ordinal in 1L..6L) {
            val decision = GrammarFocus.decide(current.copy(cursor = GrammarCursor(ordinal)))
            assertEquals(if (ordinal % 3 == 0L) "alpha" else "beta", decision.current!!.group.id)
            assertEquals(if (ordinal % 3 == 0L) 0 else 3, decision.current!!.score)
        }
        val support = GrammarFocus.decide(current).current!!.recommendations.single()
        assertEquals("foundation", support.sourceGroup.id)
        assertEquals("Compare foundation with beta.", support.link.reason)
        assertTrue(support.evidence.familiar)
        assertTrue(support.evidence.facets.values.all { it.positiveKeys == 2 && it.typedPositives == 1 && it.spanMs == 86_400_000L })
    }

    @Test
    fun incomingImportanceUsesMaximumNotSumAndDoesNotRewardLargerGroupsOrRepeatedEvidence() {
        val first = foundation.recommending("alpha" to 2, "beta" to 3)
        val second = grammarFixture("second", examples = 2).recommending("alpha" to 2)
        val current = scenario(listOf(first, second, related, unrelated), listOf(first, second))
        val decision = GrammarFocus.decide(current)
        assertEquals("beta", decision.current!!.group.id)
        assertEquals(2, decision.candidates.single { it.group.id == "alpha" }.score)
        val larger = current.copy(
            sets = listOf(first, second, related, grammarFixture("alpha", examples = 103)),
            events = current.events + current.events,
        )
        assertEquals(decision.current!!.group.id, GrammarFocus.decide(larger).current!!.group.id)
        assertEquals(2, GrammarFocus.decide(larger).candidates.single { it.group.id == "alpha" }.score)
    }

    @Test
    fun unfamiliarGroupsTakePrecedenceOverHigherScoredFamiliarGroups() {
        val current = scenario(listOf(foundation, related, unrelated), listOf(foundation, related))
        for (ordinal in listOf(1L, 3L)) {
            val decision = GrammarFocus.decide(current.copy(cursor = GrammarCursor(ordinal)))
            assertEquals(3, decision.candidates.single { it.group.id == "beta" }.score)
            assertEquals("alpha", decision.current!!.group.id)
        }
    }

    @Test
    fun familiarZeroScoreDoesNotDisplaceAnUnfamiliarRelatedGroupInExploration() {
        val familiar = grammarFixture("foundation").recommending("beta" to 3)
        val current = scenario(listOf(familiar, related), listOf(familiar), ordinal = 3)
        val decision = GrammarFocus.decide(current)
        assertEquals(0, decision.candidates.single { it.group.id == "foundation" }.score)
        assertEquals("beta", decision.current!!.group.id)
        assertEquals(3, decision.current!!.score)
        assertTrue(decision.reason.contains("No score-zero group in the familiarity-prioritized pool"))
    }

    @Test
    fun explorationUsesHighestPriorityWhenAllUnfamiliarChoicesAreRelatedAndIncludesLegacyZeroScores() {
        val from = foundation.recommending("alpha" to 1, "beta" to 3)
        val current = scenario(listOf(from, related, unrelated), listOf(from), ordinal = 3)
        assertEquals("beta", GrammarFocus.decide(current).current!!.group.id)
        val legacy = current.copy(sets = current.sets + grammarFixture("legacy", authored = false))
        assertEquals("legacy:legacy:contrast", GrammarFocus.decide(legacy).current!!.group.id)
        assertEquals("beta", GrammarFocus.decide(legacy.copy(cursor = GrammarCursor(1))).current!!.group.id)
    }

    @Test
    fun laterHardAndExpiredEvidenceImmediatelyRemoveRecommendationSupport() {
        val current = scenario(listOf(foundation, related, unrelated), listOf(foundation))
        assertEquals("beta", GrammarFocus.decide(current).current!!.group.id)
        val hard = current.events.first().copy(id = 99, grade = 2, atMs = now.minusSeconds(60).toEpochMilli())
        val changed = listOf(
            current.copy(events = current.events + hard),
            current.copy(now = now.plusSeconds(21L * 86_400)),
        )
        for (input in changed) {
            val decision = GrammarFocus.decide(input)
            assertEquals("alpha", decision.current!!.group.id)
            assertTrue(decision.candidates.all { it.score == 0 && it.recommendations.isEmpty() })
        }
    }

    @Test
    fun sourceWideRecommendationsCannotBypassScopeOrTruePrerequisites() {
        val outside = foundation.copy(exercises = foundation.exercises.map { it.copy(jlpt = 3) })
        val current = scenario(listOf(outside, related, unrelated), listOf(outside)).copy(scope = GrammarScope.N4)
        assertEquals("beta", GrammarFocus.decide(current).current!!.group.id)
        val tooHard = related.copy(exercises = related.exercises.map { it.copy(jlpt = 3) })
        assertEquals("alpha", GrammarFocus.decide(current.copy(sets = listOf(outside, tooHard, unrelated))).current!!.group.id)
        val preparation = grammarFixture("preparation", examples = 2)
        val gated = grammarFixture("beta", requires = listOf("preparation"))
        val withGate = current.copy(
            sets = listOf(outside, gated, unrelated, preparation),
            states = current.states + preparation.exercises.associate { it.id to SrsState(CardPhase.Review) },
        )
        val decision = GrammarFocus.decide(withGate)
        assertEquals("alpha", decision.current!!.group.id)
        assertTrue(decision.candidates.single { it.group.id == "beta" }.blocked!!.contains("Earned prerequisite readiness"))
    }

    @Test
    fun contentRemovalAndReimportRevalidateFocusAndReuseHistoryWithoutRepeatingStartedIntroductions() {
        val current = scenario(listOf(foundation, related, unrelated), listOf(foundation))
        assertEquals("alpha", GrammarFocus.decide(current.copy(sets = listOf(related, unrelated))).current!!.group.id)
        val introduced = GrammarFocus.decide(current).selected.first()
        val cursor = GrammarCursor(2, 1, GrammarActivation("beta", FocusOrigin.Automatic, 1, true, listOf(introduced)))
        val removed = current.copy(
            sets = listOf(foundation.recommending(), unrelated),
            states = current.states + (introduced.exerciseId to SrsState(CardPhase.Review)),
            cursor = cursor,
        )
        val reset = GrammarFocus.decide(removed)
        assertEquals("alpha", reset.current!!.group.id)
        assertTrue(reset.resetReason!!.contains("removed"))
        val restored = GrammarFocus.decide(removed.copy(sets = current.sets, cursor = cursor.copy(active = null)))
        assertEquals("beta", restored.current!!.group.id)
        assertEquals(3, restored.current!!.score)
        assertTrue(restored.selected.none { it.exerciseId == introduced.exerciseId })
    }

    @Test
    fun newRecommendationsNeverInterruptManualFocusOrAnUnfamiliarQuotaHold() {
        val current = scenario(listOf(foundation, related, unrelated), listOf(foundation))
        val manual = GrammarFocus.manual(current, "alpha")
        assertEquals("alpha", GrammarFocus.decide(current.copy(cursor = manual)).current!!.group.id)
        val selected = GrammarFocus.decide(current.copy(cursor = manual)).selected
        val held = current.copy(
            cursor = GrammarCursor(2, 1, GrammarActivation("alpha", FocusOrigin.Automatic, 1, true, selected)),
            states = current.states + selected.associate { it.exerciseId to SrsState(CardPhase.Review) },
        )
        val decision = GrammarFocus.decide(held)
        assertEquals("alpha", decision.current!!.group.id)
        assertTrue(decision.selected.isEmpty())
        assertTrue(decision.reason.contains("Review this group"))
        assertFalse(decision.current!!.evidence.familiar)
    }
}
