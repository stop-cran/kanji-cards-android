package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.grammar.GrammarAvailability
import io.github.stopcran.kanji.core.grammar.GrammarEvidence
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarMapGroup
import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import io.github.stopcran.kanji.core.grammar.GrammarRoadmap
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.system.measureNanoTime

class GrammarRoadmapTest {
    @Test
    fun branchedLayoutIsDeterministicAndEveryRealDependencyFlowsRight() {
        val ids = listOf("root", "independent", "left", "right", "last")
        val edges = listOf(
            GrammarLeafEdge("root", "left"), GrammarLeafEdge("root", "right"),
            GrammarLeafEdge("left", "last"), GrammarLeafEdge("right", "last"),
        )
        val layout = GrammarRoadmap.layout(ids, edges)
        assertEquals(layout, GrammarRoadmap.layout(ids.reversed(), edges.reversed()))
        val positions = layout.positions.associateBy { it.id }
        edges.forEach { assertTrue(positions.getValue(it.foundation).column < positions.getValue(it.dependent).column) }
        assertEquals(positions.getValue("root").column, positions.getValue("independent").column)
        assertEquals(positions.getValue("left").column, positions.getValue("right").column)
        assertEquals(ids.size, positions.values.map { it.column to it.row }.distinct().size)
        assertThrows(IllegalArgumentException::class.java) { GrammarRoadmap.layout(ids, edges + GrammarLeafEdge("last", "root")) }
        assertThrows(IllegalArgumentException::class.java) { GrammarRoadmap.layout(ids, edges + GrammarLeafEdge("absent", "root")) }
    }

    @Test
    fun partialAvailabilityCountsRealChildrenAndDoesNotLockExhaustedReviewsOrEraseReadinessWithAge() {
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val sets = listOf(grammarFixture("p", examples = 2), grammarFixture("q", requires = listOf("p")), grammarFixture("r"))
        val groups = GrammarFocus.groups(sets)
        val exercises = sets.flatMap { it.exercises }.associateBy { it.id }
        val states = (sets.first().exercises + sets.last().exercises).associate { it.id to SrsState(CardPhase.Review, due = now.minusSeconds(60)) }
        val available = GrammarAvailability.evaluate(groups, exercises, states, GrammarScope.All, true, setOf("r"), 0, now)
        val items = groups.map { group ->
            GrammarMapGroup(group, available.getValue(group.id), GrammarEvidence.evaluate(group, exercises, states, emptyList(), now),
                group.id == "r", group.members.size, emptyMap())
        }
        val progress = GrammarMapProgress.of(items)
        assertEquals(2, progress.available)
        assertEquals(1, progress.needsFoundation)
        assertEquals(1, progress.ready)
        assertEquals(0, progress.familiar)
        assertEquals(states.size, progress.introduced)
        assertEquals(states.size, progress.due)
        assertFalse(items.first().newNeedsFoundation)
        assertTrue(items.first().practiceAvailable)
        assertTrue(items[1].newNeedsFoundation)
        assertFalse(items[1].copy(availability = items[1].availability.copy(newMetadataProblem = "Sync needed")).newNeedsFoundation)
        assertFalse(items[1].copy(readinessProblem = "Readiness maintenance failed").newNeedsFoundation)
    }

    @Test
    fun syntheticThousandNodeGraphsStayBoundedAndDeepChainsDoNotRecurse() {
        val ids = (0 until 1000).map { "node-${it.toString().padStart(4, '0')}" }
        val edges = (40 until ids.size).map { GrammarLeafEdge(ids[it - 40], ids[it]) }
        repeat(10) { GrammarRoadmap.layout(ids, edges) }
        val times = (0 until 30).map { measureNanoTime { GrammarRoadmap.layout(ids, edges) } / 1_000_000.0 }.sorted()
        val p95 = times[28]
        println("ROADMAP_LAYOUT nodes=1000 edges=${edges.size} runs=30 warmup=10 p95_ms=$p95 max_ms=${times.last()}")
        assertTrue("Warm layout p95 exceeded the 100 ms development budget: $p95", p95 < 100)
        val branched = GrammarRoadmap.layout(ids, edges)
        assertEquals(25, branched.columns)
        assertEquals(40, branched.rows)
        val chain = GrammarRoadmap.layout(ids, ids.zipWithNext { a, b -> GrammarLeafEdge(a, b) })
        assertEquals(1000, chain.columns)
        assertEquals(1, chain.rows)
    }
}
