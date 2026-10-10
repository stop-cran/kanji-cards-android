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

    /** The kana stem before the okurigana dot (`い.きる` and `い.かす` share `い`): variants of one stem are one reading to learn. */
    fun stem(raw: String) = key(raw.substringBefore('.'))

    /** On'yomi in katakana, kun'yomi in hiragana; the dot goes away and a dash (prefix or suffix form) stays. */
    fun display(raw: String, kind: ReadingKind): String {
        val plain = raw.replace(".", "").trim()
        return if (kind == ReadingKind.On) String(CharArray(plain.length) { unfoldChar(plain[it]) }) else fold(plain)
    }
}

/** Identity of one reading card inside the per-kind state maps: the kanji plus the reading's match key. */
object ReadingId {
    private const val SEP = '\u001F'
    fun of(kanji: String, key: String) = "$kanji$SEP$key"
    fun kanji(id: String) = id.substringBefore(SEP)
    fun key(id: String) = id.substringAfter(SEP, "")
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

    /** The readings that become cards: the first [MAX_PER_KIND] distinct stems as listed, in order. */
    fun quizReadings(kind: ReadingKind): List<String> =
        ask(kind).filter { ReadingKey.key(it).isNotEmpty() }.distinctBy { ReadingKey.stem(it) }.distinctBy { ReadingKey.key(it) }.take(MAX_PER_KIND)

    fun quizKeys(kind: ReadingKind): List<String> = quizReadings(kind).map { ReadingKey.key(it) }

    companion object {
        const val MAX_PER_KIND = 3
    }
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

    /** [reading] is the match key of the card's reading; when null (or unknown) a random reading of the kind is asked. */
    fun question(target: ReadingCard, kind: ReadingKind, all: List<ReadingCard>, random: Random, count: Int = 4, reading: String? = null): ReadingQuestion? {
        val correctRaw = reading?.let { r -> target.ask(kind).firstOrNull { ReadingKey.key(it) == r } } ?: target.ask(kind).randomOrNull(random) ?: return null
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

/** One reading card: [reading] is the match key (see [ReadingKey.key]). */
data class ReadingItem(val kanji: String, val kind: ReadingKind, val reading: String, val isNew: Boolean) {
    val id: String get() = ReadingId.of(kanji, reading)
}

/**
 * One queue over both kinds, with one card per (kanji, kind, reading). A kanji's readings are offered once its meaning has
 * been answered well a couple of times (the caller passes those as `unlocked`) or once a reading of that kind was already
 * started. A further reading of a kanji is introduced only after the previous reading of that kind has passed learning (been
 * answered well), so a kanji never brings all its readings at once. Every new reading card costs one unit of the daily budget;
 * `introducedToday` is how many reading cards already used a unit today.
 */
object KanjiReadingQueue {
    const val EXTRA_LIMIT = 10

    /** The reading cards of [card] that may be asked now: all started ones, plus the next one when the previous is learned. */
    fun eligible(card: ReadingCard, kind: ReadingKind, states: Map<String, SrsState>, unlocked: Boolean): List<ReadingItem> {
        val keys = card.quizKeys(kind)
        fun phase(k: String) = states[ReadingId.of(card.kanji, k)]?.phase ?: CardPhase.New
        val started = keys.filter { phase(it) != CardPhase.New }
        if (started.isEmpty() && !unlocked) return emptyList()
        val nextIndex = keys.indexOfFirst { phase(it) == CardPhase.New }
        val next = keys.getOrNull(nextIndex)?.takeIf { nextIndex == 0 || phase(keys[nextIndex - 1]) == CardPhase.Review }
        return started.map { ReadingItem(card.kanji, kind, it, false) } + listOfNotNull(next?.let { ReadingItem(card.kanji, kind, it, true) })
    }

    fun build(
        cards: List<ReadingCard>,
        states: Map<ReadingKind, Map<String, SrsState>>,
        unlocked: Set<String>,
        introducedToday: Int,
        dailyNew: Int,
        now: Instant,
        extra: Boolean = false,
        noise: Double = QueueBuilder.DEFAULT_NOISE,
        rnd: Random = Random.Default,
    ): List<ReadingItem> {
        fun eligible(kind: ReadingKind) = cards.flatMap { eligible(it, kind, states[kind].orEmpty(), it.kanji in unlocked) }
        fun item(kind: ReadingKind, id: String, isNew: Boolean) = ReadingItem(ReadingId.kanji(id), kind, ReadingId.key(id), isNew)
        val kinds = ReadingKind.entries
        val shuffle = noise > 0
        if (extra) {
            val lists = kinds.map { kind ->
                QueueBuilder.extra(eligible(kind).map { it.id }, states[kind].orEmpty(), now, EXTRA_LIMIT, noise, rnd).map { item(kind, it.kanji, it.isNew) }
            }
            return separate(merge(lists, rnd, shuffle)).take(EXTRA_LIMIT)
        }
        val due = kinds.map { kind ->
            QueueBuilder.build(eligible(kind).filter { !it.isNew }.map { it.id }, states[kind].orEmpty(), now, 0, noise = noise, rnd = rnd).map { item(kind, it.kanji, false) }
        }
        val candidates = kinds.flatMap { kind -> eligible(kind).filter { it.isNew } }.let { if (shuffle) it.shuffled(rnd) else it }
        val fresh = candidates.take(maxOf(0, dailyNew - introducedToday))
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

    /** Keeps the same kanji from appearing back to back (its readings would cue each other) when possible. */
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
