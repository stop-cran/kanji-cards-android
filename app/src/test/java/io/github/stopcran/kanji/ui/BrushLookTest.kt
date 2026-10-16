package io.github.stopcran.kanji.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BrushLookTest {
    @Test
    fun sessionLookVariesOnlyWithinSubtleBounds() {
        repeat(100) { seed ->
            val look = BrushLook.random(Random(seed))
            assertTrue(look.width in 0.94f..1.06f)
            assertTrue(look.angle in -0.1f..0.1f)
            assertTrue(look.taper in 0.9f..1.1f)
            assertTrue(look.smoothing in 0.94f..1.06f)
            assertTrue(look.texture in 0.9f..1.1f)
            assertTrue(look.softness in 0.9f..1.1f)
            assertTrue(look.color.alpha == 1f)
        }
    }

    @Test
    fun sessionInkVariesHueAndBrightnessWithinDeepBlue() {
        val baseHue = hue(Color(0xFF1A237E))
        val baseValue = maxOf(26f, 35f, 126f) / 255f
        var visiblyShifted = 0
        repeat(100) { seed ->
            val color = BrushLook.random(Random(seed)).color
            val hueDelta = ((hue(color) - baseHue + 540f) % 360f) - 180f
            assertTrue(color.blue > color.green)
            assertTrue(color.green > color.red)
            assertTrue(kotlin.math.abs(hueDelta) <= 5f)
            assertTrue(kotlin.math.abs(maxOf(color.red, color.green, color.blue) - baseValue) <= 6f / 255)
            if (kotlin.math.abs(hueDelta) >= 1f) visiblyShifted++
        }
        assertTrue(visiblyShifted >= 80)
    }

    private fun hue(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val delta = max - min
        return when {
            delta == 0f -> 0f
            max == color.red -> 60f * (((color.green - color.blue) / delta) % 6f)
            max == color.green -> 60f * ((color.blue - color.red) / delta + 2f)
            else -> 60f * ((color.red - color.green) / delta + 4f)
        }.let { (it + 360f) % 360f }
    }
}
