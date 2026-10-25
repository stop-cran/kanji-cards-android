package io.github.stopcran.kanji.data

import android.os.SystemClock
import androidx.room.withTransaction
import io.github.stopcran.kanji.core.listening.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class ListeningAssessmentStudy(
    private val db: AppDatabase, settings: Settings, private val tasks: ListeningTaskStudy,
    private val clock: () -> Instant = Instant::now, private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lock = Mutex()
    private val captures = ListeningAssessmentCapture(db, settings, clock)
    private val store = ListeningAssessmentStore(db, tasks)
    private val freshCaptures = mutableSetOf<String>()

    suspend fun preview(
        seed: Long, familiar: Boolean = false, visual: Boolean = true, profile: ListeningAssessmentProfile = ListeningAssessmentProfile.N5,
    ): ListeningAssessmentPreview = db.withTransaction { captures.preview(seed, familiar, visual, profile) }

    suspend fun start(preview: ListeningAssessmentPreview): String = lock.withLock {
        db.withTransaction { captures.capture(preview) }.also { freshCaptures += it }
    }

    suspend fun snapshot(id: String): ListeningAssessmentSnapshot = db.withTransaction { store.read(id) }

    suspend fun feedbackItem(id: String, index: Int, visible: Boolean = false): ListeningTaskEncounterEntity = lock.withLock {
        db.withTransaction {
            val parent = store.read(id)
            require(parent.row.closedAtMs == null && parent.result != null) { "Scored feedback is not released yet" }
            require(index in parent.form.items.indices) { "Unknown captured item" }
            val item = parent.form.items[index]
            val child = requireNotNull(db.listeningTasks().encounter(item.encounterId)) { "Captured item is missing" }
            store.checked(parent.row, item, child)
            if (!visible) child else db.exposeListeningTask(child, clock().toEpochMilli()).also { db.listeningTasks().putEncounter(it) }
        }
    }

    suspend fun restore(id: String): ListeningAssessmentSnapshot = mutate(id) { current ->
        store.validateChildren(current)
        db.listeningTasks().assessmentEncounters(id).forEach { child ->
            val attempt = tasks.attempt(child)
            if (attempt.pending || attempt.started) {
                val recovered = ListeningTaskPolicy.interrupted(attempt, attempt.generation, 0)
                    .copy(pendingAssistance = attempt.pendingAssistance + "recovery-replay")
                db.listeningTasks().putEncounter(child.copy(stateJson = Json.encodeToString(recovered)))
            }
        }
        if (freshCaptures.remove(id) || current.state.phase == AssessmentPhase.Results) current else {
            store.save(current, ListeningAssessmentPolicy.interrupt(current.state, elapsed(), "Process or screen restoration", restoring = true))
        }
    }

    suspend fun begin(id: String): ListeningAssessmentSnapshot = mutate(id) {
        require(it.state.phase == AssessmentPhase.Ready && !it.state.paused) { "Explicitly continue this interrupted assessment" }
        store.save(it, ListeningAssessmentPolicy.beginItem(it.state, it.record.task))
    }

    suspend fun visible(
        id: String, generation: Long, help: Boolean = false, explanation: Boolean = false,
    ): ListeningAssessmentSnapshot = mutate(id) {
        if (it.state.generation != generation) return@mutate it
        val state = it.state
        val feedback = state.phase == AssessmentPhase.Results || state.phase == AssessmentPhase.ExampleFeedback
        require(help || state.phase !in setOf(AssessmentPhase.Ready, AssessmentPhase.Instruction)) { "No task evidence is visible yet" }
        require(!help || feedback || state.paused) { "Interrupt before revealing scored help" }
        require(!explanation || state.phase == AssessmentPhase.ExampleFeedback) { "No worked explanation is visible yet" }
        val permitted = help || it.record.task.choiceMode != TaskChoiceMode.Audio || it.record.task.visual != null
        require(permitted) { "This task has no permitted visual preview" }
        db.listeningTasks().putEncounter(db.exposeListeningTask(it.encounter, clock().toEpochMilli()))
        val helped = if (help && !feedback) state.copy(assistance = state.assistance + "text-help") else state
        val timed = if (state.phase == AssessmentPhase.ExampleFeedback && !explanation) helped
            else ListeningAssessmentPolicy.windowVisible(helped, elapsed())
        store.save(it, timed)
    }

    suspend fun audioCompleted(id: String, generation: Long, stage: Int): ListeningAssessmentSnapshot = mutate(id) {
        val state = it.state
        val active = !state.paused && state.generation == generation && state.stageIndex == stage
        if (!active || state.phase !in setOf(AssessmentPhase.Instruction, AssessmentPhase.Audio)) return@mutate it
        require(it.record.task.stages[stage].id in it.attempt.completedStages && !it.attempt.pending && !it.attempt.started) {
            "A required stage has not completed native playback"
        }
        store.save(it, ListeningAssessmentPolicy.audioCompleted(state, it.record.task, elapsed()))
    }

    suspend fun pulse(id: String, generation: Long): ListeningAssessmentSnapshot = mutate(id) {
        if (it.state.generation != generation) return@mutate it
        val next = settle(it)
        val checkpoint = ListeningAssessmentPolicy.checkpoint(next.state, elapsed())
        if (checkpoint == next.state) next else store.save(next, checkpoint)
    }

    suspend fun answer(id: String, generation: Long, option: String?): ListeningAssessmentSnapshot = mutate(id) {
        val next = settle(it)
        if (next.state.generation != generation || next.state.phase != AssessmentPhase.Answer) return@mutate next
        if (next.state.skipped) return@mutate next
        val state = if (option == null) ListeningAssessmentPolicy.skip(next.state, elapsed())
            else ListeningAssessmentPolicy.choose(next.state, next.record.task, option, elapsed())
        val saved = store.save(next, state)
        if (option == null) store.finalize(saved, clock().toEpochMilli())
        store.read(id)
    }

    suspend fun interrupt(id: String, reason: String): ListeningAssessmentSnapshot = mutate(id) {
        val next = settle(it)
        store.save(next, ListeningAssessmentPolicy.interrupt(next.state, elapsed(), reason))
    }

    suspend fun continuePractice(id: String): ListeningAssessmentSnapshot = mutate(id) {
        val task = it.record.task
        var state = ListeningAssessmentPolicy.continuePractice(it.state, task, elapsed())
        if (state.phase == AssessmentPhase.Ready) state = ListeningAssessmentPolicy.beginItem(state, task).copy(assistance = state.assistance)
        if (state.phase in setOf(AssessmentPhase.Audio, AssessmentPhase.Instruction)) {
            // A process can die after B persists native completion but before F moves its cursor.
            while (task.stages[state.stageIndex].id in it.attempt.completedStages &&
                task.stages[state.stageIndex].id !in it.attempt.failedStages &&
                state.phase in setOf(AssessmentPhase.Audio, AssessmentPhase.Instruction)) {
                state = ListeningAssessmentPolicy.audioCompleted(state, task, elapsed())
            }
        }
        if (it.attempt.stageIndex != state.stageIndex) {
            val aligned = it.attempt.copy(stageIndex = state.stageIndex, positionMs = 0,
                pendingAssistance = it.attempt.pendingAssistance + "recovery-replay")
            db.listeningTasks().putEncounter(it.encounter.copy(stateJson = Json.encodeToString(aligned)))
        }
        store.save(it, state)
    }

    suspend fun end(id: String): ListeningAssessmentSnapshot = mutate(id) {
        store.finish(settle(it), clock().toEpochMilli(), fullTimeline = false)
    }

    suspend fun close(id: String, discardUnreadable: Boolean = false) = lock.withLock {
        db.withTransaction {
            val dao = db.listeningTasks()
            var row = requireNotNull(dao.assessment(id)) { "Assessment no longer exists" }
            if (row.closedAtMs != null) return@withTransaction
            require(discardUnreadable || row.responseJson != null) { "Explicitly end the assessment before closing it" }
            val now = clock().toEpochMilli()
            if (discardUnreadable && row.responseJson == null) {
                val captured = readableForDiscard(id)
                if (captured != null) row = store.finish(captured, now, fullTimeline = false).row
            }
            dao.assessmentEncounters(id).forEach { child ->
                dao.putEncounter(child.copy(closedAtMs = now, closeReason = "assessment-closed", newReservation = false))
            }
            dao.putAssessment(row.copy(closedAtMs = now))
            dao.unpin(row.sourceId, "assessment", id)
            dao.pruneAssets(row.sourceId)
            if (row.responseJson == null) db.reviews().endSession(id, now, "assessment-unreadable-discarded", 0, 0)
            freshCaptures.remove(id)
        }
    }

    private suspend fun readableForDiscard(id: String): ListeningAssessmentSnapshot? = try {
        store.read(id).also { store.validateChildren(it) }
    } catch (error: IllegalArgumentException) {
        android.util.Log.w("ListeningAssessment", "Discarding an unreadable capture without interpreting its unfinished items", error)
        null
    }

    private suspend fun settle(current: ListeningAssessmentSnapshot): ListeningAssessmentSnapshot {
        if (!ListeningAssessmentPolicy.expired(current.state, elapsed())) return current
        return when (current.state.phase) {
            AssessmentPhase.Preview -> store.save(current, current.state.copy(phase = AssessmentPhase.Audio, deadlineMs = null, remainingMs = null))
            AssessmentPhase.Answer -> {
                store.finalize(current, clock().toEpochMilli())
                if (current.record.task.usage == TaskUsage.Example) {
                    store.save(current, current.state.copy(phase = AssessmentPhase.ExampleFeedback,
                        deadlineMs = null,
                        remainingMs = ListeningAssessmentBlueprint.EXAMPLE_FEEDBACK_MS))
                } else nextItem(current)
            }
            AssessmentPhase.ExampleFeedback -> nextItem(current)
            else -> error("Unexpected timed assessment phase")
        }
    }

    private suspend fun nextItem(current: ListeningAssessmentSnapshot): ListeningAssessmentSnapshot {
        if (current.state.index == current.form.items.lastIndex) return store.finish(current, clock().toEpochMilli(), fullTimeline = true)
        val next = store.save(current, current.state.copy(index = current.state.index + 1, stageIndex = 0,
            phase = AssessmentPhase.Ready, deadlineMs = null, remainingMs = null))
        return store.save(next, ListeningAssessmentPolicy.beginItem(next.state, next.record.task))
    }

    private suspend fun mutate(
        id: String, operation: suspend (ListeningAssessmentSnapshot) -> ListeningAssessmentSnapshot,
    ): ListeningAssessmentSnapshot = lock.withLock {
        db.withTransaction {
            val snapshot = store.read(id)
            if (snapshot.row.closedAtMs != null) snapshot else operation(snapshot)
        }
    }
}
