package io.github.stopcran.kanji.core.listening

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class AssessmentPhase { Ready, Instruction, Preview, Audio, Answer, ExampleFeedback, Results }

@Serializable
data class AssessmentState(
    val version: Long = 0,
    val index: Int = 0, val phase: AssessmentPhase = AssessmentPhase.Ready, val stageIndex: Int = 0,
    val generation: Long = 0, val deadlineMs: Long? = null, val remainingMs: Long? = null,
    val answer: String? = null, val skipped: Boolean = false, val paused: Boolean = false,
    val interrupted: Boolean = false, val interruptions: Set<String> = emptySet(), val assistance: Set<String> = emptySet(),
)

@Serializable
data class AssessmentItem(
    val encounterId: String, val taskId: String, val type: ListeningTaskType, val example: Boolean,
    val familiar: Boolean, val contentHash: String, val mediaHash: String,
)

@Serializable
data class AssessmentForm(
    val version: Int = 1, val blueprint: String = ListeningAssessmentBlueprint.ID, val seed: Long,
    val includeFamiliar: Boolean, val visualAccess: Boolean, val projectedMs: Long, val items: List<AssessmentItem>,
)

@Serializable
enum class AssessmentOutcome {
    @SerialName("correct") Correct,
    @SerialName("incorrect") Incorrect,
    @SerialName("unanswered") Unanswered,
    @SerialName("not-assessed") NotAssessed,
}

@Serializable
data class AssessmentResponse(
    val encounterId: String, val taskId: String, val type: ListeningTaskType, val example: Boolean,
    val familiar: Boolean, val exposed: Boolean, val visual: Boolean, val answer: String?,
    val outcome: AssessmentOutcome, val assistance: Set<String>, val interrupted: Boolean, val finalizedAtMs: Long,
)

@Serializable
data class AssessmentResult(
    val version: Int = 1, val completedFullForm: Boolean, val interrupted: Boolean, val interruptions: Set<String>,
    val planned: Int, val examples: Int, val responses: List<AssessmentResponse>,
) {
    val scored: List<AssessmentResponse> get() = responses.filterNot { it.example }
    val assessed: Int get() = scored.count { it.outcome != AssessmentOutcome.NotAssessed }
    val correct: Int get() = scored.count { it.outcome == AssessmentOutcome.Correct }
    val unanswered: Int get() = scored.count { it.outcome == AssessmentOutcome.Unanswered }
    val notAssessed: Int get() = scored.count { it.outcome == AssessmentOutcome.NotAssessed }
}

object ListeningAssessmentPolicy {
    fun beginItem(state: AssessmentState, task: ListeningTaskPackage): AssessmentState {
        val fresh = state.copy(answer = null, skipped = false, deadlineMs = null, remainingMs = null,
            assistance = emptySet(), generation = state.generation + 1)
        return if (task.usage == TaskUsage.Example) fresh.copy(phase = AssessmentPhase.Instruction, stageIndex = 0)
        else beforeAudio(fresh, task)
    }

    private fun beforeAudio(state: AssessmentState, task: ListeningTaskPackage): AssessmentState =
        state.copy(phase = if (ListeningAssessmentBlueprint.previewMs(task) > 0) AssessmentPhase.Preview else AssessmentPhase.Audio,
            stageIndex = 1, remainingMs = ListeningAssessmentBlueprint.previewMs(task).takeIf { it > 0 })

    fun windowVisible(state: AssessmentState, now: Long): AssessmentState =
        if (state.phase in setOf(AssessmentPhase.Preview, AssessmentPhase.ExampleFeedback) && !state.paused && state.deadlineMs == null) {
            state.copy(deadlineMs = now + requireNotNull(state.remainingMs))
        } else state

    fun audioCompleted(state: AssessmentState, task: ListeningTaskPackage, now: Long): AssessmentState {
        require(!state.paused) { "Assessment playback is paused" }
        return when (state.phase) {
            AssessmentPhase.Instruction -> beforeAudio(state, task)
            AssessmentPhase.Audio -> if (state.stageIndex < task.stages.lastIndex) state.copy(stageIndex = state.stageIndex + 1)
                else state.copy(phase = AssessmentPhase.Answer, deadlineMs = now + ListeningAssessmentBlueprint.answerMs(task),
                    remainingMs = ListeningAssessmentBlueprint.answerMs(task))
            else -> error("Assessment is not playing a required stage")
        }
    }

    fun expired(state: AssessmentState, now: Long): Boolean =
        !state.paused && state.deadlineMs?.let { now >= it } == true

    fun checkpoint(state: AssessmentState, now: Long): AssessmentState =
        if (!state.paused && state.deadlineMs != null) state.copy(remainingMs = (state.deadlineMs - now).coerceAtLeast(0)) else state

    fun choose(state: AssessmentState, task: ListeningTaskPackage, answer: String, now: Long): AssessmentState {
        require(task.options.any { it.id == answer }) { "Unknown captured answer" }
        require(accepting(state, now)) { "This answer window is not accepting changes" }
        return state.copy(answer = answer)
    }

    fun skip(state: AssessmentState, now: Long): AssessmentState {
        require(accepting(state, now)) { "This answer window is not accepting changes" }
        return state.copy(answer = null, skipped = true)
    }

    private fun accepting(state: AssessmentState, now: Long): Boolean {
        val active = state.phase == AssessmentPhase.Answer && !state.paused && !state.skipped
        return active && state.deadlineMs != null && !expired(state, now)
    }

    fun interrupt(state: AssessmentState, now: Long, reason: String, restoring: Boolean = false): AssessmentState {
        require(reason.isNotBlank()) { "An interruption needs a reason" }
        if (state.phase == AssessmentPhase.Results) return state
        // Persisted remaining time is trustworthy; a previous process's elapsed-realtime deadline is not.
        val remaining = if (restoring) state.remainingMs else state.deadlineMs?.let { (it - now).coerceAtLeast(0) } ?: state.remainingMs
        return state.copy(paused = true, interrupted = true, interruptions = state.interruptions + reason,
            deadlineMs = null, remainingMs = remaining, generation = state.generation + 1)
    }

    fun continuePractice(state: AssessmentState, task: ListeningTaskPackage, now: Long): AssessmentState {
        require(state.paused && state.interrupted) { "Only explicitly interrupted practice can resume" }
        val remaining = state.remainingMs ?: when (state.phase) {
            AssessmentPhase.Preview -> ListeningAssessmentBlueprint.PREVIEW_MS
            AssessmentPhase.Answer -> ListeningAssessmentBlueprint.answerMs(task)
            AssessmentPhase.ExampleFeedback -> ListeningAssessmentBlueprint.EXAMPLE_FEEDBACK_MS
            else -> null
        }
        val awaitVisibility = state.phase in setOf(AssessmentPhase.Preview, AssessmentPhase.ExampleFeedback)
        return state.copy(paused = false, generation = state.generation + 1,
            deadlineMs = remaining?.takeUnless { awaitVisibility }?.let { now + it },
            remainingMs = remaining)
    }

    fun outcome(task: ListeningTaskPackage, state: AssessmentState, sufficientEvidence: Boolean): AssessmentOutcome = when {
        !sufficientEvidence -> AssessmentOutcome.NotAssessed
        state.skipped || state.answer == null -> AssessmentOutcome.Unanswered
        state.answer == task.answer -> AssessmentOutcome.Correct
        else -> AssessmentOutcome.Incorrect
    }
}
