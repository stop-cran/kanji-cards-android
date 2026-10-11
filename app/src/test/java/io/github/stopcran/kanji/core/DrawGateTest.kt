package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.draw.DrawGate
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawGateTest {
    private val started = SrsState(phase = CardPhase.Learning, reps = 1)

    @Test
    fun drawingWaitsForRecognitionButNeverHidesStartedCards() {
        val ids = listOf("a", "b", "c", "d")
        val states = mapOf("b" to started, "d" to SrsState())
        assertEquals(listOf("a", "b"), DrawGate.eligible(ids, states, learned = setOf("a")))
        assertEquals(emptyList<String>(), DrawGate.eligible(ids, emptyMap(), emptySet()))
    }

    @Test
    fun traceOnlyForNewKanjiOncePerSession() {
        assertEquals(true, DrawGate.shouldTrace(true, null, emptySet(), "a"))
        assertEquals(true, DrawGate.shouldTrace(true, SrsState(), emptySet(), "a"))
        assertEquals(false, DrawGate.shouldTrace(true, started, emptySet(), "a"))
        assertEquals(false, DrawGate.shouldTrace(true, null, setOf("a"), "a"))
        assertEquals(false, DrawGate.shouldTrace(false, null, emptySet(), "a"))
    }
}
