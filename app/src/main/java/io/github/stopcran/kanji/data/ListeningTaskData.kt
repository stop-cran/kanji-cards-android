package io.github.stopcran.kanji.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "listening_tasks", primaryKeys = ["sourceId", "id"])
data class ListeningTaskEntity(
    val sourceId: String, val id: String, val json: String?, val contentHash: String?, val exposureId: String?,
    val taskType: String?, val title: String, val usage: String?, val estimatedMs: Long?,
    val ready: Boolean, val reason: String?, val position: Int,
)

@Entity(tableName = "listening_task_assets", primaryKeys = ["sourceId", "sha256"])
class ListeningTaskAssetEntity(val sourceId: String, val sha256: String, val bytes: ByteArray)

@Entity(tableName = "listening_asset_pins", primaryKeys = ["sourceId", "ownerKind", "ownerId", "sha256"])
data class ListeningAssetPinEntity(val sourceId: String, val ownerKind: String, val ownerId: String, val sha256: String)

@Entity(tableName = "listening_task_identities", primaryKeys = ["sourceId", "taskId"])
data class ListeningTaskIdentityEntity(val sourceId: String, val taskId: String, val exposureId: String)

@Entity(tableName = "listening_exposures", primaryKeys = ["sourceId", "exposureId"])
data class ListeningExposureEntity(val sourceId: String, val exposureId: String, val firstAtMs: Long, val lastAtMs: Long)

@Entity(tableName = "listening_task_sync")
data class ListeningTaskSyncEntity(
    @PrimaryKey val sourceId: String,
    val acceptedBranch: String?, val acceptedRevision: String?, val acceptedContentVersion: String?, val parserEpoch: Int,
    val attemptedBranch: String, val attemptedRevision: String?, val problem: String?, val updatedAtMs: Long,
)

@Entity(tableName = "listening_task_encounters", indices = [
    Index(value = ["sourceId", "taskId"]), Index(value = ["sourceId", "closedAtMs"]), Index(value = ["reviewLogId"], unique = true),
])
data class ListeningTaskEncounterEntity(
    @PrimaryKey val id: String,
    val sourceId: String, val taskId: String, val exposureId: String, val branch: String, val revision: String?,
    val contentVersion: String, val contentJson: String, val contentHash: String, val mediaHash: String,
    val policyVersion: Int, val vocabularyJson: String, val stateJson: String, val kind: String, val assessmentId: String?,
    val createdAtMs: Long, val estimatedMs: Long, val newReservation: Boolean,
    val exposedAtMs: Long? = null, val firstEncounter: Boolean = false, val reviewLogId: Long? = null,
    val responseJson: String? = null, val submittedAtMs: Long? = null, val introduced: Boolean = false,
    val closedAtMs: Long? = null, val closeReason: String? = null,
)

@Entity(tableName = "listening_assessments", indices = [Index(value = ["sourceId", "closedAtMs"])])
data class ListeningAssessmentEntity(
    @PrimaryKey val id: String,
    val sourceId: String, val branch: String, val revision: String?, val contentVersion: String,
    val blueprint: String, val policyVersion: Int, val contentJson: String, val stateJson: String,
    val createdAtMs: Long, val closedAtMs: Long? = null, val responseJson: String? = null,
)

@Dao
interface ListeningTaskDao {
    @Query("SELECT * FROM listening_tasks WHERE sourceId = :sourceId ORDER BY position")
    suspend fun tasks(sourceId: String): List<ListeningTaskEntity>

    @Query("SELECT * FROM listening_tasks WHERE sourceId = :sourceId ORDER BY position")
    fun observeTasks(sourceId: String): Flow<List<ListeningTaskEntity>>

    @Query("SELECT * FROM listening_task_sync WHERE sourceId = :sourceId")
    suspend fun sync(sourceId: String): ListeningTaskSyncEntity?

    @Query("SELECT * FROM listening_task_sync WHERE sourceId = :sourceId")
    fun observeSync(sourceId: String): Flow<ListeningTaskSyncEntity?>

    @Query("SELECT * FROM listening_task_identities WHERE sourceId = :sourceId")
    suspend fun identities(sourceId: String): List<ListeningTaskIdentityEntity>

    @Query("DELETE FROM listening_tasks WHERE sourceId = :sourceId")
    suspend fun clearTasks(sourceId: String)

    @Query("UPDATE listening_tasks SET ready = 0, reason = :reason WHERE sourceId = :sourceId AND id = :id")
    suspend fun blockTask(sourceId: String, id: String, reason: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTasks(rows: List<ListeningTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAssets(rows: List<ListeningTaskAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSync(row: ListeningTaskSyncEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putIdentities(rows: List<ListeningTaskIdentityEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun pin(rows: List<ListeningAssetPinEntity>)

    @Query("DELETE FROM listening_asset_pins WHERE sourceId = :sourceId AND ownerKind = :kind AND ownerId = :owner")
    suspend fun unpin(sourceId: String, kind: String, owner: String)

    @Query("DELETE FROM listening_asset_pins WHERE sourceId = :sourceId AND ownerKind = 'catalog'")
    suspend fun clearCatalogPins(sourceId: String)

    @Query("SELECT bytes FROM listening_task_assets WHERE sourceId = :sourceId AND sha256 = :hash")
    suspend fun asset(sourceId: String, hash: String): ByteArray?

    @Query("DELETE FROM listening_task_assets WHERE sourceId = :sourceId AND NOT EXISTS " +
        "(SELECT 1 FROM listening_asset_pins p WHERE p.sourceId = listening_task_assets.sourceId AND p.sha256 = listening_task_assets.sha256)")
    suspend fun pruneAssets(sourceId: String)

    @Query("SELECT * FROM listening_exposures WHERE sourceId = :sourceId")
    suspend fun exposures(sourceId: String): List<ListeningExposureEntity>

    @Query("SELECT * FROM listening_exposures WHERE sourceId = :sourceId AND exposureId = :id")
    suspend fun exposure(sourceId: String, id: String): ListeningExposureEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putExposure(row: ListeningExposureEntity)

    @Query("SELECT * FROM listening_task_encounters WHERE id = :id")
    suspend fun encounter(id: String): ListeningTaskEncounterEntity?

    @Query("SELECT * FROM listening_task_encounters WHERE id = :id")
    fun observeEncounter(id: String): Flow<ListeningTaskEncounterEntity?>

    @Query("SELECT * FROM listening_task_encounters WHERE sourceId = :sourceId AND assessmentId IS NULL AND closedAtMs IS NULL LIMIT 1")
    suspend fun active(sourceId: String): ListeningTaskEncounterEntity?

    @Query("SELECT * FROM listening_task_encounters WHERE sourceId = :sourceId AND assessmentId IS NULL AND closedAtMs IS NULL LIMIT 1")
    fun observeActive(sourceId: String): Flow<ListeningTaskEncounterEntity?>

    @Query("SELECT id, sourceId FROM listening_task_encounters WHERE assessmentId IS NULL AND closedAtMs IS NULL ORDER BY createdAtMs")
    fun observeOpen(): Flow<List<ListeningResume>>

    @Query("SELECT id, sourceId FROM listening_task_encounters WHERE assessmentId IS NULL AND closedAtMs IS NULL ORDER BY createdAtMs")
    suspend fun open(): List<ListeningResume>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEncounter(row: ListeningTaskEncounterEntity)

    @Query("SELECT COUNT(*) FROM listening_task_encounters WHERE sourceId = :sourceId AND newReservation = 1 " +
        "AND kind = 'graded' AND reviewLogId IS NULL AND closedAtMs IS NULL")
    suspend fun reservedNew(sourceId: String): Int

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(estimatedMs), 0) AS estimatedMs FROM listening_task_encounters " +
        "WHERE sourceId = :sourceId AND introduced = 1 AND submittedAtMs >= :sinceMs")
    suspend fun introductions(sourceId: String, sinceMs: Long): ListeningIntroductions

    @Query("SELECT * FROM listening_assessments WHERE sourceId = :sourceId AND closedAtMs IS NULL LIMIT 1")
    suspend fun activeAssessment(sourceId: String): ListeningAssessmentEntity?

    @Query("SELECT * FROM listening_assessments WHERE sourceId = :sourceId AND closedAtMs IS NULL LIMIT 1")
    fun observeActiveAssessment(sourceId: String): Flow<ListeningAssessmentEntity?>

    @Query("SELECT * FROM listening_assessments WHERE id = :id")
    suspend fun assessment(id: String): ListeningAssessmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAssessment(row: ListeningAssessmentEntity)

    @Query("SELECT * FROM listening_task_encounters ORDER BY createdAtMs, id")
    suspend fun allEncounters(): List<ListeningTaskEncounterEntity>

    @Query("SELECT * FROM listening_assessments ORDER BY createdAtMs, id")
    suspend fun allAssessments(): List<ListeningAssessmentEntity>

    @Query("SELECT * FROM listening_exposures ORDER BY sourceId, exposureId")
    suspend fun allExposures(): List<ListeningExposureEntity>
}

val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_tasks (sourceId TEXT NOT NULL, id TEXT NOT NULL, json TEXT, " +
            "contentHash TEXT, exposureId TEXT, taskType TEXT, title TEXT NOT NULL, usage TEXT, estimatedMs INTEGER, " +
            "ready INTEGER NOT NULL, reason TEXT, position INTEGER NOT NULL, PRIMARY KEY(sourceId, id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_task_assets " +
            "(sourceId TEXT NOT NULL, sha256 TEXT NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(sourceId, sha256))")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_asset_pins (sourceId TEXT NOT NULL, ownerKind TEXT NOT NULL, " +
            "ownerId TEXT NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(sourceId, ownerKind, ownerId, sha256))")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_task_identities " +
            "(sourceId TEXT NOT NULL, taskId TEXT NOT NULL, exposureId TEXT NOT NULL, PRIMARY KEY(sourceId, taskId))")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_exposures (sourceId TEXT NOT NULL, exposureId TEXT NOT NULL, " +
            "firstAtMs INTEGER NOT NULL, lastAtMs INTEGER NOT NULL, PRIMARY KEY(sourceId, exposureId))")
        db.execSQL("INSERT INTO listening_exposures SELECT sourceId, passageId, MIN(exposedAtMs), " +
            "MAX(MAX(COALESCE(lastExposureAtMs, exposedAtMs), COALESCE(lastStudyAtMs, exposedAtMs))) " +
            "FROM listening_encounters WHERE exposedAtMs IS NOT NULL GROUP BY sourceId, passageId")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_task_sync (sourceId TEXT NOT NULL PRIMARY KEY, acceptedBranch TEXT, " +
            "acceptedRevision TEXT, acceptedContentVersion TEXT, parserEpoch INTEGER NOT NULL, attemptedBranch TEXT NOT NULL, " +
            "attemptedRevision TEXT, problem TEXT, updatedAtMs INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_task_encounters (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, " +
            "taskId TEXT NOT NULL, exposureId TEXT NOT NULL, branch TEXT NOT NULL, revision TEXT, contentVersion TEXT NOT NULL, " +
            "contentJson TEXT NOT NULL, contentHash TEXT NOT NULL, mediaHash TEXT NOT NULL, policyVersion INTEGER NOT NULL, " +
            "vocabularyJson TEXT NOT NULL, stateJson TEXT NOT NULL, kind TEXT NOT NULL, assessmentId TEXT, createdAtMs INTEGER NOT NULL, " +
            "estimatedMs INTEGER NOT NULL, newReservation INTEGER NOT NULL, exposedAtMs INTEGER, firstEncounter INTEGER NOT NULL, " +
            "reviewLogId INTEGER, responseJson TEXT, submittedAtMs INTEGER, introduced INTEGER NOT NULL, closedAtMs INTEGER, closeReason TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_task_encounters_sourceId_taskId ON listening_task_encounters(sourceId, taskId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_task_encounters_sourceId_closedAtMs ON listening_task_encounters(sourceId, closedAtMs)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_listening_task_encounters_reviewLogId ON listening_task_encounters(reviewLogId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS listening_assessments (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, " +
            "branch TEXT NOT NULL, revision TEXT, contentVersion TEXT NOT NULL, blueprint TEXT NOT NULL, policyVersion INTEGER NOT NULL, " +
            "contentJson TEXT NOT NULL, stateJson TEXT NOT NULL, createdAtMs INTEGER NOT NULL, closedAtMs INTEGER, responseJson TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_assessments_sourceId_closedAtMs ON listening_assessments(sourceId, closedAtMs)")
    }
}
