package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.draw.IssueType
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.Stroke
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import io.github.stopcran.kanji.core.draw.loopArea
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class KanaStrokeMatcherTest {
    private val matcher = StrokeMatcher()
    private val body = listOf(
        listOf(Pt(15.0, 25.0), Pt(65.0, 25.0)),
        listOf(Pt(40.0, 10.0), Pt(40.0, 95.0)),
    )
    private val ticks = listOf(
        listOf(Pt(80.5, 18.2), Pt(88.2, 26.7)),
        listOf(Pt(86.9, 13.4), Pt(95.5, 21.0)),
    )

    @Test fun displacedDakutenTicksKeepTheirGeometricPartners() {
        val reference = body + ticks
        for (offset in listOf(-12.0, -11.0, -6.0, 6.0, 11.0, 12.0)) {
            val moved = ticks.map { s -> s.map { Pt(it.x + offset * 0.76, it.y - offset * 0.65) } }
            val result = matcher.match(reference, body + moved)
            assertTrue("offset $offset: ${result.issues}", result.clean)
            val swapped = matcher.match(reference, body + moved.reversed())
            assertTrue("swapped offset $offset: ${swapped.issues}", swapped.issues.any { it.type == IssueType.WrongOrder })
        }
    }

    @Test fun genuinelySwappedAndReversedDakutenTicksRemainMistakes() {
        val reference = body + ticks
        assertTrue(matcher.match(reference, body + ticks.reversed()).issues.any { it.type == IssueType.WrongOrder })
        assertTrue(matcher.match(reference, body + listOf(ticks[0].reversed(), ticks[1])).issues.any { it.type == IssueType.Reversed })
        assertFalse(matcher.match(reference, body + ticks.take(1)).clean)
        assertFalse(matcher.match(reference, body + listOf(ticks[0] + ticks[1])).clean)
    }

    @Test fun clockwiseHandakutenAcceptsEveryStartAndCounterclockwiseIsReversed() {
        val reference = body + listOf(circle(PI / 2))
        for (phase in 0 until 24) {
            val angle = phase * PI / 12
            val clockwise = matcher.match(reference, body + listOf(circle(angle)))
            assertTrue("clockwise $phase: ${clockwise.issues}", clockwise.clean)
            val counterclockwise = matcher.match(reference, body + listOf(circle(angle, clockwise = false)))
            assertTrue("counterclockwise $phase: ${counterclockwise.issues}", counterclockwise.issues.any { it.type == IssueType.Reversed })
            assertTrue(counterclockwise.issues.none { it.type == IssueType.MissingHook || it.type == IssueType.ExtraHook })
        }
    }

    @Test fun openAndDegenerateHandakutenShapesAreNotAccepted() {
        val reference = body + listOf(circle(PI / 2))
        val negatives = listOf(
            circle(0.0).take(37),
            (0..48).map { i -> Pt(90.0 + 7 * sin(i * PI / 24), 18.0 + 7 * sin(i * PI / 12)) },
            listOf(Pt(83.0, 18.0), Pt(97.0, 18.0), Pt(83.0, 18.0)),
            (0..48).map { i ->
                val angle = i * PI / 20
                val radius = 3.0 + i / 12.0
                Pt(90.0 + radius * cos(angle), 18.0 + radius * sin(angle))
            },
        )
        negatives.forEachIndexed { index, stroke ->
            assertFalse("negative $index", matcher.match(reference, body + listOf(stroke)).clean)
        }
    }

    @Test fun kanjiParallelBarsKeepWrongOrderDirectionAndStrokeBoundaries() {
        val bars = listOf(
            listOf(Pt(20.0, 20.0), Pt(90.0, 20.0)),
            listOf(Pt(25.0, 40.0), Pt(85.0, 40.0)),
            listOf(Pt(15.0, 80.0), Pt(95.0, 80.0)),
        )
        assertTrue(matcher.match(bars, bars).clean)
        assertTrue(matcher.match(bars, listOf(bars[1], bars[0], bars[2])).issues.any { it.type == IssueType.WrongOrder })
        assertTrue(matcher.match(bars, listOf(bars[0].reversed()) + bars.drop(1)).issues.any { it.type == IssueType.Reversed })
        assertFalse(matcher.match(bars, listOf(bars[0] + bars[1], bars[2])).clean)
        assertFalse(matcher.match(bars, listOf(bars[0].take(1), bars[0].drop(1)) + bars.drop(1)).clean)
    }

    @Test fun openKanjiHooksStillRequireTheCorrectTail() {
        val bar = listOf(Pt(10.0, 10.0), Pt(95.0, 10.0))
        val hooked = listOf(Pt(30.0, 20.0), Pt(70.0, 80.0), Pt(90.0, 95.0), Pt(92.0, 85.0))
        val reference = listOf(bar, hooked)
        assertTrue(matcher.match(reference, reference).clean)
        val missing = matcher.match(reference, listOf(bar, hooked.dropLast(1)))
        assertTrue("${missing.issues}", missing.issues.any { it.type == IssueType.MissingHook })
    }

    @Test fun nearClosedReturnsAndSelfTouchingLoopsAreNotCircleReferences() {
        assertNull(loopArea(listOf(Pt(20.0, 20.0), Pt(20.0, 90.0), Pt(27.0, 90.0), Pt(27.0, 26.0))))
        assertNull(loopArea(listOf(
            Pt(83.0, 11.0), Pt(97.0, 11.0), Pt(97.0, 25.0), Pt(83.0, 25.0),
            Pt(83.0, 11.0), Pt(81.0, 10.0), Pt(82.0, 9.0), Pt(83.0, 11.0),
        )))
    }

    private fun circle(start: Double, clockwise: Boolean = true): Stroke = (0..48).map { i ->
        val angle = start + (if (clockwise) 1 else -1) * i * PI / 24
        Pt(90.0 + 7 * cos(angle), 18.0 + 7 * sin(angle))
    }
}
