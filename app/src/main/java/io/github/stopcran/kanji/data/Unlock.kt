package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.focus.FocusBatch
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.unlock.MeaningUnlock

/**
 * Kanji whose Draw and readings are open: the one place every consumer (sessions, home counts, reminders) asks, so the selected
 * unlock rule applies identically everywhere. Home recomputes when review states change, which are written with the log in one transaction.
 */
suspend fun AppDatabase.meaningLearned(settings: Settings, sourceId: String, stack: String): Set<String> =
    MeaningUnlock.learned(reviews().unlockLog(sourceId, stack), settings.unlockRule.value)

/**
 * Stroke data is stored only when it parsed at import (`ContentSync` writes `StrokeData` it decoded), so presence means drawable.
 * Home loads a lite projection without the JSON, so this is the one definition all of them can share.
 */
fun List<KanjiEntity>.drawableKanji(): Set<String> = filter { it.strokesJson != null }.mapTo(HashSet()) { it.kanji }

/** How full the focus batch is for one stack. */
data class FocusStatus(val size: Int, val occupancy: Int) {
    val capacity: Int get() = FocusBatch.capacity(size, occupancy)
    val full: Boolean get() = capacity == 0
}

/** Null when the focus batch is off. [kanji] are the cards of the stack (lite entities are enough). */
suspend fun AppDatabase.focusStatus(settings: Settings, sourceId: String, stack: String, kanji: List<KanjiEntity>, nowMs: Long): FocusStatus? {
    if (!settings.focusEnabled.value) return null
    val size = settings.focusSize.value
    val since = settings.focusSince(sourceId, stack, nowMs)
    val introduced = reviews().meaningIntroducedSince(sourceId, stack, since).toSet()
    if (introduced.isEmpty()) return FocusStatus(size, 0)
    val cards = kanji.map { it.toReadingCard() }
    val requirements = FocusBatch.requirements(cards.filter { it.kanji in introduced }, kanji.drawableKanji(), cards)
    val reviews = reviews()
    suspend fun states(mode: StudyMode) = reviews.states(sourceId, stack, mode.name)
    val inLearning = FocusBatch.inLearning(
        introduced, requirements,
        states(StudyMode.Quiz).associate { it.kanji to it.toSrs() },
        states(StudyMode.Draw).associate { it.kanji to it.toSrs() },
        ReadingKind.entries.associateWith { states(it.mode).toReadingStates() },
    )
    return FocusStatus(size, inLearning.size)
}
