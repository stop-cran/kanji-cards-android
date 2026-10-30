package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarGroups
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.RepoSource
import kotlinx.serialization.Serializable

@Serializable
enum class GrammarTargetKind { Group, Set, Chapter }

@Serializable
data class GrammarPracticeTarget(val sourceId: String, val branch: String, val kind: GrammarTargetKind, val id: String) {
    init {
        require(RepoSource.parse("https://github.com/$sourceId", branch)?.id == sourceId) { "Invalid practice source." }
        require(id.isNotBlank() && id.length <= 256 && id.none(Char::isISOControl)) { "Invalid practice target." }
    }

    fun matches(source: RepoSource): Boolean = source.id == sourceId && source.branch == branch
}

data class GrammarPracticeMember(
    val exercise: GrammarExercise,
    val groupId: String,
    val started: Boolean,
    val due: Boolean,
    val outOfScope: Boolean,
    val missingPrerequisites: List<String>,
    val problem: String?,
    val allowedByPolicy: Boolean,
) {
    val eligible: Boolean get() = problem == null && allowedByPolicy
    val overridable: Boolean get() = problem == null && !eligible
}

/** Equality includes questions and restrictions, so consent cannot survive a changed accepted snapshot. */
data class GrammarPracticePreview(
    val target: GrammarPracticeTarget,
    val label: String,
    val contentVersion: String,
    val parserEpoch: Int,
    val scope: GrammarScope,
    val limit: Int,
    val members: List<GrammarPracticeMember>,
    val groups: Map<String, String>,
    val foundations: Map<String, String>,
    val notice: String?,
) {
    fun included(override: Boolean): List<GrammarPracticeMember> = members.filter { it.eligible || (override && it.overridable) }
    fun ids(override: Boolean): List<String> = included(override).map { it.exercise.id }
    fun initialCount(override: Boolean): Int = minOf(limit, included(override).size)
}

object GrammarPractice {
    fun validateSnapshot(sets: List<GrammarSet>) {
        require(sets.map { it.id }.distinct().size == sets.size) { "Duplicate Grammar sets in the cached snapshot." }
        val ids = sets.flatMap { it.exercises }.map { it.id }
        require(ids.distinct().size == ids.size) { "Duplicate Grammar exercises in the cached snapshot." }
        sets.forEach {
            ContentParser.validateGrammarSet("grammar/${it.id}.json", it)
            GrammarGroups.validate(it)
        }
        val resolved = GrammarGroups.resolve(sets)
        require(resolved.errors.isEmpty()) { "Invalid Grammar group metadata: ${resolved.errors.joinToString()}" }
    }

    @Suppress("LongParameterList")
    fun preview(
        target: GrammarPracticeTarget,
        sets: List<GrammarSet>,
        curriculum: GrammarCurriculumState,
        availability: Map<String, GrammarGroupAvailability>,
        contentVersion: String,
        parserEpoch: Int,
        scope: GrammarScope,
        limit: Int,
        readinessProblem: String? = null,
        notice: String? = null,
    ): GrammarPracticePreview {
        require(limit > 0) { "Practice batch size must be positive." }
        val groups = GrammarFocus.groups(sets).associateBy { it.id }
        val (label, ids) = when (target.kind) {
            GrammarTargetKind.Group -> {
                val group = requireNotNull(groups[target.id]) { "This learning group is no longer available. Choose a topic again." }
                group.label to group.members.map { it.exerciseId }
            }
            GrammarTargetKind.Set -> {
                val set = requireNotNull(sets.singleOrNull { it.id == target.id }) { "This Grammar set is no longer available. Choose a topic again." }
                set.title to set.exercises.map { it.id }
            }
            GrammarTargetKind.Chapter -> {
                require(curriculum is GrammarCurriculumState.Ready) {
                    when (curriculum) {
                        GrammarCurriculumState.PendingRefresh -> "Sync needed before practising a chapter."
                        is GrammarCurriculumState.Invalid -> "Chapter practice unavailable: ${curriculum.reason}"
                        else -> "This source has no available chapter catalog. Choose a group or set."
                    }
                }
                val chapter = requireNotNull(curriculum.curriculum.chapters.singleOrNull { it.id == target.id }) {
                    "This chapter is no longer available. Choose a topic again."
                }
                chapter.label to chapter.groups.flatMap { id ->
                    val group = requireNotNull(groups[id]?.takeIf { it.authored }) { "Chapter membership is invalid: $id." }
                    group.members.map { it.exerciseId }
                }
            }
        }
        require(ids.isNotEmpty() && ids.distinct().size == ids.size) { "The target has empty or ambiguous membership." }
        val exercises = sets.flatMap { it.exercises }.associateBy { it.id }
        val owners = groups.values.flatMap { group -> group.members.map { it.exerciseId to group.id } }.toMap()
        val members = ids.map { id ->
            val groupId = requireNotNull(owners[id]) { "Unknown exercise membership: $id." }
            val available = requireNotNull(availability[groupId]) { "Eligibility metadata is unavailable for $groupId." }
            val started = id in available.started
            val problem = if (started) null else available.newMetadataProblem
                ?: readinessProblem?.takeIf { available.missingPrerequisites.isNotEmpty() }
            GrammarPracticeMember(
                requireNotNull(exercises[id]) { "Missing exercise: $id." }, groupId, started, id in available.due,
                !started && id in available.outOfScopeUnseen,
                if (started) emptyList() else available.missingPrerequisites.toList(), problem,
                started || id in available.targetedNew,
            )
        }
        val foundations = members.flatMap { it.missingPrerequisites }.distinct().sorted().associateWith { id ->
            requireNotNull(groups[id]?.takeIf { it.authored }) { "Missing prerequisite metadata: $id." }.label
        }
        val labels = members.map { it.groupId }.distinct().associateWith { groups.getValue(it).label }
        return GrammarPracticePreview(target, label, contentVersion, parserEpoch, scope, limit, members, labels, foundations, notice)
    }
}
