package io.github.stopcran.kanji.core.draw

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** [turn]: how sharply a stroke bends just before its end, in degrees (positive = clockwise on screen); [tail]: length of the bent part. */
internal data class EndHook(val turn: Double, val tail: Double)

internal fun endTurn(stroke: Stroke): Double = endHook(stroke).turn

/**
 * Finds the strongest turn between the stem (the 5 units before a corner) and the chord from that corner to the end,
 * with corners up to 12 units from the end. Coordinates are in 109-unit KanjiVG space.
 */
internal fun endHook(stroke: Stroke): EndHook {
    if (stroke.size < 3 || stroke.length() < 14.0) return EndHook(0.0, 0.0)
    val q = smooth(stroke.resampleBy(1.0))
    val last = q.lastIndex
    var best = 0.0
    var tail = 0.0
    for (c in (last - 12).coerceAtLeast(5)..last - 2) {
        val sx = q[c].x - q[c - 5].x
        val sy = q[c].y - q[c - 5].y
        val tx = q[last].x - q[c].x
        val ty = q[last].y - q[c].y
        if (hypot(sx, sy) < 4.0 || hypot(tx, ty) < 1.5) continue
        var a = (atan2(ty, tx) - atan2(sy, sx)) * 180 / PI
        while (a > 180) a -= 360
        while (a < -180) a += 360
        if (abs(a) > abs(best)) { best = a; tail = hypot(tx, ty) }
    }
    return EndHook(best, tail)
}

private fun smooth(q: Stroke): Stroke =
    q.indices.map { i ->
        if (i == 0 || i == q.lastIndex) q[i]
        else Pt((q[i - 1].x + q[i].x + q[i + 1].x) / 3, (q[i - 1].y + q[i].y + q[i + 1].y) / 3)
    }

/** Resamples to points about [step] apart along the stroke (the last point is kept). */
internal fun Stroke.resampleBy(step: Double): Stroke = resample((length() / step).toInt().coerceAtLeast(2) + 1)

/** A hooked reference needs a visible hook on the same side; a straight reference rejects a pronounced, long one. */
internal fun hookIssue(refTurn: Double, drawn: EndHook): IssueType? = when {
    abs(refTurn) >= HOOK_REF && (drawn.turn * refTurn <= 0 || abs(drawn.turn) < HOOK_MIN) -> IssueType.MissingHook
    abs(refTurn) < HOOK_FLAT && abs(drawn.turn) >= HOOK_EXTRA && drawn.tail >= HOOK_EXTRA_TAIL -> IssueType.ExtraHook
    else -> null
}

private const val HOOK_REF = 60.0
private const val HOOK_MIN = 35.0
private const val HOOK_FLAT = 35.0
private const val HOOK_EXTRA = 120.0
private const val HOOK_EXTRA_TAIL = 8.0
