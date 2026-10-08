package io.github.stopcran.kanji.core.draw

import kotlin.math.hypot
import kotlin.math.max

/**
 * Drops wobble far smaller than the drawing (Douglas-Peucker, tolerance = [smoothing] of the drawing size)
 * and resamples to [samples] points. Used both before matching and to display the drawn strokes.
 */
fun regularize(strokes: List<Stroke>, smoothing: Double, samples: Int): List<Stroke> {
    if (strokes.isEmpty()) return strokes
    val b = boxOf(strokes)
    val eps = max(b.w, b.h) * smoothing
    return strokes.map { s -> if (s.size < 3) s else simplify(s, eps).resample(samples) }
}

private fun simplify(s: Stroke, eps: Double): Stroke {
    if (s.size < 3) return s
    val a = s.first()
    val z = s.last()
    var worst = 0.0
    var at = 0
    for (i in 1 until s.size - 1) {
        val d = segDist(s[i], a, z)
        if (d > worst) { worst = d; at = i }
    }
    if (worst <= eps) return listOf(a, z)
    return simplify(s.subList(0, at + 1), eps).dropLast(1) + simplify(s.subList(at, s.size), eps)
}

private fun segDist(p: Pt, a: Pt, b: Pt): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val l2 = dx * dx + dy * dy
    val t = if (l2 < 1e-12) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}
