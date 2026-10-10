package io.github.stopcran.kanji.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import io.github.stopcran.kanji.core.reading.ReadingCard
import io.github.stopcran.kanji.core.reading.ReadingKind
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
    val jlpt: Int? = null,
    @ColumnInfo(defaultValue = "''") val quizExclusions: String = "",
    @ColumnInfo(defaultValue = "''") val quizDistractors: String = "",
)

@Entity(tableName = "articles", primaryKeys = ["sourceId", "slug"])
data class ArticleEntity(val sourceId: String, val slug: String, val title: String, val body: String)

/** Scheduling state per (content source, stack, kanji, mode); survives content updates and card edits. */
@Entity(tableName = "review_state", primaryKeys = ["sourceId", "stack", "kanji", "mode", "reading"])
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
    /** Match key of the reading for the kanji readings modes; '' for every other mode. */
    @ColumnInfo(defaultValue = "''") val reading: String = "",
)

@Entity(
    tableName = "review_log",
    indices = [Index(value = ["sourceId", "stack", "kanji", "mode"]), Index(value = ["atMs"])],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val stack: String,
    val kanji: String,
    val mode: String,
    val grade: Int,
    val atMs: Long,
    @ColumnInfo(defaultValue = "''") val reading: String = "",
)

@Entity(tableName = "sync_meta", primaryKeys = ["sourceId"])
data class SyncMetaEntity(val sourceId: String, val contentVersion: String, val syncedAtMs: Long, val problems: Int)

@Dao
interface ContentDao {
    @Query("SELECT * FROM kanji WHERE sourceId = :sourceId ORDER BY position")
    fun observeKanji(sourceId: String): Flow<List<KanjiEntity>>

    /** Home-screen variant: no article body; strokesJson is only '' (present) or NULL (absent). */
    @Query(
        "SELECT sourceId, kanji, title, jlpt, tags, strokeCount, radical, phonetic, onyomi, kunyomi, distractors, " +
            "'' AS body, CASE WHEN strokesJson IS NULL THEN NULL ELSE '' END AS strokesJson, position " +
            "FROM kanji WHERE sourceId = :sourceId ORDER BY position"
    )
    fun observeKanjiLite(sourceId: String): Flow<List<KanjiEntity>>

    @Query(
        "SELECT sourceId, word, reading, title, type, kanji, tags, '' AS body, jlpt, quizExclusions, quizDistractors " +
            "FROM words WHERE sourceId = :sourceId ORDER BY word"
    )
    fun observeWordsLite(sourceId: String): Flow<List<WordEntity>>

    /** Same as [kanji] without the article body or stroke data (used by background work). */
    @Query(
        "SELECT sourceId, kanji, title, jlpt, tags, strokeCount, radical, phonetic, onyomi, kunyomi, distractors, " +
            "'' AS body, CASE WHEN strokesJson IS NULL THEN NULL ELSE '' END AS strokesJson, position " +
            "FROM kanji WHERE sourceId = :sourceId ORDER BY position",
    )
    suspend fun kanjiLite(sourceId: String): List<KanjiEntity>

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

    @Query("SELECT COUNT(*) FROM words WHERE sourceId = :sourceId")
    suspend fun wordCount(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM kanji WHERE sourceId = :sourceId")
    suspend fun kanjiCount(sourceId: String): Int

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

    /** Distinct items (kanji or words, across every mode) whose first-ever review happened since [sinceMs]: the shared daily new-item pool. */
    @Query("SELECT COUNT(*) FROM (SELECT 1 FROM review_log WHERE sourceId = :sourceId GROUP BY stack, kanji, reading, CASE WHEN reading = '' THEN '' ELSE mode END HAVING MIN(atMs) >= :sinceMs)")
    suspend fun itemsIntroducedSince(sourceId: String, sinceMs: Long): Int

    /** Cards that were reviewed before and are due at [nowMs], in any mode. */
    @Query("SELECT COUNT(*) FROM review_state WHERE sourceId = :sourceId AND phase != 'New' AND dueMs <= :nowMs")
    suspend fun dueCount(sourceId: String, nowMs: Long): Int

    @Query("SELECT atMs FROM review_log WHERE sourceId = :sourceId AND atMs >= :sinceMs")
    suspend fun reviewTimesSince(sourceId: String, sinceMs: Long): List<Long>

    /** Reading cards (kanji + reading) whose first review happened since [sinceMs]; each used a unit of the daily budget. */
    @Query("SELECT COUNT(*) FROM (SELECT 1 FROM review_log WHERE sourceId = :sourceId AND stack = :stack AND mode IN ('KanjiOn', 'KanjiKun') AND reading != '' GROUP BY mode, kanji, reading HAVING MIN(atMs) >= :sinceMs)")
    suspend fun readingCardsIntroducedSince(sourceId: String, stack: String, sinceMs: Long): Int

    /** Every answer of one source and stack in any mode, oldest first: the input of [io.github.stopcran.kanji.core.unlock.MeaningUnlock]. */
    @Query("SELECT id, atMs, kanji, mode, grade FROM review_log WHERE sourceId = :sourceId AND stack = :stack ORDER BY atMs, id")
    suspend fun unlockLog(sourceId: String, stack: String): List<io.github.stopcran.kanji.core.unlock.LogEvent>

    /** Kanji whose first meaning answer (any grade) was logged at or after [sinceMs]: the ones a focus batch counts. */
    @Query("SELECT kanji FROM review_log WHERE sourceId = :sourceId AND stack = :stack AND mode = 'Quiz' GROUP BY kanji HAVING MIN(atMs) >= :sinceMs")
    suspend fun meaningIntroducedSince(sourceId: String, stack: String, sinceMs: Long): List<String>

    @Transaction
    suspend fun record(state: ReviewStateEntity, log: ReviewLogEntity) {
        putState(state)
        putLog(log)
    }
}

@Database(
    entities = [KanjiEntity::class, WordEntity::class, ArticleEntity::class, ReviewStateEntity::class, ReviewLogEntity::class, SyncMetaEntity::class],
    version = 5,
    exportSchema = true,
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

/** Keep content and review state; reimport even an unchanged snapshot to pick up previously ignored word metadata. */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE words ADD COLUMN jlpt INTEGER")
        db.execSQL("ALTER TABLE words ADD COLUMN quizExclusions TEXT NOT NULL DEFAULT ''")
        db.execSQL("DELETE FROM sync_meta")
    }
}

/** Curated word distractors: keep review state, reimport an unchanged snapshot so the new field is read. */
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE words ADD COLUMN quizDistractors TEXT NOT NULL DEFAULT ''")
        db.execSQL("DELETE FROM sync_meta")
    }
}

/**
 * Kanji readings are scheduled per reading. The state of each (kanji, kind) is copied to every reading card of that kanji and kind;
 * the old per-kind rows go away. Orphaned states (kanji no longer in the corpus) and states of a kanji with no readings of that kind
 * are retired, because there is no reading card to carry them. Also adds the review_log indexes.
 */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE review_state_new (sourceId TEXT NOT NULL, stack TEXT NOT NULL, kanji TEXT NOT NULL, mode TEXT NOT NULL, " +
                "phase TEXT NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL, dueMs INTEGER NOT NULL, lastReviewMs INTEGER, " +
                "reps INTEGER NOT NULL, lapses INTEGER NOT NULL, reading TEXT NOT NULL DEFAULT '', PRIMARY KEY(sourceId, stack, kanji, mode, reading))",
        )
        db.execSQL(
            "INSERT INTO review_state_new SELECT sourceId, stack, kanji, mode, phase, stability, difficulty, dueMs, lastReviewMs, reps, lapses, '' " +
                "FROM review_state WHERE mode NOT IN ('KanjiOn', 'KanjiKun')",
        )
        val legacy = ArrayList<Array<Any?>>()
        db.query(
            "SELECT s.sourceId, s.stack, s.kanji, s.mode, s.phase, s.stability, s.difficulty, s.dueMs, s.lastReviewMs, s.reps, s.lapses, k.onyomi, k.kunyomi " +
                "FROM review_state s JOIN kanji k ON k.sourceId = s.sourceId AND k.kanji = s.kanji WHERE s.mode IN ('KanjiOn', 'KanjiKun')",
        ).use { c ->
            while (c.moveToNext()) {
                val kind = if (c.getString(3) == "KanjiOn") ReadingKind.On else ReadingKind.Kun
                val card = ReadingCard(c.getString(2), c.getString(11).splitSep(), c.getString(12).splitSep())
                for (key in card.quizKeys(kind)) {
                    legacy += arrayOf(
                        c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getDouble(5), c.getDouble(6),
                        c.getLong(7), if (c.isNull(8)) null else c.getLong(8), c.getInt(9), c.getInt(10), key,
                    )
                }
            }
        }
        legacy.forEach {
            db.execSQL("INSERT OR REPLACE INTO review_state_new VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", it)
        }
        db.execSQL("DROP TABLE review_state")
        db.execSQL("ALTER TABLE review_state_new RENAME TO review_state")
        db.execSQL("ALTER TABLE review_log ADD COLUMN reading TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_review_log_sourceId_stack_kanji_mode ON review_log (sourceId, stack, kanji, mode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_review_log_atMs ON review_log (atMs)")
    }
}
