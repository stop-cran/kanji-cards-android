package io.github.stopcran.kanji.core.draw

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/** How a drawn stroke that was matched to a reference stroke differs from it; shown in place of a generic "wrong shape". */
enum class ShapeFault(val phrase: String) {
    TooLow("too low"),
    TooHigh("too high"),
    TooFarLeft("too far left"),
    TooFarRight("too far right"),
    TooShort("too short"),
    TooLong("too long"),
    SlopesDown("sloping down too much"),
    SlopesUp("sloping up too much"),
    LeansRight("with its top too far right"),
    LeansLeft("with its top too far left"),
    TooSteep("too steep"),
    TooFlat("too flat"),
    TooCurved("too curved"),
    TooStraight("too straight"),
}

/** Tolerances are in the 109-unit KanjiVG box, like [MatcherConfig]. */
internal object ShapeDiagnosis {
    private const val OFFSET = 6.0
    private const val SHORT_RATIO = 0.75
    private const val LONG_RATIO = 1.35
    private const val MIN_LENGTH = 14.0
    private const val ANGLE = 15.0
    private const val BEND = 0.08
    private const val MAX_FAULTS = 3

    /** Compares [drawn] (already normalized into the reference box) to [ref]; empty when no single cause stands out. */
    fun diagnose(ref: Stroke, drawn: Stroke): List<ShapeFault> {
        if (ref.size < 2 || drawn.size < 2) return emptyList()
        val refLen = ref.length()
        if (refLen < MIN_LENGTH) return emptyList()
        val ratio = drawn.length() / refLen
        val refAngle = lineAngle(ref)
        val axis = when {
            fold(refAngle) < 25.0 -> Axis.Horizontal
            fold(refAngle) > 65.0 -> Axis.Vertical
            else -> Axis.Diagonal
        }
        val lengthOff = ratio < SHORT_RATIO || ratio > LONG_RATIO
        return (position(boxOf(listOf(ref)), boxOf(listOf(drawn)), axis, lengthOff) + length(ratio) + angle(ref, drawn, axis) + curvature(ref, drawn))
            .take(MAX_FAULTS)
    }

    private enum class Axis { Horizontal, Vertical, Diagonal }

    /** A stroke that is too short or long also moves its centre along its own axis, so that axis is not reported. */
    private fun position(ref: Box, drawn: Box, axis: Axis, lengthOff: Boolean): List<ShapeFault> {
        val dx = drawn.cx - ref.cx
        val dy = drawn.cy - ref.cy
        val faults = ArrayList<ShapeFault>()
        if (abs(dy) >= OFFSET && !(lengthOff && axis != Axis.Horizontal)) faults += if (dy > 0) ShapeFault.TooLow else ShapeFault.TooHigh
        if (abs(dx) >= OFFSET && !(lengthOff && axis != Axis.Vertical)) faults += if (dx > 0) ShapeFault.TooFarRight else ShapeFault.TooFarLeft
        return faults
    }

    private fun length(ratio: Double): List<ShapeFault> = when {
        ratio < SHORT_RATIO -> listOf(ShapeFault.TooShort)
        ratio > LONG_RATIO -> listOf(ShapeFault.TooLong)
        else -> emptyList()
    }

    private fun angle(ref: Stroke, drawn: Stroke, axis: Axis): List<ShapeFault> {
        val turn = signedTurn(lineAngle(drawn) - lineAngle(ref))
        if (abs(turn) < ANGLE) return emptyList()
        val clockwise = turn > 0
        return listOf(
            when (axis) {
                Axis.Horizontal -> if (clockwise) ShapeFault.SlopesDown else ShapeFault.SlopesUp
                Axis.Vertical -> if (clockwise) ShapeFault.LeansRight else ShapeFault.LeansLeft
                Axis.Diagonal -> if (fold(lineAngle(drawn)) > fold(lineAngle(ref))) ShapeFault.TooSteep else ShapeFault.TooFlat
            },
        )
    }

    private fun curvature(ref: Stroke, drawn: Stroke): List<ShapeFault> {
        val diff = bend(drawn) - bend(ref)
        return when {
            diff >= BEND -> listOf(ShapeFault.TooCurved)
            diff <= -BEND -> listOf(ShapeFault.TooStraight)
            else -> emptyList()
        }
    }
    /** Direction of the first-to-last chord in degrees on screen (y grows downwards, so positive turns are clockwise). */
    private fun lineAngle(s: Stroke): Double = atan2(s.last().y - s.first().y, s.last().x - s.first().x) * 180 / PI

    /** Angle of the line, ignoring direction, from the nearest horizontal: 0 = level, 90 = upright. */
    private fun fold(angle: Double): Double {
        val a = ((angle % 180) + 180) % 180
        return min(a, 180 - a)
    }

    /** Turn between two chord angles ignoring direction (a reversed stroke has its own message), in -90..90. */
    private fun signedTurn(diff: Double): Double {
        var a = ((diff % 180) + 180) % 180
        if (a > 90) a -= 180
        return a
    }

    /** Largest distance of the stroke from its chord as a fraction of the chord length. */
    private fun bend(s: Stroke): Double {
        val a = s.first()
        val b = s.last()
        val chord = hypot(b.x - a.x, b.y - a.y)
        if (chord < 1e-6) return 0.0
        val worst = s.maxOf { abs((b.x - a.x) * (a.y - it.y) - (a.x - it.x) * (b.y - a.y)) / chord }
        return worst / max(chord, 1e-6)
    }
}
