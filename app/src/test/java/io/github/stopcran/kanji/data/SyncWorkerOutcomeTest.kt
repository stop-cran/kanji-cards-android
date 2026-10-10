package io.github.stopcran.kanji.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerOutcomeTest {
    @Test
    fun transientFailuresRetryPermanentOnesDoNot() {
        assertEquals(WorkOutcome.Retry, SyncResult.Failed("offline", retryable = true).toWorkOutcome())
        assertEquals(WorkOutcome.Failure, SyncResult.Failed("not found", retryable = false).toWorkOutcome())
    }

    @Test
    fun updatedAndUpToDateSucceed() {
        assertEquals(WorkOutcome.Success, SyncResult.UpToDate.toWorkOutcome())
        assertEquals(WorkOutcome.Success, SyncResult.Updated(1, 0, emptyList()).toWorkOutcome())
    }
}