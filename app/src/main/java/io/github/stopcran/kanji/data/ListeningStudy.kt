package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class ListeningOverview(
    val sourceId: String,
    val passages: List<ListeningEntity>,
    val due: List<String>,
    val fresh: List<String>,
    val blocked: Map<String, String>,
    val cooldowns: Map<String, Instant>,
    val active: ListeningEncounterEntity?,
    val allowance: ListeningAllowance,
    val sharedAllowance: NewAllowance,
    val minimumNewMs: Long?,
    val notice: String?,
    val extra: List<String> = emptyList(),
)

private const val PLAYBACK_PROBLEM = "Playback failed; retry the encounter or sync again"

private class InvalidListeningAudio(val sourceId: String, val hash: String, cause: IllegalArgumentException) :
    IllegalArgumentException(cause.message, cause)

class ListeningStudy(
    private val db: AppDatabase,
    private val settings: Settings,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    fun attempt(row: ListeningEncounterEntity): ListeningAttempt =
        json.decodeFromString(ListeningAttempt.serializer(), row.stateJson)

    fun response(row: ListeningEncounterEntity): ListeningAttempt? =
        row.responseJson?.let { json.decodeFromString(ListeningAttempt.serializer(), it) }

    fun record(row: ListeningEncounterEntity): ListeningRecord =
        ListeningContent.parse("listening/${row.passageId}.json", row.contentJson).also {
            require(it.contentHash == row.contentHash && it.ready && it.passage.audio?.sha256 == row.audioHash) {
                "The captured listening package is invalid"
            }
        }

    suspend fun overview(sourceId: String, format: String = "all"): ListeningOverview = db.withTransaction {
        overviewInside(sourceId, clock(), ListeningSelection(settings.listeningScopeFor(sourceId), format))
    }

    private suspend fun overviewInside(sourceId: String, now: Instant, selection: ListeningSelection): ListeningOverview {
        val queue = db.listeningQueue(settings, sourceId, now.atZone(zone()), selection = selection)
        val legacyIds = queue.passages.mapTo(hashSetOf()) { it.id }
        val taskIds = queue.tasks.mapTo(hashSetOf()) { it.id }
        return ListeningOverview(
            sourceId, queue.passages, queue.due.filter { it in legacyIds }, queue.fresh.filter { it in legacyIds },
            queue.blocked.filterKeys { it !in taskIds }, queue.cooldowns.filterKeys { it in legacyIds },
            db.listening().active(sourceId), queue.allowance, queue.shared, queue.minimumNewMs,
            db.content().meta(sourceId)?.listeningProblem,
            queue.extra.filter { it in legacyIds },
        )
    }

    suspend fun start(
        sourceId: String, passageId: String, study: Boolean = false, extra: Boolean = false,
        selection: ListeningSelection = ListeningSelection(settings.listeningScopeFor(sourceId)),
    ): String = lock.withLock {
        require(!study || !extra) { "Extra listening practice is scored, not unscored study" }
        try {
            db.withTransaction { createEncounter(sourceId, passageId, study, extra, selection) }
        } catch (e: InvalidListeningAudio) { blockInvalidAudio(e) }
    }

    private suspend fun createEncounter(
        sourceId: String, passageId: String, study: Boolean, extra: Boolean, selection: ListeningSelection,
    ): String {
        require(settings.source.value.id == sourceId) { "The selected content source changed; reopen Listening" }
        val dao = db.listening()
        dao.active(sourceId)?.let { active ->
            require(active.passageId == passageId && (active.kind == "study") == study) { "Resume or discard the unfinished listening encounter first" }
            return active.id
        }
        db.requireNoOtherListeningActivity(sourceId)
        require(selection.scope == settings.listeningScopeFor(sourceId)) { "Selected listening level changed; reopen Listening" }
        val now = clock()
        val home = overviewInside(sourceId, now, selection)
        val row = requireNotNull(home.passages.find { it.id == passageId }) { "Passage is unavailable in the selected listening level/format" }
        require(row.ready) { row.reason ?: "Listening audio has not been approved" }
        val eligible = if (extra) home.extra else home.due + home.fresh
        require(study || passageId in eligible) { "This passage is not eligible for a new scored attempt now" }
        val record = ListeningContent.parse("listening/$passageId.json", row.json)
        val audio = requireNotNull(record.passage.audio)
        require(record.ready && record.contentHash == row.contentHash && audio.sha256 == row.audioHash) { "Listening content changed; sync again" }
        checkedAudio(sourceId, audio.sha256, audio.durationMs)
        val imageBytes = record.passage.image?.let { image ->
            requireNotNull(db.content().imageBytes(sourceId, image.path.removePrefix("images/"))).also {
                require(ContentParser.rawSha256(it) == image.sha256) { "The listening picture changed" }
            }
        }
        val words = db.content().referencedWords(sourceId, record.passage.vocabulary.mapNotNull { it.word })
            .associate { it.word to it.body }
        val state = ListeningAttempt(
            record.passage.questions.associate { q -> q.id to q.options.map { it.id }.shuffled() },
            phase = if (study) "study" else "listen", vocabularyBodies = words,
        )
        val id = "listening:${UUID.randomUUID()}"
        val reviewed = reviewState(sourceId, passageId).phase != CardPhase.New
        val encounter = ListeningEncounterEntity(
            id, sourceId, passageId, row.json, row.contentHash, audio.sha256, imageBytes,
            encode(state), if (study) "study" else "graded", now.toEpochMilli(),
            ListeningPolicy.estimatedMs(audio.durationMs), newReservation = !study && !reviewed,
        )
        dao.putEncounter(encounter)
        db.reviews().insertSession(StudySessionEntity(id, sourceId, LISTENING_STACK, LISTENING_MODE, study || extra, now.toEpochMilli()))
        return id
    }

    private suspend fun reviewState(sourceId: String, passageId: String): SrsState =
        db.reviews().states(sourceId, LISTENING_STACK, LISTENING_MODE)
            .singleOrNull { it.kanji == passageId && it.reading.isEmpty() }?.toSrs() ?: SrsState()

    suspend fun audio(id: String): ByteArray = withContext(Dispatchers.IO) {
        val row = requireNotNull(db.listening().encounter(id)) { "Listening encounter no longer exists" }
        val audio = requireNotNull(record(row).passage.audio)
        try {
            checkedAudio(row.sourceId, row.audioHash, audio.durationMs)
        } catch (e: InvalidListeningAudio) { blockInvalidAudio(e) }
    }

    private suspend fun blockInvalidAudio(error: InvalidListeningAudio): Nothing {
        db.listening().blockAudio(error.sourceId, error.hash, "Cached audio is invalid; sync again")
        throw error
    }

    private suspend fun checkedAudio(sourceId: String, hash: String, duration: Long): ByteArray = try {
        val bytes = requireNotNull(db.listening().audio(sourceId, hash)) { "Cached audio is missing; sync again" }
        require(ContentParser.rawSha256(bytes) == hash && kotlin.math.abs(OpusAudio.durationMs(bytes) - duration) <= 100) {
            "Cached audio failed verification; sync again"
        }
        bytes
    } catch (e: IllegalArgumentException) { throw InvalidListeningAudio(sourceId, hash, e) }

    suspend fun playbackFailed(id: String) = db.withTransaction {
        val row = requireNotNull(db.listening().encounter(id)) { "Listening encounter no longer exists" }
        db.listening().blockAudio(row.sourceId, row.audioHash, PLAYBACK_PROBLEM)
    }

    private suspend fun recoverPlayback(row: ListeningEncounterEntity) {
        val dao = db.listening()
        dao.passages(row.sourceId).filter { it.audioHash == row.audioHash && it.reason == PLAYBACK_PROBLEM }.forEach { current ->
            val verified = ListeningContent.parse("listening/${current.id}.json", current.json)
            val audio = verified.passage.audio
            if (verified.ready && verified.contentHash == current.contentHash && audio?.sha256 == row.audioHash) {
                checkedAudio(row.sourceId, row.audioHash, audio.durationMs)
                dao.restorePlayback(row.sourceId, current.id, current.contentHash, row.audioHash, PLAYBACK_PROBLEM)
            }
        }
    }

    suspend fun playbackStarted(id: String, restart: Boolean, slower: Boolean) = mutate(id) { row, state, _, now ->
        val scoredPhase = row.kind == "graded" && row.reviewLogId == null
        val starts = if (scoredPhase && (restart || state.starts == 0)) state.starts + 1 else state.starts
        val help = if (scoredPhase) buildSet {
            addAll(state.assistance)
            if (starts > 2) add("extra-replay")
            if (slower) add("slower-playback")
        } else state.assistance
        row.copy(
            stateJson = encode(state.copy(starts = starts, assistance = help, positionMs = if (restart) 0 else state.positionMs)),
            exposedAtMs = row.exposedAtMs ?: now,
            firstEncounter = if (row.exposedAtMs == null) !db.listening().hasExposure(row.sourceId, row.passageId) else row.firstEncounter,
            lastExposureAtMs = now,
            lastStudyAtMs = if (!scoredPhase) now else row.lastStudyAtMs,
        )
    }

    suspend fun revealStudy(id: String) = mutate(id) { row, _, _, now ->
        require(row.kind == "study" || row.reviewLogId != null) { "Transcript is unavailable before submission" }
        row.copy(
            exposedAtMs = row.exposedAtMs ?: now, lastExposureAtMs = now, lastStudyAtMs = now,
            firstEncounter = if (row.exposedAtMs == null) !db.listening().hasExposure(row.sourceId, row.passageId) else row.firstEncounter,
        )
    }

    suspend fun position(id: String, positionMs: Long) = mutate(id) { row, state, passage, now ->
        val audio = requireNotNull(passage.audio)
        require(positionMs in 0..audio.durationMs + 200) { "Invalid playback position" }
        row.copy(
            stateJson = encode(state.copy(positionMs = positionMs.coerceAtMost(audio.durationMs))),
            lastExposureAtMs = if (row.exposedAtMs != null) now else null,
            lastStudyAtMs = if (row.kind == "study" || row.reviewLogId != null) now else row.lastStudyAtMs,
        )
    }

    suspend fun playbackCompleted(id: String) = mutate(id) { row, state, passage, now ->
        require(row.exposedAtMs != null) { "Playback never started" }
        recoverPlayback(row)
        val scoredPhase = row.kind == "graded" && row.reviewLogId == null
        row.copy(
            stateJson = encode(state.copy(
                completedPlaybacks = if (scoredPhase) state.completedPlaybacks + 1 else state.completedPlaybacks,
                positionMs = requireNotNull(passage.audio).durationMs, phase = if (scoredPhase) "questions" else state.phase,
            )),
            lastExposureAtMs = now, lastStudyAtMs = if (!scoredPhase) now else row.lastStudyAtMs,
        )
    }

    suspend fun answer(id: String, questionId: String, optionId: String) = mutate(id) { row, state, passage, _ ->
        require(row.kind == "graded" && row.reviewLogId == null && state.phase == "questions") { "This encounter is not accepting answers" }
        require(passage.questions.any { it.id == questionId } && optionId in state.optionOrder[questionId].orEmpty()) {
            "Answer refers to a different presentation"
        }
        row.copy(stateJson = encode(state.copy(answers = state.answers + (questionId to optionId))))
    }

    suspend fun submit(id: String, guessed: Boolean = false, giveUp: Boolean = false) = mutate(id, allowSubmitted = true) { row, state, passage, now ->
        if (row.reviewLogId != null) return@mutate row
        require(row.kind == "graded" && row.exposedAtMs != null && (giveUp || state.completedPlaybacks > 0)) {
            "This encounter has no completed scored listening attempt"
        }
        val assistance = state.assistance + if (guessed) setOf("guessed") else emptySet()
        val result = ListeningPolicy.grade(passage, state.answers, assistance.isNotEmpty(), giveUp)
        val previous = reviewState(row.sourceId, row.passageId)
        val next = ListeningPolicy.scheduler.review(
            previous, result.grade, Instant.ofEpochMilli(now), firstSuccessCapDays = Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS,
            fuzzSeed = Fsrs.seed(row.passageId, LISTENING_MODE, previous.reps),
        )
        db.reviews().putState(next.toEntity(row.sourceId, LISTENING_STACK, row.passageId, StudyMode.Listening))
        val log = db.reviews().insertLog(ReviewLogEntity(
            sourceId = row.sourceId, stack = LISTENING_STACK, kanji = row.passageId, mode = LISTENING_MODE,
            grade = result.grade.value, atMs = now, sessionId = row.id,
        ))
        val response = encode(state.copy(phase = "answered", correct = result.correct, grade = result.grade.value, assistance = assistance))
        row.copy(
            stateJson = response, responseJson = response, reviewLogId = log, submittedAtMs = now,
            introduced = previous.phase == CardPhase.New, newReservation = false, lastStudyAtMs = now,
        )
    }

    suspend fun close(id: String, discard: Boolean = false) = lock.withLock {
        db.withTransaction {
            val row = requireNotNull(db.listening().encounter(id)) { "Listening encounter no longer exists" }
            if (row.closedAtMs != null) return@withTransaction
            require(discard || row.kind == "study" || row.reviewLogId != null) { "An unfinished scored encounter must be resumed or explicitly discarded" }
            val now = clock().toEpochMilli()
            val state = attempt(row)
            val studied = row.kind == "study" && row.exposedAtMs != null
            val reason = if (discard && row.reviewLogId == null) "discarded" else "completed"
            val closed = row.copy(
                closedAtMs = now, closeReason = reason,
                newReservation = false, imageBytes = null,
                lastExposureAtMs = if (studied) now else row.lastExposureAtMs,
                lastStudyAtMs = if (studied || row.reviewLogId != null) now else row.lastStudyAtMs,
            )
            db.listening().putEncounter(closed)
            recordExposure(closed)
            db.reviews().endSession(row.id, now, reason,
                if (row.reviewLogId == null) 0 else 1, if ((state.grade ?: 0) > 1) 1 else 0)
            db.listening().pruneAudio(row.sourceId)
        }
    }

    private suspend fun mutate(
        id: String,
        allowSubmitted: Boolean = false,
        block: suspend (ListeningEncounterEntity, ListeningAttempt, ListeningPassage, Long) -> ListeningEncounterEntity,
    ): ListeningEncounterEntity = lock.withLock {
        db.withTransaction {
            val row = requireNotNull(db.listening().encounter(id)) { "Listening encounter no longer exists" }
            require(row.closedAtMs == null || (allowSubmitted && row.reviewLogId != null)) { "Listening encounter is already closed" }
            val updated = block(row, attempt(row), record(row).passage, clock().toEpochMilli())
            recordExposure(updated)
            db.listening().putEncounter(updated)
            updated
        }
    }

    private suspend fun recordExposure(row: ListeningEncounterEntity) {
        row.exposedAtMs?.let { first ->
            db.recordListeningExposure(row.sourceId, row.passageId, first, maxOf(row.lastExposureAtMs ?: first, row.lastStudyAtMs ?: first))
        }
    }

    private fun encode(state: ListeningAttempt) = json.encodeToString(ListeningAttempt.serializer(), state)
}
