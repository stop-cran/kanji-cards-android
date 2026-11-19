package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.readingtext.ReadingContent
import io.github.stopcran.kanji.core.readingtext.ReadingPackage
import io.github.stopcran.kanji.ui.AppDestination
import io.github.stopcran.kanji.ui.hasReadingCapture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TextReadingRepositoryTest {
    @Test fun freshEntriesUseCurrentContentAndBranchWithoutRetargetingRetainedCaptures() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        settings.setSource("https://github.com/owner/repo", "main")
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
            val row = ReadingEntity("owner/repo", "reading-fixture-peach", raw, ContentParser.sha256(raw.toByteArray()), "A small peach", 5, 0)
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), emptyList(), emptyList(), reading = listOf(row)),
                SyncMetaEntity("owner/repo", "v1", 1, 0, readingParserEpoch = 1, contentRevision = "a".repeat(40)))
            settings.sync.setSyncedCommit(settings.source.value, "b".repeat(40))
            val repository = RoomTextReadingRepository(db, settings)
            val original = repository.open("owner/repo", row.id)
            assertFalse(original.saved.furigana)
            assertEquals("a".repeat(40), original.saved.revision)
            repository.position(original, 1, 23)
            settings.setSource("https://github.com/owner/repo", "different-branch")
            val branch = repository.open("owner/repo", row.id)
            assertNotEquals(original.saved.captureId, branch.saved.captureId)
            assertEquals("different-branch", branch.saved.branch)
            val revised = raw.replace("A small peach", "A revised peach")
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), emptyList(), emptyList(),
                reading = listOf(row.copy(json = revised, contentHash = ContentParser.sha256(revised.toByteArray())))),
                SyncMetaEntity("owner/repo", "v2", 2, 0, readingParserEpoch = 1))
            val current = repository.open("owner/repo", row.id)
            assertEquals("A revised peach", current.content.title)
            val retained = repository.open("owner/repo", row.id, original.saved.captureId)
            assertEquals("A small peach", retained.content.title)
            assertEquals("main", retained.saved.branch)
            assertEquals(1, retained.saved.sectionIndex)
            assertEquals(23, retained.saved.scrollOffset)
            assertEquals("different-branch", repository.open("owner/repo", row.id, branch.saved.captureId).saved.branch)
        } finally {
            db.close()
        }
    }

    @Test fun capturedTextWordBodyPositionAndSupportSurviveReplacementReopenAndSourceChange() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        settings.setSource("https://github.com/owner/repo", "main")
        val name = "reading-reader-persistence.db"
        context.deleteDatabase(name)
        fun database() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        var db = database()
        try {
            val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
            val hash = ContentParser.sha256(raw.toByteArray())
            val row = ReadingEntity("owner/repo", "reading-fixture-peach", raw, hash, "A small peach", 5, 0)
            val word = WordEntity("owner/repo", "桃", "もも", "peach", null, "", "", "Original captured body", 5)
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), listOf(word), emptyList(), reading = listOf(row)),
                SyncMetaEntity("owner/repo", "original", 1, 0, readingParserEpoch = 1))
            val repository = RoomTextReadingRepository(db, settings)
            val capture = repository.open("owner/repo", row.id)
            assertFalse(capture.saved.furigana)
            repository.position(capture, 2, 31)
            repository.furigana(capture, true)
            repository.complete(capture)
            repository.selectWord(capture, "桃")
            repository.selectWord(capture, "桃")
            val selected = db.reading().wordIntents("owner/repo").single()
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), listOf(word.copy(body = "Different new body")), emptyList()),
                SyncMetaEntity("owner/repo", "new", 2, 0, readingParserEpoch = 1))
            settings.setSource("https://github.com/other/repo", "another-branch")
            db.close()
            db = database()
            val reopened = RoomTextReadingRepository(db, settings)
            val restored = reopened.open("owner/repo", row.id, capture.saved.captureId)
            assertEquals(hash, restored.saved.contentHash)
            assertEquals("main", restored.saved.branch)
            assertEquals("original", restored.saved.contentVersion)
            assertEquals("Original captured body", restored.words.getValue("桃").body)
            assertEquals(2, restored.saved.sectionIndex)
            assertEquals(31, restored.saved.scrollOffset)
            assertTrue(restored.saved.furigana)
            assertNotNull(restored.saved.completedAtMs)
            assertTrue(db.reviews().allLog().isEmpty())
            assertTrue(db.reviews().allSessions().isEmpty())
            assertEquals(listOf(selected), db.reading().wordIntents("owner/repo"))
            assertTrue(db.reading().wordIntents("other/repo").isEmpty())
            db.reading().removeWordIntent("owner/repo", "桃")
            reopened.selectWord(restored, "桃")
            db.reading().consumeWordIntent("owner/repo", "桃", selected.token)
            assertEquals(1, db.reading().wordIntents("owner/repo").size)
            val admitted = AppDestination.TextReading("owner/repo", row.id, capture.saved.captureId)
            assertTrue(db.hasReadingCapture(admitted))
            assertFalse(db.hasReadingCapture(admitted.copy(captureHash = null)))
            assertFalse(db.hasReadingCapture(admitted.copy(sourceId = "other/repo")))
            assertFalse(db.hasReadingCapture(admitted.copy(captureHash = "0".repeat(64))))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }

    }

    @Test fun largeValidVocabularyCaptureUsesPortableBindCounts() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        settings.setSource("https://github.com/owner/repo", "main")
        val bindCounts = mutableListOf<Int>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries()
            .setQueryCallback({ sql, args ->
                if (sql.contains("FROM words") && sql.contains(" IN ")) bindCounts += args.size
            }, Executor { it.run() }).build()
        try {
            val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
            val fixture = ReadingContent.parse("reading/reading-fixture-peach.json", raw).content
            val original = fixture.sections.first().vocabulary.single()
            val occurrences = (0 until 1_100).map { original.copy(start = it, end = it + 1, word = "桃$it") }
            val section = fixture.sections.first().copy(text = "桃".repeat(occurrences.size), vocabulary = occurrences)
            val content = fixture.copy(sections = listOf(section), questions = fixture.questions.map {
                it.copy(evidence = listOf(it.evidence.single().copy(sectionId = section.id, start = 0, end = 1)))
            })
            val json = Json.encodeToString(ReadingPackage.serializer(), content)
            val parsed = ReadingContent.parse("reading/${content.id}.json", json)
            val row = ReadingEntity("owner/repo", content.id, json, parsed.hash, content.title, content.jlpt, 0)
            val words = occurrences.map {
                WordEntity("owner/repo", requireNotNull(it.word), "もも", "peach", null, "", "", "本文".repeat(1_024), 5)
            }
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), words, emptyList(), reading = listOf(row)),
                SyncMetaEntity("owner/repo", "v1", 1, 0, readingParserEpoch = 1))
            val capture = RoomTextReadingRepository(db, settings).open("owner/repo", row.id)
            assertEquals(1_100, capture.words.size)
            assertEquals(2, bindCounts.size)
            assertTrue(bindCounts.all { it <= 999 })
            assertTrue(capture.words.values.sumOf { it.body.toByteArray().size } > 2 * 1024 * 1024)
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), emptyList(), emptyList()),
                SyncMetaEntity("owner/repo", "v2", 2, 0, readingParserEpoch = 1))
            val restored = RoomTextReadingRepository(db, settings).open("owner/repo", row.id, capture.saved.captureId)
            assertEquals(capture.words, restored.words)
            assertEquals("[]", restored.saved.wordsJson)
        } finally {
            db.close()
        }
    }

    @Test fun missingCardsKeepLocalExplanationsAndForeignFreshStartsAreRejected() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        settings.setSource("https://github.com/owner/repo", "main")
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
            val row = ReadingEntity("owner/repo", "reading-fixture-peach", raw, ContentParser.sha256(raw.toByteArray()), "A small peach", 5, 0)
            db.content().replaceContent("owner/repo", ContentRows(emptyList(), emptyList(), emptyList(), reading = listOf(row)),
                SyncMetaEntity("owner/repo", "v1", 1, 0, readingParserEpoch = 1))
            val repository = RoomTextReadingRepository(db, settings)
            val capture = repository.open("owner/repo", row.id)
            assertTrue(capture.words.isEmpty())
            assertEquals("peach", capture.content.sections.first().vocabulary.single().gloss)
            var rejected = false
            try {
                repository.open("other/repo", row.id)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertNull(db.reading().reader("other/repo", row.id))
        } finally {
            db.close()
        }
    }
}
