package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.ShapeDiagnosis
import io.github.stopcran.kanji.core.draw.ShapeFault
import io.github.stopcran.kanji.core.draw.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeDiagnosisTest {
    private fun line(x0: Double, y0: Double, x1: Double, y1: Double): Stroke = List(11) { Pt(x0 + (x1 - x0) * it / 10, y0 + (y1 - y0) * it / 10) }

    private val bar = line(20.0, 50.0, 80.0, 50.0)
    private val post = line(50.0, 20.0, 50.0, 80.0)
    private val slash = line(70.0, 20.0, 30.0, 80.0)

    @Test
    fun matchingStrokeHasNoFaults() {
        assertTrue(ShapeDiagnosis.diagnose(bar, line(21.0, 51.0, 80.0, 49.0)).isEmpty())
    }

    @Test
    fun reportsPosition() {
        assertEquals(listOf(ShapeFault.TooLow), ShapeDiagnosis.diagnose(bar, line(20.0, 62.0, 80.0, 62.0)))
        assertEquals(listOf(ShapeFault.TooHigh), ShapeDiagnosis.diagnose(bar, line(20.0, 38.0, 80.0, 38.0)))
        assertEquals(listOf(ShapeFault.TooFarLeft), ShapeDiagnosis.diagnose(post, line(38.0, 20.0, 38.0, 80.0)))
        assertEquals(listOf(ShapeFault.TooFarRight), ShapeDiagnosis.diagnose(post, line(62.0, 20.0, 62.0, 80.0)))
    }

    @Test
    fun lengthDoesNotCauseAnAlongStrokePositionFault() {
        assertEquals(listOf(ShapeFault.TooShort), ShapeDiagnosis.diagnose(bar, line(20.0, 50.0, 50.0, 50.0)))
        assertEquals(listOf(ShapeFault.TooLong), ShapeDiagnosis.diagnose(post, line(50.0, 10.0, 50.0, 100.0)))
    }

    @Test
    fun reportsAngle() {
        assertEquals(listOf(ShapeFault.SlopesDown), ShapeDiagnosis.diagnose(bar, line(20.0, 38.0, 80.0, 62.0)))
        assertEquals(listOf(ShapeFault.SlopesUp), ShapeDiagnosis.diagnose(bar, line(20.0, 62.0, 80.0, 38.0)))
        assertEquals(listOf(ShapeFault.LeansRight), ShapeDiagnosis.diagnose(post, line(62.0, 20.0, 38.0, 80.0)))
        assertEquals(listOf(ShapeFault.LeansLeft), ShapeDiagnosis.diagnose(post, line(38.0, 20.0, 62.0, 80.0)))
        assertEquals(listOf(ShapeFault.TooFlat), ShapeDiagnosis.diagnose(slash, line(85.0, 35.0, 15.0, 65.0)))
        assertEquals(listOf(ShapeFault.TooSteep), ShapeDiagnosis.diagnose(slash, line(58.0, 10.0, 42.0, 90.0)))
    }

    @Test
    fun reportsCurvature() {
        val arc: Stroke = List(11) { Pt(20.0 + 6.0 * it, 50.0 - 10.0 * (1 - Math.pow((it - 5) / 5.0, 2.0))) }
        assertEquals(listOf(ShapeFault.TooCurved), ShapeDiagnosis.diagnose(bar, arc))
        assertEquals(listOf(ShapeFault.TooStraight), ShapeDiagnosis.diagnose(arc, bar))
    }

    @Test
    fun reversedStrokeIsNotAnAngleFault() {
        assertTrue(ShapeDiagnosis.diagnose(bar, bar.reversed()).isEmpty())
    }
}
