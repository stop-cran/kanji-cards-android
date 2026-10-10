package io.github.stopcran.kanji.core.srs

/**
 * In-session relearning of a failed card (successive relearning): after a failure it is asked again [FIRST_GAP] cards later and,
 * when [confirm] is on, once more [CONFIRM_GAP] cards after a right re-ask. The re-ask is recorded like any answer; the final
 * confirmation is practice only and is recorded (as a failure) only when it is wrong, so one lapse does not stack
 * several successes onto the card's stability within minutes.
 *
 * A [weak] answer (right but flawed, e.g. a drawing with stroke mistakes) earns one re-ask when the card was not already being relearned.
 */
class Relearn(private val confirm: Boolean = true) {
    /** [reinsertAt] is where to put the card back in the queue (null = done with it); [record] says whether to save this answer. */
    data class Step(val reinsertAt: Int?, val record: Boolean)

    private val stage = HashMap<String, Int>()

    /** With nothing else queued ([queueSize] 0) a repeat would follow itself directly, so it is dropped; the answer is still recorded. */
    fun answered(id: String, ok: Boolean, queueSize: Int, weak: Boolean = false): Step {
        val s = stage[id] ?: 0
        val alone = queueSize == 0
        return when {
            !ok || (weak && s == 0) -> if (alone) { stage.remove(id); Step(null, true) } else { stage[id] = 1; Step(minOf(FIRST_GAP, queueSize), true) }
            s == 1 && confirm -> if (alone) { stage.remove(id); Step(null, true) } else { stage[id] = 2; Step(minOf(CONFIRM_GAP, queueSize), true) }
            s == 2 -> { stage.remove(id); Step(null, false) }
            else -> { stage.remove(id); Step(null, true) }
        }
    }

    companion object {
        const val FIRST_GAP = 3
        const val CONFIRM_GAP = 7
    }
}
