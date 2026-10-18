package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.GrammarFacet
import io.github.stopcran.kanji.core.content.GrammarMember
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

data class FocusCandidate(val group: IntroductionGroup, val evidence: GroupEvidence, val blocked: String?)

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
)

object GrammarFocus {
    fun groups(sets: List<GrammarSet>): List<IntroductionGroup> = sets.flatMap { set ->
        val authored = set.introductionGroups.map {
            IntroductionGroup(it.id, it.label, true, it.facets, it.members, it.requires)
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
        val eligible = GrammarEligibility.pool(exercises.values.toList(), input.states, input.scope, true).ids.toSet()
        val candidates = groups.map { group ->
            FocusCandidate(group, evidence.getValue(group.id), blocked(group, eligible, input.states, evidence))
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
        val group = automatic(input, candidates)
            ?: return GrammarFocusDecision(candidates, null, emptyList(), "No eligible focus has new material.", resetReason)
        val activation = GrammarActivation(group.id, FocusOrigin.Automatic, input.cursor.automaticOrdinal)
        val reason = if (input.budget <= 0) "No ordinary new-item allowance remains." else {
            val policy = if (input.cursor.automaticOrdinal % 3 == 0L) "Exploration" else "Authored-first"
            "$policy slot ${input.cursor.automaticOrdinal}: ${group.label}. Recommendation scores are zero in Phase C."
        }
        return GrammarFocusDecision(candidates, activation, select(input, group, activation), reason, resetReason)
    }

    private fun blocked(
        group: IntroductionGroup,
        eligible: Set<String>,
        states: Map<String, SrsState>,
        evidence: Map<String, GroupEvidence>,
    ): String? {
        if (group.authored) {
            val missing = group.facets.filter { facet ->
                group.members.filter { it.facetId == facet.id && it.exerciseId in eligible }.map { it.exampleKey }.distinct().size < 2
            }
            if (missing.isNotEmpty()) return "Scope cannot cover two contexts for: ${missing.joinToString { it.label }}."
            val prerequisites = group.requires.filter { evidence[it]?.familiar != true }
            if (prerequisites.isNotEmpty()) return "Supported familiarity needed for: ${prerequisites.joinToString()}."
        }
        if (group.members.none { it.exerciseId in eligible && !isStarted(states[it.exerciseId]) }) return "No eligible unseen members remain."
        return null
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

    private fun automatic(input: GrammarFocusInput, candidates: List<FocusCandidate>): IntroductionGroup? {
        var pool = candidates.filter { it.blocked == null }
        if (input.cursor.automaticOrdinal % 3 != 0L && pool.any { it.group.authored }) pool = pool.filter { it.group.authored }
        if (pool.any { !it.evidence.familiar }) pool = pool.filter { !it.evidence.familiar }
        return pool.minByOrNull { tie(input.sourceId, input.cursor.automaticOrdinal, it.group.id) }?.group
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
