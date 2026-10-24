package io.github.stopcran.kanji.core.listening

import io.github.stopcran.kanji.core.srs.Grade
import kotlinx.serialization.Serializable

@Serializable
data class ListeningTaskAttempt(
    val stageIndex: Int = 0,
    val completedStages: Set<String> = emptySet(),
    val wholePass: Int = 0,
    val wholeReplayInProgress: Boolean = false,
    val wholeReplayHeard: Boolean = false,
    val generation: Long = 0,
    val pending: Boolean = false,
    val started: Boolean = false,
    val positionMs: Long = 0,
    val assistance: Set<String> = emptySet(),
    val pendingAssistance: Set<String> = emptySet(),
    val answer: String? = null,
    val technicalProblem: String? = null,
    val pictureProblem: String? = null,
    val failedStages: Set<String> = emptySet(),
    val phase: String = "listen",
    val correct: Boolean? = null,
    val grade: Int? = null,
    val giveUp: Boolean = false,
)

data class TaskPlayRequest(
    val stage: Int, val wholeReplay: Boolean = false, val targeted: Boolean = false,
    val slower: Boolean = false, val recoveryReplay: Boolean = false,
)

object ListeningTaskPolicy {
    // B installs the contracts without enabling unfinished C/D renderers.
    val supportedTypes: Set<ListeningTaskType> = emptySet()

    fun request(
        task: ListeningTaskPackage, state: ListeningTaskAttempt, request: TaskPlayRequest,
    ): ListeningTaskAttempt {
        val stage = request.stage
        require(stage in task.stages.indices && !state.pending && !state.started) { "Stop the current stage before starting another" }
        require(!request.wholeReplay || stage == 1) { "A whole replay starts after the generic instruction" }
        val redirectsReplay = request.wholeReplay || request.targeted
        val reset = redirectsReplay || request.recoveryReplay
        val abandonedReplay = state.wholeReplayInProgress && state.wholeReplayHeard && redirectsReplay
        return state.copy(stageIndex = stage,
            wholeReplayInProgress = request.wholeReplay || state.wholeReplayInProgress && !request.targeted,
            wholeReplayHeard = if (request.wholeReplay) false else state.wholeReplayHeard,
            generation = Math.addExact(state.generation, 1), pending = true, started = false,
            positionMs = if (reset || stage != state.stageIndex) 0 else state.positionMs,
            assistance = state.assistance + if (abandonedReplay) setOf("partial-replay") else emptySet(),
            pendingAssistance = assistance(task, state, request, reset))
    }

    private fun assistance(task: ListeningTaskPackage, state: ListeningTaskAttempt, request: TaskPlayRequest, reset: Boolean): Set<String> =
        buildSet {
            if (!reset && request.stage == state.stageIndex) addAll(state.pendingAssistance)
            if (request.targeted && task.stages[request.stage].role != TaskStageRole.Instruction) add("targeted-replay")
            if (request.slower) add("slower-playback")
            if (request.recoveryReplay) add("recovery-replay")
        }

    fun started(state: ListeningTaskAttempt, generation: Long): ListeningTaskAttempt {
        if (state.generation != generation || !state.pending) return state
        val firstReplayStart = state.wholeReplayInProgress && !state.wholeReplayHeard
        val pass = state.wholePass + if (firstReplayStart) 1 else 0
        return state.copy(pending = false, started = true, wholePass = pass,
            wholeReplayHeard = state.wholeReplayHeard || state.wholeReplayInProgress,
            assistance = state.assistance + state.pendingAssistance + if (pass > 1) setOf("extra-replay") else emptySet(),
            pendingAssistance = emptySet())
    }

    fun completed(task: ListeningTaskPackage, state: ListeningTaskAttempt, generation: Long): ListeningTaskAttempt {
        if (state.generation != generation || !state.started) return state
        val completed = state.completedStages + task.stages[state.stageIndex].id
        val failed = state.failedStages - task.stages[state.stageIndex].id
        val all = task.stages.all { it.id in completed }
        return state.copy(completedStages = completed, stageIndex = (state.stageIndex + 1).coerceAtMost(task.stages.lastIndex),
            wholeReplayInProgress = state.wholeReplayInProgress && state.stageIndex != task.stages.lastIndex,
            pending = false, started = false, positionMs = 0, failedStages = failed,
            technicalProblem = if (failed.isEmpty()) null else state.technicalProblem,
            phase = if (all && state.phase == "listen") "answer" else state.phase)
    }

    fun interrupted(state: ListeningTaskAttempt, generation: Long, positionMs: Long): ListeningTaskAttempt =
        if (generation != state.generation) state else state.copy(generation = Math.addExact(state.generation, 1),
            pending = false, started = false, pendingAssistance = state.pendingAssistance - "slower-playback",
            positionMs = if (state.started) positionMs else state.positionMs)

    fun grade(task: ListeningTaskPackage, state: ListeningTaskAttempt, exposed: Boolean, giveUp: Boolean): Grade {
        require(state.technicalProblem == null && state.pictureProblem == null && exposed) { "Unavailable or unheard evidence cannot be graded" }
        require(giveUp || task.stages.all { it.id in state.completedStages }) { "Required stages are incomplete" }
        require(giveUp || state.answer in task.options.map { it.id }) { "Choose an answer before submitting" }
        return when {
            giveUp || state.answer != task.answer -> Grade.Again
            state.assistance.isNotEmpty() -> Grade.Hard
            else -> Grade.Good
        }
    }
}
