package io.github.stopcran.kanji.core.srs

import java.time.Instant
import kotlin.random.Random

/** An already-started card of another stack that can fill a short practice session; [id] is the card identity within its mode. */
data class PadCandidate(val id: String, val kanji: String, val owner: String, val state: SrsState)

/** Kanji that share a radical or a reading, built once from per-kanji feature strings. */
class SimilarityIndex(features: Map<String, Set<String>>) {
    private val byFeature: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        for ((k, fs) in features) for (f in fs) getOrPut(f) { mutableListOf() }.add(k)
    }
    private val featuresOf = features

    fun similar(a: String, b: String): Boolean = a != b && featuresOf[a].orEmpty().any { f -> byFeature[f]?.contains(b) == true }

    fun similarToAny(kanji: String, anchors: Collection<String>): Boolean = anchors.any { similar(kanji, it) }
}

/** What the session already holds ([exclude] card ids, [anchors] kanji) and how to tell confusable kanji ([similarity]). */
class PadContext(val exclude: Set<String>, val anchors: Collection<String>, val similarity: SimilarityIndex?)

/**
 * Fills a practice session that is shorter than the minimum with the weakest cards the user already learned elsewhere
 * (other stacks). Never introduces a card; the ordinary queue keeps its order and only the shortfall is appended.
 */
object PracticePadding {
    const val CONFUSABLE_BOOST = 0.15
    const val JITTER = 0.02
    private val fsrs = Fsrs()

    fun fill(candidates: List<PadCandidate>, context: PadContext, need: Int, now: Instant, rnd: Random = Random.Default): List<PadCandidate> {
        if (need <= 0) return emptyList()
        val owners = candidates.filter { it.state.phase != CardPhase.New && it.id !in context.exclude }
            .groupBy { it.id }
            .map { (_, same) -> same.minWith(compareBy<PadCandidate> { fsrs.retrievability(it.state, now) }.thenBy { it.owner }) }
        return owners.map { c ->
            val boost = if (context.similarity?.similarToAny(c.kanji, context.anchors) == true) CONFUSABLE_BOOST else 0.0
            c to fsrs.retrievability(c.state, now) - boost + JITTER * rnd.nextDouble()
        }.sortedBy { it.second }.take(need).map { it.first }
    }
}
