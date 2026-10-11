package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.core.srs.PadCandidate
import io.github.stopcran.kanji.core.srs.SimilarityIndex
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode

/** Smallest "Practice more" session: the batch size setting, never below the ordinary extra queue of 10. */
fun Settings.practiceMinimum(): Int = maxOf(PRACTICE_BASE, focusSize.value)

private const val PRACTICE_BASE = 10

/**
 * Started cards of [mode] from the source's other stacks, for padding a short "Practice more" session.
 * Kanji that belong to the current stack are left out so its unlock rules are never bypassed; the "all" stack borrows nothing.
 */
suspend fun AppDatabase.padCandidates(sourceId: String, stack: String, mode: StudyMode, content: List<KanjiEntity>, inStack: Set<String>): List<PadCandidate> {
    if (stack == Stacks.ALL) return emptyList()
    val known = content.mapTo(HashSet()) { it.kanji }
    val reading = mode == StudyMode.KanjiOn || mode == StudyMode.KanjiKun
    return reviews().borrowable(sourceId, mode.name, stack)
        .filter { it.kanji in known && it.kanji !in inStack }
        .map { PadCandidate(if (reading) ReadingId.of(it.kanji, it.reading) else it.kanji, it.kanji, it.stack, it.toSrs()) }
}

/** Kanji are alike when they share a radical or a reading; structural tags (JLPT level, stack) do not count. */
fun List<KanjiEntity>.similarityIndex(): SimilarityIndex = SimilarityIndex(
    associate { k ->
        k.kanji to buildSet {
            k.radical?.takeIf { it.isNotBlank() }?.let { add("r:$it") }
            (k.onyomi.splitSep() + k.kunyomi.splitSep()).map { io.github.stopcran.kanji.core.reading.ReadingKey.stem(it) }
                .filter { it.isNotEmpty() }.forEach { add("y:$it") }
        }
    },
)
