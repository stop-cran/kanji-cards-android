package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stack
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.WordStack
import io.github.stopcran.kanji.core.words.wordLevel
import java.time.Instant

fun ReviewStateEntity.toSrs() = SrsState(
    CardPhase.valueOf(phase), stability, difficulty, Instant.ofEpochMilli(dueMs), lastReviewMs?.let(Instant::ofEpochMilli), reps, lapses,
)

fun SrsState.toEntity(source: String, stack: String, kanji: String, mode: StudyMode) = ReviewStateEntity(
    source, stack, kanji, mode.name, phase.name, stability, difficulty, due.toEpochMilli(), lastReview?.toEpochMilli(), reps, lapses,
)

fun List<KanjiEntity>.stacks(): List<Stack> = Stacks.available(map { it.tags.splitSep() })

/** The cards of [stackId]; an unknown or empty stack falls back to all cards. */
fun List<KanjiEntity>.inStack(stackId: String): List<KanjiEntity> {
    val stack = Stacks.find(stackId, stacks())
    return filter { stack.contains(it.tags.splitSep()) }
}

fun List<KanjiEntity>.levels(): Map<String, Int?> = associate { it.kanji to it.jlpt }

fun WordEntity.toCard() = WordCard(word, reading, title, type, kanji.splitSep(), tags.splitSep())

/** The words of [stack], by the level of their hardest kanji. */
fun List<WordEntity>.inWordStack(stack: WordStack, levels: Map<String, Int?>): List<WordEntity> =
    filter { stack.contains(wordLevel(it.kanji.splitSep(), levels)) }

