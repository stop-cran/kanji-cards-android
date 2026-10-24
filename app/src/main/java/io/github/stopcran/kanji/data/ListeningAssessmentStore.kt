package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.listening.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ListeningAssessmentSnapshot(
    val row: ListeningAssessmentEntity, val form: AssessmentForm, val state: AssessmentState,
    val encounter: ListeningTaskEncounterEntity, val record: ListeningTaskRecord, val attempt: ListeningTaskAttempt,
    val result: AssessmentResult?,
)

internal class ListeningAssessmentStore(private val db: AppDatabase, private val tasks: ListeningTaskStudy) {
    suspend fun read(id: String): ListeningAssessmentSnapshot {
        val row = requireNotNull(db.listeningTasks().assessment(id)) { "Assessment no longer exists" }
        require(row.policyVersion == ListeningTaskContent.POLICY_VERSION && row.blueprint == ListeningAssessmentBlueprint.ID) {
            "This captured assessment policy is unsupported; update the app or explicitly discard it"
        }
        val form = Json.decodeFromString<AssessmentForm>(row.contentJson)
        validate(form)
        val state = Json.decodeFromString<AssessmentState>(row.stateJson)
        require(state.index in form.items.indices && state.generation >= 0 && state.version >= 0) { "Captured assessment cursor is invalid" }
        require(state.remainingMs == null || state.remainingMs >= 0) { "Captured assessment timer is invalid" }
        require(state.deadlineMs == null || state.deadlineMs >= 0) { "Captured assessment deadline is invalid" }
        require(state.remainingMs == null || state.remainingMs <= 15_000) { "Captured assessment window is too long" }
        val item = form.items[state.index]
        val encounter = requireNotNull(db.listeningTasks().encounter(item.encounterId)) { "Captured assessment item is missing" }
        val record = checked(row, item, encounter)
        require(state.stageIndex in record.task.stages.indices) { "Captured assessment stage is invalid" }
        val result = row.responseJson?.let { Json.decodeFromString<AssessmentResult>(it) }
        require((state.phase == AssessmentPhase.Results) == (result != null)) { "Captured assessment completion state is invalid" }
        result?.let {
            require(it.version == 1 && it.planned == 24 && it.examples == 4) { "Unsupported captured diagnostic result" }
            require(it.responses.map { response -> response.encounterId } == form.items.map { item -> item.encounterId }) {
                "Captured diagnostic response order changed"
            }
        }
        return ListeningAssessmentSnapshot(row, form, state, encounter, record, tasks.attempt(encounter), result)
    }

    suspend fun validateChildren(snapshot: ListeningAssessmentSnapshot) {
        val children = db.listeningTasks().assessmentEncounters(snapshot.row.id).associateBy { it.id }
        snapshot.form.items.forEach { item ->
            val child = requireNotNull(children[item.encounterId]) { "Captured assessment item is missing" }
            checked(snapshot.row, item, child)
            child.responseJson?.let { Json.decodeFromString<AssessmentResponse>(it) }
        }
    }

    private fun validate(form: AssessmentForm) {
        require(form.version == 1 && form.blueprint == ListeningAssessmentBlueprint.ID) { "Unsupported captured assessment form" }
        require(form.projectedMs in ListeningAssessmentBlueprint.MIN_MS..ListeningAssessmentBlueprint.MAX_MS) { "Invalid captured form duration" }
        require(form.items.size == 28 && form.items.map { it.taskId }.distinct().size == 28 &&
            form.items.map { it.encounterId }.distinct().size == 28) { "Invalid captured assessment membership" }
        var offset = 0
        ListeningAssessmentBlueprint.groups.forEach { group ->
            val items = form.items.subList(offset, offset + group.scored + 1)
            require(items.all { it.type == group.type } && items.first().example && items.drop(1).none { it.example }) {
                "Invalid captured assessment group order"
            }
            offset += items.size
        }
    }

    fun checked(
        parent: ListeningAssessmentEntity, item: AssessmentItem, row: ListeningTaskEncounterEntity,
    ): ListeningTaskRecord {
        val ownership = row.assessmentId == parent.id && row.sourceId == parent.sourceId && row.kind == "assessment"
        val binding = row.branch == parent.branch && row.revision == parent.revision && row.contentVersion == parent.contentVersion
        require(ownership && binding && !row.newReservation && !row.introduced && row.reviewLogId == null) { "Invalid diagnostic item ownership" }
        val record = tasks.record(row)
        require(record.task.id == item.taskId && record.contentHash == item.contentHash && record.mediaHash == item.mediaHash) {
            "Captured diagnostic item changed"
        }
        require(record.task.taskType == item.type && (record.task.usage == TaskUsage.Example) == item.example) {
            "Captured diagnostic item role changed"
        }
        return record
    }

    suspend fun save(snapshot: ListeningAssessmentSnapshot, state: AssessmentState): ListeningAssessmentSnapshot {
        db.listeningTasks().putAssessment(snapshot.row.copy(stateJson = Json.encodeToString(state.copy(version = snapshot.state.version + 1))))
        return read(snapshot.row.id)
    }

    suspend fun finalize(snapshot: ListeningAssessmentSnapshot, now: Long, notAssessed: Boolean = false) {
        if (snapshot.encounter.responseJson != null) return
        val item = snapshot.form.items[snapshot.state.index]
        val task = snapshot.record.task
        val required = if (item.example) task.stages else task.stages.drop(1)
        val available = snapshot.attempt.technicalProblem == null && snapshot.attempt.pictureProblem == null
        val evidence = available && required.all { it.id in snapshot.attempt.completedStages } && snapshot.encounter.exposedAtMs != null
        val outcome = if (notAssessed) AssessmentOutcome.NotAssessed else ListeningAssessmentPolicy.outcome(task, snapshot.state, evidence)
        val response = AssessmentResponse(item.encounterId, item.taskId, item.type, item.example, item.familiar,
            snapshot.encounter.exposedAtMs != null, task.visual != null, snapshot.state.answer.takeUnless { notAssessed }, outcome,
            snapshot.state.assistance + snapshot.attempt.assistance, snapshot.state.interrupted, now)
        db.listeningTasks().putEncounter(snapshot.encounter.copy(responseJson = Json.encodeToString(response), submittedAtMs = now))
    }

    suspend fun finish(snapshot: ListeningAssessmentSnapshot, now: Long, fullTimeline: Boolean): ListeningAssessmentSnapshot {
        if (snapshot.row.responseJson != null) return snapshot
        val dao = db.listeningTasks()
        val children = dao.assessmentEncounters(snapshot.row.id).associateBy { it.id }
        val responses = snapshot.form.items.map { item ->
            var child = requireNotNull(children[item.encounterId]) { "Captured assessment item is missing" }
            val record = checked(snapshot.row, item, child)
            if (child.responseJson == null) {
                val response = AssessmentResponse(item.encounterId, item.taskId, item.type, item.example, item.familiar,
                    child.exposedAtMs != null, record.task.visual != null, null, AssessmentOutcome.NotAssessed,
                    tasks.attempt(child).assistance, snapshot.state.interrupted, now)
                child = child.copy(responseJson = Json.encodeToString(response), submittedAtMs = now)
                dao.putEncounter(child)
            }
            Json.decodeFromString<AssessmentResponse>(requireNotNull(child.responseJson)).also {
                require(it.encounterId == item.encounterId && it.taskId == item.taskId && it.example == item.example) {
                    "Captured diagnostic response changed"
                }
            }
        }
        val scored = responses.filterNot { it.example }
        val complete = fullTimeline && !snapshot.state.interrupted && scored.all { it.outcome != AssessmentOutcome.NotAssessed }
        val result = AssessmentResult(completedFullForm = complete, interrupted = snapshot.state.interrupted,
            interruptions = snapshot.state.interruptions, planned = scored.size, examples = responses.size - scored.size, responses = responses)
        val state = snapshot.state.copy(phase = AssessmentPhase.Results, paused = false, deadlineMs = null, remainingMs = null,
            version = snapshot.state.version + 1)
        dao.putAssessment(snapshot.row.copy(stateJson = Json.encodeToString(state), responseJson = Json.encodeToString(result)))
        db.reviews().endSession(snapshot.row.id, now, if (complete) "assessment-completed" else "assessment-partial", 0, 0)
        return read(snapshot.row.id)
    }
}
