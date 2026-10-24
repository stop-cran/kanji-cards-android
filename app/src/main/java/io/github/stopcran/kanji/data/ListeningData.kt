package io.github.stopcran.kanji.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "listening_passages", primaryKeys = ["sourceId", "id"])
data class ListeningEntity(
    val sourceId: String, val id: String, val json: String, val contentHash: String,
    val audioHash: String?, val durationMs: Long?, val ready: Boolean, val reason: String?, val position: Int,
)

@Entity(tableName = "listening_audio", primaryKeys = ["sourceId", "sha256"])
class ListeningAudioEntity(val sourceId: String, val sha256: String, val bytes: ByteArray)

@Entity(
    tableName = "listening_encounters",
    indices = [Index(value = ["sourceId", "passageId"]), Index(value = ["sourceId", "closedAtMs"]),
        Index(value = ["reviewLogId"], unique = true)],
)
data class ListeningEncounterEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val passageId: String,
    val contentJson: String,
    val contentHash: String,
    val audioHash: String,
    val imageBytes: ByteArray?,
    val stateJson: String,
    val kind: String,
    val createdAtMs: Long,
    val estimatedMs: Long,
    val newReservation: Boolean,
    val exposedAtMs: Long? = null,
    val lastExposureAtMs: Long? = null,
    val lastStudyAtMs: Long? = null,
    val firstEncounter: Boolean = false,
    val reviewLogId: Long? = null,
    val responseJson: String? = null,
    val submittedAtMs: Long? = null,
    val introduced: Boolean = false,
    val closedAtMs: Long? = null,
    val closeReason: String? = null,
)

data class ListeningExposure(val passageId: String, val lastPracticeAtMs: Long?)
data class ListeningIntroductions(val count: Int, val estimatedMs: Long)
data class ListeningResume(val id: String, val sourceId: String)

@Dao
interface ListeningDao {
    @Query("SELECT * FROM listening_passages WHERE sourceId = :sourceId ORDER BY position")
    suspend fun passages(sourceId: String): List<ListeningEntity>

    @Query("SELECT id FROM listening_passages WHERE sourceId = :sourceId " +
        "UNION SELECT passageId AS id FROM listening_encounters WHERE sourceId = :sourceId")
    suspend fun knownPassageIds(sourceId: String): List<String>

    @Query("SELECT * FROM listening_passages WHERE sourceId = :sourceId ORDER BY position")
    fun observePassages(sourceId: String): Flow<List<ListeningEntity>>

    @Query("SELECT * FROM listening_passages WHERE sourceId = :sourceId AND id = :id")
    suspend fun passage(sourceId: String, id: String): ListeningEntity?

    @Query("SELECT bytes FROM listening_audio WHERE sourceId = :sourceId AND sha256 = :hash")
    suspend fun audio(sourceId: String, hash: String): ByteArray?

    @Query("DELETE FROM listening_audio WHERE sourceId = :sourceId " +
        "AND sha256 NOT IN (SELECT audioHash FROM listening_passages WHERE sourceId = :sourceId AND audioHash IS NOT NULL) " +
        "AND sha256 NOT IN (SELECT audioHash FROM listening_encounters WHERE sourceId = :sourceId AND closedAtMs IS NULL)")
    suspend fun pruneAudio(sourceId: String)

    @Query("UPDATE listening_passages SET ready = 0, reason = :reason WHERE sourceId = :sourceId AND audioHash = :hash")
    suspend fun blockAudio(sourceId: String, hash: String, reason: String)

    @Query("UPDATE listening_passages SET ready = 1, reason = NULL " +
        "WHERE sourceId = :sourceId AND id = :id AND contentHash = :contentHash AND audioHash = :hash AND reason = :reason")
    suspend fun restorePlayback(sourceId: String, id: String, contentHash: String, hash: String, reason: String)

    @Query("SELECT * FROM listening_encounters WHERE id = :id")
    suspend fun encounter(id: String): ListeningEncounterEntity?

    @Query("SELECT * FROM listening_encounters WHERE id = :id")
    fun observeEncounter(id: String): Flow<ListeningEncounterEntity?>

    @Query("SELECT * FROM listening_encounters WHERE sourceId = :sourceId AND closedAtMs IS NULL ORDER BY createdAtMs LIMIT 1")
    suspend fun active(sourceId: String): ListeningEncounterEntity?

    @Query("SELECT * FROM listening_encounters WHERE sourceId = :sourceId AND closedAtMs IS NULL ORDER BY createdAtMs LIMIT 1")
    fun observeActive(sourceId: String): Flow<ListeningEncounterEntity?>

    @Query("SELECT id, sourceId FROM listening_encounters WHERE closedAtMs IS NULL ORDER BY createdAtMs")
    fun observeOpen(): Flow<List<ListeningResume>>

    @Query("SELECT id, sourceId FROM listening_encounters WHERE closedAtMs IS NULL ORDER BY createdAtMs")
    suspend fun open(): List<ListeningResume>

    @Query("SELECT * FROM listening_encounters ORDER BY createdAtMs, id")
    suspend fun allEncounters(): List<ListeningEncounterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEncounter(encounter: ListeningEncounterEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM listening_encounters WHERE sourceId = :sourceId AND passageId = :passageId AND exposedAtMs IS NOT NULL) " +
        "OR EXISTS(SELECT 1 FROM listening_exposures WHERE sourceId = :sourceId AND exposureId = :passageId)")
    suspend fun hasExposure(sourceId: String, passageId: String): Boolean

    @Query("SELECT passageId, MAX(lastPracticeAtMs) AS lastPracticeAtMs FROM (" +
        "SELECT passageId, CASE WHEN kind = 'study' OR closeReason = 'discarded' THEN lastExposureAtMs ELSE lastStudyAtMs END AS lastPracticeAtMs " +
        "FROM listening_encounters WHERE sourceId = :sourceId UNION ALL " +
        "SELECT exposureId AS passageId, lastAtMs AS lastPracticeAtMs FROM listening_exposures WHERE sourceId = :sourceId) GROUP BY passageId")
    suspend fun exposures(sourceId: String): List<ListeningExposure>

    @Query("SELECT COUNT(*) FROM listening_encounters WHERE sourceId = :sourceId AND newReservation = 1 " +
        "AND kind = 'graded' AND reviewLogId IS NULL AND closedAtMs IS NULL")
    suspend fun reservedNew(sourceId: String): Int

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(estimatedMs), 0) AS estimatedMs FROM listening_encounters " +
        "WHERE sourceId = :sourceId AND introduced = 1 AND submittedAtMs >= :sinceMs")
    suspend fun introductions(sourceId: String, sinceMs: Long): ListeningIntroductions
}

val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_passages (" +
            "sourceId TEXT NOT NULL, id TEXT NOT NULL, json TEXT NOT NULL, contentHash TEXT NOT NULL, " +
            "audioHash TEXT, durationMs INTEGER, ready INTEGER NOT NULL, reason TEXT, position INTEGER NOT NULL, PRIMARY KEY(sourceId, id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_audio (" +
            "sourceId TEXT NOT NULL, sha256 TEXT NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(sourceId, sha256))")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_encounters (" +
            "id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, passageId TEXT NOT NULL, contentJson TEXT NOT NULL, " +
            "contentHash TEXT NOT NULL, audioHash TEXT NOT NULL, imageBytes BLOB, stateJson TEXT NOT NULL, kind TEXT NOT NULL, " +
            "createdAtMs INTEGER NOT NULL, estimatedMs INTEGER NOT NULL, newReservation INTEGER NOT NULL, " +
            "exposedAtMs INTEGER, lastExposureAtMs INTEGER, lastStudyAtMs INTEGER, firstEncounter INTEGER NOT NULL, " +
            "reviewLogId INTEGER, responseJson TEXT, submittedAtMs INTEGER, introduced INTEGER NOT NULL, closedAtMs INTEGER, closeReason TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_encounters_sourceId_passageId ON listening_encounters(sourceId, passageId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_encounters_sourceId_closedAtMs ON listening_encounters(sourceId, closedAtMs)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_listening_encounters_reviewLogId ON listening_encounters(reviewLogId)")
        db.execSQL("ALTER TABLE sync_meta ADD COLUMN listeningProblem TEXT")
    }
}
