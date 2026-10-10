package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.Relearn
import io.github.stopcran.kanji.core.srs.Relearn.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class RelearnTest {
    @Test
    fun cleanAnswerNeedsNoRepeat() {
        assertEquals(Step(null, true), Relearn().answered("a", ok = true, queueSize = 10))
    }

    @Test
    fun failureIsAskedAgainThenConfirmedOnceWithoutRecordingTheConfirmation() {
        val r = Relearn()
        assertEquals(Step(3, true), r.answered("a", ok = false, queueSize = 10))
        assertEquals(Step(7, true), r.answered("a", ok = true, queueSize = 10))
        assertEquals(Step(null, false), r.answered("a", ok = true, queueSize = 10))
        assertEquals(Step(null, true), r.answered("a", ok = true, queueSize = 10))
    }

    @Test
    fun aWrongConfirmationStartsOverAndIsRecorded() {
        val r = Relearn()
        r.answered("a", false, 10); r.answered("a", true, 10)
        assertEquals(Step(3, true), r.answered("a", ok = false, queueSize = 10))
    }

    @Test
    fun gapsAreClampedToTheQueue() {
        val r = Relearn()
        assertEquals(Step(1, true), r.answered("a", false, 1))
        assertEquals(Step(2, true), r.answered("a", true, 2))
    }

    @Test
    fun aRepeatIsDroppedWhenNothingElseIsQueued() {
        val r = Relearn()
        assertEquals(Step(null, true), r.answered("a", ok = false, queueSize = 0))
        assertEquals(Step(1, true), r.answered("b", ok = false, queueSize = 1))
        assertEquals(Step(null, true), r.answered("b", ok = true, queueSize = 0))
        assertEquals(Step(null, true), Relearn(confirm = false).answered("c", ok = true, queueSize = 0, weak = true))
    }

    @Test
    fun weakAnswerGetsOneReaskWhenConfirmationIsOff() {
        val r = Relearn(confirm = false)
        assertEquals(Step(3, true), r.answered("d", ok = true, queueSize = 9, weak = true))
        assertEquals(Step(null, true), r.answered("d", ok = true, queueSize = 9, weak = true))
        assertEquals(Step(3, true), r.answered("e", ok = false, queueSize = 9))
        assertEquals(Step(null, true), r.answered("e", ok = true, queueSize = 9))
    }
}
