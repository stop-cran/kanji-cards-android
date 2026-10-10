package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.WordArticle
import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stack
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStack
import io.github.stopcran.kanji.core.words.hasReadingToLearn
import io.github.stopcran.kanji.core.words.wordLevel
import java.time.Instant

fun ReviewStateEntity.toSrs() = SrsState(
    CardPhase.valueOf(phase), stability, difficulty, Instant.ofEpochMilli(dueMs), lastReviewMs?.let(Instant::ofEpochMilli), reps, lapses,
)

fun SrsState.toEntity(source: String, stack: String, kanji: String, mode: StudyMode, reading: String = "") = ReviewStateEntity(
    source, stack, kanji, mode.name, phase.name, stability, difficulty, due.toEpochMilli(), lastReview?.toEpochMilli(), reps, lapses, reading,
)

/** Reading-mode states keyed by [ReadingId] (kanji + reading); other modes by kanji or word. */
fun List<ReviewStateEntity>.toReadingStates(): Map<String, SrsState> = associate { ReadingId.of(it.kanji, it.reading) to it.toSrs() }

fun List<KanjiEntity>.stacks(): List<Stack> = Stacks.available(map { it.tags.splitSep() })

/** The cards of [stackId]; an unknown or empty stack falls back to all cards. */
fun List<KanjiEntity>.inStack(stackId: String): List<KanjiEntity> {
    val stack = Stacks.find(stackId, stacks())
    return filter { stack.contains(it.tags.splitSep()) }
}

fun List<KanjiEntity>.levels(): Map<String, Int?> = associate { it.kanji to it.jlpt }

fun WordArticle.toEntity(sourceId: String) = WordEntity(sourceId, word, reading, title, type, kanji.joinSep(), tags.joinSep(), body, jlpt, quizExclusions.joinSep())

fun WordEntity.toCard() = WordCard(word, reading, title, type, kanji.splitSep(), tags.splitSep(), jlpt, quizExclusions.splitSep())

/** The reading quiz skips words whose written form already gives the reading. */
fun List<WordEntity>.forDirection(direction: WordDirection): List<WordEntity> =
    if (direction == WordDirection.Reading) filter { hasReadingToLearn(it.word, it.reading) } else this

/** Shared by quizzes, home counts/progression and reminders; explicit word levels take precedence over kanji. */
fun List<WordEntity>.inWordStack(stack: WordStack, levels: Map<String, Int?>): List<WordEntity> =
    filter { stack.contains(wordLevel(it.kanji.splitSep(), levels, it.jlpt)) }
