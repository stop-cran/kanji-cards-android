package io.github.stopcran.kanji.core.listening

import kotlin.random.Random

data class AssessmentCandidate(
    val record: ListeningTaskRecord, val lastExposureMs: Long? = null, val problem: String? = null,
)

data class AssessmentGroup(val type: ListeningTaskType, val scored: Int, val pool: Int)

data class AssessmentAvailability(
    val group: AssessmentGroup, val readyScored: Int, val eligibleScored: Int, val eligibleExamples: Int,
    val unavailable: Int, val coolingDown: Int, val familiarExcluded: Int, val inaccessible: Int,
    val nextCooldownMs: Long?,
)

data class AssessmentSelection(
    val items: List<AssessmentCandidate>, val groups: List<AssessmentAvailability>,
    val problems: List<String>, val durationMs: Long?,
) {
    val available: Boolean get() = problems.isEmpty() && items.isNotEmpty()
    val familiarCount: Int get() = items.count { it.lastExposureMs != null && it.record.task.usage == TaskUsage.Scored }
}

object ListeningAssessmentBlueprint {
    const val ID = "n5-section-v1"
    const val PREVIEW_MS = 5_000L
    const val EXAMPLE_ANSWER_MS = 10_000L
    const val EXAMPLE_FEEDBACK_MS = 10_000L
    const val MIN_MS = 26 * 60_000L
    const val MAX_MS = 34 * 60_000L
    val groups = listOf(
        AssessmentGroup(ListeningTaskType.TaskBased, 7, 8),
        AssessmentGroup(ListeningTaskType.KeyPoint, 6, 7),
        AssessmentGroup(ListeningTaskType.VerbalExpression, 5, 6),
        AssessmentGroup(ListeningTaskType.QuickResponse, 6, 7),
    )

    fun answerMs(task: ListeningTaskPackage): Long = when {
        task.usage == TaskUsage.Example -> EXAMPLE_ANSWER_MS
        task.taskType.audioChoices -> 10_000L
        else -> 15_000L
    }

    fun previewMs(task: ListeningTaskPackage): Long =
        if (task.taskType == ListeningTaskType.QuickResponse) 0 else PREVIEW_MS

    fun durationMs(task: ListeningTaskPackage): Long {
        val media = requireNotNull(task.media) { "Assessment media is missing" }
        val stages = if (task.usage == TaskUsage.Example) task.stages else task.stages.drop(1)
        return stages.sumOf { media.audio.getValue(it.id).durationMs } + previewMs(task) + answerMs(task) +
            if (task.usage == TaskUsage.Example) EXAMPLE_FEEDBACK_MS else 0
    }

    fun select(
        candidates: List<AssessmentCandidate>, nowMs: Long, seed: Long,
        includeFamiliar: Boolean = false, visualAccess: Boolean = true,
    ): AssessmentSelection {
        val pool = candidates.filter { it.record.task.blueprint == ID }
        val ready = pool.filter { it.record.ready && it.problem == null }
        val eligible = ready.filter { exclusion(it, nowMs, includeFamiliar, visualAccess) == null }
        val availability = groups.map { group ->
            availability(group, pool, nowMs, includeFamiliar, visualAccess)
        }
        val problems = availability.flatMap(::shortages).toMutableList()
        if (ready.map { it.record.task.exposureId }.distinct().size != ready.size) {
            problems += "The approved blueprint pool shares story identities; independent stories are required."
        }
        if (pool.map { it.record.task.id }.distinct().size != pool.size) problems += "The blueprint pool repeats task IDs."
        if (problems.isNotEmpty()) return AssessmentSelection(emptyList(), availability, problems, null)
        val random = Random(seed)
        val selected = groups.flatMap { group ->
            val choices = eligible.filter { it.record.task.taskType == group.type }.sortedBy { it.record.task.id }
            choices.filter { it.record.task.usage == TaskUsage.Example }.shuffled(random).take(1) +
                choices.filter { it.record.task.usage == TaskUsage.Scored }.shuffled(random).take(group.scored)
        }
        val duration = selected.sumOf { durationMs(it.record.task) }
        if (duration !in MIN_MS..MAX_MS) problems +=
            "The selected form lasts $duration ms; a full section requires $MIN_MS-$MAX_MS ms."
        return AssessmentSelection(selected, availability, problems, duration)
    }

    private fun exclusion(candidate: AssessmentCandidate, now: Long, familiar: Boolean, visual: Boolean): String? = when {
        !visual && candidate.record.task.visual?.role == "required" -> "visual"
        candidate.lastExposureMs != null && now < candidate.lastExposureMs + ListeningPolicy.COOLDOWN_MS -> "cooldown"
        !familiar && candidate.lastExposureMs != null -> "familiar"
        else -> null
    }

    private fun availability(
        group: AssessmentGroup, pool: List<AssessmentCandidate>, now: Long, familiar: Boolean, visual: Boolean,
    ): AssessmentAvailability {
        val members = pool.filter { it.record.task.taskType == group.type && it.record.ready && it.problem == null }
        val exclusions = members.groupBy { exclusion(it, now, familiar, visual) }
        val usable = exclusions[null].orEmpty()
        return AssessmentAvailability(
            group, members.count { it.record.task.usage == TaskUsage.Scored },
            usable.count { it.record.task.usage == TaskUsage.Scored }, usable.count { it.record.task.usage == TaskUsage.Example },
            pool.count { it.record.task.taskType == group.type } - members.size,
            exclusions["cooldown"].orEmpty().size, exclusions["familiar"].orEmpty().size, exclusions["visual"].orEmpty().size,
            exclusions["cooldown"]?.minOfOrNull { requireNotNull(it.lastExposureMs) + ListeningPolicy.COOLDOWN_MS },
        )
    }

    private fun shortages(status: AssessmentAvailability): List<String> = buildList {
        val type = status.group.type.wire
        if (status.readyScored < status.group.pool) add("$type: ${status.readyScored}/${status.group.pool} required ready scored pool items.")
        if (status.eligibleScored < status.group.scored) add("$type: ${status.eligibleScored}/${status.group.scored} eligible scored items.")
        if (status.eligibleExamples == 0) add("$type: no eligible, separate worked example.")
    }
}
