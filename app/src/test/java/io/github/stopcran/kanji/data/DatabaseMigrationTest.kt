package io.github.stopcran.kanji.data

import android.app.Application
import android.content.Context
import android.database.Cursor
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.Advancement
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQueues
import io.github.stopcran.kanji.core.words.WordStacks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DatabaseMigrationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val name = "word-jlpt-test.db"
    private val sources = listOf("owner/repo", "other/repo")

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    private fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = openDatabase()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun wordLevelsRoundTripAcrossDatabaseReopen() = runBlocking {
        val expected = (0..5).map { level ->
            val field = if (level == 0) "" else "jlpt: $level"
            val exclusions = if (level == 0) "" else "quiz_exclusions: [word-0, word-${level % 5 + 1}]"
            ContentParser.parseWord(
                "words/word-$level.md",
                "---\nword: word-$level\nreading: reading\ntitle: meaning\nkanji: []\ntags: [animals, custom]\n$field\n$exclusions\n---\nbody",
            ).toEntity(sources.first())
        }
        withDatabase { db -> db.content().insertWords(expected) }
        withDatabase { db ->
            assertEquals(expected, db.content().words(sources.first()))
            assertEquals(expected, db.content().observeWords(sources.first()).first())
            expected.forEach { assertEquals(it, db.content().word(it.sourceId, it.word)) }
            val withoutLevel = expected.last().copy(jlpt = null)
            db.content().insertWords(listOf(withoutLevel))
            assertEquals(withoutLevel, db.content().word(withoutLevel.sourceId, withoutLevel.word))
        }
    }

    @Test
    fun homeWordProjectionPreservesMetadataAndMatchesQuizQueuesAndAdvancement() = runBlocking {
        val source = sources.first()
        val levels = mapOf("easy" to 5, "medium" to 4, "hard" to 1, "unknown" to null)
        fun word(id: String, kanji: List<String>, jlpt: Int? = null, exclusions: List<String> = emptyList()) =
            WordEntity(source, id, "reading $id", "meaning $id", "noun", kanji.joinSep(), "custom", "article body", jlpt, exclusions.joinSep())
        val spellings = listOf(emptyList(), listOf("hard"), listOf("unknown"), listOf("missing"))
        val explicit = (0..9).map { word("explicit-$it", spellings[it % spellings.size], 5, listOf("explicit-${(it + 1) % 10}")) }
        val legacy = (0..9).map { word("legacy-$it", listOf("easy")) }
        val expected = explicit + legacy + listOf(
            word("explicit-n4", listOf("easy"), 4),
            word("explicit-n3", listOf("easy"), 3),
            word("legacy-n4", listOf("easy", "medium")),
            word("legacy-kana", emptyList()),
            word("legacy-unknown", listOf("unknown")),
            word("legacy-missing", listOf("missing")),
        )
        withDatabase { db ->
            db.content().insertWords(expected + expected.first().copy(sourceId = sources.last(), word = "other-source"))
        }
        withDatabase { db ->
            val full = db.content().words(source)
            val home = db.content().observeWordsLite(source).first()
            assertEquals(expected.sortedBy { it.word }, full)
            assertEquals(full.map { it.copy(body = "") }, home)
            assertEquals(full.map { it.toCard() }, home.map { it.toCard() })
            assertEquals((explicit + legacy).map { it.word }.toSet(), home.inWordStack(WordStacks.n5, levels).map { it.word }.toSet())
            assertEquals(22, home.inWordStack(WordStacks.n4, levels).size)
            assertEquals(26, home.inWordStack(WordStacks.all, levels).size)

            val now = Instant.parse("2026-01-10T00:00:00Z")
            val solid = SrsState(CardPhase.Review, 30.0, 5.0, now, now.minusSeconds(86400), 3)
            val states = full.associate { it.word to solid }
            for (stack in listOf(WordStacks.n5, WordStacks.n4, WordStacks.all)) {
                val quizIds = full.inWordStack(stack, levels).map { it.word }
                val homeIds = home.inWordStack(stack, levels).map { it.word }
                assertEquals(quizIds, homeIds)
                for (direction in WordDirection.entries) {
                    assertEquals(
                        WordQueues.build(direction, quizIds, states, states, now, 0, noise = 0.0),
                        WordQueues.build(direction, homeIds, states, states, now, 0, noise = 0.0),
                    )
                }
            }
            val knownLegacy = legacy.associate { it.word to solid }
            assertTrue(Advancement.ready(List(2) { legacy.map { knownLegacy[it.word] } }, now))
            val homeN5 = home.inWordStack(WordStacks.n5, levels)
            assertFalse(Advancement.ready(List(2) { homeN5.map { knownLegacy[it.word] } }, now))
            assertTrue(Advancement.ready(List(2) { homeN5.map { states[it.word] } }, now))
        }
    }

    @Test
    fun migratesVersion2AndReimportsLabelsWithoutLosingState() = runBlocking {
        checkUpgrade(2)
    }

    @Test
    fun migratesVersion1ThroughBothRegisteredMigrations() = runBlocking {
        checkUpgrade(1)
    }

    @Test
    fun laterCorpusReimportCanClearExclusionsWithoutChangingProgress() = runBlocking {
        checkUpgrade(2)
        withDatabase { db ->
            val source = sources.first()
            val stateQuery = "SELECT * FROM review_state ORDER BY sourceId, stack, kanji, mode"
            val logQuery = "SELECT * FROM review_log ORDER BY id"
            val states = rows(db.openHelper.writableDatabase.query(stateQuery))
            val logs = rows(db.openHelper.writableDatabase.query(logQuery))
            val words = db.content().words(source).map { word ->
                ContentParser.parseWord(
                    "words/${word.word}.md",
                    "---\nword: ${word.word}\nreading: reading\ntitle: meaning\nkanji: []\ntags: [custom]\njlpt: 5\nquiz_exclusions: []\n---\nword body",
                ).toEntity(source)
            }
            db.content().replaceContent(
                source, db.content().kanji(source), words,
                listOf(requireNotNull(db.content().article(source, "article"))), SyncMetaEntity(source, "new-corpus", 789, 0),
            )
            assertEquals(words, db.content().words(source))
            assertEquals(states, rows(db.openHelper.writableDatabase.query(stateQuery)))
            assertEquals(logs, rows(db.openHelper.writableDatabase.query(logQuery)))
        }
        withDatabase { db ->
            db.content().words(sources.first()).forEach {
                assertEquals("", it.quizExclusions)
                assertEquals(5, it.jlpt)
            }
        }
    }

    private suspend fun checkUpgrade(version: Int) {
        val snapshots = createLegacyDatabase(version)
        withDatabase { db ->
            fun assertPreserved() {
                snapshots.forEach { (query, expected) ->
                    assertEquals(query, expected, rows(db.openHelper.writableDatabase.query(query)))
                }
            }
            assertPreserved()
            sources.forEach { source ->
                assertNull(db.content().meta(source))
                assertNull(requireNotNull(db.content().word(source, "legacy-word")).jlpt)
                assertEquals("", requireNotNull(db.content().word(source, "legacy-word")).quizExclusions)
            }
            if (version == 1) {
                for (table in listOf("review_state", "review_log")) {
                    assertEquals(listOf(listOf("all")), rows(db.openHelper.writableDatabase.query("SELECT DISTINCT stack FROM $table")))
                }
            }

            val source = sources.first()
            val words = db.content().words(source).map {
                it.copy(jlpt = 5, quizExclusions = if (it.word == "legacy-word") listOf("peer-word").joinSep() else "")
            }
            db.content().replaceContent(
                source,
                db.content().kanji(source),
                words,
                listOf(requireNotNull(db.content().article(source, "article"))),
                SyncMetaEntity(source, "unchanged-content-version", 456, 0),
            )
            assertEquals(words, db.content().words(source))
            assertEquals(5, requireNotNull(db.content().word(source, "legacy-word")).jlpt)
            assertEquals(listOf("peer-word"), requireNotNull(db.content().word(source, "legacy-word")).toCard().quizExclusions)
            assertNull(requireNotNull(db.content().word(sources.last(), "legacy-word")).jlpt)
            assertEquals("", requireNotNull(db.content().word(sources.last(), "legacy-word")).quizExclusions)
            assertPreserved()
        }
        withDatabase { db ->
            val restored = requireNotNull(db.content().word(sources.first(), "legacy-word"))
            assertEquals(5, restored.jlpt)
            assertEquals(listOf("peer-word"), restored.toCard().quizExclusions)
            snapshots.forEach { (query, expected) -> assertEquals(query, expected, rows(db.openHelper.writableDatabase.query(query))) }
        }
    }

    private fun rows(cursor: Cursor): List<List<String?>> = cursor.use {
        buildList {
            while (it.moveToNext()) add((0 until it.columnCount).map { column -> it.getString(column) })
        }
    }

    private fun createLegacyDatabase(version: Int): Map<String, List<List<String?>>> {
        val stackColumn = if (version == 2) "stack TEXT NOT NULL, " else ""
        val stackKey = if (version == 2) "stack, " else ""
        // Frozen v1/v2 schemas: the project did not export historical Room schema files.
        val schema = listOf(
            """CREATE TABLE kanji (sourceId TEXT NOT NULL, kanji TEXT NOT NULL, title TEXT NOT NULL, jlpt INTEGER,
                tags TEXT NOT NULL, strokeCount INTEGER, radical TEXT, phonetic TEXT, onyomi TEXT NOT NULL,
                kunyomi TEXT NOT NULL, distractors TEXT NOT NULL, body TEXT NOT NULL, strokesJson TEXT,
                position INTEGER NOT NULL, PRIMARY KEY(sourceId, kanji))""",
            """CREATE TABLE words (sourceId TEXT NOT NULL, word TEXT NOT NULL, reading TEXT NOT NULL, title TEXT NOT NULL,
                type TEXT, kanji TEXT NOT NULL, tags TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY(sourceId, word))""",
            """CREATE TABLE articles (sourceId TEXT NOT NULL, slug TEXT NOT NULL, title TEXT NOT NULL,
                body TEXT NOT NULL, PRIMARY KEY(sourceId, slug))""",
            """CREATE TABLE review_state (sourceId TEXT NOT NULL, ${stackColumn}kanji TEXT NOT NULL, mode TEXT NOT NULL,
                phase TEXT NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL, dueMs INTEGER NOT NULL,
                lastReviewMs INTEGER, reps INTEGER NOT NULL, lapses INTEGER NOT NULL, PRIMARY KEY(sourceId, ${stackKey}kanji, mode))""",
            """CREATE TABLE review_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceId TEXT NOT NULL,
                ${stackColumn}kanji TEXT NOT NULL, mode TEXT NOT NULL, grade INTEGER NOT NULL, atMs INTEGER NOT NULL)""",
            """CREATE TABLE sync_meta (sourceId TEXT NOT NULL, contentVersion TEXT NOT NULL,
                syncedAtMs INTEGER NOT NULL, problems INTEGER NOT NULL, PRIMARY KEY(sourceId))""",
        )
        val stateColumns = if (version == 2) "*" else "sourceId, kanji, mode, phase, stability, difficulty, dueMs, lastReviewMs, reps, lapses"
        val logColumns = if (version == 2) "*" else "id, sourceId, kanji, mode, grade, atMs"
        val queries = listOf(
            "SELECT sourceId, word, reading, title, type, kanji, tags, body FROM words ORDER BY sourceId, word",
            "SELECT * FROM kanji ORDER BY sourceId, kanji",
            "SELECT * FROM articles ORDER BY sourceId, slug",
            "SELECT $stateColumns FROM review_state ORDER BY sourceId, ${stackKey}kanji, mode",
            "SELECT $logColumns FROM review_log ORDER BY id",
        )
        return context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            schema.forEach { db.execSQL(it) }
            sources.forEach { source ->
                db.execSQL("INSERT INTO words VALUES (?, 'legacy-word', 'reading', 'meaning', NULL, '', 'custom', 'word body')", arrayOf(source))
                db.execSQL("INSERT INTO words VALUES (?, 'peer-word', 'reading', 'meaning', NULL, '', 'custom', 'word body')", arrayOf(source))
                db.execSQL("INSERT INTO kanji VALUES (?, 'known', 'meaning', 5, 'tag', 1, NULL, NULL, 'on', 'kun', '', 'kanji body', NULL, 0)", arrayOf(source))
                db.execSQL("INSERT INTO articles VALUES (?, 'article', 'title', 'article body')", arrayOf(source))
                db.execSQL("INSERT INTO sync_meta VALUES (?, 'unchanged-content-version', 123, 1)", arrayOf(source))
                val stacks = if (version == 2) listOf("words-n5", "words-n4", "words-all") else listOf("all")
                for (stack in stacks) {
                    for (mode in listOf("WordJpEn", "WordEnJp")) {
                        val stackValue = if (version == 2) "'$stack', " else ""
                        db.execSQL(
                            "INSERT INTO review_state VALUES (?, ${stackValue}'legacy-word', ?, 'Review', 30.0, 5.0, 123456, 123450, 12, 2)",
                            arrayOf(source, mode),
                        )
                        db.execSQL(
                            "INSERT INTO review_log (sourceId, ${stackKey}kanji, mode, grade, atMs) VALUES (?, ${stackValue}'legacy-word', ?, 3, 123450)",
                            arrayOf(source, mode),
                        )
                    }
                }
            }
            db.version = version
            queries.associateWith { rows(db.rawQuery(it, null)) }
        }
    }
}
