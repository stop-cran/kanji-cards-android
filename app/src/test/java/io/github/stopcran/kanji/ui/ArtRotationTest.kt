package io.github.stopcran.kanji.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ArtRotationTest {
    @Test
    fun neverRepeatsTheLastSetAndReachesTheOthers() {
        val random = Random(7)
        for (last in 0 until 4) {
            val picks = List(200) { ArtRotation.next(4, last, random) }
            assertTrue(picks.none { it == last })
            assertEquals((0 until 4).filter { it != last }.toSet(), picks.toSet())
        }
    }

    @Test
    fun firstRunCanPickAnySet() {
        val random = Random(1)
        assertEquals((0 until 4).toSet(), List(300) { ArtRotation.next(4, -1, random) }.toSet())
    }

    @Test
    fun unknownLastStaysInRange() {
        val random = Random(1)
        assertTrue(List(100) { ArtRotation.next(4, 99, random) }.all { it in 0 until 4 })
    }

    @Test
    fun singleSetIsAlwaysChosen() {
        assertEquals(0, ArtRotation.next(1, 0, Random(1)))
    }
}
