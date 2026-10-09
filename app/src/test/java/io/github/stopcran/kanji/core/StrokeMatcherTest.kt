package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.StrokeData
import io.github.stopcran.kanji.core.draw.IssueType
import io.github.stopcran.kanji.core.draw.LookalikeGate
import io.github.stopcran.kanji.core.draw.MatchResult
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.Stroke
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import io.github.stopcran.kanji.core.draw.endTurn
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Simulates a hand: global scale/rotation/offset, per-stroke wobble and offset, ragged sampling, shortened ends. */
private class Hand(private val rnd: Random) {
    private val sx = 1.6 + rnd.nextDouble(-0.35, 0.35)
    private val sy = 1.6 + rnd.nextDouble(-0.35, 0.35)
    private val rot = Math.toRadians(rnd.nextDouble(-4.0, 4.0))
    private val ox = 20 + rnd.nextDouble(-10.0, 10.0)
    private val oy = 20 + rnd.nextDouble(-10.0, 10.0)

    fun draw(ref: Stroke): Stroke {
        val n = rnd.nextInt(12, 40)
        val dx = rnd.nextDouble(-2.5, 2.5)
        val dy = rnd.nextDouble(-2.5, 2.5)
        val a1 = rnd.nextDouble(0.5, 1.8)
        val f1 = rnd.nextDouble(0.5, 2.5)
        val ph = rnd.nextDouble(0.0, 6.28)
        val trimEnd = rnd.nextDouble(0.0, 0.06).takeIf { kotlin.math.abs(endTurn(ref)) < 60.0 } ?: 0.0
        val pts = ref.map { it }
        val total = (1 until pts.size).sumOf { hypot(pts[it].x - pts[it - 1].x, pts[it].y - pts[it - 1].y) }
        val out = ArrayList<Pt>()
        for (k in 0 until n) {
            val t = k.toDouble() / (n - 1) * (1 - trimEnd)
            val p = at(pts, t * total)
            val w = a1 * sin(f1 * 6.28 * t + ph)
            val jx = rnd.nextDouble(-0.6, 0.6)
            val jy = rnd.nextDouble(-0.6, 0.6)
            val x = p.x + dx + w + jx
            val y = p.y + dy - w + jy
            val cx = x - 54.5
            val cy = y - 54.5
            out += Pt(ox + (cx * cos(rot) - cy * sin(rot)) * sx + 54.5 * sx, oy + (cx * sin(rot) + cy * cos(rot)) * sy + 54.5 * sy)
        }
        return out
    }

    private fun at(pts: List<Pt>, dist: Double): Pt {
        var acc = 0.0
        for (i in 1 until pts.size) {
            val l = hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
            if (acc + l >= dist || i == pts.size - 1) {
                val t = if (l < 1e-9) 0.0 else ((dist - acc) / l).coerceIn(0.0, 1.0)
                return Pt(pts[i - 1].x + (pts[i].x - pts[i - 1].x) * t, pts[i - 1].y + (pts[i].y - pts[i - 1].y) * t)
            }
            acc += l
        }
        return pts.last()
    }
}

class StrokeMatcherTest {
    private val matcher = StrokeMatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private val refs: Map<String, List<Stroke>> by lazy {
        val dir = File("../../learning-japanese/strokes")
        assumeTrue(dir.exists())
        dir.listFiles { f -> f.extension == "json" }!!.associate { f ->
            val d = json.decodeFromString(StrokeData.serializer(), f.readText())
            d.kanji to d.strokes.map { s -> s.points.map { Pt(it[0], it[1]) } }
        }
    }

    private fun len(s: Stroke) = (1 until s.size).sumOf { hypot(s[it].x - s[it - 1].x, s[it].y - s[it - 1].y) }
    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    private fun handDraw(ref: List<Stroke>, seed: Int): List<Stroke> {
        val h = Hand(Random(seed))
        return ref.map { h.draw(it) }
    }

    private fun rate(name: String, cases: List<Pair<String, Boolean>>, minRate: Double) {
        val failed = cases.filter { !it.second }.map { it.first }
        val r = 1.0 - failed.size.toDouble() / cases.size
        println("$name: ${cases.size - failed.size}/${cases.size}")
        assertTrue("$name success ${"%.2f".format(r)} < $minRate; failures: ${failed.take(25)}", r >= minRate)
    }

    @Test
    fun cleanHandDrawingsAreAccepted() {
        val cases = refs.flatMap { (k, ref) -> (1..25).map { seed -> "$k#$seed" to matcher.match(ref, handDraw(ref, seed)).clean } }
        rate("clean", cases, 0.95)
    }

    @Test
    fun declaredOrderVariantIsAcceptedOthersAreNot() {
        val ref = refs.getValue("上")
        val swapped = listOf(ref[0], ref[2], ref[1])
        assertTrue(matcher.match(ref, swapped).issues.any { it.type == IssueType.WrongOrder })
        assertTrue(matcher.match(ref, swapped, listOf(listOf(1, 3, 2))).clean)
        val other = listOf(ref[1], ref[0], ref[2])
        assertTrue(!matcher.match(ref, other, listOf(listOf(1, 3, 2))).clean)
        assertTrue(!matcher.match(ref, swapped, listOf(listOf(1, 1, 2), listOf(1, 2))).clean)
    }

    @Test
    fun dotPlacedAndSizedLooselyIsAccepted() {
        val ref = refs.getValue("下")
        val dot = ref[2]
        val c = dot.first()
        // Real-device case: the dot of 下 sat ~11 units left and ~11 lower (nearer the stem) and was 30% shorter.
        val loose = dot.map { Pt(c.x + (it.x - c.x) * 0.7 - 11, c.y + (it.y - c.y) * 0.7 + 11) }
        val res = matcher.match(ref, listOf(ref[0], ref[1], loose))
        assertTrue(res.issues.toString(), res.clean)
    }

    @Test
    fun shorterSlashIsAccepted() {
        val ref = refs.getValue("休")
        // Real-device case: the first slash of 亻 was drawn ~30% shorter than the reference.
        val short = ref[0].let { it.take((it.size * 0.7).toInt()) }
        val res = matcher.match(ref, listOf(short) + ref.drop(1))
        assertTrue(res.issues.toString(), res.clean)
    }

    @Test
    fun reversedStrokeIsReported() {
        val cases = ArrayList<Pair<String, Boolean>>()
        for ((k, ref) in refs) for (i in ref.indices) {
            if (dist(ref[i].first(), ref[i].last()) < 20) continue
            repeat(5) { seed ->
                val d = handDraw(ref, seed).toMutableList()
                d[i] = d[i].reversed()
                val res = matcher.match(ref, d)
                cases += "$k s$i #$seed" to res.issues.any { it.type == IssueType.Reversed && it.refIndex == i }
            }
        }
        rate("reversed", cases, 0.92)
    }

    @Test
    fun swappedStrokesAreReportedAsWrongOrder() {
        val cases = ArrayList<Pair<String, Boolean>>()
        for ((k, ref) in refs) for (i in 0 until ref.size - 1) {
            if (dist(ref[i].first(), ref[i + 1].first()) < 18 && dist(ref[i].last(), ref[i + 1].last()) < 18) continue
            repeat(5) { seed ->
                val d = handDraw(ref, seed).toMutableList()
                val t = d[i]; d[i] = d[i + 1]; d[i + 1] = t
                val res = matcher.match(ref, d)
                cases += "$k s$i #$seed" to (res.issues.any { it.type == IssueType.WrongOrder } && res.issues.none { it.type == IssueType.Missing || it.type == IssueType.Extra })
            }
        }
        rate("swap", cases, 0.9)
    }

    @Test
    fun joinedStrokesAreReported() {
        val cases = ArrayList<Pair<String, Boolean>>()
        for ((k, ref) in refs) for (i in 0 until ref.size - 1) {
            if (dist(ref[i].last(), ref[i + 1].first()) > 30) continue
            repeat(5) { seed ->
                val d = handDraw(ref, seed)
                val joined = d.take(i) + listOf(d[i] + d[i + 1]) + d.drop(i + 2)
                val res = matcher.match(ref, joined)
                cases += "$k s$i #$seed" to (!res.clean && res.issues.any { it.type == IssueType.Joined || it.type == IssueType.Missing || it.type == IssueType.WrongShape })
            }
        }
        rate("joined-not-clean", cases, 1.0)
        val exact = ArrayList<Pair<String, Boolean>>()
        for ((k, ref) in refs) for (i in 0 until ref.size - 1) {
            if (dist(ref[i].last(), ref[i + 1].first()) > 30) continue
            repeat(5) { seed ->
                val d = handDraw(ref, seed)
                val res = matcher.match(ref, d.take(i) + listOf(d[i] + d[i + 1]) + d.drop(i + 2))
                exact += "$k s$i #$seed" to res.issues.any { it.type == IssueType.Joined && it.refIndex == i }
            }
        }
        rate("joined-exact", exact, 0.8)
    }

    @Test
    fun brokenStrokeIsReported() {
        val cases = ArrayList<Pair<String, Boolean>>()
        for ((k, ref) in refs) for (i in ref.indices) {
            if (len(ref[i]) < 35) continue
            repeat(5) { seed ->
                val d = handDraw(ref, seed)
                val s = d[i]
                val cut = s.size / 2
                val broken = d.take(i) + listOf(s.take(cut), s.drop(cut + 1)) + d.drop(i + 1)
                val res = matcher.match(ref, broken)
                cases += "$k s$i #$seed" to res.issues.any { it.type == IssueType.Broken && it.refIndex == i }
            }
        }
        rate("broken", cases, 0.85)
    }

    @Test
    fun missingStrokeIsReported() {
        val cases = ArrayList<Pair<String, Boolean>>()
        for ((k, ref) in refs) {
            if (ref.size < 3) continue
            for (i in ref.indices) repeat(4) { seed ->
                val d = handDraw(ref, seed).toMutableList()
                d.removeAt(i)
                val res = matcher.match(ref, d)
                cases += "$k s$i #$seed" to res.issues.any { it.type == IssueType.Missing && it.refIndex == i }
            }
        }
        rate("missing", cases, 0.9)
    }

    @Test
    fun extraStrokeIsReported() {
        val cases = ArrayList<Pair<String, Boolean>>()
        for ((k, ref) in refs) repeat(10) { seed ->
            val d = handDraw(ref, seed).toMutableList()
            val rnd = Random(seed)
            val at = rnd.nextInt(d.size + 1)
            val extra = listOf(Pt(40.0 + rnd.nextInt(60), 30.0 + rnd.nextInt(30)), Pt(120.0 + rnd.nextInt(40), 140.0 + rnd.nextInt(50)))
            d.add(at, extra)
            val res = matcher.match(ref, d)
            cases += "$k #$seed" to res.issues.any { it.type == IssueType.Extra }
        }
        rate("extra", cases, 0.9)
    }

    @Test
    fun otherKanjiIsNotAccepted() {
        // Pairwise cost is quadratic and the recognition gate was tuned on the N5/N4 baseline: compare those kanji only.
        val base = File("../../learning-japanese/kanji").listFiles { f -> f.extension == "md" }!!
            .filter { f -> f.useLines { l -> l.firstOrNull { it.startsWith("jlpt:") }?.substringAfter(":")?.trim() in setOf("4", "5") } }
            .map { it.nameWithoutExtension }.toSet()
        val keys = refs.keys.filter { it in base }.sorted()
        val notClean = ArrayList<Pair<String, Boolean>>()
        val notRecognizable = ArrayList<Pair<String, Boolean>>()
        for (a in keys) for (b in keys) {
            if (a == b) continue
            val res: MatchResult = matcher.match(refs.getValue(b), handDraw(refs.getValue(a), 1))
            notClean += "$a as $b" to !res.clean
            notRecognizable += "$a as $b" to !res.recognizable
        }
        // A handful of near-identical two-stroke pairs (入/八/九, 円/月) are accepted by the matcher; the recognition gate separates them.
        rate("other-not-clean", notClean, 0.995)
        // Similar kanji (上/下, 大/人, 木/休) legitimately share strokes; telling them apart is the recognition gate's job.
        rate("other-not-recognizable", notRecognizable, 0.8)
    }

    @Test
    fun largeHookIsAcceptedAndMissingHookIsNot() {
        val ref = refs.getValue("気")
        val hooked = ref[3]
        val stem = hooked.dropLast(3)
        val e = stem.last()
        fun withHook(tail: Double): Stroke {
            val a = Pt(e.x + 1, e.y + 2)
            val b = Pt(e.x + 2.5, e.y + 4)
            return stem + listOf(a, b, Pt(b.x + 0.6, b.y - tail * 0.5), Pt(b.x + 1.0, b.y - tail))
        }
        fun verdict(s: Stroke, seed: Int): MatchResult {
            val shown = ref.toMutableList().also { it[3] = s }
            return matcher.match(ref, handDraw(shown, seed))
        }
        for (tail in listOf(9.0, 14.0, 18.0, 22.0)) for (seed in 1..10) {
            val r = verdict(withHook(tail), seed)
            assertTrue("tail $tail #$seed: ${r.issues}", r.issues.none { it.type == IssueType.MissingHook })
        }
        val flat = (1..10).count { seed -> verdict(stem + Pt(e.x + 1, e.y + 2), seed).issues.any { it.type == IssueType.MissingHook } }
        assertTrue("hook-less stroke flagged in $flat/10", flat >= 9)
    }

    @Test
    fun cowDrawnForNoonIsCaughtAndTheReverseToo() {
        val gate = LookalikeGate(matcher)
        val pool = refs.map { it.key to it.value }
        for ((asked, drawnKanji) in listOf("午" to "牛", "牛" to "午")) {
            val hits = (1..10).count { seed ->
                val d = handDraw(refs.getValue(drawnKanji), seed)
                gate.find(asked, matcher.match(refs.getValue(asked), d), d, pool) == drawnKanji
            }
            assertTrue("$drawnKanji asked as $asked caught in $hits/10", hits >= 8)
            val own = (1..10).count { seed ->
                val d = handDraw(refs.getValue(asked), seed)
                gate.find(asked, matcher.match(refs.getValue(asked), d), d, pool) != null
            }
            assertEquals("$asked drawn correctly was rejected $own/10", 0, own)
        }
    }

    @Test
    fun lookalikeGateRejectsNoGenuineDrawingsAndCatchesMostLookalikes() {
        val gate = LookalikeGate(matcher)
        val byCount = refs.entries.groupBy { it.value.size }
        var genuine = 0
        var falseRejects = ArrayList<String>()
        var cases = 0
        var caught = 0
        for ((a, ref) in refs) {
            val d = handDraw(ref, 1)
            val own = matcher.match(ref, d)
            if (!own.clean) continue
            genuine++
            val pool = byCount.getValue(ref.size).map { it.key to it.value }
            if (gate.find(a, own, d, pool) != null) falseRejects += a
            for ((b, rb) in pool) {
                if (b == a) continue
                val asB = matcher.match(rb, d)
                if (!asB.clean) continue
                cases++
                if (gate.find(b, asB, d, pool) == a) caught++
            }
        }
        println("lookalike gate: false rejects ${falseRejects.size}/$genuine, caught $caught/$cases")
        assertTrue("false rejects: ${falseRejects.take(20)}", falseRejects.size * 200 <= genuine)
        assertTrue("caught $caught/$cases", cases == 0 || caught * 10 >= cases * 8)
    }

    @Test
    fun emptyInputs() {
        val ref = refs.values.first()
        assertEquals(0, matcher.match(ref, emptyList()).matchedStrokes)
    }
}
