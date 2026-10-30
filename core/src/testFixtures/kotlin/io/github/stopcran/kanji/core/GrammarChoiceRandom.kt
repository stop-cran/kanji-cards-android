package io.github.stopcran.kanji.core

import kotlin.random.Random

class GrammarChoiceRandom(private val bucket: Int = 0, private val alternative: Int = 0) : Random() {
    override fun nextBits(bitCount: Int): Int = error("Unexpected random operation")
    override fun nextInt(until: Int): Int = if (until == 5) bucket else alternative.coerceAtMost(until - 1)
}
