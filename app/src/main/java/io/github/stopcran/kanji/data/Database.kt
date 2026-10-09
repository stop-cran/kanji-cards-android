package io.github.stopcran.kanji.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** List-valued columns are stored joined with this separator (never occurs in front-matter values). */
const val SEP = "\u001F"

fun List<String>.joinSep(): String = joinToString(SEP)
fun String.splitSep(): List<String> = if (isEmpty()) emptyList() else split(SEP)

@Entity(tableName = "kanji", primaryKeys = ["sourceId", "kanji"])
data class KanjiEntity(
    val sourceId: String,
    val kanji: String,
    val title: String,
    val jlpt: Int?,
    val tags: String,
    val strokeCount: Int?,
    val radical: String?,
    val phonetic: String?,
    val onyomi: String,
    val kunyomi: String,
    val distractors: String,
    val body: String,
    val strokesJson: String?,
    val position: Int,
)

@Entity(tableName = "words", primaryKeys = ["sourceId", "word"])
data class WordEntity(
    val sourceId: String,
    val word: String,
    val reading: String,
    val title: String,
    val type: String?,
    val kanji: String,
    val tags: String,
    val body: String,
)

@Entity(tableName = "articles", primaryKeys = ["sourceId", "slug"])
data class ArticleEntity(val sourceId: String, val slug: String, val title: String, val body: String)

/** Scheduling state per (content source, stack, kanji, mode); survives content updates and card edits. */
@Entity(tableName = "review_state", primaryKeys = ["sourceId", "stack", "kanji", "mode"])
data class ReviewStateEntity(
    val sourceId: String,
    val stack: String,
    val kanji: String,
    val mode: String,
    val phase: String,
    val stability: Double,
    val difficulty: Double,
    val dueMs: Long,
    val lastReviewMs: Long?,
    val reps: Int,
    val lapses: Int,
)

@Entity(tableName = "review_log")
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val stack: String,
    val kanji: String,
    val mode: String,
    val grade: Int,
    val atMs: Long,
)

@Entity(tableName = "sync_meta", primaryKeys = ["sourceId"])
data class SyncMetaEntity(val sourceId: String, val contentVersion: String, val syncedAtMs: Long, val problems: Int)

@Dao
interface ContentDao {
    @Query("SELECT * FROM kanji WHERE sourceId = :sourceId ORDER BY position")
    fun observeKanji(sourceId: String): Flow<List<KanjiEntity>>

    @Query("SELECT * FROM kanji WHERE sourceId = :sourceId ORDER BY position")
    suspend fun kanji(sourceId: String): List<KanjiEntity>

    @Query("SELECT * FROM kanji WHERE sourceId = :sourceId AND kanji = :kanji")
    suspend fun kanjiCard(sourceId: String, kanji: String): KanjiEntity?

    @Query("SELECT * FROM words WHERE sourceId = :sourceId ORDER BY word")
    suspend fun words(sourceId: String): List<WordEntity>

    @Query("SELECT * FROM words WHERE sourceId = :sourceId ORDER BY word")
    fun observeWords(sourceId: String): Flow<List<WordEntity>>

    @Query("SELECT * FROM words WHERE sourceId = :sourceId AND word = :word")
    suspend fun word(sourceId: String, word: String): WordEntity?

    @Query("SELECT * FROM articles WHERE sourceId = :sourceId AND slug = :slug")
    suspend fun article(sourceId: String, slug: String): ArticleEntity?

    @Query("SELECT COUNT(*) FROM words WHERE sourceId = :sourceId")
    fun observeWordCount(sourceId: String): Flow<Int>

    @Query("SELECT * FROM sync_meta WHERE sourceId = :sourceId")
    fun observeMeta(sourceId: String): Flow<SyncMetaEntity?>

    @Query("SELECT * FROM sync_meta WHERE sourceId = :sourceId")
    suspend fun meta(sourceId: String): SyncMetaEntity?

    @Query("DELETE FROM kanji WHERE sourceId = :sourceId")
    suspend fun clearKanji(sourceId: String)

    @Query("DELETE FROM words WHERE sourceId = :sourceId")
    suspend fun clearWords(sourceId: String)

    @Query("DELETE FROM articles WHERE sourceId = :sourceId")
    suspend fun clearArticles(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertKanji(items: List<KanjiEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWords(items: List<WordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArticles(items: List<ArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: SyncMetaEntity)

    /** Atomic swap: readers never see a half-imported snapshot, and a failure leaves the last-known-good content. */
    @Transaction
    suspend fun replaceContent(
        sourceId: String,
        kanji: List<KanjiEntity>,
        words: List<WordEntity>,
        articles: List<ArticleEntity>,
        meta: SyncMetaEntity,
    ) {
        clearKanji(sourceId)
        clearWords(sourceId)
        clearArticles(sourceId)
        insertKanji(kanji)
        insertWords(words)
        insertArticles(articles)
        putMeta(meta)
    }
}

@Dao
interface ReviewDao {
    @Query("SELECT * FROM review_state WHERE sourceId = :sourceId AND stack = :stack AND mode = :mode")
    suspend fun states(sourceId: String, stack: String, mode: String): List<ReviewStateEntity>

    @Query("SELECT * FROM review_state WHERE sourceId = :sourceId AND stack = :stack AND mode = :mode")
    fun observeStates(sourceId: String, stack: String, mode: String): Flow<List<ReviewStateEntity>>

    @Query("SELECT MAX(atMs) FROM review_log")
    suspend fun lastReviewMs(): Long?

    @Query("SELECT COUNT(*) FROM review_log WHERE atMs >= :sinceMs")
    suspend fun reviewsSince(sinceMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: ReviewStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLog(log: ReviewLogEntity)

    @Query("SELECT COUNT(*) FROM (SELECT kanji FROM review_log WHERE sourceId = :sourceId AND stack = :stack AND mode = :mode GROUP BY kanji HAVING MIN(atMs) >= :sinceMs)")
    suspend fun newCardsIntroducedSince(sourceId: String, stack: String, mode: String, sinceMs: Long): Int

    @Transaction
    suspend fun record(state: ReviewStateEntity, log: ReviewLogEntity) {
        putState(state)
        putLog(log)
    }
}

@Database(
    entities = [KanjiEntity::class, WordEntity::class, ArticleEntity::class, ReviewStateEntity::class, ReviewLogEntity::class, SyncMetaEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun content(): ContentDao
    abstract fun reviews(): ReviewDao
}

/** v1 had a single stack; its state becomes the "all" stack. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE review_state_new (sourceId TEXT NOT NULL, stack TEXT NOT NULL, kanji TEXT NOT NULL, mode TEXT NOT NULL, " +
                "phase TEXT NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL, dueMs INTEGER NOT NULL, lastReviewMs INTEGER, " +
                "reps INTEGER NOT NULL, lapses INTEGER NOT NULL, PRIMARY KEY(sourceId, stack, kanji, mode))",
        )
        db.execSQL(
            "INSERT INTO review_state_new SELECT sourceId, 'all', kanji, mode, phase, stability, difficulty, dueMs, lastReviewMs, reps, lapses FROM review_state",
        )
        db.execSQL("DROP TABLE review_state")
        db.execSQL("ALTER TABLE review_state_new RENAME TO review_state")
        db.execSQL("ALTER TABLE review_log ADD COLUMN stack TEXT NOT NULL DEFAULT 'all'")
    }
}
