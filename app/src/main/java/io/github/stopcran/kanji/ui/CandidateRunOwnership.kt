package io.github.stopcran.kanji.ui

internal class CandidateRunOwnership {
    private var generation = 0L

    fun start(): Long = ++generation

    fun cancel() {
        generation++
    }

    fun finish(token: Long): Boolean = token == generation
}
