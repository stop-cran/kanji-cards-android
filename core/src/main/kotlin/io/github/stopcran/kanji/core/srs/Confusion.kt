package io.github.stopcran.kanji.core.srs

import java.time.Duration
import java.time.Instant

/**
 * Picking the wrong card in a multiple-choice question is evidence that the picked card is also fuzzy, but not that its recall failed,
 * so its score stays as it is and it is only brought forward for a second look.
 */
object Confusion {
    val NUDGE: Duration = Duration.ofDays(2)

    /** The state with its due date pulled to [NUDGE] from [now], or null when nothing changes (a new card, or one that is due sooner anyway). */
    fun nudged(state: SrsState?, now: Instant): SrsState? {
        if (state == null || state.phase == CardPhase.New) return null
        val target = now.plus(NUDGE)
        return if (state.due.isAfter(target)) state.copy(due = target) else null
    }
}
