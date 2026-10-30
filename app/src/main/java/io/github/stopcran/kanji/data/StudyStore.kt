package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.srs.ReviewRecord

data class StudyStart(val id: String, val sourceId: String, val stack: String, val mode: String, val extra: Boolean, val atMs: Long)
data class StudyEnd(val id: String, val atMs: Long, val reason: String, val answered: Int, val correct: Int)

interface StudySessionStore {
    suspend fun start(value: StudyStart)
    suspend fun end(value: StudyEnd)
}

class RoomStudySessionStore(private val db: AppDatabase) : StudySessionStore {
    override suspend fun start(value: StudyStart) {
        db.reviews().insertSession(StudySessionEntity(value.id, value.sourceId, value.stack, value.mode, value.extra, value.atMs))
    }

    override suspend fun end(value: StudyEnd) {
        check(db.reviews().endSession(value.id, value.atMs, value.reason, value.answered, value.correct) == 1) {
            "Could not close study session"
        }
    }
}

fun interface ReviewStore {
    suspend fun record(value: ReviewRecord)
}

class RoomReviewStore(private val db: AppDatabase) : ReviewStore {
    override suspend fun record(value: ReviewRecord) {
        db.reviews().record(
            value.state.toEntity(value.sourceId, value.stack, value.itemId, value.mode),
            ReviewLogEntity(
                0, value.sourceId, value.stack, value.itemId, value.mode.name, value.grade.value,
                value.at.toEpochMilli(), sessionId = value.sessionId,
            ),
        )
    }
}
