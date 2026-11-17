package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordDirection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LearningQueueStoreTest {
    private val source = "owner/content"
    private val otherSource = "other/content"
    private lateinit var db: AppDatabase
    private lateinit var queue: LearningQueueStore

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        queue = LearningQueueStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun entriesAreOrderedPerSourceAndSurviveContentReplacement() = runBlocking {
        db.content().insertKanji(listOf(kanji(source, "日"), kanji(otherSource, "日")))
        assertEquals(QueueAddResult.Added, queue.addKanji(source, "jlpt-n5", "日", 1))
        assertEquals(QueueAddResult.AlreadyQueued, queue.addKanji(source, "jlpt-n5", "日", 2))
        assertEquals(QueueAddResult.Added, queue.addKanji(otherSource, "jlpt-n5", "日", 3))

        db.content().replaceContent(
            source,
            ContentRows(kanji = emptyList(), words = emptyList(), articles = emptyList()),
            SyncMetaEntity(source, "next-content", 4, 0),
        )

        assertEquals(listOf("日"), db.learningQueue().items(source, LearningItemKind.Kanji).map { it.itemId })
        assertEquals(listOf("日"), db.learningQueue().items(otherSource, LearningItemKind.Kanji).map { it.itemId })
        assertTrue(db.content().kanji(source).isEmpty())

        val output = ByteArrayOutputStream()
        ProgressExport.write(db, Settings(RuntimeEnvironment.getApplication()), output)
        val files = ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                }
            }
        }
        assertEquals(
            "8",
            Json.parseToJsonElement(files.getValue("export_manifest.json"))
                .jsonObject.getValue("formatVersion").jsonPrimitive.content,
        )
        val exportedQueue = Json.parseToJsonElement(files.getValue("learning_queue.json")).jsonArray
        assertEquals(setOf(source, otherSource), exportedQueue.map { it.jsonObject.getValue("sourceId").jsonPrimitive.content }.toSet())
    }

    @Test
    fun addingMissingOrAlreadyLearningItemsIsRejected() = runBlocking {
        db.content().insertKanji(listOf(kanji(source, "日")))
        db.content().insertWords(listOf(word(source, "日本")))
        assertEquals(QueueAddResult.Missing, queue.addKanji(source, "jlpt-n5", "月", 1))
        assertEquals(QueueAddResult.Missing, queue.addWord(source, "jlpt-n5", "月日", 1))

        db.reviews().putState(state(source, Stacks.JLPT_STATE, "日", StudyMode.Quiz.name, "Review"))
        db.reviews().putState(state(source, "jlpt-n5", "日本", WordDirection.JpToEn.mode.name, "Review"))

        assertEquals(QueueAddResult.AlreadyLearning, queue.addKanji(source, "jlpt-n4", "日", 2))
        assertEquals(QueueAddResult.AlreadyLearning, queue.addWord(source, "jlpt-n5", "日本", 2))
    }

    @Test
    fun reorderingIsScopedAndFoundationalAnswersRemoveOnlyTheirQueuedItem() = runBlocking {
        db.content().insertKanji(listOf(kanji(source, "日"), kanji(source, "本"), kanji(otherSource, "日")))
        db.content().insertWords(listOf(word(source, "日本")))
        queue.addKanji(source, "jlpt-n5", "日", 1)
        queue.addKanji(source, "jlpt-n5", "本", 2)
        queue.addKanji(otherSource, "jlpt-n5", "日", 3)
        queue.addWord(source, "jlpt-n5", "日本", 4)

        queue.move(source, LearningItemKind.Kanji, "本", -1)
        assertEquals(
            listOf("本", "日"),
            db.learningQueue().items(source, LearningItemKind.Kanji).map { it.itemId },
        )

        db.reviews().record(
            state(source, Stacks.JLPT_STATE, "日", StudyMode.Draw.name, "Review"),
            log(source, Stacks.JLPT_STATE, "日", StudyMode.Draw.name),
        )
        assertEquals(listOf("本", "日"), db.learningQueue().items(source, LearningItemKind.Kanji).map { it.itemId })

        db.reviews().record(
            state(source, Stacks.JLPT_STATE, "日", StudyMode.Quiz.name, "Review"),
            log(source, Stacks.JLPT_STATE, "日", StudyMode.Quiz.name),
        )
        db.reviews().record(
            state(source, "jlpt-n5", "日本", WordDirection.JpToEn.mode.name, "Review"),
            log(source, "jlpt-n5", "日本", WordDirection.JpToEn.mode.name),
        )

        assertEquals(listOf("本"), db.learningQueue().items(source, LearningItemKind.Kanji).map { it.itemId })
        assertTrue(db.learningQueue().items(source, LearningItemKind.Word).isEmpty())
        assertEquals(listOf("日"), db.learningQueue().items(otherSource, LearningItemKind.Kanji).map { it.itemId })
    }

    private fun kanji(sourceId: String, id: String) =
        KanjiEntity(sourceId, id, id, 5, "jlpt-n5", 1, null, null, "", "", "", "", null, 0)

    private fun word(sourceId: String, id: String) =
        WordEntity(sourceId, id, id, id, null, id, "", "")

    private fun state(sourceId: String, stack: String, id: String, mode: String, phase: String) =
        ReviewStateEntity(sourceId, stack, id, mode, phase, 1.0, 5.0, 100, null, 1, 0)

    private fun log(sourceId: String, stack: String, id: String, mode: String) =
        ReviewLogEntity(sourceId = sourceId, stack = stack, kanji = id, mode = mode, grade = 3, atMs = 100)
}
