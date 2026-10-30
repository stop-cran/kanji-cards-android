package io.github.stopcran.kanji.core.srs

import java.time.Clock
import java.time.Instant

data class ReviewIdentity(val sourceId: String, val stack: String, val itemId: String, val mode: StudyMode, val sessionId: String)

/** Immutable confirmation-time payload; persistence must keep its state and log atomic. */
data class ReviewRecord(
    val state: SrsState, val sourceId: String, val stack: String, val itemId: String,
    val mode: StudyMode, val grade: Grade, val at: Instant, val sessionId: String,
)

class ReviewRecording(private val clock: Clock = Clock.systemUTC()) {
    private val fsrs = Fsrs()

    fun confirm(
        identity: ReviewIdentity, states: MutableMap<String, SrsState>, grade: Grade, firstSuccessCapDays: Double?,
    ): ReviewRecord {
        val previous = states[identity.itemId] ?: SrsState()
        val at = clock.instant()
        val updated = fsrs.review(
            previous, grade, at, firstSuccessCapDays = firstSuccessCapDays,
            fuzzSeed = Fsrs.seed(identity.itemId, identity.mode.name, previous.reps),
        )
        // A following answer must see this state even if the ordered writer is still busy.
        states[identity.itemId] = updated
        return ReviewRecord(updated, identity.sourceId, identity.stack, identity.itemId, identity.mode, grade, at, identity.sessionId)
    }
}
