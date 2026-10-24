package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.GrammarFacet
import io.github.stopcran.kanji.core.content.GrammarMember
import io.github.stopcran.kanji.core.content.GrammarRecommendation
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.srs.SrsState
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant

data class IntroductionGroup(
    val id: String,
    val label: String,
    val authored: Boolean,
    val facets: List<GrammarFacet>,
    val members: List<GrammarMember>,
    val requires: List<String>,
    val next: List<GrammarRecommendation> = emptyList(),
)

@Serializable
enum class FocusOrigin { Automatic, Manual }

@Serializable
data class GrammarExposure(val exerciseId: String, val facetId: String, val exampleKey: String)

@Serializable
data class GrammarActivation(
    val groupId: String,
    val origin: FocusOrigin,
    val seed: Long,
    val committed: Boolean = false,
    val introduced: List<GrammarExposure> = emptyList(),
)

@Serializable
data class GrammarCursor(
    val automaticOrdinal: Long = 1,
    val selectionNonce: Long = 0,
    val active: GrammarActivation? = null,
) {
    fun validate() {
        require(automaticOrdinal > 0 && selectionNonce >= 0) { "Invalid focus counters" }
        active?.let { activation ->
            require(activation.groupId.matches(Regex("[a-z0-9-]+|legacy:[a-z0-9-]+:[a-z0-9-]+"))) { "Invalid focus group ID" }
            require(activation.seed > 0) { "Invalid activation seed" }
            if (activation.origin == FocusOrigin.Automatic) {
                require(activation.seed == automaticOrdinal - if (activation.committed) 1 else 0) { "Invalid reserved ordinal" }
            } else require(activation.seed <= selectionNonce) { "Invalid manual selection nonce" }
            require(activation.introduced.map { it.exerciseId }.distinct().size == activation.introduced.size) { "Duplicate focus introductions" }
            require(activation.introduced.groupBy { it.facetId }.values.all { it.size <= 2 }) { "Invalid focus quota" }
            require(activation.committed == activation.introduced.isNotEmpty()) { "Inconsistent activation commitment" }
            require(activation.introduced.all {
                listOf(it.exerciseId, it.facetId, it.exampleKey).all { value -> value.matches(Regex("[a-z0-9-]+")) }
            }) { "Invalid stored introduction" }
        }
    }
}

data class FocusRecommendation(val sourceGroup: IntroductionGroup, val evidence: GroupEvidence, val link: GrammarRecommendation)

data class FocusCandidate(
    val group: IntroductionGroup,
    val evidence: GroupEvidence,
    val blocked: String?,
    val recommendations: List<FocusRecommendation> = emptyList(),
) {
    val score: Int get() = recommendations.maxOfOrNull { it.link.importance } ?: 0
}

data class GrammarFocusDecision(
    val candidates: List<FocusCandidate>,
    val activation: GrammarActivation?,
    val selected: List<GrammarExposure>,
    val reason: String,
    val resetReason: String? = null,
    val continuing: Boolean = false,
) {
    val current: FocusCandidate? get() = candidates.find { it.group.id == activation?.groupId }
}

data class GrammarFocusInput(
    val sourceId: String,
    val sets: List<GrammarSet>,
    val states: Map<String, SrsState>,
    val events: List<GrammarEvent>,
    val scope: GrammarScope,
    val budget: Int,
    val now: Instant,
    val cursor: GrammarCursor,
    val readyGroups: Set<String> = emptySet(),
    val newMetadataProblem: String? = null,
)

object GrammarFocus {
    fun groups(sets: List<GrammarSet>): List<IntroductionGroup> = sets.flatMap { set ->
        val authored = set.introductionGroups.map {
            IntroductionGroup(it.id, it.label, true, it.facets, it.members, it.requires, it.next)
        }
        val owned = authored.flatMap { it.members }.map { it.exerciseId }.toSet()
        val legacy = set.exercises.filter { it.id !in owned }.groupBy { it.relationship }.map { (relationship, exercises) ->
            val label = set.relationships.single { it.id == relationship }.label
            IntroductionGroup(
                "legacy:${set.id}:$relationship", "Unguided contrast: ${set.title} / $label", false,
                exercises.map { it.target }.distinct().map { target ->
                    GrammarFacet(target, set.constructions.single { it.id == target }.label)
                },
                exercises.map { GrammarMember(it.id, it.target, it.id) }, emptyList(),
            )
        }
        authored + legacy
    }

    fun decide(input: GrammarFocusInput): GrammarFocusDecision {
        input.cursor.validate()
        val groups = groups(input.sets)
        val exercises = input.sets.flatMap { it.exercises }.associateBy { it.id }
        val evidence = groups.associate { group ->
            group.id to GrammarEvidence.evaluate(group, exercises, input.states, input.events, input.now)
        }
        val recommendations = groups.filter { evidence.getValue(it.id).familiar }.flatMap { group ->
            group.next.map { FocusRecommendation(group, evidence.getValue(group.id), it) }
        }.groupBy { it.link.groupId }
        val eligible = GrammarEligibility.pool(exercises.values.toList(), input.states, input.scope, true).ids.toSet()
        val availability = GrammarAvailability.evaluate(
            groups, exercises, input.states, input.scope, true, input.readyGroups, input.budget, input.now,
            input.cursor.active, input.newMetadataProblem,
        )
        val candidates = groups.map { group ->
            FocusCandidate(
                group, evidence.getValue(group.id), availability.getValue(group.id).guidedBlock,
                recommendations[group.id].orEmpty(),
            )
        }
        val active = input.cursor.active
        val previous = candidates.find { it.group.id == active?.groupId }
        val resetReason = if (active != null && (previous == null || previous.blocked != null)) {
            previous?.blocked ?: "Previous focus was removed from the current content."
        } else null
        val continuing = previous?.takeIf { it.blocked == null }
        if (active != null && continuing != null && continueFocus(continuing, active, input.states, eligible)) {
            val selected = select(input, continuing.group, active)
            val reason = if (selected.isEmpty()) {
                if (input.budget <= 0) "No ordinary new-item allowance remains." else "Review this group, or change focus."
            } else "Continue ${continuing.group.label}."
            return GrammarFocusDecision(candidates, active, selected, reason, continuing = true)
        }
        val candidate = automatic(input, candidates)
            ?: return GrammarFocusDecision(candidates, null, emptyList(), "No eligible focus has new material.", resetReason)
        val group = candidate.group
        val activation = GrammarActivation(group.id, FocusOrigin.Automatic, input.cursor.automaticOrdinal)
        return GrammarFocusDecision(candidates, activation, select(input, group, activation), selectionReason(input, candidate), resetReason)
    }

    private fun continueFocus(
        candidate: FocusCandidate,
        activation: GrammarActivation,
        states: Map<String, SrsState>,
        eligible: Set<String>,
    ): Boolean {
        if (candidate.group.authored) return !activation.committed || !(candidate.evidence.covered && candidate.evidence.familiar)
        return candidate.group.members.any { member ->
            member.exerciseId in eligible && !isStarted(states[member.exerciseId]) && activation.introduced.count { it.facetId == member.facetId } < 2
        }
    }

    private fun automatic(input: GrammarFocusInput, candidates: List<FocusCandidate>): FocusCandidate? {
        var pool = candidates.filter { it.blocked == null }
        val exploration = input.cursor.automaticOrdinal % 3 == 0L
        if (!exploration && pool.any { it.group.authored }) pool = pool.filter { it.group.authored }
        if (pool.any { !it.evidence.familiar }) pool = pool.filter { !it.evidence.familiar }
        if (exploration && pool.any { it.score == 0 }) pool = pool.filter { it.score == 0 }
        return pool.minWithOrNull(
            compareByDescending<FocusCandidate> { it.score }.thenBy { tie(input.sourceId, input.cursor.automaticOrdinal, it.group.id) },
        )
    }

    private fun selectionReason(input: GrammarFocusInput, candidate: FocusCandidate): String {
        if (input.budget <= 0) return "No ordinary new-item allowance remains."
        val exploration = input.cursor.automaticOrdinal % 3 == 0L
        val policy = if (exploration) "Exploration" else "Authored-first"
        val rule = when {
            exploration && candidate.score == 0 -> "Score-zero exploration"
            exploration -> "No score-zero group in the familiarity-prioritized pool"
            else -> "Maximum supported incoming importance"
        }
        return "$policy slot ${input.cursor.automaticOrdinal}: ${candidate.group.label}. $rule; recommendation priority ${candidate.score}/3."
    }

    private fun select(input: GrammarFocusInput, group: IntroductionGroup, activation: GrammarActivation): List<GrammarExposure> {
        val exercises = input.sets.flatMap { it.exercises }.associateBy { it.id }
        val pending = group.members.filter {
            !isStarted(input.states[it.exerciseId]) && input.scope.matches(exercises.getValue(it.exerciseId).jlpt, cumulative = true)
        }.toMutableList()
        val selected = mutableListOf<GrammarExposure>()
        while (selected.size < input.budget) {
            val used = activation.introduced + selected
            val candidate = pending.filter { member -> used.count { it.facetId == member.facetId } < 2 }.minWithOrNull(
                compareBy<GrammarMember> { member -> used.count { it.facetId == member.facetId } }
                    .thenBy { member ->
                        used.any { it.facetId == member.facetId && it.exampleKey == member.exampleKey } ||
                            group.members.any {
                                it.facetId == member.facetId && it.exampleKey == member.exampleKey && isStarted(input.states[it.exerciseId])
                            }
                    }
                    .thenBy { tie(input.sourceId, activation.seed, group.id, it.facetId, it.exerciseId) },
            ) ?: break
            selected += GrammarExposure(candidate.exerciseId, candidate.facetId, candidate.exampleKey)
            pending.remove(candidate)
        }
        return selected
    }

    fun manual(input: GrammarFocusInput, groupId: String): GrammarCursor {
        val decision = decide(input)
        val candidate = decision.candidates.find { it.group.id == groupId }
        require(candidate != null && candidate.blocked == null) { candidate?.blocked ?: "Focus is unavailable." }
        val nonce = Math.addExact(input.cursor.selectionNonce, 1)
        return input.cursor.copy(selectionNonce = nonce, active = GrammarActivation(groupId, FocusOrigin.Manual, nonce))
    }

    fun recorded(cursor: GrammarCursor, exposure: GrammarExposure): GrammarCursor {
        val active = requireNotNull(cursor.active) { "No reserved focus" }
        if (active.introduced.any { it.exerciseId == exposure.exerciseId }) return cursor
        require(active.introduced.count { it.facetId == exposure.facetId } < 2) { "Focus quota already consumed" }
        val ordinal = if (!active.committed && active.origin == FocusOrigin.Automatic) Math.addExact(cursor.automaticOrdinal, 1) else {
            cursor.automaticOrdinal
        }
        return cursor.copy(automaticOrdinal = ordinal, active = active.copy(committed = true, introduced = active.introduced + exposure))
    }

    private fun tie(source: String, seed: Long, group: String, vararg suffix: String): String =
        MessageDigest.getInstance("SHA-256").digest((listOf(source, seed.toString(), group) + suffix).joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
