package io.github.stopcran.kanji.core.reading

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.ReadingOptions
import java.time.Instant
import kotlin.random.Random

/** The two kinds of kanji reading; each is scheduled separately because they are separate memories. */
enum class ReadingKind(val mode: StudyMode, val label: String) {
    On(StudyMode.KanjiOn, "on'yomi"),
    Kun(StudyMode.KanjiKun, "kun'yomi"),
}

/** Readings as written on a card (`イチ`, `ひと.つ`, `ひと-`) in three forms: raw, match key and display. */
object ReadingKey {
    private fun foldChar(c: Char) = if (c in '\u30A1'..'\u30F6') (c.code - 0x60).toChar() else c
    private fun unfoldChar(c: Char) = if (c in '\u3041'..'\u3096') (c.code + 0x60).toChar() else c

    fun fold(s: String) = String(CharArray(s.length) { foldChar(s[it]) })

    /** Kana-folded, with the okurigana dot and attachment dashes removed: used only to compare and de-duplicate. */
    fun key(raw: String) = fold(raw).filter { it != '.' && it != '-' && it != '・' && !it.isWhitespace() }

    /** On'yomi in katakana, kun'yomi in hiragana; the dot goes away and a dash (prefix or suffix form) stays. */
    fun display(raw: String, kind: ReadingKind): String {
        val plain = raw.replace(".", "").trim()
        return if (kind == ReadingKind.On) String(CharArray(plain.length) { unfoldChar(plain[it]) }) else fold(plain)
    }
}

/** [on] and [kun] are all the card's readings (used to rule out wrong options); [askOn]/[askKun] are the ones worth asking. */
data class ReadingCard(
    val kanji: String,
    val on: List<String>,
    val kun: List<String>,
    val askOn: List<String> = on,
    val askKun: List<String> = kun,
    val distractors: List<String> = emptyList(),
) {
    fun ask(kind: ReadingKind) = if (kind == ReadingKind.On) askOn else askKun
}

data class ReadingOption(val key: String, val label: String)

/** [correctKey] is the key of the one option that is valid for the target and kind. */
data class ReadingQuestion(val kind: ReadingKind, val options: List<ReadingOption>, val correctKey: String)

/**
 * Options for "pick the on'yomi / kun'yomi of this kanji". Exactly one option is valid: the correct one is a reading of the
 * asked kind of the target, and no other option may be a reading of the target in either kind, or a voicing / long-vowel /
 * small-っ variant of one (rendaku makes those plausible in real words). Options are distinct by key. Fewer than `count`
 * options are returned when too few safe candidates exist, and null when fewer than two.
 */
object KanjiReadingQuiz {
    fun excludedKeys(target: ReadingCard): Set<String> {
        val out = HashSet<String>()
        for (r in target.on + target.kun) {
            val k = ReadingKey.key(r)
            out += k
            out += ReadingOptions.mutations(k)
        }
        return out
    }

    fun question(target: ReadingCard, kind: ReadingKind, all: List<ReadingCard>, random: Random, count: Int = 4): ReadingQuestion? {
        val correctRaw = target.ask(kind).randomOrNull(random) ?: return null
        val correctKey = ReadingKey.key(correctRaw)
        if (correctKey.isEmpty()) return null
        val excluded = excludedKeys(target)
        val used = hashSetOf(correctKey)
        val chosen = ArrayList<ReadingOption>()
        fun add(raw: String) {
            val k = ReadingKey.key(raw)
            if (chosen.size < count - 1 && k.isNotEmpty() && k !in excluded && used.add(k)) chosen += ReadingOption(k, ReadingKey.display(raw, kind))
        }
        fun closeness(raw: String): Int {
            val k = ReadingKey.key(raw)
            return (if (kotlin.math.abs(k.length - correctKey.length) <= 1) 2 else 0) +
                (if (k.last() == correctKey.last()) 1 else 0) + (if (k.first() == correctKey.first()) 1 else 0)
        }
        fun usable(c: ReadingCard) = c.ask(kind).filter { ReadingKey.key(it).isNotEmpty() }
        val byKanji = all.associateBy { it.kanji }
        val others = all.filter { it.kanji != target.kanji && usable(it).isNotEmpty() }
        target.distractors.mapNotNull { byKanji[it] }.filter { it.kanji != target.kanji }.forEach { c -> usable(c).randomOrNull(random)?.let(::add) }
        others.shuffled(random).mapNotNull { c -> usable(c).maxByOrNull { closeness(it) } }.sortedByDescending { closeness(it) }.forEach(::add)
        others.shuffled(random).forEach { c -> usable(c).forEach(::add) }
        if (chosen.isEmpty()) return null
        val options = (chosen + ReadingOption(correctKey, ReadingKey.display(correctRaw, kind))).shuffled(random)
        return ReadingQuestion(kind, options, correctKey)
    }
}

data class ReadingItem(val kanji: String, val kind: ReadingKind, val isNew: Boolean)

/**
 * One queue over both kinds. A kanji's reading is offered once its meaning has been answered well a couple of times (the
 * caller passes those as `unlocked`) or once that kind was already started. The daily new budget is shared: a kanji with
 * any reading introduced today (`introducedToday`) already used a unit, and its other kind is free; a new kanji costs one unit
 * however many kinds it brings.
 */
object KanjiReadingQueue {
    const val EXTRA_LIMIT = 10

    fun build(
        cards: List<ReadingCard>,
        states: Map<ReadingKind, Map<String, SrsState>>,
        unlocked: Set<String>,
        introducedToday: Set<String>,
        dailyNew: Int,
        now: Instant,
        extra: Boolean = false,
        noise: Double = QueueBuilder.DEFAULT_NOISE,
        rnd: Random = Random.Default,
    ): List<ReadingItem> {
        fun started(kind: ReadingKind, k: String) = states[kind]?.get(k)?.phase.let { it != null && it != CardPhase.New }
        fun eligible(kind: ReadingKind) = cards.filter { it.ask(kind).isNotEmpty() && (it.kanji in unlocked || started(kind, it.kanji)) }.map { it.kanji }
        val kinds = ReadingKind.entries
        val shuffle = noise > 0
        if (extra) {
            val lists = kinds.map { kind ->
                QueueBuilder.extra(eligible(kind), states[kind].orEmpty(), now, EXTRA_LIMIT, noise, rnd).map { ReadingItem(it.kanji, kind, it.isNew) }
            }
            return separate(merge(lists, rnd, shuffle)).take(EXTRA_LIMIT)
        }
        val due = kinds.map { kind ->
            QueueBuilder.build(eligible(kind), states[kind].orEmpty(), now, 0, noise = noise, rnd = rnd).map { ReadingItem(it.kanji, kind, false) }
        }
        val candidates = kinds.flatMap { kind ->
            eligible(kind).filter { states[kind]?.get(it)?.phase.let { p -> p == null || p == CardPhase.New } }.map { ReadingItem(it, kind, true) }
        }
        val (free, costly) = candidates.partition { it.kanji in introducedToday }
        val costlyKanji = costly.map { it.kanji }.distinct().let { if (shuffle) it.shuffled(rnd) else it }
        val picked = costlyKanji.take(maxOf(0, dailyNew - introducedToday.size)).toSet()
        val fresh = (free + costly.filter { it.kanji in picked }).let { if (shuffle) it.shuffled(rnd) else it }
        return separate(merge(listOf(merge(due, rnd, shuffle), fresh), rnd, shuffle))
    }

    /** Merges lists keeping each list's order; randomly weighted by remaining length when [random], else in list order. */
    private fun merge(lists: List<List<ReadingItem>>, rnd: Random, random: Boolean): List<ReadingItem> {
        val idx = IntArray(lists.size)
        val out = ArrayList<ReadingItem>()
        while (true) {
            val open = lists.indices.filter { idx[it] < lists[it].size }
            if (open.isEmpty()) return out
            val pick = if (!random) open.first() else {
                var r = rnd.nextInt(open.sumOf { lists[it].size - idx[it] })
                open.first { i -> (lists[i].size - idx[i]).let { w -> if (r < w) true else { r -= w; false } } }
            }
            out += lists[pick][idx[pick]++]
        }
    }

    /** Keeps the same kanji from appearing back to back (its two kinds would cue each other) when possible. */
    private fun separate(items: List<ReadingItem>): List<ReadingItem> {
        val out = items.toMutableList()
        for (i in 1 until out.size) {
            if (out[i].kanji != out[i - 1].kanji) continue
            val j = (i + 1 until out.size).firstOrNull { out[it].kanji != out[i - 1].kanji } ?: continue
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        return out
    }
}
