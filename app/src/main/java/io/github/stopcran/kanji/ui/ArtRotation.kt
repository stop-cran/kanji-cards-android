package io.github.stopcran.kanji.ui

import kotlin.random.Random

/** Picks the illustration set for a cold start: random, but never the one used last time (when there is a choice). */
object ArtRotation {
    fun next(count: Int, last: Int, random: Random): Int {
        if (count <= 1) return 0
        if (last !in 0 until count) return random.nextInt(count)
        val pick = random.nextInt(count - 1)
        return if (pick >= last) pick + 1 else pick
    }
}
