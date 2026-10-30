package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import io.github.stopcran.kanji.core.draw.varyReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class VariationTest {
    private val ref = listOf(
        (0..15).map { Pt(10.0 + it * 5.0, 20.0 + it * 0.1) },
        (0..15).map { Pt(54.0, 23.0 + it * 4.7) },
        (0..15).map { Pt(68.0 + it, 37.0 + it) },
    )

    @Test
    fun deterministicPerSeedAndDifferentAcrossSeeds() {
        assertEquals(varyReference(ref, 1), varyReference(ref, 1))
        assertNotEquals(varyReference(ref, 1), varyReference(ref, 2))
    }

    @Test
    fun staysCloseAndKeepsDirectionAndCount() {
        repeat(200) { seed ->
            val v = varyReference(ref, seed)
            assertEquals(ref.size, v.size)
            ref.indices.forEach { i ->
                assertEquals(ref[i].size, v[i].size)
                ref[i].indices.forEach { j -> assertTrue(hypot(ref[i][j].x - v[i][j].x, ref[i][j].y - v[i][j].y) < 6.0) }
                // first-to-last chord still points the same way
                val a = ref[i].last().x - ref[i].first().x
                val b = v[i].last().x - v[i].first().x
                assertTrue(a * b >= 0 || kotlin.math.abs(a) < 1)
            }
        }
    }

    @Test
    fun variedReferenceStillMatchesItself() {
        val m = StrokeMatcher()
        repeat(100) { seed -> assertTrue(m.match(ref, varyReference(ref, seed)).clean) }
    }
}
