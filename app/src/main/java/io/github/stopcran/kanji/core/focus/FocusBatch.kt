package io.github.stopcran.kanji.core.focus

import io.github.stopcran.kanji.core.reading.KanjiReadingQuiz
import io.github.stopcran.kanji.core.reading.ReadingCard
import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import kotlin.random.Random

/** The cards a kanji needs before it is finished: meaning, drawing when drawable, and the readings that can really be asked. */
data class Requirements(val kanji: String, val drawable: Boolean, val readingKeys: Map<ReadingKind, List<String>>)

object FocusBatch {
    const val DEFAULT_SIZE = 10
    val SIZE_RANGE = 5..30

    /**
     * Reading keys of [kind] that are required: the card's reading cards in queue order, stopping at the first one no question
     * can be built for (the queue introduces readings in order, so later ones could never start).
     */
    fun requiredReadingKeys(card: ReadingCard, kind: ReadingKind, all: List<ReadingCard>): List<String> {
        val out = ArrayList<String>()
        for (key in card.quizKeys(kind)) {
            if (KanjiReadingQuiz.question(card, kind, all, Random(0), reading = key) == null) break
            out += key
        }
        return out
    }

    fun requirements(introduced: List<ReadingCard>, drawable: Set<String>, all: List<ReadingCard>): Map<String, Requirements> =
        introduced.associate { c ->
            c.kanji to Requirements(c.kanji, c.kanji in drawable, ReadingKind.entries.associateWith { requiredReadingKeys(c, it, all) })
        }

    /**
     * Kanji in the batch: introduced (first meaning answer logged at or after the cutoff, whatever its grade) and with a required
     * card that is not yet past learning. A card never started counts as New, so a locked mode keeps its slot.
     */
    fun inLearning(
        introduced: Set<String>,
        requirements: Map<String, Requirements>,
        quiz: Map<String, SrsState>,
        draw: Map<String, SrsState>,
        readings: Map<ReadingKind, Map<String, SrsState>>,
    ): Set<String> = introduced.filterTo(HashSet()) { kanji ->
        val r = requirements[kanji] ?: return@filterTo false
        fun open(s: SrsState?) = (s?.phase ?: CardPhase.New) != CardPhase.Review
        open(quiz[kanji]) || (r.drawable && open(draw[kanji])) ||
            r.readingKeys.any { (kind, keys) -> keys.any { open(readings[kind]?.get(ReadingId.of(kanji, it))) } }
    }

    /** New meaning cards that may still be introduced. */
    fun capacity(size: Int, occupancy: Int) = (size - occupancy).coerceAtLeast(0)
}
