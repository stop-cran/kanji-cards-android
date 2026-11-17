package io.github.stopcran.kanji.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordDirection
import kotlinx.coroutines.flow.Flow

object LearningItemKind {
    const val Kanji = "kanji"
    const val Word = "word"
}

@Entity(
    tableName = "learning_queue",
    primaryKeys = ["sourceId", "kind", "itemId"],
    indices = [Index(value = ["sourceId", "kind", "position"])],
)
data class LearningQueueEntity(
    val sourceId: String,
    val kind: String,
    val itemId: String,
    val position: Int,
    val addedAtMs: Long,
)

@Dao
interface LearningQueueDao {
    @Query("SELECT * FROM learning_queue WHERE sourceId = :sourceId ORDER BY kind, position, addedAtMs, itemId")
    fun observe(sourceId: String): Flow<List<LearningQueueEntity>>

    @Query("SELECT * FROM learning_queue WHERE sourceId = :sourceId AND kind = :kind ORDER BY position, addedAtMs, itemId")
    suspend fun items(sourceId: String, kind: String): List<LearningQueueEntity>

    @Query("SELECT * FROM learning_queue ORDER BY sourceId, kind, position, addedAtMs, itemId")
    suspend fun all(): List<LearningQueueEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM learning_queue WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun nextPosition(sourceId: String, kind: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: LearningQueueEntity): Long

    @Query("DELETE FROM learning_queue WHERE sourceId = :sourceId AND kind = :kind AND itemId = :itemId")
    suspend fun remove(sourceId: String, kind: String, itemId: String)

    @Query("UPDATE learning_queue SET position = :position WHERE sourceId = :sourceId AND kind = :kind AND itemId = :itemId")
    suspend fun setPosition(sourceId: String, kind: String, itemId: String, position: Int)
}

enum class QueueAddResult { Added, AlreadyQueued, AlreadyLearning, Missing }

class LearningQueueStore(private val db: AppDatabase) {
    fun observe(sourceId: String): Flow<List<LearningQueueEntity>> = db.learningQueue().observe(sourceId)

    suspend fun all(): List<LearningQueueEntity> = db.learningQueue().all()

    suspend fun addKanji(sourceId: String, selectedStack: String, kanji: String, nowMs: Long): QueueAddResult =
        db.withTransaction {
            if (db.content().kanji(sourceId).none { it.kanji == kanji }) return@withTransaction QueueAddResult.Missing
            val stateStack = Stacks.stateId(selectedStack)
            if (db.reviews().states(sourceId, stateStack, StudyMode.Quiz.name)
                    .any { it.kanji == kanji && it.phase != "New" }
            ) return@withTransaction QueueAddResult.AlreadyLearning
            add(sourceId, LearningItemKind.Kanji, kanji, nowMs)
        }

    suspend fun addWord(sourceId: String, selectedStack: String, word: String, nowMs: Long): QueueAddResult =
        db.withTransaction {
            if (db.content().word(sourceId, word) == null) return@withTransaction QueueAddResult.Missing
            if (db.reviews().states(sourceId, selectedStack, WordDirection.JpToEn.mode.name)
                    .any { it.kanji == word && it.phase != "New" }
            ) return@withTransaction QueueAddResult.AlreadyLearning
            add(sourceId, LearningItemKind.Word, word, nowMs)
        }

    private suspend fun add(sourceId: String, kind: String, itemId: String, nowMs: Long): QueueAddResult {
        val dao = db.learningQueue()
        val inserted = dao.insert(LearningQueueEntity(sourceId, kind, itemId, dao.nextPosition(sourceId, kind), nowMs))
        return if (inserted == -1L) QueueAddResult.AlreadyQueued else QueueAddResult.Added
    }

    suspend fun remove(sourceId: String, kind: String, itemId: String) {
        db.withTransaction {
            val dao = db.learningQueue()
            dao.remove(sourceId, kind, itemId)
            compact(dao, sourceId, kind)
        }
    }

    suspend fun move(sourceId: String, kind: String, itemId: String, offset: Int) {
        require(offset == -1 || offset == 1) { "Queue movement must be one position" }
        db.withTransaction {
            val dao = db.learningQueue()
            val items = dao.items(sourceId, kind)
            val from = items.indexOfFirst { it.itemId == itemId }
            val to = from + offset
            if (from < 0 || to !in items.indices) return@withTransaction
            val reordered = items.toMutableList().apply { add(to, removeAt(from)) }
            reordered.forEachIndexed { index, item -> dao.setPosition(sourceId, kind, item.itemId, index) }
        }
    }

    private suspend fun compact(dao: LearningQueueDao, sourceId: String, kind: String) {
        dao.items(sourceId, kind).forEachIndexed { index, item -> dao.setPosition(sourceId, kind, item.itemId, index) }
    }
}
