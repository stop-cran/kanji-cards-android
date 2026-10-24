package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.srs.SrsState
import java.time.Instant

data class GrammarGroupAvailability(
    val groupId: String,
    val newMetadataProblem: String?,
    val missingCoherentFacets: List<String>,
    val missingPrerequisites: List<String>,
    val inScopeUnseen: List<String>,
    val outOfScopeUnseen: List<String>,
    val started: List<String>,
    val due: List<String>,
    val remainingFocusQuota: Map<String, Int>,
    val dailyAllowance: Int,
) {
    val guidedBlock: String? get() = when {
        newMetadataProblem != null -> newMetadataProblem
        missingCoherentFacets.isNotEmpty() -> "Scope cannot cover two contexts for: ${missingCoherentFacets.joinToString()}."
        missingPrerequisites.isNotEmpty() -> "Earned prerequisite readiness needed for: ${missingPrerequisites.joinToString()}."
        inScopeUnseen.isEmpty() -> "No eligible unseen members remain."
        else -> null
    }
    val targetedNew: List<String> get() = if (newMetadataProblem == null && missingPrerequisites.isEmpty()) inScopeUnseen else emptyList()
}

object GrammarAvailability {
    fun evaluate(
        groups: List<IntroductionGroup>,
        exercises: Map<String, GrammarExercise>,
        states: Map<String, SrsState>,
        scope: GrammarScope,
        levelsReady: Boolean,
        readyGroups: Set<String>,
        dailyAllowance: Int,
        now: Instant,
        active: GrammarActivation? = null,
        newMetadataProblem: String? = null,
    ): Map<String, GrammarGroupAvailability> {
        val eligible = GrammarEligibility.pool(exercises.values.toList(), states, scope, levelsReady).ids.toSet()
        return groups.associate { group ->
            val started = group.members.filter { isStarted(states[it.exerciseId]) }.map { it.exerciseId }
            val unseen = group.members.filter { !isStarted(states[it.exerciseId]) }.map { it.exerciseId }
            val missing = if (!group.authored) emptyList() else group.facets.filter { facet ->
                group.members.filter { it.facetId == facet.id && it.exerciseId in eligible }.map { it.exampleKey }.distinct().size < 2
            }.map { it.label }
            group.id to GrammarGroupAvailability(
                group.id, newMetadataProblem, missing, group.requires.filter { it !in readyGroups },
                unseen.filter { it in eligible }, unseen.filter { it !in eligible }, started,
                started.filter { requireNotNull(states[it]).due <= now },
                group.facets.associate { facet ->
                    facet.id to (2 - if (active?.groupId == group.id) active.introduced.count { it.facetId == facet.id } else 0).coerceAtLeast(0)
                },
                dailyAllowance,
            )
        }
    }
}
