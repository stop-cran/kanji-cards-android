package io.github.stopcran.kanji.core.draw

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Display-only variation of the reference strokes, so the student does not memorise one exact picture.
 * Each stroke gets a small shift, rotation, length change and gentle bow; stroke order and direction are untouched.
 * [amount] 1.0 is the default strength (about 1-2 units of the 109-unit box).
 */
fun varyReference(reference: List<Stroke>, seed: Int, amount: Double = 1.0): List<Stroke> {
    val rnd = Random(seed)
    fun r(range: Double) = (rnd.nextDouble() * 2 - 1) * range * amount
    return reference.map { s ->
        if (s.size < 2) return@map s
        val cx = s.sumOf { it.x } / s.size
        val cy = s.sumOf { it.y } / s.size
        val dx = r(1.2)
        val dy = r(1.2)
        val ang = r(2.5 * PI / 180)
        val scale = 1 + r(0.05)
        val bow = r(1.2)
        val ca = cos(ang)
        val sa = sin(ang)
        val chord = hypot(s.last().x - s.first().x, s.last().y - s.first().y).coerceAtLeast(1e-6)
        val nx = -(s.last().y - s.first().y) / chord
        val ny = (s.last().x - s.first().x) / chord
        s.mapIndexed { i, p ->
            val u = (p.x - cx) * scale
            val v = (p.y - cy) * scale
            val k = if (s.size > 1) sin(PI * i / (s.size - 1)) * bow else 0.0
            Pt(cx + u * ca - v * sa + dx + nx * k, cy + u * sa + v * ca + dy + ny * k)
        }
    }
}
