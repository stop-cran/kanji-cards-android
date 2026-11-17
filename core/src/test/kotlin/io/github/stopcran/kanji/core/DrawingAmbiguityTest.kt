package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.draw.DrawGrader
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.IssueType
import io.github.stopcran.kanji.core.draw.LookalikeGate
import io.github.stopcran.kanji.core.draw.LookalikeReference
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingAmbiguityTest {
    private val matcher = StrokeMatcher()
    private val gate = LookalikeGate(matcher)

    @Test
    fun knownWrongGlyphsAndBothIntendedMissingStrokeControlsAreAmbiguousNotAutomaticallyRejected() {
        for (case in DrawingAmbiguityFixtures.cases) {
            val match = matcher.match(DrawingAmbiguityFixtures.references.getValue(case.target), case.drawn)
            assertFalse(case.id, match.clean)
            assertTrue(case.id, match.recognizable)
            assertEquals(case.id, null, gate.find(case.target, match, case.drawn, DrawingAmbiguityFixtures.pool))
            assertEquals(
                case.id, case.alternative,
                gate.fallbackAlternative(case.target, match, case.drawn, DrawingAmbiguityFixtures.alternatives),
            )
            assertEquals(case.id, DrawOutcome.Mistakes, DrawGrader.outcome(null, case.target, match))
            if (case.target == "日") assertEquals(listOf(IssueType.Missing), match.issues.map { it.type })
        }
    }

    @Test
    fun anExactSubsetCostTieNeedsAChoiceRatherThanAnAutomaticIdentityDecision() {
        val mouth = DrawingAmbiguityFixtures.references.getValue("口")
        val target = listOf(mouth[0], mouth[1], listOf(
            Pt(34.0, 58.0),
            Pt(75.0, 58.0),
        ), mouth[2])
        val match = matcher.match(target, mouth)
        val competitor = matcher.match(mouth, mouth)
        assertFalse(match.clean)
        assertTrue(match.recognizable)
        assertTrue(competitor.clean)
        assertEquals(competitor.fitCost, match.fitCost, 1e-12)
        assertEquals(listOf(IssueType.Missing), match.issues.map { it.type })
        assertEquals(
            "口",
            gate.fallbackAlternative("synthetic-target", match, mouth, listOf(LookalikeReference("口", mouth))),
        )
        assertEquals(null, gate.find("synthetic-target", match, mouth, listOf("口" to mouth)))
        assertEquals(DrawOutcome.Mistakes, DrawGrader.outcome(null, "synthetic-target", match))
    }

    @Test
    fun aCleanAlternativeCanBeAmbiguousEvenWhenThePartialTargetFitsBetter() {
        val sun = DrawingAmbiguityFixtures.references.getValue("日")
        val mouth = DrawingAmbiguityFixtures.references.getValue("口")
        val drawn = listOf(sun[0], sun[1], sun[3])
        val match = matcher.match(sun, drawn)
        val other = matcher.match(mouth, drawn)
        assertTrue(other.clean)
        assertTrue(match.fitCost < other.fitCost)
        assertEquals(listOf(IssueType.Missing), match.issues.map { it.type })
        assertEquals("口", gate.fallbackAlternative("日", match, drawn, DrawingAmbiguityFixtures.alternatives))
        assertEquals(DrawOutcome.Mistakes, DrawGrader.outcome(null, "日", match))
    }

    @Test
    fun anAuthoredCompetitorOrderCanBeCleanWithoutChangingTheCanonicalCleanVeto() {
        val mouth = DrawingAmbiguityFixtures.references.getValue("口")
        val target = listOf(mouth[0], mouth[1], listOf(Pt(34.0, 58.0), Pt(75.0, 58.0)), mouth[2])
        val drawn = listOf(mouth[1], mouth[0], mouth[2])
        val variants = listOf(listOf(2, 1, 3))
        val match = matcher.match(target, drawn)
        assertFalse(matcher.match(mouth, drawn).clean)
        assertTrue(matcher.match(mouth, drawn, variants).clean)
        assertEquals(
            "口",
            gate.fallbackAlternative("synthetic-target", match, drawn, listOf(LookalikeReference("口", mouth, variants))),
        )
        assertEquals(
            null,
            gate.fallbackAlternative("synthetic-target", match, drawn, listOf(LookalikeReference("口", mouth))),
        )
        assertEquals(null, gate.find("synthetic-target", match, drawn, listOf("口" to mouth)))
        assertEquals(DrawOutcome.Mistakes, DrawGrader.outcome(null, "synthetic-target", match))
    }

    @Test
    fun ordinaryTargetErrorsAndMissingOrImperfectCompetitorsDoNotCreateAnAmbiguity() {
        val ref = DrawingAmbiguityFixtures.references.getValue("封")
        val reversed = listOf(ref[0].reversed()) + ref.drop(1)
        val match = matcher.match(ref, reversed)
        assertTrue(match.issues.any { it.type == IssueType.Reversed })
        assertEquals(null, gate.fallbackAlternative("封", match, reversed, DrawingAmbiguityFixtures.alternatives))

        val case = DrawingAmbiguityFixtures.cases.first()
        val wrongMatch = matcher.match(ref, case.drawn)
        assertEquals(null, gate.fallbackAlternative(case.target, wrongMatch, case.drawn, emptyList()))
        val imperfect = DrawingAmbiguityFixtures.references.getValue(case.alternative)
            .let { listOf(it[0].reversed()) + it.drop(1) }
        assertEquals(
            null, gate.fallbackAlternative(
                case.target, wrongMatch, case.drawn, listOf(LookalikeReference(case.alternative, imperfect)),
            ),
        )
    }
}
