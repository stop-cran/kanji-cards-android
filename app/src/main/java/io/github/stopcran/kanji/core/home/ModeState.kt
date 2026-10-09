package io.github.stopcran.kanji.core.home

/** What a study mode's home row shows and does. One state drives the summary text, the single button and its accessibility description. */
sealed interface ModeState {
    data object Loading : ModeState
    /** Cards are due or new within today's budget. */
    data class Start(val due: Int, val new: Int) : ModeState
    /** Nothing is offered right now, but extra practice has cards to go through. */
    data object PracticeMore : ModeState
    /** The mode exists but has nothing to ask yet (e.g. waits for earlier learning). */
    data class Locked(val reason: String) : ModeState
    /** The current stack cannot support the mode at all (too few cards). */
    data class Unavailable(val reason: String) : ModeState

    val enabled: Boolean get() = this is Start || this is PracticeMore

    companion object {
        /**
         * [available] is whether the stack has enough cards for the mode's session (the same predicate its view model enforces);
         * [due]/[new] count the normal queue and [extraPossible] whether an extra-practice queue would be non-empty.
         */
        fun of(available: Boolean, unavailableReason: String, due: Int, new: Int, extraPossible: Boolean, lockedReason: String): ModeState = when {
            !available -> Unavailable(unavailableReason)
            due + new > 0 -> Start(due, new)
            extraPossible -> PracticeMore
            else -> Locked(lockedReason)
        }
    }
}
