package io.github.stopcran.kanji.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reading_texts", primaryKeys = ["sourceId", "id"])
data class ReadingEntity(
    val sourceId: String,
    val id: String,
    val json: String,
    val contentHash: String,
    val title: String,
    val jlpt: Int,
    val position: Int,
)

@Entity(tableName = "reading_images", primaryKeys = ["sourceId", "path"])
class ReadingImageEntity(val sourceId: String, val path: String, val sha256: String, val bytes: ByteArray)

@Entity(tableName = "reading_readers", primaryKeys = ["sourceId", "textId", "captureId"])
data class ReadingReaderEntity(
    val sourceId: String,
    val textId: String,
    val branch: String,
    val revision: String?,
    val contentVersion: String,
    val contentJson: String,
    val contentHash: String,
    val wordsJson: String,
    val openedAtMs: Long,
    val sectionIndex: Int = 0,
    val scrollOffset: Int = 0,
    val furigana: Boolean = true,
    val completedAtMs: Long? = null,
    val captureId: String = contentHash,
)

@Entity(tableName = "reading_reader_images", primaryKeys = ["sourceId", "textId", "captureId", "path"])
class ReadingReaderImageEntity(
    val sourceId: String, val textId: String, val captureId: String, val path: String, val sha256: String, val bytes: ByteArray,
)

@Entity(tableName = "reading_reader_words", primaryKeys = ["sourceId", "textId", "captureId", "word"])
data class ReadingReaderWordEntity(
    val sourceId: String, val textId: String, val captureId: String,
    val word: String, val reading: String, val title: String, val body: String, val position: Int,
)

@Entity(tableName = "reading_word_intents", primaryKeys = ["sourceId", "word"])
data class ReadingWordIntentEntity(val sourceId: String, val word: String, val token: String, val requestedAtMs: Long)

@Entity(tableName = "reading_progress", primaryKeys = ["sourceId", "textId"])
data class ReadingProgressEntity(val sourceId: String, val textId: String, val unlockedTier: Int)

@Entity(tableName = "reading_quizzes")
data class ReadingQuizEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val textId: String,
    val captureId: String,
    val planJson: String,
    val answersJson: String,
    val startedAtMs: Long,
    val newReservation: Boolean = false,
    val submittedAtMs: Long? = null,
    val resultJson: String? = null,
    val reviewLogId: Long? = null,
    val closedAtMs: Long? = null,
)

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_progress WHERE sourceId = :sourceId AND textId = :textId")
    suspend fun progress(sourceId: String, textId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProgress(row: ReadingProgressEntity)

    @Query("SELECT * FROM reading_quizzes WHERE id = :id")
    suspend fun quiz(id: String): ReadingQuizEntity?

    @Query(
        "SELECT * FROM reading_quizzes WHERE sourceId = :sourceId AND textId = :textId " +
            "AND submittedAtMs IS NULL AND closedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1",
    )
    suspend fun openQuiz(sourceId: String, textId: String): ReadingQuizEntity?

    @Query(
        "SELECT * FROM reading_quizzes WHERE sourceId = :sourceId AND textId = :textId " +
            "AND submittedAtMs IS NULL AND closedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1",
    )
    fun observeOpenQuiz(sourceId: String, textId: String): Flow<ReadingQuizEntity?>

    @Query("SELECT COUNT(*) FROM reading_quizzes WHERE sourceId = :sourceId AND newReservation = 1")
    suspend fun reservedNew(sourceId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM review_log WHERE sourceId = :sourceId AND stack = 'reading-texts' AND kanji = :textId)")
    suspend fun introduced(sourceId: String, textId: String): Boolean

    @Query("SELECT * FROM reading_quizzes ORDER BY startedAtMs, id")
    suspend fun allQuizzes(): List<ReadingQuizEntity>

    @Query("SELECT * FROM reading_progress ORDER BY sourceId, textId")
    suspend fun allProgress(): List<ReadingProgressEntity>

    @Insert
    suspend fun insertQuiz(row: ReadingQuizEntity)

    @Query(
        "UPDATE reading_quizzes SET answersJson = :answers WHERE id = :id AND answersJson = :previous " +
            "AND submittedAtMs IS NULL AND closedAtMs IS NULL",
    )
    suspend fun saveQuizAnswers(id: String, previous: String, answers: String): Int

    @Query(
        "UPDATE reading_quizzes SET submittedAtMs = :atMs, resultJson = :result, reviewLogId = :logId, newReservation = 0 " +
            "WHERE id = :id AND submittedAtMs IS NULL AND closedAtMs IS NULL",
    )
    suspend fun submitQuiz(id: String, atMs: Long, result: String, logId: Long): Int

    @Query("UPDATE reading_quizzes SET closedAtMs = :atMs, newReservation = 0 WHERE id = :id AND closedAtMs IS NULL")
    suspend fun closeQuiz(id: String, atMs: Long): Int

    @Query("SELECT * FROM reading_word_intents WHERE sourceId = :sourceId ORDER BY requestedAtMs, word")
    fun observeWordIntents(sourceId: String): Flow<List<ReadingWordIntentEntity>>

    @Query("SELECT * FROM reading_word_intents WHERE sourceId = :sourceId ORDER BY requestedAtMs, word")
    suspend fun wordIntents(sourceId: String): List<ReadingWordIntentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putWordIntent(row: ReadingWordIntentEntity)

    @Query("DELETE FROM reading_word_intents WHERE sourceId = :sourceId AND word = :word")
    suspend fun removeWordIntent(sourceId: String, word: String)

    @Query("DELETE FROM reading_word_intents WHERE sourceId = :sourceId AND word = :word AND token = :token")
    suspend fun consumeWordIntent(sourceId: String, word: String, token: String)

    @Query("SELECT * FROM reading_texts WHERE sourceId = :sourceId ORDER BY position")
    fun observeTexts(sourceId: String): Flow<List<ReadingEntity>>

    @Query("SELECT * FROM reading_texts WHERE sourceId = :sourceId ORDER BY position")
    suspend fun texts(sourceId: String): List<ReadingEntity>

    @Query("SELECT * FROM reading_texts WHERE sourceId = :sourceId AND id = :id")
    suspend fun text(sourceId: String, id: String): ReadingEntity?

    @Query("SELECT * FROM reading_images WHERE sourceId = :sourceId AND path = :path")
    suspend fun image(sourceId: String, path: String): ReadingImageEntity?

    @Query(
        "SELECT * FROM reading_readers WHERE sourceId = :sourceId AND textId = :textId " +
            "AND (:captureId IS NULL OR captureId = :captureId) ORDER BY openedAtMs DESC LIMIT 1",
    )
    suspend fun reader(sourceId: String, textId: String, captureId: String? = null): ReadingReaderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReader(row: ReadingReaderEntity)

    @Insert
    suspend fun putReaderWords(rows: List<ReadingReaderWordEntity>)

    @Query("SELECT * FROM reading_reader_words WHERE sourceId = :sourceId AND textId = :textId AND captureId = :captureId ORDER BY position")
    suspend fun readerWords(sourceId: String, textId: String, captureId: String): List<ReadingReaderWordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putReaderImages(rows: List<ReadingReaderImageEntity>)

    @Query("SELECT * FROM reading_reader_images WHERE sourceId = :sourceId AND textId = :textId AND captureId = :captureId AND path = :path")
    suspend fun readerImage(sourceId: String, textId: String, captureId: String, path: String): ReadingReaderImageEntity?

    @Query(
        "UPDATE reading_readers SET sectionIndex = :sectionIndex, scrollOffset = :offset " +
            "WHERE sourceId = :sourceId AND textId = :textId AND captureId = :hash",
    )
    suspend fun savePosition(sourceId: String, textId: String, hash: String, sectionIndex: Int, offset: Int): Int

    @Query("UPDATE reading_readers SET furigana = :visible WHERE sourceId = :sourceId AND textId = :textId AND captureId = :hash")
    suspend fun saveFurigana(sourceId: String, textId: String, hash: String, visible: Boolean): Int

    @Query(
        "UPDATE reading_readers SET completedAtMs = COALESCE(completedAtMs, :atMs) " +
            "WHERE sourceId = :sourceId AND textId = :textId AND captureId = :hash",
    )
    suspend fun completeReading(sourceId: String, textId: String, hash: String, atMs: Long): Int
}
