package io.github.stopcran.kanji.core.srs

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

enum class StudyMode { Quiz, Draw }

data class QueueItem(val kanji: String, val state: SrsState, val isNew: Boolean)

/**
 * Due cards first, then new cards. Order inside each group is "mostly by urgency" with noise, so sessions do not
 * repeat the same sequence and neighbouring cards do not become cues for each other. Urgency is how overdue a card is
 * relative to its own interval, jittered by [noise] (0 = strict order, 0.3 = about a third of an interval).
 * New cards are drawn at random from the unseen pool and mixed into the due cards.
 */
object QueueBuilder {
    fun build(
        allKanji: List<String>,
        states: Map<String, SrsState>,
        now: Instant,
        newCardsRemainingToday: Int,
        limit: Int = Int.MAX_VALUE,
        noise: Double = DEFAULT_NOISE,
        rnd: Random = Random.Default,
    ): List<QueueItem> {
        val due = allKanji.mapNotNull { k ->
            val s = states[k] ?: return@mapNotNull null
            if (s.phase != CardPhase.New && !s.due.isAfter(now)) QueueItem(k, s, false) else null
        }.let { byUrgency(it, now, noise, rnd) }
        val fresh = allKanji.filter { states[it] == null || states[it]!!.phase == CardPhase.New }
            .let { if (noise > 0) it.shuffled(rnd) else it }
            .take(maxOf(0, newCardsRemainingToday))
            .map { QueueItem(it, states[it] ?: SrsState(), true) }
        return (if (noise > 0) interleave(due, fresh, rnd) else due + fresh).take(limit)
    }

    /** Extra practice when nothing is due: overdue, then unseen, then the soonest-due cards. Early reviews simply go through FSRS. */
    fun extra(
        allKanji: List<String>,
        states: Map<String, SrsState>,
        now: Instant,
        limit: Int = 10,
        noise: Double = DEFAULT_NOISE,
        rnd: Random = Random.Default,
    ): List<QueueItem> {
        val seen = allKanji.mapNotNull { k -> states[k]?.takeIf { it.phase != CardPhase.New }?.let { k to it } }
        val overdue = byUrgency(seen.filter { !it.second.due.isAfter(now) }.map { QueueItem(it.first, it.second, false) }, now, noise, rnd)
        val fresh = allKanji.filter { k -> seen.none { it.first == k } }.let { if (noise > 0) it.shuffled(rnd) else it }
            .map { QueueItem(it, states[it] ?: SrsState(), true) }
        val upcoming = byUrgency(seen.filter { it.second.due.isAfter(now) }.map { QueueItem(it.first, it.second, false) }, now, noise, rnd)
        return (overdue + fresh + upcoming).take(limit)
    }

    const val DEFAULT_NOISE = 0.3

    private fun byUrgency(items: List<QueueItem>, now: Instant, noise: Double, rnd: Random): List<QueueItem> =
        items.map { it to urgency(it.state, now) + noise * (rnd.nextDouble() * 2 - 1) }
            .sortedByDescending { it.second }
            .map { it.first }

    /** Time overdue as a fraction of the card's own interval; negative when not yet due. */
    internal fun urgency(s: SrsState, now: Instant): Double {
        val interval = Duration.between(s.lastReview ?: s.due, s.due).seconds.coerceAtLeast(3600L)
        return Duration.between(s.due, now).seconds.toDouble() / interval
    }

    /** Random merge that keeps the relative order inside each list. */
    private fun interleave(a: List<QueueItem>, b: List<QueueItem>, rnd: Random): List<QueueItem> {
        val out = ArrayList<QueueItem>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            val takeA = if (i >= a.size) false else if (j >= b.size) true else rnd.nextInt(a.size - i + b.size - j) < a.size - i
            out += if (takeA) a[i++] else b[j++]
        }
        return out
    }
}
