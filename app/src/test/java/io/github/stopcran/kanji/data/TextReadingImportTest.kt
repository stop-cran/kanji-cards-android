package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TextReadingImportTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test fun version22CaptureAndMediaRetainTheirAdmissionIdentityAfterUpgrade() = runBlocking {
        val name = "text-reading-captures-upgrade.db"
        val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
        val hash = ContentParser.sha256(raw.toByteArray())
        createMigrationSchema(context, name, 22) { sqlite ->
            sqlite.execSQL(
                "INSERT INTO reading_readers VALUES ('owner/repo', 'reading-fixture-peach', 'main', NULL, 'v1', ?, ?, '[]', 1, 2, 31, 0, 9)",
                arrayOf(raw, hash),
            )
            sqlite.execSQL(
                "INSERT INTO reading_reader_images VALUES ('owner/repo', 'reading-fixture-peach', 'reading-images/test.webp', ?, ?)",
                arrayOf(hash, byteArrayOf(1, 2, 3)),
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_22_25, MIGRATION_25_26).allowMainThreadQueries().build()
        try {
            val saved = requireNotNull(db.reading().reader("owner/repo", "reading-fixture-peach", hash))
            assertEquals(raw, saved.contentJson)
            assertEquals(31, saved.scrollOffset)
            assertFalse(saved.furigana)
            assertEquals(9L, saved.completedAtMs)
            assertArrayEquals(byteArrayOf(1, 2, 3),
                db.reading().readerImage("owner/repo", saved.textId, hash, "reading-images/test.webp")?.bytes)
            assertTrue(db.reading().wordIntents("owner/repo").isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun replacementIsSourceScopedAndMigrationPreservesStudyHistory() = runBlocking {
        val name = "text-reading-upgrade.db"
        createMigrationSchema(context, name, 21) { sqlite ->
            sqlite.execSQL(
                "INSERT INTO review_log (sourceId, stack, kanji, mode, grade, atMs, reading) " +
                    "VALUES ('owner/repo', 'words-n5', '山', 'WordJpEn', 3, 10, '')",
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_21_25, MIGRATION_25_26).allowMainThreadQueries().build()
        try {
            val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
            val hash = ContentParser.sha256(raw.toByteArray())
            val first = ReadingEntity("owner/repo", "reading-fixture-peach", raw, hash, "A small peach", 5, 0)
            val other = first.copy(sourceId = "other/repo")
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), emptyList(), emptyList(), reading = listOf(first)),
                SyncMetaEntity("owner/repo", "v1", 1, 0, readingParserEpoch = 1))
            db.content().replaceContent("other/repo", ContentRows(emptyList(), emptyList(), emptyList(), reading = listOf(other)),
                SyncMetaEntity("other/repo", "v1", 1, 0, readingParserEpoch = 1))
            assertEquals(1, db.reading().texts("owner/repo").size)
            assertEquals(1, db.content().meta("owner/repo")?.readingParserEpoch)
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), emptyList(), emptyList()),
                SyncMetaEntity("owner/repo", "v2", 2, 1, readingParserEpoch = 1, readingProblem = "reading/broken.json: invalid"))
            assertTrue(db.reading().texts("owner/repo").isEmpty())
            assertEquals(1, db.reading().texts("other/repo").size)
            assertNotNull(db.content().meta("owner/repo")?.readingProblem)
            assertEquals("山", db.reviews().allLog().single().kanji)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }

    }

    @Test fun oversizedVersion23VocabularyMigratesWithoutReadingAnOversizedRow() = runBlocking {
        val name = "text-reading-large-words-upgrade.db"
        val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
        val hash = ContentParser.sha256(raw.toByteArray())
        val words = (0 until 1_100).map { ReadingWordSnapshot("桃$it", "もも", "peach", "本文".repeat(1_024)) }
        val wordsJson = Json.encodeToString(ListSerializer(ReadingWordSnapshot.serializer()), words)
        assertTrue(wordsJson.toByteArray().size > 2 * 1024 * 1024)
        createMigrationSchema(context, name, 23) { sqlite ->
            sqlite.execSQL(
                "INSERT INTO reading_readers VALUES ('owner/repo', 'reading-fixture-peach', 'main', NULL, 'v1', ?, ?, ?, 1, 2, 31, 0, 9, ?)",
                arrayOf(raw, hash, wordsJson, hash),
            )
            sqlite.execSQL(
                "INSERT INTO reading_reader_images VALUES ('owner/repo', 'reading-fixture-peach', ?, 'reading-images/test.webp', ?, ?)",
                arrayOf(hash, hash, byteArrayOf(1, 2, 3)),
            )
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_23_26).allowMainThreadQueries().build()
        try {
            val saved = requireNotNull(db.reading().reader("owner/repo", "reading-fixture-peach", hash))
            assertEquals("[]", saved.wordsJson)
            assertEquals(31, saved.scrollOffset)
            assertFalse(saved.furigana)
            assertEquals(9L, saved.completedAtMs)
            val restored = db.reading().readerWords(saved.sourceId, saved.textId, hash)
            assertEquals(words, restored.map { ReadingWordSnapshot(it.word, it.reading, it.title, it.body) })
            assertArrayEquals(byteArrayOf(1, 2, 3),
                db.reading().readerImage(saved.sourceId, saved.textId, hash, "reading-images/test.webp")?.bytes)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
