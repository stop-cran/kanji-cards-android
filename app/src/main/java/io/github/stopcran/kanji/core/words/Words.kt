package io.github.stopcran.kanji.core.words

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import java.time.Instant
import kotlin.random.Random

/**
 * The word-quiz directions; each keeps its own scheduling state. [Reading] shows the written word and asks for its kana
 * reading. [other] is the direction whose memory gates this one (and, for the forward direction, slightly defers it).
 */
enum class WordDirection(val mode: StudyMode) {
    JpToEn(StudyMode.WordJpEn),
    EnToJp(StudyMode.WordEnJp),
    Reading(StudyMode.WordReading),
    ;

    val other: WordDirection get() = if (this == JpToEn) EnToJp else JpToEn

    /** Directions other than the forward one open only after the forward direction has been answered a couple of times. */
    val gated: Boolean get() = this != JpToEn
}

data class WordCard(
    val word: String,
    val reading: String,
    val title: String,
    val type: String?,
    val kanji: List<String>,
    val tags: List<String> = emptyList(),
    val jlpt: Int? = null,
    val quizExclusions: List<String> = emptyList(),
)

/** A reading is worth asking only when it differs from the written form, i.e. the word contains kanji. */
fun hasReadingToLearn(word: String, reading: String) = word != reading

/**
 * An explicit vocabulary JLPT level wins, independently of the written kanji. Otherwise use the hardest kanji
 * (N5 = 5 is easiest, so the lowest number). Unlabelled words without kanji, or with an unknown kanji level,
 * appear only in the "all words" stack.
 */
fun wordLevel(kanji: List<String>, kanjiLevels: Map<String, Int?>, jlpt: Int? = null): Int? {
    if (jlpt != null) return jlpt
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

    /** Stability that a card only reaches after a successful review on a later day (same-day reviews grow it far less). */
    const val MIN_FORWARD_STABILITY = 2.0

    fun reverseUnlocked(forward: SrsState?): Boolean =
        forward != null && forward.phase == CardPhase.Review && forward.reps >= MIN_FORWARD_REPS && forward.stability >= MIN_FORWARD_STABILITY
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
 * first, with a bonus for sharing a kanji with the target (the confusions that matter). Meaning quizzes keep exact
 * title/written-form duplicates and curated exclusion pairs apart; reading quizzes use distinct reading keys.
 */
object WordQuizBuilder {
    fun options(target: WordCard, all: List<WordCard>, direction: WordDirection, random: Random, count: Int = 4): List<WordOption> {
        if (direction == WordDirection.Reading) return ReadingOptions.build(target, all, random, count)
        val usedTitles = hashSetOf(target.title)
        val usedWords = hashSetOf(target.word)
        val chosen = ArrayList<WordCard>()
        fun excluded(a: WordCard, b: WordCard) = b.word in a.quizExclusions || a.word in b.quizExclusions
        fun tryAdd(c: WordCard) {
            if (chosen.size < count - 1 && c.word !in usedWords &&
                !excluded(target, c) && chosen.none { excluded(it, c) } && usedTitles.add(c.title)) {
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
        WordDirection.Reading -> c.reading
    }
}

/**
 * Options for the reading quiz are kana readings, which are also the option keys. Every option differs from the correct
 * reading and from each other, so exactly one is right. Wrong options are the confusions that matter: generated look-alikes
 * (voiced/unvoiced, long/short vowel, small っ present or absent) and real readings of words sharing a kanji or of a similar shape.
 */
object ReadingOptions {
    private val voicing = mapOf(
        'か' to "が", 'き' to "ぎ", 'く' to "ぐ", 'け' to "げ", 'こ' to "ご", 'さ' to "ざ", 'し' to "じ", 'す' to "ず", 'せ' to "ぜ", 'そ' to "ぞ",
        'た' to "だ", 'ち' to "じ", 'つ' to "ず", 'て' to "で", 'と' to "ど", 'は' to "ばぱ", 'ひ' to "びぴ", 'ふ' to "ぶぷ", 'へ' to "べぺ", 'ほ' to "ぼぽ",
        'が' to "か", 'ぎ' to "き", 'ぐ' to "く", 'げ' to "け", 'ご' to "こ", 'ざ' to "さ", 'じ' to "し", 'ず' to "す", 'ぜ' to "せ", 'ぞ' to "そ",
        'だ' to "た", 'で' to "て", 'ど' to "と", 'ば' to "は", 'び' to "ひ", 'ぶ' to "ふ", 'べ' to "へ", 'ぼ' to "ほ",
        'ぱ' to "は", 'ぴ' to "ひ", 'ぷ' to "ふ", 'ぺ' to "へ", 'ぽ' to "ほ",
    )
    private const val LONG_O = "おこそとのほもよろごぞどぼぽょ"
    private const val LONG_U = "ゅくすつぬふむゆるぐずぶぷ"
    private const val DOUBLING_NEXT = "かきくけこさしすせそたちつてとぱぴぷぺぽ"

    fun mutations(reading: String): List<String> {
        val out = linkedSetOf<String>()
        for (i in reading.indices) {
            val c = reading[i]
            voicing[c]?.forEach { out += reading.replaceRange(i, i + 1, it.toString()) }
            if (c == 'っ') out += reading.removeRange(i, i + 1)
            else if (i > 0 && i < reading.lastIndex && reading[i - 1] != 'っ' && c in DOUBLING_NEXT) out += reading.substring(0, i) + "っ" + reading.substring(i)
            if (c == 'う' && i > 0 && (reading[i - 1] in LONG_O || reading[i - 1] in LONG_U)) out += reading.removeRange(i, i + 1)
            else if (c in LONG_O && (i == reading.lastIndex || reading[i + 1] != 'う')) out += reading.substring(0, i + 1) + "う" + reading.substring(i + 1)
        }
        out.remove(reading)
        return out.filter { it.length >= 2 }
    }

    private fun closeness(target: WordCard, c: WordCard): Int {
        val d = kotlin.math.abs(c.reading.length - target.reading.length)
        return (if (c.kanji.any { it in target.kanji }) 3 else 0) +
            (if (d <= 1) 2 else 0) +
            (if (c.reading.last() == target.reading.last()) 1 else 0) +
            (if (c.reading.first() == target.reading.first()) 1 else 0)
    }

    fun build(target: WordCard, all: List<WordCard>, random: Random, count: Int = 4): List<WordOption> {
        val used = hashSetOf(target.reading)
        val chosen = ArrayList<String>()
        fun add(r: String) {
            if (chosen.size < count - 1 && used.add(r)) chosen += r
        }
        val generated = mutations(target.reading).shuffled(random)
        generated.take(2).forEach(::add)
        all.filter { it.word != target.word && hasReadingToLearn(it.word, it.reading) && it.reading.isNotEmpty() }
            .shuffled(random).sortedByDescending { closeness(target, it) }.forEach { add(it.reading) }
        generated.drop(2).forEach(::add)
        return (chosen + target.reading).map { WordOption(it, it) }.shuffled(random)
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
