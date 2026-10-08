package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.draw.DrawGrader
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.IssueType
import io.github.stopcran.kanji.core.draw.MatchResult
import io.github.stopcran.kanji.core.draw.StrokeIssue
import io.github.stopcran.kanji.core.draw.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawGraderTest {
    private val clean = MatchResult(emptyList(), 4, 4, 4)
    private val mistakes = MatchResult(listOf(StrokeIssue(IssueType.Reversed, 1, 1)), 4, 4, 4)
    private val unrecognizable = MatchResult(listOf(StrokeIssue(IssueType.Missing, 0, null), StrokeIssue(IssueType.Missing, 1, null), StrokeIssue(IssueType.Missing, 2, null)), 4, 1, 1)

    @Test
    fun cleanMatchWinsOverRecogniser() {
        assertEquals(DrawOutcome.Clean, DrawGrader.outcome(listOf("x"), "日", clean))
        assertEquals(DrawOutcome.Clean, DrawGrader.outcome(null, "日", clean))
    }

    @Test
    fun recognisedWithIssuesIsMistakes() {
        assertEquals(DrawOutcome.Mistakes, DrawGrader.outcome(listOf("目", "日"), "日", mistakes))
        assertEquals(DrawOutcome.Mistakes, DrawGrader.outcome(listOf("日"), "日", unrecognizable))
    }

    @Test
    fun notRecognisedIsRejectedEvenIfStrokesLookClose() {
        assertEquals(DrawOutcome.NotRecognized, DrawGrader.outcome(listOf("目", "自", "白", "百", "貝", "日"), "日", mistakes))
    }

    @Test
    fun withoutRecogniserMatcherDecides() {
        assertEquals(DrawOutcome.Mistakes, DrawGrader.outcome(null, "日", mistakes))
        assertEquals(DrawOutcome.NotRecognized, DrawGrader.outcome(null, "日", unrecognizable))
    }

    @Test
    fun emptyDrawingIsNotRecognised() {
        assertEquals(DrawOutcome.NotRecognized, DrawGrader.outcome(listOf("日"), "日", MatchResult(emptyList(), 4, 0, 0)))
    }

    @Test
    fun describesIssuesWithOneBasedNumbers() {
        assertTrue(StrokeIssue(IssueType.Joined, 0, 0).describe().contains("1 and 2"))
        assertTrue(StrokeIssue(IssueType.Reversed, 2, 2).describe().contains("Stroke 3"))
    }
}
