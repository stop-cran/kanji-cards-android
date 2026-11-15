package io.github.stopcran.kanji.data

import android.app.Application
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReadingSchemaMigrationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val name = "reading-schema-migration.db"
    private val source = "owner/repo"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .openHelperFactory(PreservingSQLiteOpenHelperFactory())
        .addMigrations(MIGRATION_21_25, MIGRATION_22_25)
        .allowMainThreadQueries()
        .build()

    private fun fixtureContext() = object : ContextWrapper(context) {
        override fun getDatabasePath(name: String) = context.getDatabasePath(this@ReadingSchemaMigrationTest.name)
    }

    private fun databaseHash(): List<Byte> =
        MessageDigest.getInstance("SHA-256").digest(context.getDatabasePath(name).readBytes()).toList()

    private fun seedProgress(sqlite: android.database.sqlite.SQLiteDatabase) {
        sqlite.execSQL(
            "INSERT INTO review_state (sourceId, stack, kanji, mode, phase, stability, difficulty, dueMs, lastReviewMs, reps, lapses, reading) " +
                "VALUES (?, 'all', '日', 'KanjiOn', 'Review', 12.5, 5, 999, 500, 3, 1, 'にち')",
            arrayOf(source),
        )
        sqlite.execSQL(
            "INSERT INTO review_log (sourceId, stack, kanji, mode, grade, atMs, reading) " +
                "VALUES (?, 'all', '日', 'KanjiOn', 3, 500, 'にち')",
            arrayOf(source),
        )
        sqlite.execSQL("INSERT INTO sync_meta (sourceId, contentVersion, syncedAtMs, problems) VALUES (?, 'v1', 42, 0)", arrayOf(source))
    }

    private fun seedReading(sqlite: android.database.sqlite.SQLiteDatabase) {
        sqlite.execSQL("UPDATE sync_meta SET readingParserEpoch = 8, readingProblem = 'retained' WHERE sourceId = ?", arrayOf(source))
        sqlite.execSQL(
            "INSERT INTO reading_texts VALUES (?, 'article', 'text', 'hash', 'title', 5, 2)",
            arrayOf(source),
        )
        sqlite.execSQL("INSERT INTO reading_images VALUES (?, 'image', 'imagehash', X'010203')", arrayOf(source))
        sqlite.execSQL(
            "INSERT INTO reading_readers VALUES (?, 'article', 'branch', 'revision', 'v1', 'content', 'hash', 'words', 123, 4, 25, 1, 456)",
            arrayOf(source),
        )
        sqlite.execSQL("INSERT INTO reading_reader_images VALUES (?, 'article', 'image', 'imagehash', X'040506')", arrayOf(source))
    }

    private fun assertReadingPreserved(db: SupportSQLiteDatabase) {
        db.query("SELECT json, contentHash FROM reading_texts").use {
            assertTrue(it.moveToFirst())
            assertEquals("text", it.getString(0))
            assertEquals("hash", it.getString(1))
        }
        db.query("SELECT bytes FROM reading_images").use {
            assertTrue(it.moveToFirst())
            assertArrayEquals(byteArrayOf(1, 2, 3), it.getBlob(0))
        }
        db.query("SELECT sectionIndex, scrollOffset, completedAtMs, wordsJson FROM reading_readers").use {
            assertTrue(it.moveToFirst())
            assertEquals(4, it.getInt(0))
            assertEquals(25, it.getInt(1))
            assertEquals(456L, it.getLong(2))
            assertEquals("words", it.getString(3))
        }
        db.query("SELECT bytes FROM reading_reader_images").use {
            assertTrue(it.moveToFirst())
            assertArrayEquals(byteArrayOf(4, 5, 6), it.getBlob(0))
        }
    }

    @Test
    fun historicalVersion22PreservesProgressAndReadingThroughSyncAndReopen() = runBlocking {
        createMigrationSchema(context, name, 22) { sqlite ->
            seedProgress(sqlite)
            seedReading(sqlite)
        }
        val beforeGate = databaseHash()
        checkDatabaseCompatibility(fixtureContext())
        assertEquals(beforeGate, databaseHash())
        val db = openDatabase()
        try {
            assertReadingPreserved(db.openHelper.writableDatabase)
            assertEquals(3, db.reviews().states(source, "all", "KanjiOn").single().reps)
            assertEquals(1, db.reviews().allLog().size)
            db.content().replaceContent(
                source, ContentRows(emptyList(), emptyList(), emptyList()), SyncMetaEntity(source, "v2", 99, 0),
            )
            assertEquals(8, db.content().meta(source)!!.readingParserEpoch)
            assertEquals("retained", db.content().meta(source)!!.readingProblem)
            assertReadingPreserved(db.openHelper.writableDatabase)
        } finally {
            db.close()
        }
        val reopened = openDatabase()
        try {
            assertReadingPreserved(reopened.openHelper.writableDatabase)
            assertEquals(3, reopened.reviews().states(source, "all", "KanjiOn").single().reps)
            assertEquals("retained", reopened.content().meta(source)!!.readingProblem)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun version21UpgradesWithoutChangingProgress() = runBlocking {
        createMigrationSchema(context, name, 21, ::seedProgress)
        val db = openDatabase()
        try {
            assertEquals(3, db.reviews().states(source, "all", "KanjiOn").single().reps)
            assertEquals(1, db.reviews().allLog().size)
            assertEquals(0, db.content().meta(source)!!.readingParserEpoch)
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM reading_readers").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun incompatibleVersion22RollsBackWithoutDestroyingData() {
        createMigrationSchema(context, name, 22) { sqlite ->
            seedProgress(sqlite)
            seedReading(sqlite)
            sqlite.execSQL("ALTER TABLE reading_readers RENAME TO unknown_readers")
        }
        val db = openDatabase()
        try {
            try {
                db.openHelper.writableDatabase
                fail("An incompatible v22 schema must not be accepted")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("Migration"))
            }
        } finally {
            db.close()
        }
        context.openOrCreateDatabase(name, 0, null).use { sqlite ->
            assertEquals(22, sqlite.version)
            sqlite.rawQuery("SELECT scrollOffset FROM unknown_readers", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(25, it.getInt(0))
            }
            sqlite.rawQuery("SELECT reps FROM review_state", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(3, it.getInt(0))
            }
        }
    }

    @Test
    fun unknownVersion22IsRejectedReadOnlyWithSchemaButNoRows() {
        createMigrationSchema(context, name, 22) { sqlite ->
            seedProgress(sqlite)
            seedReading(sqlite)
            sqlite.execSQL("UPDATE room_master_table SET identity_hash = 'unknown-v22' WHERE id = 42")
        }
        val before = databaseHash()
        try {
            checkDatabaseCompatibility(fixtureContext())
            fail("Unknown historical identities must not be migrated")
        } catch (expected: UnsupportedDatabaseSchemaException) {
            val message = expected.message.orEmpty()
            assertTrue(message.contains("Database user_version: 22"))
            assertTrue(message.contains("Room identity_hash: unknown-v22"))
            assertTrue(message.contains("Table: reading_readers"))
            assertTrue(message.contains("scrollOffset:INTEGER"))
            assertTrue(!message.contains("retained"))
            assertTrue(!message.contains("owner/repo"))
        }
        assertEquals(before, databaseHash())
    }

    @Test
    fun corruptDatabaseIsNotDeletedByInspectionOrRoom() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(512) { 0x55 })
        val before = databaseHash()
        try {
            checkDatabaseCompatibility(fixtureContext())
            fail("Corrupt database should not open")
        } catch (_: SQLiteException) {
            assertEquals(before, databaseHash())
        }
        val db = openDatabase()
        try {
            try {
                db.openHelper.writableDatabase
                fail("Corrupt database should not open")
            } catch (_: SQLiteException) {
                assertEquals(before, databaseHash())
            }
        } finally {
            db.close()
        }
    }
}
