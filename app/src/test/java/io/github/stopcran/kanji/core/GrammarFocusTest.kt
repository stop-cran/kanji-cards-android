package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.GrammarConstruction
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarFacet
import io.github.stopcran.kanji.core.content.GrammarIntroductionGroup
import io.github.stopcran.kanji.core.content.GrammarMember
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarRelationship
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.FocusOrigin
import io.github.stopcran.kanji.core.grammar.GrammarActivation
import io.github.stopcran.kanji.core.grammar.GrammarCursor
import io.github.stopcran.kanji.core.grammar.GrammarEvent
import io.github.stopcran.kanji.core.grammar.GrammarEvidence
import io.github.stopcran.kanji.core.grammar.GrammarExposure
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

internal fun grammarFixture(id: String = "alpha", examples: Int = 3, authored: Boolean = true, requires: List<String> = emptyList()): GrammarSet {
    val targets = listOf("a", "b")
    val exercises = targets.flatMap { target ->
        (0 until examples).map { index ->
            GrammarExercise(
                "$id-$target-$index", target, "contrast", "Synthetic ____.", "Synthetic meaning.", "Evidence.", "Explanation.",
                target, listOf(target), targets.map { GrammarOption(it, it, "Rationale.") }, 4,
            )
        }
    }
    val group = GrammarIntroductionGroup(
        id, id, targets.map { GrammarFacet(it, it) },
        exercises.map { GrammarMember(it.id, it.target, it.id.substringAfterLast('-')) }, requires = requires,
    )
    return GrammarSet(
        1, id, id, "articles/x.md", targets.map { GrammarConstruction(it, it) },
        listOf(GrammarRelationship("contrast", "contrast", targets)), exercises,
        if (authored) listOf(group) else emptyList(),
    )
}

internal fun familiarEvents(set: GrammarSet, now: Instant): List<GrammarEvent> =
    set.exercises.filter { it.id.endsWith("-0") || it.id.endsWith("-1") }.mapIndexed { index, exercise ->
        GrammarEvent(
            index.toLong(), exercise.id, 3, now.minusSeconds(if (exercise.id.endsWith("-0")) 172_800 else 86_400).toEpochMilli(),
            exercise.target, exercise.relationship, if (exercise.id.endsWith("-0")) "choose" else "type", true, true,
        )
    }

class GrammarFocusTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val first = grammarFixture()
    private fun input(
        sets: List<GrammarSet> = listOf(first),
        states: Map<String, SrsState> = emptyMap(),
        cursor: GrammarCursor = GrammarCursor(),
        events: List<GrammarEvent> = emptyList(),
        scope: GrammarScope = GrammarScope.All,
    ) = GrammarFocusInput("owner/repo", sets, states, events, scope, 10, now, cursor)

    private fun evidence(events: List<GrammarEvent>) = GrammarEvidence.evaluate(
        GrammarFocus.groups(listOf(first)).single(), first.exercises.associateBy { it.id }, emptyMap(), events, now,
    )

    @Test
    fun familiarityNeedsEveryFacetTwoContextsTypingAndAnExactDaySpan() {
        val good = familiarEvents(first, now)
        assertTrue(evidence(good).familiar)
        assertFalse(evidence(good).covered)
        assertFalse(evidence(good.map { it.copy(promptType = "choose") }).familiar)
        assertFalse(evidence(good.filterNot { it.exerciseId == "alpha-b-1" }).familiar)
        assertFalse(evidence(good.map { if (it.promptType == "type") it.copy(atMs = it.atMs - 1) else it }).familiar)
        val oneContext = good.map { if (it.exerciseId.endsWith("-1")) it.copy(exerciseId = it.exerciseId.dropLast(1) + "0") else it }
        assertFalse(evidence(oneContext).familiar)
        assertEquals(evidence(good), evidence(good + good))
        assertTrue(evidence(good.map { it.copy(grade = 4) }).familiar)
        val boundary = good.map { it.copy(atMs = it.atMs - 19L * 86_400_000) }
        assertTrue(evidence(boundary).familiar)
        assertFalse(evidence(boundary.map { it.copy(atMs = it.atMs - 1) }).familiar)
    }

    @Test
    fun failuresHardExpiryFutureAndInconsistentEvidenceCannotCreateFamiliarity() {
        val good = familiarEvents(first, now)
        for ((grade, correct) in listOf(1 to false, 2 to true, 3 to false, 1 to true)) {
            val reset = good.first().copy(id = 99, atMs = now.minusSeconds(60).toEpochMilli(), grade = grade, correct = correct)
            assertFalse(evidence(good + reset).familiar)
        }
        assertFalse(evidence(good.map { it.copy(atMs = it.atMs - 21L * 86_400_000) }).familiar)
        val future = evidence(good.map { if (it.promptType == "type") it.copy(atMs = now.toEpochMilli() + 1) else it })
        assertFalse(future.familiar)
        assertTrue(future.diagnostics.any { it.contains("Future-dated") })
        for (unknown in listOf(
            good.map { it.copy(identityMatches = false) },
            good.map { it.copy(constructionId = "other") },
            good.map { it.copy(relationshipId = "other") },
            good + good.first().copy(correct = false),
        )) {
            assertFalse(evidence(unknown).familiar)
            assertTrue(evidence(unknown).diagnostics.isNotEmpty())
        }
    }

    @Test
    fun oneActivationStaysInOneGroupAndBalancesTwoDistinctExamplesPerFacet() {
        val plan = GrammarFocus.decide(input(sets = listOf(first, grammarFixture("beta"))))
        assertEquals(4, plan.selected.size)
        assertEquals(setOf(plan.activation!!.groupId), plan.selected.map { it.exerciseId.substringBefore('-') }.toSet())
        assertEquals(mapOf("a" to 2, "b" to 2), plan.selected.groupingBy { it.facetId }.eachCount())
        assertTrue(plan.selected.groupBy { it.facetId }.values.all { items -> items.map { it.exampleKey }.distinct().size == 2 })
        assertEquals(1, GrammarFocus.decide(input().copy(budget = 1)).selected.size)
    }

    @Test
    fun quotaWaitsForReviewOrManualRevisitRatherThanDumpingTheGroup() {
        val selected = GrammarFocus.decide(input()).selected
        val active = GrammarActivation("alpha", FocusOrigin.Automatic, 1, true, selected)
        val states = selected.associate { it.exerciseId to SrsState(CardPhase.Review) }
        val current = input(states = states, cursor = GrammarCursor(2, 1, active))
        val held = GrammarFocus.decide(current)
        assertTrue(held.selected.isEmpty())
        assertTrue(held.reason.contains("Review this group"))
        val manual = GrammarFocus.manual(current, "alpha")
        assertEquals(2L, manual.automaticOrdinal)
        assertEquals(FocusOrigin.Manual, manual.active!!.origin)
        assertEquals(2, GrammarFocus.decide(current.copy(cursor = manual)).selected.size)
    }

    @Test
    fun familiarityAndCoverageReleaseFocusWhileManualRevisitStillStarts() {
        val events = familiarEvents(first, now)
        val states = events.associate { it.exerciseId to SrsState(CardPhase.Review) }
        val introduced = events.map { GrammarExposure(it.exerciseId, it.constructionId!!, it.exerciseId.substringAfterLast('-')) }
        val cursor = GrammarCursor(2, 1, GrammarActivation("alpha", FocusOrigin.Automatic, 1, true, introduced))
        val current = input(listOf(first, grammarFixture("beta")), states, cursor, events)
        assertEquals("beta", GrammarFocus.decide(current).activation!!.groupId)
        val revisited = GrammarFocus.manual(current, "alpha")
        assertEquals("alpha", GrammarFocus.decide(current.copy(cursor = revisited)).activation!!.groupId)
        assertEquals(2, GrammarFocus.decide(current.copy(cursor = revisited)).selected.size)
    }

    @Test
    fun partialContrastsStayBlockedUntilOutOfScopeMembersHaveStarted() {
        val mixed = first.copy(exercises = first.exercises.map { if (it.target == "b") it.copy(jlpt = 3) else it })
        val current = input(listOf(mixed), scope = GrammarScope.N4)
        assertTrue(GrammarFocus.decide(current).selected.isEmpty())
        assertTrue(GrammarFocus.decide(current).candidates.single().blocked!!.contains("two contexts"))
        val states = mixed.exercises.filter { it.target == "b" }.take(2).associate { it.id to SrsState(CardPhase.Learning) }
        val eligible = GrammarFocus.decide(current.copy(states = states))
        assertEquals(2, eligible.selected.size)
        assertTrue(eligible.selected.all { it.facetId == "a" })
    }

    @Test
    fun prerequisiteEvidenceIsSourceWideButLegacyGroupsNeverSupplyCredentials() {
        val prerequisite = grammarFixture("alpha", examples = 2).let { set ->
            set.copy(exercises = set.exercises.map { it.copy(jlpt = 3) })
        }
        val dependent = grammarFixture("beta", requires = listOf("alpha"))
        val events = familiarEvents(prerequisite, now)
        val states = prerequisite.exercises.associate { it.id to SrsState(CardPhase.Review) }
        val current = input(listOf(prerequisite, dependent), states, events = events, scope = GrammarScope.N4)
        assertTrue(GrammarFocus.decide(current.copy(events = emptyList())).selected.isEmpty())
        assertTrue(GrammarFocus.decide(current).selected.isEmpty())
        assertEquals("beta", GrammarFocus.decide(current.copy(readyGroups = setOf("alpha"), events = emptyList())).activation!!.groupId)
        val legacy = prerequisite.copy(introductionGroups = emptyList())
        assertFalse(GrammarEvidence.evaluate(
            GrammarFocus.groups(listOf(legacy)).single(), legacy.exercises.associateBy { it.id }, states, events, now,
        ).familiar)
    }

    @Test
    fun compatibilityDoesNotPoolLocalIdsOrWaitForeverOnOutOfScopeTargets() {
        val legacy = grammarFixture(authored = false).let { set ->
            set.copy(exercises = set.exercises.map { if (it.target == "b") it.copy(jlpt = 3) else it })
        }
        assertEquals(2, GrammarFocus.groups(listOf(legacy, grammarFixture("beta", authored = false))).size)
        val current = input(listOf(legacy), scope = GrammarScope.N4)
        val selected = GrammarFocus.decide(current).selected
        assertEquals(2, selected.size)
        val cursor = GrammarCursor(2, 1, GrammarActivation("legacy:alpha:contrast", FocusOrigin.Automatic, 1, true, selected))
        val resumed = GrammarFocus.decide(current.copy(cursor = cursor, states = selected.associate { it.exerciseId to SrsState(CardPhase.Review) }))
        assertEquals(1, resumed.selected.size)
        assertEquals(2L, resumed.activation!!.seed)
    }

    @Test
    fun automaticOrdinalsCommitOnlyOnceAndManualActivationsDoNotConsumeThem() {
        val selection = GrammarFocus.decide(input()).selected.first()
        val pending = GrammarCursor(1, 1, GrammarActivation("alpha", FocusOrigin.Automatic, 1))
        val committed = GrammarFocus.recorded(pending, selection)
        assertEquals(2L, committed.automaticOrdinal)
        assertEquals(committed, GrammarFocus.recorded(committed, selection))
        val manual = GrammarFocus.manual(input(cursor = committed), "alpha")
        assertEquals(2L, GrammarFocus.recorded(manual, selection).automaticOrdinal)
    }

    @Test
    fun ordinarySlotsPreferAuthoredGroupsAndLargerGroupsDoNotGainPriority() {
        val sets = listOf(first, grammarFixture("beta"), grammarFixture("legacy-15", authored = false))
        val expected = listOf("alpha", "beta", "legacy:legacy-15:contrast", "alpha", "alpha", "legacy:legacy-15:contrast")
        for (ordinal in 1L..6L) {
            val current = input(sets, cursor = GrammarCursor(ordinal))
            val decision = GrammarFocus.decide(current)
            assertEquals(expected[(ordinal - 1).toInt()], decision.activation!!.groupId)
            if (ordinal % 3 != 0L) assertTrue(decision.current!!.group.authored)
            else assertTrue(decision.reason.contains("Exploration slot $ordinal"))
            val larger = current.copy(sets = listOf(grammarFixture(examples = 103), sets[1], sets[2]))
            assertEquals(decision.activation.groupId, GrammarFocus.decide(larger).activation!!.groupId)
        }
    }
}
