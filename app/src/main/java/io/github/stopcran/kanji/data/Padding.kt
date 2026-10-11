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

/**
 * Started words of [mode] from the other word stacks, for padding a short "Practice more" session. Stacks keep separate states
 * for the same word, so a word learned in N5 can be unstarted in N4; it qualifies when it is in [eligible] (this stack's words
 * for the direction) and has no state here ([exclude]), which also keeps this stack's unlock rules intact.
 */
suspend fun AppDatabase.padWordCandidates(sourceId: String, stack: String, mode: StudyMode, eligible: Set<String>, exclude: Set<String>): List<PadCandidate> =
    reviews().borrowable(sourceId, mode.name, stack)
        .filter { it.kanji in eligible && it.kanji !in exclude }
        .map { PadCandidate(it.kanji, it.kanji, it.stack, it.toSrs()) }

/** Words are alike when they share a kanji. */
fun List<io.github.stopcran.kanji.data.WordEntity>.wordSimilarityIndex(): SimilarityIndex =
    SimilarityIndex(associate { w -> w.word to w.toCard().kanji.toSet() })

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
