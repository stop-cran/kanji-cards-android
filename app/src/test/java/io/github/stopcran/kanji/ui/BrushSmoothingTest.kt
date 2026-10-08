package io.github.stopcran.kanji.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushSmoothingTest {
    @Test
    fun shortSegmentsAreKept() {
        val pts = listOf(Offset(0f, 0f), Offset(2f, 1f), Offset(4f, 3f), Offset(6f, 6f))
        assertEquals(pts, smoothLongSegments(pts, 10f, 1f))
    }

    @Test
    fun longSegmentsAreInterpolatedThroughTheOriginalPoints() {
        val pts = listOf(Offset(0f, 0f), Offset(100f, 0f), Offset(200f, 100f), Offset(200f, 200f))
        val out = smoothLongSegments(pts, 10f, 5f)
        assertTrue(out.size > pts.size * 5)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        pts.forEach { assertTrue(it in out) }
    }
}
