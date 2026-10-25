package io.github.stopcran.kanji.core.counters

import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterReverse
import io.github.stopcran.kanji.core.content.CounterWord
import io.github.stopcran.kanji.core.reading.ReadingKey
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.QueueItem
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordGate
import java.text.Normalizer
import java.time.Instant
import kotlin.random.Random

/** Legacy suffix/noun practice and independent whole-expression writing/reading skills. */
enum class CounterMode(val code: String, val mode: StudyMode, val label: String) {
    Choose("ch", StudyMode.CounterChoose, "Choose the counter"),
    Type("ty", StudyMode.CounterType, "Type the counter"),
    Noun("nn", StudyMode.CounterNoun, "Which thing does it count?"),
    Expression("ex", StudyMode.CounterExpression, "Write the expression"),
    Reading("rd", StudyMode.CounterReading, "Read the expression");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: Choose
    }
}

/** All loaded counter files, indexed for the quiz. Card IDs are the SRS keys (one stack, no collisions across files). */
class CounterIndex(val files: List<CounterFile>, private val sourceFiles: List<CounterFile> = files) {
    val cards: Map<String, CounterCard> = files.flatMap { it.cards }.associateBy { it.id }
    val fileOf: Map<String, CounterFile> = files.flatMap { f -> f.cards.map { it.id to f } }.toMap()
    val reverse: Map<String, CounterReverse> = files.flatMap { it.reverse }.associateBy { it.id }
    val counterReading: Map<String, String> = sourceFiles.associate { it.counter to it.reading }
    val vocabulary: Map<String, CounterWord> = sourceFiles.fold(emptyMap()) { acc, f -> acc + f.vocabulary }
    val expressions: Map<String, CounterExpression> = files.flatMap { it.expressions }.associateBy { it.id }
    private val expressionFiles = files.flatMap { f -> f.expressions.map { it.id to f } }.toMap()
    private val referenceCards = sourceFiles.flatMap { it.cards }.associateBy { it.id }
    private val reverseFiles = files.flatMap { f -> f.reverse.map { it.id to f } }.toMap()

    fun inGroup(group: CounterGroup) =
        CounterIndex(sourceFiles.filter { group == CounterGroup.All || it.group == group.code }, sourceFiles)

    /** A scoped reverse question can still refer to a distractor in another group. */
    fun referenceCard(id: String): CounterCard = referenceCards.getValue(id)

    fun article(id: String): String? = (expressionFiles[id] ?: fileOf[id])?.article

    fun expression(id: String): CounterExpression = expressions[id] ?: cards.getValue(id).let { c ->
        CounterExpression(
            c.id, c.en, c.phrase, listOf(c.phrase), c.reading,
            c.readingAnswers.ifEmpty { listOf(c.reading) }, c.note ?: fileOf.getValue(id).title,
        )
    }

    /** Ids asked in [mode]: forward cards for choose and type, reverse questions for noun. */
    fun ids(mode: CounterMode): List<String> = when (mode) {
        CounterMode.Noun -> files.flatMap { f -> f.reverse.map { it.id } }
        CounterMode.Expression -> expressions.keys.toList()
        CounterMode.Reading -> (cards.keys + expressions.keys).filter { CounterExpressions.canRead(expression(it).written) }
        else -> files.flatMap { f -> f.cards.map { it.id } }
    }

    /** Counter asked by a reverse question: the file that owns that question. */
    fun reverseFile(id: String): CounterFile? = reverseFiles[id]

    /** Vocabulary of the words that occur in the card's sentence or noun, for reading and gloss hints. */
    fun glossary(card: CounterCard): List<Pair<String, CounterWord>> =
        vocabulary.entries.filter { (w, _) -> w in card.text || w in card.noun }.map { it.key to it.value }

    fun counterLabel(counter: String): String = counterReading[counter]?.let { "$counter ($it)" } ?: counter

    fun nounLabel(card: CounterCard): String = vocabulary[card.noun]?.takeIf { it.reading.isNotEmpty() }
        ?.let { "${card.noun} (${it.reading})" } ?: card.noun

    /** The text may already carry the number before "{}" (e.g. 二{}), while the phrase repeats it; drop the shared part. */
    fun sentence(card: CounterCard): String {
        val at = card.text.indexOf("{}")
        val before = card.text.substring(0, at)
        val shared = (minOf(before.length, card.phrase.length) downTo 0).first { before.endsWith(card.phrase.take(it)) }
        return before.dropLast(shared) + card.phrase + card.text.substring(at + 2)
    }
}

/** Number-aware normalization so 三本, ３本 and 3本 are the same answer while 二本 and 三本 stay different. */
object CounterText {
    private const val DIGITS = "〇一二三四五六七八九"

    fun normalize(s: String): String {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFKC).filter { !it.isWhitespace() && it != '、' && it != ',' }
        val out = StringBuilder()
        var i = 0
        while (i < folded.length) {
            if (!isNumeral(folded[i])) {
                out.append(folded[i++])
                continue
            }
            var j = i
            while (j < folded.length && isNumeral(folded[j])) j++
            out.append(numeral(folded.substring(i, j)))
            i = j
        }
        return ReadingKey.fold(out.toString())
    }

    private fun isNumeral(c: Char) = c in DIGITS || c == '十' || c == '百'

    /** A single digit or a positional 十/百 number becomes Arabic; a plain run such as 五六 (five or six) stays as written. */
    private fun numeral(run: String): String {
        if (run.length == 1 && run[0] in DIGITS) return DIGITS.indexOf(run[0]).toString()
        if ('十' !in run && '百' !in run) return run
        var total = 0
        var current = -1
        for (c in run) {
            if (c in DIGITS) {
                if (current >= 0) return run
                current = DIGITS.indexOf(c)
            } else {
                total += (if (current < 0) 1 else current) * (if (c == '百') 100 else 10)
                current = -1
            }
        }
        return (total + maxOf(current, 0)).toString()
    }
}

enum class CounterVerdict { Good, Yellow, Wrong }

/** [reason] explains a Yellow answer (shown to the learner). */
data class CounterResult(val verdict: CounterVerdict, val reason: String? = null) {
    val ok: Boolean get() = verdict != CounterVerdict.Wrong
}

object CounterGrader {
    const val KANA_REASON = "Right counter, but written in kana; recall the kanji."

    fun grade(input: String, card: CounterCard, counterReading: String?): CounterResult {
        val typed = CounterText.normalize(input)
        if (typed.isEmpty()) return CounterResult(CounterVerdict.Wrong)
        if (card.good.any { CounterText.normalize(it) == typed }) return CounterResult(CounterVerdict.Good)
        val kana = card.kana + listOfNotNull(counterReading)
        if (kana.any { CounterText.normalize(it) == typed }) return CounterResult(CounterVerdict.Yellow, KANA_REASON)
        card.hard.firstOrNull { h -> h.forms.any { CounterText.normalize(it) == typed } }?.let {
            return CounterResult(CounterVerdict.Yellow, it.reason)
        }
        return CounterResult(CounterVerdict.Wrong)
    }
}

object CounterOptions {
    /** The counter and the card's three distractors, in random order. */
    fun choose(card: CounterCard, counter: String, rnd: Random = Random.Default): List<String> =
        (listOf(counter) + card.distractors).shuffled(rnd)

    /** The four nouns of a reverse question, in random order. */
    fun nouns(reverse: CounterReverse, rnd: Random = Random.Default): List<String> = reverse.options.shuffled(rnd)
}

object CounterGate {
    const val MIN_CARDS_FOR_NOUN = 3

    private fun started(s: SrsState?) = s != null && s.phase != CardPhase.New

    /** Typing opens for a card once its choose card is solid; already started cards stay open. */
    fun typeOpen(chooseState: SrsState?, ownState: SrsState?) = WordGate.reverseUnlocked(chooseState) || started(ownState)

    /** The noun question of a counter opens once [MIN_CARDS_FOR_NOUN] of its sentences are solid in choose mode. */
    fun nounOpen(file: CounterFile, choose: Map<String, SrsState>, ownState: SrsState?): Boolean =
        started(ownState) || file.cards.count { WordGate.reverseUnlocked(choose[it.id]) } >= MIN_CARDS_FOR_NOUN
}

/** What a queue is built from: this mode's states, the choose-mode states that gate the others, and the day's new-card budget. */
class CounterQueueRequest(
    val mode: CounterMode,
    val own: Map<String, SrsState>,
    val choose: Map<String, SrsState>,
    val now: Instant,
    val newBudget: Int,
    /** Non-null asks for extra practice of at most this many cards instead of the due queue. */
    val extraLimit: Int? = null,
)

object CounterQueues {
    fun eligible(mode: CounterMode, index: CounterIndex, own: Map<String, SrsState>, choose: Map<String, SrsState>): List<String> {
        val ids = index.ids(mode)
        return when (mode) {
            CounterMode.Choose -> ids
            CounterMode.Expression, CounterMode.Reading -> ids
            CounterMode.Type -> ids.filter { CounterGate.typeOpen(choose[it], own[it]) }
            CounterMode.Noun -> ids.filter { id -> index.reverseFile(id)?.let { CounterGate.nounOpen(it, choose, own[id]) } == true }
        }
    }

    fun build(index: CounterIndex, r: CounterQueueRequest): List<QueueItem> {
        val ids = eligible(r.mode, index, r.own, r.choose)
        return if (r.extraLimit != null) QueueBuilder.extra(ids, r.own, r.now, r.extraLimit) else QueueBuilder.build(ids, r.own, r.now, r.newBudget)
    }
}
