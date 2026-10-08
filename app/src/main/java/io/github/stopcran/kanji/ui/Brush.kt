package io.github.stopcran.kanji.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class BrushStyle(val label: String, val hint: String) {
    Dot("Dot pen", "constant round line"),
    Chisel("Chisel nib", "diagonal nib: thick and thin by direction"),
    Soft("Soft brush", "gentle entry swell and tapered end"),
    Ink("Ink brush", "stronger taper, slightly uneven edge"),
    ;

    companion object {
        val Default = Chisel
        fun parse(name: String?) = entries.firstOrNull { it.name == name } ?: Default
    }
}

val LocalBrush = compositionLocalOf { BrushStyle.Default }

/**
 * Draws one stroke in the given brush. All sizes are fractions of [canvasWidth], so the pad, the answer
 * panels and the settings preview look alike. The width comes from direction (chisel) or from the position
 * along the stroke (soft, ink); it never changes grading, which uses the raw points.
 */
fun DrawScope.drawBrushStroke(rawPoints: List<Offset>, color: Color, style: BrushStyle, canvasWidth: Float = size.width) {
    if (rawPoints.isEmpty()) return
    val w = canvasWidth
    val points = smoothLongSegments(rawPoints, w * 0.012f, w * 0.003f)
    when (style) {
        BrushStyle.Dot -> dot(points, color, w * 0.011f)
        BrushStyle.Chisel -> chisel(densify(points, w * 0.003f), color, w)
        BrushStyle.Soft -> variable(densify(points, w * 0.003f), color, w * 0.022f, endWidth = 0.4f, rough = 0f)
        BrushStyle.Ink -> variable(densify(points, w * 0.003f), color, w * 0.026f, endWidth = 0.12f, rough = 0.07f)
    }
}

private fun DrawScope.dot(points: List<Offset>, color: Color, width: Float) {
    if (points.size == 1) {
        drawCircle(color, width / 2, points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, color, style = DrawStroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.chisel(pts: List<Offset>, color: Color, w: Float) {
    val half = w * 0.013f
    val a = Offset(cos(-PI / 4).toFloat() * half, sin(-PI / 4).toFloat() * half)
    val thin = w * 0.004f
    val path = Path()
    // Swept area of the nib between consecutive stamps; orientation is normalised so overlaps never cancel.
    for (i in 0 until pts.size - 1) {
        val p = pts[i]
        val q = pts[i + 1]
        val cross = (q.x - p.x) * a.y - (q.y - p.y) * a.x
        val c = if (cross >= 0) listOf(p + a, q + a, q - a, p - a) else listOf(p - a, q - a, q + a, p + a)
        path.moveTo(c[0].x, c[0].y)
        for (k in 1..3) path.lineTo(c[k].x, c[k].y)
        path.close()
    }
    if (pts.isNotEmpty()) {
        drawPath(path, color, style = Fill)
        drawPath(path, color, style = DrawStroke(thin, join = StrokeJoin.Round))
        for (p in listOf(pts.first(), pts.last())) drawLine(color, p - a, p + a, thin, StrokeCap.Round)
    }
}

private fun DrawScope.variable(pts: List<Offset>, color: Color, peak: Float, endWidth: Float, rough: Float) {
    if (pts.size < 2) {
        drawCircle(color, peak * 0.4f, pts[0])
        return
    }
    val n = pts.size
    fun half(i: Int): Float {
        val s = i.toFloat() / (n - 1)
        val entry = 0.7f + 0.3f * (s / 0.12f).coerceAtMost(1f)
        val taper = if (s > 0.65f) 1f - (1f - endWidth) * ((s - 0.65f) / 0.35f) else 1f
        val wobble = 1f + rough * (sin(i * 0.19f + 1.3f) + 0.6f * sin(i * 0.43f))
        return peak * entry * taper * wobble / 2
    }
    val left = ArrayList<Offset>(n)
    val right = ArrayList<Offset>(n)
    for (i in 0 until n) {
        val prev = pts[maxOf(i - 3, 0)]
        val next = pts[minOf(i + 3, n - 1)]
        val dx = next.x - prev.x
        val dy = next.y - prev.y
        val len = hypot(dx, dy).coerceAtLeast(1e-3f)
        val nx = -dy / len
        val ny = dx / len
        val h = half(i)
        left += Offset(pts[i].x + nx * h, pts[i].y + ny * h)
        right += Offset(pts[i].x - nx * h, pts[i].y - ny * h)
    }
    val path = Path().apply {
        moveTo(left[0].x, left[0].y)
        for (i in 1 until n) lineTo(left[i].x, left[i].y)
        for (i in n - 1 downTo 0) lineTo(right[i].x, right[i].y)
        close()
    }
    drawPath(path, color, style = Fill)
    drawCircle(color, half(0), pts.first())
    drawCircle(color, half(n - 1), pts.last())
}

/**
 * Fast strokes leave few touch samples, so the line would show as visible straight segments. Segments longer
 * than [minSeg] are replaced by a Catmull-Rom curve through the neighbouring samples; short ones stay as they are.
 */
internal fun smoothLongSegments(pts: List<Offset>, minSeg: Float, step: Float): List<Offset> {
    if (pts.size < 3) return pts
    val out = ArrayList<Offset>()
    out += pts[0]
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        val d = hypot(b.x - a.x, b.y - a.y)
        if (d > minSeg) {
            val p0 = pts[maxOf(i - 1, 0)]
            val p3 = pts[minOf(i + 2, pts.size - 1)]
            val k = (d / step).toInt().coerceIn(2, 60)
            for (j in 1 until k) {
                val t = j.toFloat() / k
                val t2 = t * t
                val t3 = t2 * t
                out += Offset(
                    0.5f * (2 * a.x + (-p0.x + b.x) * t + (2 * p0.x - 5 * a.x + 4 * b.x - p3.x) * t2 + (-p0.x + 3 * a.x - 3 * b.x + p3.x) * t3),
                    0.5f * (2 * a.y + (-p0.y + b.y) * t + (2 * p0.y - 5 * a.y + 4 * b.y - p3.y) * t2 + (-p0.y + 3 * a.y - 3 * b.y + p3.y) * t3),
                )
            }
        }
        out += b
    }
    return out
}

/** Inserts points so that no gap is longer than [step]. */
internal fun densify(points: List<Offset>, step: Float): List<Offset> {
    if (points.size < 2) return points
    val out = ArrayList<Offset>()
    out += points[0]
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val d = hypot(b.x - a.x, b.y - a.y)
        if (d < 1e-3f) continue
        val k = (d / step).toInt()
        for (j in 1..k) {
            val t = j * step / d
            if (t < 1f) out += Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        out += b
    }
    return out
}

/** Typical strokes (horizontal, vertical, falling left, falling right, dot) so the brush can be judged in Settings. */
@androidx.compose.runtime.Composable
fun BrushPreview(style: BrushStyle) {
    androidx.compose.foundation.Canvas(
        androidx.compose.ui.Modifier
            .fillMaxWidth()
            .aspectRatio(2.2f)
            .background(Color.White),
    ) {
        val w = size.width
        val h = size.height
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, bend: Float = 0f): List<Offset> =
            (0..24).map { i ->
                val t = i / 24f
                val b = kotlin.math.sin(PI.toFloat() * t) * bend
                Offset((x1 + (x2 - x1) * t) * w + b * h, (y1 + (y2 - y1) * t) * h)
            }
        val ink = Color(0xFF1A237E)
        drawBrushStroke(line(0.06f, 0.3f, 0.34f, 0.28f), ink, style, w)
        drawBrushStroke(line(0.2f, 0.12f, 0.2f, 0.88f), ink, style, w)
        drawBrushStroke(line(0.56f, 0.2f, 0.42f, 0.8f, -0.05f), ink, style, w)
        drawBrushStroke(line(0.58f, 0.3f, 0.78f, 0.85f, 0.05f), ink, style, w)
        drawBrushStroke(line(0.88f, 0.3f, 0.92f, 0.4f), ink, style, w)
    }
}
