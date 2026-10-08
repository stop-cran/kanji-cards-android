package io.github.stopcran.kanji.core.draw

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Double, val y: Double)

typealias Stroke = List<Pt>

internal fun Stroke.length(): Double {
    var s = 0.0
    for (i in 1 until size) s += hypot(this[i].x - this[i - 1].x, this[i].y - this[i - 1].y)
    return s
}

/** Resamples to [n] points evenly spaced by arc length (a zero-length stroke becomes n copies of its point). */
internal fun Stroke.resample(n: Int): Stroke {
    val total = length()
    if (size == 1 || total < 1e-9) return List(n) { first() }
    val out = ArrayList<Pt>(n)
    var seg = 1
    var acc = 0.0
    var segLen = hypot(this[1].x - this[0].x, this[1].y - this[0].y)
    for (k in 0 until n) {
        val target = total * k / (n - 1)
        while (seg < size - 1 && acc + segLen < target) {
            acc += segLen
            seg++
            segLen = hypot(this[seg].x - this[seg - 1].x, this[seg].y - this[seg - 1].y)
        }
        val t = if (segLen < 1e-12) 0.0 else ((target - acc) / segLen).coerceIn(0.0, 1.0)
        out += Pt(this[seg - 1].x + (this[seg].x - this[seg - 1].x) * t, this[seg - 1].y + (this[seg].y - this[seg - 1].y) * t)
    }
    return out
}

internal data class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val w get() = maxX - minX
    val h get() = maxY - minY
    val cx get() = (minX + maxX) / 2
    val cy get() = (minY + maxY) / 2
}

internal fun boxOf(strokes: Collection<Stroke>): Box {
    val pts = strokes.flatten()
    return Box(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
}

/** Mean point-to-point distance between two equally sampled strokes. */
internal fun meanDist(a: Stroke, b: Stroke): Double {
    var s = 0.0
    for (i in a.indices) s += hypot(a[i].x - b[i].x, a[i].y - b[i].y)
    return s / a.size
}

enum class IssueType { Reversed, WrongOrder, WrongShape, Joined, Broken, Missing, Extra }

/**
 * One mistake. [refIndex] is the 0-based reference stroke (stroke order position) and [drawnIndex] the 0-based
 * index in the drawing; either may be null (Missing has no drawn stroke, Extra has no reference stroke).
 */
data class StrokeIssue(val type: IssueType, val refIndex: Int?, val drawnIndex: Int?)

data class MatchResult(
    val issues: List<StrokeIssue>,
    val referenceStrokes: Int,
    val drawnStrokes: Int,
    /** Reference strokes found in the drawing, possibly with order or direction mistakes. */
    val matchedStrokes: Int,
) {
    val clean: Boolean get() = issues.isEmpty()

    /** True when most of the kanji is there; below this the drawing is "not the kanji" as far as the matcher can tell. */
    val recognizable: Boolean get() = referenceStrokes > 0 && matchedStrokes * 10 >= max(referenceStrokes, drawnStrokes) * 6

    /** Reference stroke indexes (0-based) to highlight in the answer overlay. */
    val flaggedRefStrokes: Set<Int> get() = issues.mapNotNull { it.refIndex }.toSet()
}

/** Tolerances are fractions of the 109-unit KanjiVG box. */
data class MatcherConfig(
    val samples: Int = 24,
    /** Above this mean distance a drawn stroke is not considered to be the reference stroke at all. */
    val matchLimit: Double = 0.17,
    /** Below this a matched stroke is accepted as correctly drawn. */
    val shapeLimit: Double = 0.13,
    /** Accepted drawn/reference length ratio for strokes longer than [shortStroke] units. */
    val minLengthRatio: Double = 0.55,
    val maxLengthRatio: Double = 1.7,
    val shortStroke: Double = 14.0,
    /** Extra cost for explaining one drawn stroke as two reference strokes or vice versa, so plain matches win. */
    val joinPenalty: Double = 0.03,
    /** Wobble below this fraction of the drawing size is ignored. */
    val smoothing: Double = 0.02,
    /** Extra shape tolerance for strokes up to [dotLength] units long, tapering to zero at [relaxedUntil]. */
    val shortStrokeBonus: Double = 0.10,
    val dotLength: Double = 15.0,
    val relaxedUntil: Double = 50.0,
    val box: Double = 109.0,
)

/**
 * Compares a drawing against reference strokes in stroke order. Wrong stroke order, reverse direction,
 * joined or broken strokes and wrong shapes are reported separately so the UI can show what went wrong.
 * Pure Kotlin, no Android dependencies.
 */
class StrokeMatcher(private val cfg: MatcherConfig = MatcherConfig()) {

    private sealed interface Link {
        val cost: Double
        data class One(val d: Int, val r: Int, override val cost: Double, val reversed: Boolean, val badLength: Boolean, val limit: Double) : Link
        /** One drawn stroke covers reference strokes r and r+1. */
        data class Join(val d: Int, val r: Int, override val cost: Double) : Link
        /** Drawn strokes d and d+1 together make up reference stroke r. */
        data class Split(val d: Int, val r: Int, override val cost: Double) : Link
    }

    fun match(reference: List<Stroke>, rawDrawn: List<Stroke>): MatchResult {
        val drawn = regularize(rawDrawn, cfg.smoothing, cfg.samples)
        if (reference.isEmpty() || drawn.isEmpty()) return MatchResult(emptyList(), reference.size, drawn.size, 0)
        var links = link(reference, normalize(drawn, boxOf(drawn), boxOf(reference)))
        // Refit using only what was found, so a missing or extra stroke does not distort the rest.
        repeat(2) {
            val usedD = links.flatMap { l -> when (l) { is Link.One -> listOf(l.d); is Link.Join -> listOf(l.d); is Link.Split -> listOf(l.d, l.d + 1) } }
            val usedR = links.flatMap { l -> when (l) { is Link.One -> listOf(l.r); is Link.Join -> listOf(l.r, l.r + 1); is Link.Split -> listOf(l.r) } }
            if (usedD.size >= 2 && usedR.size >= 2) {
                links = link(reference, normalize(drawn, boxOf(usedD.map { drawn[it] }), boxOf(usedR.map { reference[it] })))
            }
        }
        return classify(reference.size, drawn.size, links)
    }

    /** Maps [from] onto [to]; scaling is per axis (within 1.4x of uniform) unless an axis is nearly flat. */
    private fun normalize(drawn: List<Stroke>, from: Box, to: Box): List<Stroke> {
        val big = max(from.w, from.h).coerceAtLeast(1e-6)
        val refBig = max(to.w, to.h).coerceAtLeast(1e-6)
        val uniform = refBig / big
        fun axis(srcLen: Double, dstLen: Double): Double =
            if (srcLen < 0.2 * big || dstLen < 0.2 * refBig) uniform else (dstLen / srcLen).coerceIn(uniform / 1.4, uniform * 1.4)
        val sx = axis(from.w, to.w)
        val sy = axis(from.h, to.h)
        return drawn.map { s -> s.map { p -> Pt(to.cx + (p.x - from.cx) * sx, to.cy + (p.y - from.cy) * sy) } }
    }

    private fun link(reference: List<Stroke>, drawn: List<Stroke>): List<Link> {
        val n = cfg.samples
        val refR = reference.map { it.resample(n) }
        val drR = drawn.map { it.resample(n) }
        val refLen = reference.map { it.length() }
        val drLen = drawn.map { it.length() }
        val cands = ArrayList<Link>()

        for (d in drawn.indices) for (r in reference.indices) {
            val fwd = meanDist(drR[d], refR[r]) / cfg.box
            val rev = meanDist(drR[d].reversed(), refR[r]) / cfg.box
            val cost = min(fwd, rev)
            if (cost >= cfg.matchLimit) continue
            val reversed = (rev < fwd * 0.7 && fwd - rev > 0.015) || (chordCosine(drawn[d], reference[r]) < -0.3 && refLen[r] > 10.0)
            val ratio = drLen[d] / max(refLen[r], 1e-6)
            val badLength = refLen[r] > cfg.shortStroke && (ratio < cfg.minLengthRatio || ratio > cfg.maxLengthRatio)
            cands += Link.One(d, r, cost, reversed, badLength, shapeLimitFor(refLen[r]))
        }
        for (d in drawn.indices) for (r in 0 until reference.size - 1) {
            val c = meanDist(drR[d], (reference[r] + reference[r + 1]).resample(n)) / cfg.box + cfg.joinPenalty
            if (c < cfg.matchLimit) cands += Link.Join(d, r, c)
        }
        for (d in 0 until drawn.size - 1) for (r in reference.indices) {
            val c = meanDist((drawn[d] + drawn[d + 1]).resample(n), refR[r]) / cfg.box + cfg.joinPenalty
            if (c < cfg.matchLimit) cands += Link.Split(d, r, c)
        }

        val usedD = BooleanArray(drawn.size)
        val usedR = BooleanArray(reference.size)
        val chosen = ArrayList<Link>()
        for (l in cands.sortedBy { it.cost }) {
            val ds: List<Int>
            val rs: List<Int>
            when (l) {
                is Link.One -> { ds = listOf(l.d); rs = listOf(l.r) }
                is Link.Join -> { ds = listOf(l.d); rs = listOf(l.r, l.r + 1) }
                is Link.Split -> { ds = listOf(l.d, l.d + 1); rs = listOf(l.r) }
            }
            if (ds.any { usedD[it] } || rs.any { usedR[it] }) continue
            ds.forEach { usedD[it] = true }
            rs.forEach { usedR[it] = true }
            chosen += l
        }
        return chosen
    }

    /** Short strokes (dots, ticks) vary a lot in real handwriting, so they get a looser shape limit that tapers off with length. */
    private fun shapeLimitFor(refLength: Double): Double {
        val t = ((refLength - cfg.dotLength) / (cfg.relaxedUntil - cfg.dotLength)).coerceIn(0.0, 1.0)
        return cfg.shapeLimit + cfg.shortStrokeBonus * (1 - t)
    }

    private fun chordCosine(a: Stroke, b: Stroke): Double {
        val ax = a.last().x - a.first().x
        val ay = a.last().y - a.first().y
        val bx = b.last().x - b.first().x
        val by = b.last().y - b.first().y
        val na = hypot(ax, ay)
        val nb = hypot(bx, by)
        return if (na < 1e-6 || nb < 1e-6) 1.0 else (ax * bx + ay * by) / (na * nb)
    }

    private fun classify(refCount: Int, drawnCount: Int, links: List<Link>): MatchResult {
        val issues = ArrayList<StrokeIssue>()
        val usedD = BooleanArray(drawnCount)
        val usedR = BooleanArray(refCount)
        // (drawn index, first ref index) for every explained link, to check stroke order.
        val order = ArrayList<Pair<Int, Int>>()

        for (l in links) when (l) {
            is Link.One -> {
                usedD[l.d] = true; usedR[l.r] = true
                order += l.d to l.r
                if (l.reversed) issues += StrokeIssue(IssueType.Reversed, l.r, l.d)
                if (l.cost > l.limit || l.badLength) issues += StrokeIssue(IssueType.WrongShape, l.r, l.d)
            }
            is Link.Join -> {
                usedD[l.d] = true; usedR[l.r] = true; usedR[l.r + 1] = true
                order += l.d to l.r
                issues += StrokeIssue(IssueType.Joined, l.r, l.d)
            }
            is Link.Split -> {
                usedD[l.d] = true; usedD[l.d + 1] = true; usedR[l.r] = true
                order += l.d to l.r
                issues += StrokeIssue(IssueType.Broken, l.r, l.d)
            }
        }

        // Strokes outside the longest increasing run of reference indexes were drawn at the wrong time.
        val seq = order.sortedBy { it.first }
        val keep = longestIncreasing(seq.map { it.second })
        seq.forEachIndexed { i, (d, r) -> if (i !in keep) issues += StrokeIssue(IssueType.WrongOrder, r, d) }

        val leftD = usedD.indices.filter { !usedD[it] }
        val leftR = usedR.indices.filter { !usedR[it] }
        if (leftD.size == leftR.size) {
            leftD.zip(leftR).forEach { (d, r) -> issues += StrokeIssue(IssueType.WrongShape, r, d) }
        } else {
            leftR.forEach { issues += StrokeIssue(IssueType.Missing, it, null) }
            leftD.forEach { issues += StrokeIssue(IssueType.Extra, null, it) }
        }
        return MatchResult(issues.sortedWith(compareBy({ it.refIndex ?: Int.MAX_VALUE }, { it.type })), refCount, drawnCount, refCount - leftR.size)
    }

    /** Indexes (into [a]) of one longest strictly increasing subsequence. */
    private fun longestIncreasing(a: List<Int>): Set<Int> {
        if (a.isEmpty()) return emptySet()
        val len = IntArray(a.size) { 1 }
        val prev = IntArray(a.size) { -1 }
        for (i in a.indices) for (j in 0 until i) if (a[j] < a[i] && len[j] + 1 > len[i]) { len[i] = len[j] + 1; prev[i] = j }
        var best = len.indices.maxByOrNull { len[it] }!!
        val out = HashSet<Int>()
        while (best >= 0) { out += best; best = prev[best] }
        return out
    }
}
