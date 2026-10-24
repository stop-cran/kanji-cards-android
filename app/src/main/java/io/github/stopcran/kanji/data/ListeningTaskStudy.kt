package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ListeningTaskStudy(
    private val db: AppDatabase, private val settings: Settings,
    private val clock: () -> Instant = Instant::now, private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val supportedTypes: Set<ListeningTaskType> = ListeningTaskPolicy.supportedTypes,
    private val allowUnapprovedStudyCapture: Boolean = false,
) {
    private val lock = Mutex()
    private val json = Json
    private val assets = ListeningTaskAssets(db)

    fun attempt(row: ListeningTaskEncounterEntity): ListeningTaskAttempt =
        json.decodeFromString(ListeningTaskAttempt.serializer(), row.stateJson)

    fun record(row: ListeningTaskEncounterEntity): ListeningTaskRecord {
        require(row.policyVersion == ListeningTaskContent.POLICY_VERSION) {
            "This captured listening policy is unsupported; update the app or explicitly discard the unfinished task"
        }
        return ListeningTaskContent.parse("listening-tasks/${row.taskId}.json", row.contentJson).also {
            require((it.ready || authorStudy(row, it)) && it.contentHash == row.contentHash && it.mediaHash == row.mediaHash &&
                it.task.exposureId == row.exposureId && it.task.policyVersion == row.policyVersion) { "Captured task is invalid" }
        }
    }

    // Only private preseeded author studies opt in; catalog admission remains approval-gated.
    private fun authorStudy(row: ListeningTaskEncounterEntity, record: ListeningTaskRecord): Boolean {
        if (!allowUnapprovedStudyCapture) return false
        val standalone = row.kind == "study" && row.assessmentId == null && !row.newReservation
        val ungraded = row.reviewLogId == null && row.submittedAtMs == null && !row.introduced
        val completeDraft = record.task.approval == null && record.mediaHash != null && row.responseJson == null
        return standalone && ungraded && completeDraft
    }

    internal suspend fun overview(sourceId: String): ListeningQueue =
        db.withTransaction { db.listeningQueue(settings, sourceId, clock(), zone(), supportedTypes) }

    suspend fun start(
        sourceId: String, taskId: String, study: Boolean = false, extra: Boolean = false, described: Boolean = false,
    ): String = db.withTransaction {
        require(settings.source.value.id == sourceId) { "Selected content source changed; reopen Listening" }
        checkMode(study, extra, described)
        val dao = db.listeningTasks()
        dao.active(sourceId)?.let {
            require(it.taskId == taskId && (it.kind == "study") == study &&
                attempt(it).describedStudy == described) { "Resume or discard the unfinished listening activity first" }
            return@withTransaction it.id
        }
        db.requireNoOtherListeningActivity(sourceId)
        val now = clock()
        val queue = db.listeningQueue(settings, sourceId, now, zone(), supportedTypes)
        val row = requireNotNull(queue.tasks.find { it.id == taskId }) { "Task is no longer available" }
        checkEligibility(queue, row, study, extra)
        val captured = ListeningTaskContent.parse("listening-tasks/$taskId.json", requireNotNull(row.json))
        require(captured.ready && captured.contentHash == row.contentHash && captured.task.exposureId == row.exposureId) { "Task changed; sync again" }
        require(study || captured.task.usage == TaskUsage.Scored) { "Worked examples are unscored" }
        require(!described || captured.task.visual != null) { "This task has no described picture presentation" }
        assets.check(sourceId, captured)
        val sync = requireNotNull(dao.sync(sourceId)) { "Task source revision is missing" }
        val words = db.content().referencedWords(sourceId, captured.task.vocabulary.mapNotNull { it.word })
        require(words.mapTo(hashSetOf()) { it.word }.containsAll(captured.task.vocabulary.mapNotNull { it.word })) { "A task word article is missing" }
        val id = "listening-task:${UUID.randomUUID()}"
        val state = ListeningTaskAttempt(phase = if (study) "study" else "listen", describedStudy = described)
        val prior = queue.states[taskId] ?: SrsState()
        dao.putEncounter(ListeningTaskEncounterEntity(
            id, sourceId, taskId, captured.task.exposureId, requireNotNull(sync.acceptedBranch), sync.acceptedRevision,
            requireNotNull(sync.acceptedContentVersion), captured.json, captured.contentHash, requireNotNull(captured.mediaHash),
            captured.task.policyVersion, JsonObject(words.associate { it.word to JsonPrimitive(it.body) }).toString(),
            encode(state), if (study) "study" else "graded", null, now.toEpochMilli(), requireNotNull(captured.estimatedMs),
            newReservation = !study && prior.phase == CardPhase.New,
        ))
        dao.pin(captured.assetHashes.map { ListeningAssetPinEntity(sourceId, "encounter", id, it) })
        db.reviews().insertSession(StudySessionEntity(id, sourceId, LISTENING_STACK, LISTENING_MODE, study || extra, now.toEpochMilli()))
        id
    }

    private fun checkMode(study: Boolean, extra: Boolean, described: Boolean) {
        require(!study || !extra) { "Extra practice is scored, not unscored study" }
        require(!described || study) { "Described presentation is explicitly unscored study" }
    }

    private fun checkEligibility(queue: ListeningQueue, row: ListeningTaskEntity, study: Boolean, extra: Boolean) {
        require(row.ready && row.taskType in supportedTypes.map { it.wire }) { row.reason ?: "This task type is not available" }
        require(study || row.id in if (extra) queue.extra else queue.due + queue.fresh) { "Task is not eligible for scored practice now" }
    }

    suspend fun image(id: String): ByteArray? = lock.withLock {
        db.withTransaction {
            val dao = db.listeningTasks()
            val row = requireNotNull(dao.encounter(id)) { "Listening task no longer exists" }
            val picture = record(row).task.media?.image
            val result: Result<ByteArray?> = try { Result.success(picture?.let { assets.image(row.sourceId, it) }) }
            catch (e: IllegalArgumentException) { Result.failure(e) }
            if (row.closedAtMs == null) {
                val problem = result.exceptionOrNull()?.let { it.message ?: "Captured task picture is invalid" }
                dao.putEncounter(row.copy(stateJson = encode(attempt(row).copy(pictureProblem = problem))))
            }
            result
        }.getOrThrow()
    }

    suspend fun stageAudio(id: String, generation: Long): ByteArray = db.withTransaction {
        val row = requireNotNull(db.listeningTasks().encounter(id)) { "Listening task no longer exists" }
        val state = attempt(row)
        if (state.generation != generation || !state.pending || row.closedAtMs != null) throw CancellationException("Superseded task playback")
        val task = record(row).task
        assets.audio(row.sourceId, requireNotNull(task.media).audio.getValue(task.stages[state.stageIndex].id))
    }

    suspend fun request(
        id: String, stage: Int? = null, wholeReplay: Boolean = false, slower: Boolean = false, recoveryReplay: Boolean = false,
    ): ListeningTaskEncounterEntity = mutate(id) { row, state, task ->
        val index = if (wholeReplay) 1 else stage ?: state.stageIndex
        require(index in task.stages.indices) { "Unknown listening stage" }
        val targeted = !wholeReplay && (stage != null ||
            !state.wholeReplayInProgress && state.positionMs == 0L && task.stages[index].id in state.completedStages)
        row.copy(stateJson = encode(ListeningTaskPolicy.request(task, state, TaskPlayRequest(index, wholeReplay, targeted, slower, recoveryReplay))))
    }

    suspend fun started(id: String, generation: Long) = mutate(id, nativeEvent = true) { row, state, task ->
        val next = ListeningTaskPolicy.started(state, generation)
        if (next == state) row else {
            val exposed = if (task.stages[next.stageIndex].role != TaskStageRole.Instruction) expose(row) else row
            exposed.copy(stateJson = encode(next))
        }
    }

    suspend fun completed(id: String, generation: Long) = mutate(id, nativeEvent = true) { row, state, task ->
        val next = ListeningTaskPolicy.completed(task, state, generation)
        if (next == state) row else {
            val exposed = if (task.stages[state.stageIndex].role != TaskStageRole.Instruction) expose(row) else row
            exposed.copy(stateJson = encode(next))
        }
    }

    suspend fun interrupt(id: String, generation: Long, positionMs: Long) = mutate(id, nativeEvent = true) { row, state, task ->
        if (state.generation != generation) row else {
            val duration = requireNotNull(task.media).audio.getValue(task.stages[state.stageIndex].id).durationMs
            require(positionMs in 0..duration + 200) { "Invalid captured playback position" }
            val exposed = if (state.started && task.stages[state.stageIndex].role != TaskStageRole.Instruction) expose(row) else row
            exposed.copy(stateJson = encode(ListeningTaskPolicy.interrupted(state, generation, positionMs.coerceAtMost(duration))))
        }
    }

    suspend fun failed(id: String, generation: Long, reason: String) = mutate(id, nativeEvent = true) { row, state, task ->
        if (state.generation != generation) row else row.copy(stateJson = encode(
            ListeningTaskPolicy.interrupted(state, generation, state.positionMs).copy(technicalProblem = reason,
                failedStages = state.failedStages + task.stages[state.stageIndex].id,
                assistance = state.assistance + if (state.started) setOf("recovery-replay") else emptySet()),
        ))
    }

    suspend fun recover(id: String) = mutate(id) { row, state, _ ->
        if (!state.pending && !state.started) row else row.copy(stateJson = encode(
            ListeningTaskPolicy.interrupted(state, state.generation, 0).copy(assistance = state.assistance + "recovery-replay"),
        ))
    }

    suspend fun revealEvidence(id: String, textHelp: Boolean = false) = mutate(id) { row, state, task ->
        require(textHelp || row.kind == "study" || row.reviewLogId != null ||
            task.choiceMode != TaskChoiceMode.Audio || task.visual?.role == "required") { "This task has no permitted visual evidence" }
        val next = if (textHelp && row.kind == "graded" && row.reviewLogId == null) {
            state.copy(assistance = state.assistance + "text-help")
        } else state
        expose(row).copy(stateJson = encode(next))
    }

    private suspend fun expose(row: ListeningTaskEncounterEntity): ListeningTaskEncounterEntity =
        db.exposeListeningTask(row, clock().toEpochMilli())

    suspend fun answer(id: String, option: String) = mutate(id) { row, state, task ->
        require(row.kind == "graded" && row.reviewLogId == null && state.phase == "answer" &&
            task.options.any { it.id == option }) { "Task is not accepting this answer" }
        row.copy(stateJson = encode(state.copy(answer = option)))
    }

    suspend fun submit(id: String, guessed: Boolean = false, giveUp: Boolean = false) = mutate(id, allowSubmitted = true) { row, state, task ->
        if (row.reviewLogId != null) return@mutate row
        require(row.kind == "graded" && row.assessmentId == null) { "This listening activity is unscored" }
        val response = state.copy(assistance = buildSet {
            addAll(state.assistance)
            if (guessed) add("guessed")
            if (state.wholeReplayInProgress && state.wholeReplayHeard) add("partial-replay")
        })
        val grade = ListeningTaskPolicy.grade(task, response, row.exposedAtMs != null, giveUp)
        val previous = db.reviews().states(row.sourceId, LISTENING_STACK, LISTENING_MODE)
            .singleOrNull { it.kanji == row.taskId && it.reading.isEmpty() }?.toSrs() ?: SrsState()
        val now = clock()
        val next = ListeningPolicy.scheduler.review(previous, grade, now, firstSuccessCapDays = Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS,
            fuzzSeed = Fsrs.seed(row.taskId, LISTENING_MODE, previous.reps))
        db.reviews().putState(next.toEntity(row.sourceId, LISTENING_STACK, row.taskId, StudyMode.Listening))
        val log = db.reviews().insertLog(ReviewLogEntity(sourceId = row.sourceId, stack = LISTENING_STACK, kanji = row.taskId,
            mode = LISTENING_MODE, grade = grade.value, atMs = now.toEpochMilli(), sessionId = row.id))
        val frozen = encode(response.copy(phase = "answered", correct = !giveUp && response.answer == task.answer, grade = grade.value, giveUp = giveUp))
        expose(row).copy(stateJson = frozen, responseJson = frozen, reviewLogId = log, submittedAtMs = now.toEpochMilli(),
            newReservation = false, introduced = previous.phase == CardPhase.New)
    }

    suspend fun close(id: String, discard: Boolean = false) = db.withTransaction {
        val dao = db.listeningTasks()
        val row = requireNotNull(dao.encounter(id)) { "Listening task no longer exists" }
        if (row.closedAtMs != null) return@withTransaction
        require(row.assessmentId == null) { "End or close the owning assessment instead" }
        require(discard || row.kind == "study" || row.reviewLogId != null) { "Explicitly discard unfinished scored work" }
        val now = clock().toEpochMilli()
        val reason = if (discard && row.reviewLogId == null) "discarded" else "completed"
        val state = if (discard && row.reviewLogId == null) null else attempt(row)
        dao.putEncounter(row.copy(closedAtMs = now, closeReason = reason, newReservation = false,
            stateJson = state?.let { encode(ListeningTaskPolicy.interrupted(it, it.generation, it.positionMs)) } ?: row.stateJson))
        dao.unpin(row.sourceId, "encounter", row.id)
        dao.pruneAssets(row.sourceId)
        db.reviews().endSession(id, now, reason, if (row.reviewLogId == null) 0 else 1, if (state?.correct == true) 1 else 0)
    }

    private suspend fun mutate(
        id: String, allowSubmitted: Boolean = false, nativeEvent: Boolean = false,
        block: suspend (ListeningTaskEncounterEntity, ListeningTaskAttempt, ListeningTaskPackage) -> ListeningTaskEncounterEntity,
    ): ListeningTaskEncounterEntity = lock.withLock {
        db.withTransaction {
            val row = requireNotNull(db.listeningTasks().encounter(id)) { "Listening task no longer exists" }
            if (nativeEvent && row.closedAtMs != null) return@withTransaction row
            require(row.closedAtMs == null || allowSubmitted && row.reviewLogId != null) { "Listening task is closed" }
            val task = record(row).task
            val updated = block(row, attempt(row), task)
            db.listeningTasks().putEncounter(updated)
            updated
        }
    }

    private fun encode(state: ListeningTaskAttempt) = json.encodeToString(ListeningTaskAttempt.serializer(), state)
}
