package io.github.stopcran.kanji.core.draw

import kotlin.math.hypot
import kotlin.math.max

/**
 * Drops wobble far smaller than the drawing (Douglas-Peucker, tolerance = [smoothing] of the drawing size)
 * and resamples to [samples] points. Used both before matching and to display the drawn strokes.
 */
fun regularize(strokes: List<Stroke>, smoothing: Double, samples: Int, curved: Boolean = false): List<Stroke> {
    if (strokes.isEmpty()) return strokes
    val b = boxOf(strokes)
    val eps = max(b.w, b.h) * smoothing
    return strokes.map { s ->
        if (s.size < 3) s
        else {
            val v = simplify(s, eps)
            (if (curved) spline(v) else v).resample(samples)
        }
    }
}

/** The same wobble removal as [regularize] but without resampling, so small features such as hooks keep their corners. */
fun simplifyStrokes(strokes: List<Stroke>, smoothing: Double): List<Stroke> {
    if (strokes.isEmpty()) return strokes
    val b = boxOf(strokes)
    val eps = max(b.w, b.h) * smoothing
    return strokes.map { if (it.size < 3) it else simplify(it, eps) }
}

// Catmull-Rom through the vertices, so simplified strokes look like curves instead of polygons.
private fun spline(v: Stroke): Stroke {
    if (v.size < 3) return v
    val out = ArrayList<Pt>()
    for (i in 0 until v.size - 1) {
        val p0 = v[maxOf(i - 1, 0)]
        val p1 = v[i]
        val p2 = v[i + 1]
        val p3 = v[minOf(i + 2, v.size - 1)]
        for (k in 0 until 12) {
            val t = k / 12.0
            val t2 = t * t
            val t3 = t2 * t
            fun c(a: Double, b: Double, c: Double, d: Double) =
                0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (3 * b - a - 3 * c + d) * t3)
            out += Pt(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y))
        }
    }
    out += v.last()
    return out
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
