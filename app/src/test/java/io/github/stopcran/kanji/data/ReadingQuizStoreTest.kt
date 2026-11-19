package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.readingtext.ReadingBookMode
import io.github.stopcran.kanji.core.readingtext.ReadingContent
import io.github.stopcran.kanji.core.readingtext.ReadingPackage
import io.github.stopcran.kanji.core.readingtext.ReadingQuizPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReadingQuizStoreTest {
    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private lateinit var readers: RoomTextReadingRepository
    private lateinit var store: ReadingQuizStore
    private val source = "owner/repo"
    private val text = "reading-fixture-peach"
    private val name = "reading-quiz-store-test.db"

    @Before fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        settings = Settings(context)
        settings.setSource("https://github.com/$source", "main")
        settings.setWeekPlan("FFFFFFF")
        settings.setDailyNewCards(1)
        context.deleteDatabase(name)
        open()
        val original = ReadingContent.parse("reading/$text.json",
            requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()).content
        val questions = (1..4).flatMap { tier ->
            (1..4).map { index -> original.questions.single().copy(id = "tier-$tier-question-$index", tier = tier) }
        }
        val json = Json.encodeToString(ReadingPackage.serializer(), original.copy(questions = questions))
        db.content().replaceContent(source, ContentRows(emptyList(),
            listOf(WordEntity(source, "桃", "もも", "peach", null, "", "", "Captured peach body", 5)), emptyList(), reading = listOf(
            ReadingEntity(source, text, json, ContentParser.sha256(json.toByteArray()), original.title, 5, 0),
        )), SyncMetaEntity(source, "original", 1, 0, readingParserEpoch = 1))
    }

    private fun open() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        readers = RoomTextReadingRepository(db, settings)
        store = ReadingQuizStore(db, readers, settings) { Instant.parse("2026-06-01T10:00:00Z") }
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test fun modesShareSameDayUnlocksButHaveIndependentSchedulesAndIdempotentResults() = runBlocking<Unit> {
        val reader = readers.open(source, text)
        readers.complete(reader)
        val first = answer(store.create(reader, ReadingBookMode.OpenBook))
        assertTrue(first.saved.newReservation)
        assertEquals(0, db.newAllowance(settings, source, Instant.parse("2026-06-01T10:00:00Z")).remaining)
        val submitted = store.submit(first.saved.id)
        assertFalse(submitted.saved.newReservation)
        assertTrue(requireNotNull(submitted.result).passed)
        assertEquals(2, db.reading().progress(source, text)?.unlockedTier)
        assertEquals(1, db.reviews().states(source, READING_TEXT_STACK, "ReadingOpenBook").single().reps)
        assertTrue(db.reviews().states(source, READING_TEXT_STACK, "ReadingClosedBook").isEmpty())
        val second = store.create(reader, ReadingBookMode.ClosedBook)
        assertFalse(second.saved.newReservation)
        assertEquals(2, second.plan.highestTier)
        answer(second)
        store.submit(second.saved.id)
        assertEquals(3, db.reading().progress(source, text)?.unlockedTier)
        assertEquals(1, db.reviews().states(source, READING_TEXT_STACK, "ReadingClosedBook").single().reps)
        assertEquals(1, db.reviews().states(source, READING_TEXT_STACK, "ReadingOpenBook").single().reps)
        assertEquals(submitted, store.submit(first.saved.id))
        assertEquals(3, db.reading().progress(source, text)?.unlockedTier)
        assertEquals(2, db.reviews().allLog().size)
        val bytes = ByteArrayOutputStream()
        ProgressExport.write(db, settings, bytes)
        val files = mutableMapOf<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) files[(zip.nextEntry ?: break).name] = zip.readBytes().toString(Charsets.UTF_8)
        }
        val exported = Json.parseToJsonElement(files.getValue("reading_quizzes.json")).jsonArray
        assertEquals(setOf("OpenBook", "ClosedBook"), exported.map {
            it.jsonObject.getValue("result").jsonObject.getValue("mode").jsonPrimitive.content
        }.toSet())
        assertEquals(setOf(first.plan, second.plan), exported.map {
            Json.decodeFromJsonElement(ReadingQuizPlan.serializer(), it.jsonObject.getValue("plan"))
        }.toSet())
        exported.forEach { attempt ->
            val word = attempt.jsonObject.getValue("wordBodies").jsonArray.single().jsonObject
            assertEquals("桃", word.getValue("word").jsonPrimitive.content)
            assertEquals("Captured peach body", word.getValue("body").jsonPrimitive.content)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.answer(first.saved.id, first.plan.questions.first().question.id, null) }
        }
    }

    @Test fun availabilityMatchesCreateAdmissionAndKeepsUnfinishedQuizzesResumable() = runBlocking<Unit> {
        val reader = readers.open(source, text)
        readers.complete(reader)
        settings.setDailyNewCards(0)
        assertEquals(ReadingQuizAvailability.NoAllowance, store.availability(source, text))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.create(reader, ReadingBookMode.OpenBook) } }
        settings.setDailyNewCards(1)
        assertEquals(ReadingQuizAvailability.Available, store.availability(source, text))
        val first = store.create(reader, ReadingBookMode.OpenBook)
        settings.setDailyNewCards(0)
        assertEquals(ReadingQuizAvailability.Resume(first.saved.id), store.availability(source, text))
        answer(first)
        store.submit(first.saved.id)
        assertEquals(0, db.newAllowance(settings, source, Instant.parse("2026-06-01T10:00:00Z")).remaining)
        assertEquals(ReadingQuizAvailability.Available, store.availability(source, text))
        store.create(reader, ReadingBookMode.ClosedBook)
    }

    @Test fun observedAvailabilityFollowsTheClockOnEachTick() = runBlocking<Unit> {
        var now = Instant.parse("2026-06-01T10:00:00Z")
        val clocked = ReadingQuizStore(db, readers, settings) { now }
        readers.complete(readers.open(source, text))
        db.reviews().insertLog(ReviewLogEntity(sourceId = source, stack = "other", kanji = "日", mode = "Quiz", grade = 3,
            atMs = now.toEpochMilli()))
        val ticks = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val seen = Channel<ReadingQuizAvailability>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) { clocked.observeAvailability(source, text, ticks).collect { seen.send(it) } }
        try {
            assertEquals(ReadingQuizAvailability.NoAllowance, withTimeout(10_000) { seen.receive() })
            now = Instant.parse("2026-06-02T10:00:00Z")
            ticks.emit(Unit)
            assertEquals(ReadingQuizAvailability.Available, withTimeout(10_000) { seen.receive() })
        } finally {
            job.cancel()
        }
    }

    @Test fun durableCursorKeepsItsSnapshotAndIncompleteOrDiscardedAttemptsEarnNothing() = runBlocking {
        val reader = readers.open(source, text)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.create(reader, ReadingBookMode.ClosedBook) } }
        readers.complete(reader)
        val created = store.create(reader, ReadingBookMode.ClosedBook)
        val question = created.plan.questions.first().question
        val answered = store.answer(created.saved.id, question.id, question.correctOptionId)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.submit(created.saved.id) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.answer(created.saved.id, question.id, question.correctOptionId) }
        }
        assertTrue(db.reviews().allLog().isEmpty())
        assertNull(db.reading().progress(source, text))
        db.content().replaceContent(source, ContentRows(emptyList(), emptyList(), emptyList()), SyncMetaEntity(source, "changed", 2, 0))
        settings.setSource("https://github.com/other/repo", "different")
        db.close()
        open()
        assertEquals(answered, store.load(created.saved.id))
        assertEquals("original", store.load(created.saved.id).reader.saved.contentVersion)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.create(reader, ReadingBookMode.OpenBook) } }
        store.close(created.saved.id)
        assertEquals(0, db.reading().reservedNew(source))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.submit(created.saved.id) } }
        assertTrue(db.reviews().allLog().isEmpty())
        assertTrue(db.reviews().states(source, READING_TEXT_STACK, "ReadingClosedBook").isEmpty())
        assertNull(db.reading().progress(source, text))
    }

    @Test fun failedSubmissionRollsBackItsScheduleReviewProgressAndReservationConversion() = runBlocking {
        val reader = readers.open(source, text)
        readers.complete(reader)
        val quiz = answer(store.create(reader, ReadingBookMode.OpenBook))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_reading_progress BEFORE INSERT ON reading_progress " +
                "BEGIN SELECT RAISE(ABORT, 'fixture progress failure'); END",
        )
        try {
            assertThrows(android.database.sqlite.SQLiteException::class.java) { runBlocking { store.submit(quiz.saved.id) } }
            assertTrue(db.reviews().allLog().isEmpty())
            assertTrue(db.reviews().allStates().isEmpty())
            assertNull(db.reading().progress(source, text))
            assertEquals(quiz, store.load(quiz.saved.id))
            assertEquals(1, db.reading().reservedNew(source))
            assertNull(db.reviews().allSessions().single().endedAtMs)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_reading_progress")
        }
        assertNotNull(store.submit(quiz.saved.id).result)
        assertEquals(0, db.reading().reservedNew(source))
    }

    private suspend fun answer(initial: CapturedReadingQuiz): CapturedReadingQuiz {
        var current = initial
        var highestCorrect = 3
        initial.plan.questions.forEach { captured ->
            val question = captured.question
            val option = if (question.tier == initial.plan.highestTier && highestCorrect-- > 0) question.correctOptionId else null
            current = store.answer(initial.saved.id, question.id, option)
        }
        return current
    }
}
