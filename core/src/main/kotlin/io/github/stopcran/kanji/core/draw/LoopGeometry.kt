package io.github.stopcran.kanji.core.draw

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Signed enclosed area: positive is clockwise in screen coordinates. Null means this is not a simple loop. */
internal fun loopArea(stroke: Stroke): Double? {
    if (stroke.size < 4) return null
    val length = stroke.length()
    if (length < 1e-6 || hypot(stroke.first().x - stroke.last().x, stroke.first().y - stroke.last().y) > length * 0.15) return null
    val ring = stroke.dropLast(1)
    val area = ring.indices.sumOf { i ->
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        a.x * b.y - b.x * a.y
    } / 2
    // A circle has area/length² ~= 0.08; thin out-and-back strokes and figure eights are not circles.
    if (abs(area) < length * length * 0.04) return null
    for (i in ring.indices) for (j in i + 2 until ring.size) {
        if (i == 0 && j == ring.lastIndex) continue
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        val c = ring[j]
        val d = ring[(j + 1) % ring.size]
        if (intersects(a, b, c, d)) return null
    }
    return area
}

private fun side(a: Pt, b: Pt, p: Pt): Double = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

private fun intersects(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
    val abC = side(a, b, c)
    val abD = side(a, b, d)
    val cdA = side(c, d, a)
    val cdB = side(c, d, b)
    if (abC * abD < 0 && cdA * cdB < 0) return true
    return onSegment(a, b, c, abC) || onSegment(a, b, d, abD) ||
        onSegment(c, d, a, cdA) || onSegment(c, d, b, cdB)
}

private fun onSegment(a: Pt, b: Pt, p: Pt, cross: Double): Boolean =
    abs(cross) < 1e-9 && p.x in min(a.x, b.x)..max(a.x, b.x) && p.y in min(a.y, b.y)..max(a.y, b.y)

/** Compare equally sampled loops without making their pen-down location part of the shape. */
internal fun loopDist(drawn: Stroke, reference: Stroke): Double {
    val n = drawn.size - 1
    var best = Double.MAX_VALUE
    for (offset in 0 until n) {
        var distance = 0.0
        for (i in 0 until n) {
            val a = drawn[(i + offset) % n]
            val b = reference[i]
            distance += hypot(a.x - b.x, a.y - b.y)
        }
        best = min(best, distance / n)
    }
    return best
}
