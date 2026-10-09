package io.github.stopcran.kanji.core.words

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import java.time.Instant
import kotlin.random.Random

/** The two word-quiz directions; each keeps its own scheduling state. */
enum class WordDirection(val mode: StudyMode) {
    JpToEn(StudyMode.WordJpEn),
    EnToJp(StudyMode.WordEnJp),
    ;

    val other: WordDirection get() = if (this == JpToEn) EnToJp else JpToEn
}

data class WordCard(val word: String, val reading: String, val title: String, val type: String?, val kanji: List<String>, val tags: List<String> = emptyList())

/**
 * A word's JLPT level is that of its hardest kanji (N5 = 5 is easiest, so the lowest number). Words without kanji, or with a kanji
 * that has no known level, have no level: they appear only in the "all words" stack.
 */
fun wordLevel(kanji: List<String>, kanjiLevels: Map<String, Int?>): Int? {
    if (kanji.isEmpty()) return null
    val levels = kanji.map { kanjiLevels[it] ?: return null }
    return levels.min()
}

/** Word stacks are cumulative: studying N4 words includes the N5 words. */
data class WordStack(val id: String, val label: String, val minLevel: Int?) {
    fun contains(level: Int?) = minLevel == null || (level != null && level >= minLevel)
}

object WordStacks {
    val n5 = WordStack("words-n5", "N5 words", 5)
    val n4 = WordStack("words-n4", "N4 words (with N5)", 4)
    val all = WordStack("words-all", "All words", null)

    /** Stacks offered to the user; the larger ones appear once N4 is unlocked. */
    fun offered(n4Unlocked: Boolean): List<WordStack> = if (n4Unlocked) listOf(n5, n4, all) else listOf(n5)

    fun find(id: String, offered: List<WordStack>): WordStack = offered.firstOrNull { it.id == id } ?: offered.first()
}

/**
 * English to Japanese is harder recall, so it is offered only for words already answered a couple of times
 * in the forward direction.
 */
object WordGate {
    const val MIN_FORWARD_REPS = 2

    fun reverseUnlocked(forward: SrsState?): Boolean =
        forward != null && forward.phase != CardPhase.New && forward.reps >= MIN_FORWARD_REPS
}

/**
 * Ordering helper: when a word is asked in one direction, a recently remembered other direction makes it slightly less
 * urgent (seeing the answer from the other side is real exposure). Only ordering is affected; due dates and the stored
 * state of each direction are untouched. Retrievability is a probability, so the effect is multiplicative and bounded:
 * urgency shrinks by at most [MAX_DEFERRAL], and an unseen or forgotten other side changes nothing.
 */
object WordBlend {
    const val MAX_DEFERRAL = 0.2

    fun urgencyFactor(other: SrsState?, now: Instant, fsrs: Fsrs = Fsrs()): Double {
        if (other == null || other.phase == CardPhase.New) return 1.0
        return 1.0 - MAX_DEFERRAL * fsrs.retrievability(other, now).coerceIn(0.0, 1.0)
    }
}

data class WordOption(val word: String, val label: String)

/**
 * Builds answer options for a word. Wrong options are the "same kind" of word: the most shared tags and the same type
 * first, with a bonus for sharing a kanji with the target (the confusions that matter). Options never share a meaning
 * or a written form with the target or each other, so there is exactly one correct answer.
 */
object WordQuizBuilder {
    fun options(target: WordCard, all: List<WordCard>, direction: WordDirection, random: Random, count: Int = 4): List<WordOption> {
        val usedTitles = hashSetOf(target.title)
        val usedWords = hashSetOf(target.word)
        val chosen = ArrayList<WordCard>()
        fun tryAdd(c: WordCard) {
            if (chosen.size < count - 1 && c.word !in usedWords && usedTitles.add(c.title)) {
                usedWords += c.word
                chosen += c
            }
        }
        fun score(c: WordCard) = 2 * c.tags.count { it in target.tags } + (if (c.type != null && c.type == target.type) 1 else 0) + (if (c.kanji.any { it in target.kanji }) 1 else 0)
        all.filter { it.word != target.word }.shuffled(random).sortedByDescending(::score).forEach(::tryAdd)
        return (chosen + target).map { WordOption(it.word, label(it, direction)) }.shuffled(random)
    }

    fun label(c: WordCard, direction: WordDirection): String = when (direction) {
        WordDirection.JpToEn -> c.title
        WordDirection.EnToJp -> if (c.word == c.reading) c.word else "${c.word} (${c.reading})"
    }
}

/**
 * Decides when to offer the next level. An item (kanji quiz, kanji drawing, word quiz in either direction) is solid when its
 * stability is at least [SOLID_DAYS] and it is still likely remembered. The offer needs a high share of solid items overall
 * and a reasonable share in each mode.
 */
object Advancement {
    const val SOLID_DAYS = 7.0
    const val SOLID_RECALL = 0.85
    const val OVERALL_SHARE = 0.85
    const val PER_MODE_SHARE = 0.6
    const val MIN_ITEMS = 20
    const val REOFFER_DAYS = 7L

    fun isSolid(s: SrsState?, now: Instant, fsrs: Fsrs = Fsrs()): Boolean =
        s != null && s.phase == CardPhase.Review && s.stability >= SOLID_DAYS && fsrs.retrievability(s, now) >= SOLID_RECALL

    /** [items] lists, per mode, the state of every item in the current level (null when never studied). */
    fun ready(items: List<List<SrsState?>>, now: Instant): Boolean {
        val modes = items.filter { it.isNotEmpty() }
        val total = modes.sumOf { it.size }
        if (total < MIN_ITEMS) return false
        val solid = modes.map { m -> m.count { isSolid(it, now) } }
        if (solid.sum().toDouble() / total < OVERALL_SHARE) return false
        return modes.indices.all { solid[it].toDouble() / modes[it].size >= PER_MODE_SHARE }
    }

    fun shouldOffer(ready: Boolean, unlocked: Boolean, dismissedAtMs: Long, nowMs: Long): Boolean =
        ready && !unlocked && nowMs - dismissedAtMs >= REOFFER_DAYS * 24 * 3600 * 1000
}
