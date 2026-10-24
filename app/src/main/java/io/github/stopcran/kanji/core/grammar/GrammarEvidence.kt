package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import java.time.Duration
import java.time.Instant

data class GrammarEvent(
    val id: Long,
    val exerciseId: String,
    val grade: Int,
    val atMs: Long,
    val constructionId: String?,
    val relationshipId: String?,
    val promptType: String?,
    val correct: Boolean?,
    val identityMatches: Boolean,
)

data class FacetEvidence(
    val startedKeys: Int,
    val positiveKeys: Int,
    val typedPositives: Int,
    val spanMs: Long,
    val supported: Boolean,
)

data class GroupEvidence(val facets: Map<String, FacetEvidence>, val diagnostics: List<String>) {
    val familiar: Boolean get() = facets.isNotEmpty() && facets.values.all { it.supported }
    val covered: Boolean get() = facets.isNotEmpty() && facets.values.all { it.startedKeys >= 2 }
}

object GrammarEvidence {
    val WINDOW: Duration = Duration.ofDays(21)

    fun evaluate(
        group: IntroductionGroup,
        exercises: Map<String, GrammarExercise>,
        states: Map<String, SrsState>,
        events: List<GrammarEvent>,
        now: Instant,
    ): GroupEvidence {
        val diagnostics = mutableListOf<String>()
        val members = group.members.associateBy { it.exerciseId }
        val matching = events.filter { it.exerciseId in members }.groupBy { it.id }.values.mapNotNull { duplicates ->
            if (duplicates.distinct().size == 1) duplicates.first() else {
                diagnostics += "Conflicting copies of event ${duplicates.first().id}; evidence unknown."
                null
            }
        }.filter { event ->
            validEvent(event, exercises.getValue(event.exerciseId), now, diagnostics)
        }
        val facets = group.facets.associate { facet ->
            val own = group.members.filter { it.facetId == facet.id }
            val ids = own.map { it.exerciseId }.toSet()
            val history = matching.filter { it.exerciseId in ids }
            val reset = history.filter { it.grade in 1..2 || it.correct == false }.maxOfOrNull { it.atMs } ?: Long.MIN_VALUE
            val positives = history.filter { it.correct == true && it.grade in 3..4 && it.atMs > reset }
            val keys = positives.map { members.getValue(it.exerciseId).exampleKey }.toSet()
            val typed = positives.count { it.promptType == "type" }
            val span = if (positives.isEmpty()) 0L else positives.maxOf { it.atMs } - positives.minOf { it.atMs }
            val started = own.filter { isStarted(states[it.exerciseId]) }.map { it.exampleKey }.toSet().size
            facet.id to FacetEvidence(
                started, keys.size, typed, span,
                group.authored && keys.size >= 2 && typed > 0 && span >= Duration.ofHours(24).toMillis(),
            )
        }
        return GroupEvidence(facets, diagnostics.distinct())
    }

    internal fun validEvent(event: GrammarEvent, exercise: GrammarExercise, now: Instant, diagnostics: MutableList<String>): Boolean {
        if (event.atMs > now.toEpochMilli()) {
            diagnostics += "Future-dated event ${event.id} excluded; check the device clock."
            return false
        }
        if (event.atMs < now.minus(WINDOW).toEpochMilli()) return false
        val mapped = event.identityMatches && event.constructionId == exercise.target && event.relationshipId == exercise.relationship
        val validShape = event.grade in 1..4 && event.correct != null && event.promptType in listOf("choose", "type")
        if (!mapped || !validShape) {
            diagnostics += "Event ${event.id} cannot be mapped safely; evidence unknown."
            return false
        }
        if (event.correct != (event.grade > 1)) {
            diagnostics += "Event ${event.id} has inconsistent grade/correctness; not positive evidence."
        }
        return true
    }
}

internal fun isStarted(state: SrsState?): Boolean = state != null && state.phase != CardPhase.New
