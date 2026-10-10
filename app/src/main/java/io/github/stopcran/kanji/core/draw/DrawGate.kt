package io.github.stopcran.kanji.core.draw

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState

/**
 * Drawing is production, so it is not asked before recognition: a kanji enters Draw once its meaning has been answered well
 * on two days (`learned`, the same unlock as the readings) or once it was already drawn. Shared by the session, home counts and reminders.
 */
object DrawGate {
    fun eligible(strokeIds: List<String>, drawStates: Map<String, SrsState>, learned: Set<String>): List<String> =
        strokeIds.filter { it in learned || drawStates[it]?.phase.let { p -> p != null && p != CardPhase.New } }
}
