package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammarFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GrammarCurriculumMigrationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val name = "grammar-curriculum-test.db"
    private val sources = listOf("owner/repo", "other/repo")

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_13_14, MIGRATION_14_15).allowMainThreadQueries().build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun version14UpgradePreservesContentHistoryAndSupportsReopenedMilestones() = runBlocking {
        val source = sources.first()
        createMigrationSchema(context, name, 14) { sqlite ->
            sqlite.execSQL("INSERT INTO sync_meta VALUES (?, 'v14', 500, 0, NULL, NULL, NULL, NULL)", arrayOf(source))
            sqlite.execSQL("INSERT INTO grammar_focus VALUES (?, 9, '{}')", arrayOf(source))
            sqlite.execSQL("INSERT INTO review_log VALUES (7, ?, 'grammar', 'alpha-a-0', 'Grammar', 3, 500, '', NULL)", arrayOf(source))
        }
        withDatabase { db ->
            assertEquals("v14", db.content().meta(source)!!.contentVersion)
            assertEquals(9L, db.reviews().grammarFocus(source)!!.revision)
            assertEquals(7L, db.reviews().allLog().single().id)
            assertTrue(db.reviews().grammarReadiness(source, 1).isEmpty())
            db.reviews().insertGrammarReadiness(listOf(GrammarReadinessEntity(source, "alpha", "a", 1, 500)))
            db.reviews().putGrammarReadinessStatus(GrammarReadinessStatusEntity(source, "fixture", true))
        }
        withDatabase { db ->
            assertEquals(500L, db.reviews().grammarReadiness(source, 1).single().attainedAtMs)
            assertTrue(db.reviews().grammarReadinessStatus(source)!!.complete)
            assertEquals(7L, db.reviews().allLog().single().id)
        }
    }

    @Test
    fun version13UpgradePreservesSnapshotAndHistoryAndReopensCurriculumMetadata() = runBlocking {
        val source = sources.first()
        val set = grammarFixture()
        val setJson = Json.encodeToString(GrammarSet.serializer(), set)
        createMigrationSchema(context, name, 13) { sqlite ->
            sqlite.execSQL("INSERT INTO grammar_sets VALUES (?, ?, ?, 0)", arrayOf(source, set.id, setJson))
            sqlite.execSQL("INSERT INTO sync_meta VALUES (?, 'same-version', 500, 0, NULL, NULL)", arrayOf(source))
            sqlite.execSQL("INSERT INTO grammar_focus VALUES (?, 9, '{}')", arrayOf(source))
            sqlite.execSQL("INSERT INTO review_log VALUES (7, ?, 'grammar', 'alpha-a-0', 'Grammar', 3, 500, '', NULL)", arrayOf(source))
            sqlite.execSQL(
                "INSERT INTO review_state VALUES (?, 'grammar', 'alpha-a-0', 'Grammar', 'Review', 12.5, 5.0, 999, 500, 3, 1, '')",
                arrayOf(source),
            )
            sqlite.execSQL(
                "INSERT INTO grammar_responses VALUES (7, ?, 'grammar', 'alpha-a-0', 'a', 'contrast', 'type', 'a', 1, NULL)",
                arrayOf(source),
            )
        }
        val catalog = """{"schemaVersion":1,"chapters":[{"id":"first","label":"First","groups":["alpha"]}]}"""
        withDatabase { db ->
            assertEquals(listOf(set), db.grammarSets(source))
            val meta = db.content().meta(source)!!
            assertEquals("same-version", meta.contentVersion)
            assertNull(meta.grammarCurriculumJson)
            assertNull(meta.grammarCurriculumProblem)
            db.content().putMeta(meta.copy(grammarCurriculumJson = catalog))
        }
        withDatabase { db ->
            assertEquals(catalog, db.content().meta(source)!!.grammarCurriculumJson)
            assertEquals(listOf(set), db.grammarSets(source))
            assertEquals(9L, db.reviews().grammarFocus(source)!!.revision)
            assertEquals(3, db.reviews().allStates().single().reps)
            assertEquals(7L, db.reviews().allLog().single().id)
            assertEquals(7L, db.reviews().allGrammarResponses().single().reviewLogId)
            assertNull(db.content().meta(sources.last()))
        }
    }
}
