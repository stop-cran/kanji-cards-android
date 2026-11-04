package io.github.stopcran.kanji.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRunOwnershipTest {
    @Test
    fun rejectedRunCanRetryUnchangedFormAndStaleCompletionCannotReleaseNewRun() {
        val runs = CandidateRunOwnership()
        val rejected = runs.start()
        assertTrue(runs.finish(rejected))

        val retry = runs.start()
        assertTrue(runs.finish(retry))

        val superseded = runs.start()
        val current = runs.start()
        assertFalse(runs.finish(superseded))
        assertTrue(runs.finish(current))
        runs.cancel()
        assertFalse(runs.finish(current))
    }
}
