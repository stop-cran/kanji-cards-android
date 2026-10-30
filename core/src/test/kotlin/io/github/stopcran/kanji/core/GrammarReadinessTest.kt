package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.grammar.GrammarAvailability
import io.github.stopcran.kanji.core.grammar.GrammarAttainment
import io.github.stopcran.kanji.core.grammar.GrammarEvent
import io.github.stopcran.kanji.core.grammar.GrammarEvidence
import io.github.stopcran.kanji.core.grammar.GrammarFacetKey
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarReadiness
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GrammarReadinessTest {
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val set = grammarFixture()
    private val groups = GrammarFocus.groups(listOf(set))
    private val exercises = set.exercises.associateBy { it.id }
    private val a = GrammarFacetKey("alpha", "a")
    private val b = GrammarFacetKey("alpha", "b")

    private fun scan(events: List<GrammarEvent>): List<GrammarAttainment> {
        val history = GrammarReadiness.History(groups, exercises, emptySet(), now)
        return events.sortedWith(compareBy<GrammarEvent> { it.atMs }.thenBy { it.id }).flatMap { history.accept(it) } + history.finish()
    }

    @Test
    fun historicalQualificationSurvivesExpiryAndLaterLapseWithoutClaimingFreshness() {
        val old = familiarEvents(set, now.minus(Duration.ofDays(100)))
        val lapse = old.first().copy(id = 50, grade = 1, correct = false, atMs = now.toEpochMilli())
        val earned = scan(old + lapse)
        assertEquals(setOf(a, b), earned.map { it.key }.toSet())
        assertTrue(earned.all { it.atMs == old.maxOf { event -> event.atMs } })
        assertFalse(GrammarEvidence.evaluate(groups.single(), exercises, emptyMap(), old + lapse, now).familiar)
        assertEquals(setOf("alpha"), GrammarReadiness.readyGroups(groups, earned.map { it.key }.toSet()))
    }

    @Test
    fun historicalWindowsUseTheSameContextTypingSpacingAndResetRules() {
        val good = familiarEvents(set, now.minus(Duration.ofDays(100)))
        assertEquals(2, scan(good).size)
        assertTrue(scan(good.map { it.copy(promptType = "choose") }).isEmpty())
        assertTrue(scan(good.map { if (it.promptType == "type") it.copy(atMs = it.atMs - 1) else it }).isEmpty())
        assertTrue(scan(good.map { it.copy(exerciseId = it.exerciseId.dropLast(1) + "0") }).isEmpty())
        val oldest = good.minOf { it.atMs }
        val boundary = good.map { if (it.promptType == "type") it.copy(atMs = oldest + Duration.ofDays(21).toMillis()) else it }
        assertEquals(2, scan(boundary).size)
        assertTrue(scan(boundary.map { if (it.promptType == "type") it.copy(atMs = it.atMs + 1) else it }).isEmpty())
        for ((grade, correct) in listOf(1 to false, 2 to true, 3 to false, 1 to true)) {
            val reset = good.first().copy(id = 50, atMs = oldest + 1, grade = grade, correct = correct)
            assertFalse(a in scan(good + reset).map { it.key })
        }
    }

    @Test
    fun sameTimestampResetsAreOrderIndependentIncludingAcrossCallerPages() {
        val good = familiarEvents(set, now).filter { it.constructionId == "a" }.sortedBy { it.atMs }
        for (resetId in listOf(-1L, 50L)) {
            val reset = good.first().copy(id = resetId, grade = 2)
            assertTrue(scan(good + reset).isEmpty())
            val history = GrammarReadiness.History(groups, exercises, emptySet(), now)
            val ordered = (good + reset).sortedWith(compareBy<GrammarEvent> { it.atMs }.thenBy { it.id })
            val values = ordered.chunked(1).flatMap { page -> page.flatMap { history.accept(it) } } + history.finish()
            assertTrue(values.isEmpty())
        }
    }

    @Test
    fun UnmappedInvalidAndFutureEventsCannotEarnMilestones() {
        val good = familiarEvents(set, now)
        for (bad in listOf(
            good.map { it.copy(identityMatches = false) },
            good.map { it.copy(relationshipId = "changed") },
            good.map { it.copy(correct = null) },
            good.map { it.copy(grade = 9) },
            good.map { it.copy(promptType = "unknown") },
            good.map { it.copy(exerciseId = "removed") },
            good.map { if (it.promptType == "type") it.copy(atMs = now.toEpochMilli() + 1) else it },
        )) assertTrue(scan(bad).isEmpty())
    }

    @Test
    fun ReadinessIsAFacetConjunctionNotALabelOrWholeGroupAverage() {
        val one = groups.single().copy(facets = groups.single().facets.take(1), members = groups.single().members.filter { it.facetId == "a" })
        assertEquals(setOf("alpha"), GrammarReadiness.readyGroups(listOf(one), setOf(a)))
        assertTrue(GrammarReadiness.readyGroups(groups, setOf(a)).isEmpty())
        assertEquals(setOf("alpha"), GrammarReadiness.readyGroups(groups.map { it.copy(label = "Renamed") }, setOf(a, b)))
        assertTrue(GrammarReadiness.readyGroups(groups.map { it.copy(authored = false) }, setOf(a, b)).isEmpty())
        assertEquals(GrammarReadiness.mappingHash(listOf(set)), GrammarReadiness.mappingHash(listOf(set.copy(title = "Renamed"))))
    }

    @Test
    fun realMixedLevelConditionalsKeepTargetedN5WorkAndStartedN4ReviewsAvailable() {
        val file = java.nio.file.Paths.get("..", "..", "learning-japanese", "grammar", "conditionals-core.json").toFile()
        org.junit.Assume.assumeTrue("Sibling content checkout is required", file.exists())
        val set = ContentParser.parseGrammar("grammar/conditionals-core.json", file.readText())
        val groups = GrammarFocus.groups(listOf(set))
        val group = groups.single { it.id == "conditionals-forms" }
        val exercises = set.exercises.associateBy { it.id }
        val own = group.members.map { exercises.getValue(it.exerciseId) }
        assertEquals(setOf(4, 5), own.map { it.jlpt }.toSet())
        val started = own.first { it.jlpt == 4 }.id
        val states = mapOf(started to SrsState(CardPhase.Review, due = now.minusSeconds(1)))
        val available = GrammarAvailability.evaluate(
            groups, exercises, states, GrammarScope.N5, true, group.requires.toSet(), 10, now,
        ).getValue(group.id)
        assertEquals(4, available.missingCoherentFacets.size)
        assertTrue(available.targetedNew.isNotEmpty())
        assertTrue(available.targetedNew.all { exercises.getValue(it).jlpt == 5 })
        assertEquals(listOf(started), available.due)
    }

    @Test
    fun GuidedCoherenceDoesNotBecomeATargetedOrStartedReviewLock() {
        val mixed = set.copy(exercises = set.exercises.map { it.copy(jlpt = if (it.target == "a") 5 else 4) })
        val dependent = grammarFixture("beta", requires = listOf("alpha"))
        val sets = listOf(mixed, dependent)
        val states = mapOf("alpha-b-0" to SrsState(CardPhase.Review, due = now.minusSeconds(1)))
        val availability = GrammarAvailability.evaluate(
            GrammarFocus.groups(sets), sets.flatMap { it.exercises }.associateBy { it.id }, states,
            GrammarScope.N5, true, emptySet(), 0, now,
        )
        val partial = availability.getValue("alpha")
        assertEquals(listOf("b"), partial.missingCoherentFacets)
        assertEquals(3, partial.targetedNew.size)
        assertEquals(listOf("alpha-b-0"), partial.due)
        assertEquals(listOf("alpha"), availability.getValue("beta").missingPrerequisites)
        assertEquals(0, partial.dailyAllowance)
    }
}
