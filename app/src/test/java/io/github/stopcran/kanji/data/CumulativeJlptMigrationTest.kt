package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.srs.Stacks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class CumulativeJlptMigrationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val name = "cumulative-jlpt-migration.db"
    private val owner = "owner/content"
    private val other = "other/content"

    @After fun cleanUp() { context.deleteDatabase(name) }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_20_21).allowMainThreadQueries().build()
        try { block(db) } finally { db.close() }
    }

    @Test
    fun newestPerSourceModeAndReadingWinsWhileLogsAndOtherStacksSurvive() = runBlocking {
        createMigrationSchema(context, name, 20) { sqlite ->
            fun state(source: String, stack: String, kanji: String, mode: String, reading: String, phase: String, last: Long?, reps: Int, due: Long) {
                sqlite.execSQL(
                    "INSERT INTO review_state (sourceId, stack, kanji, mode, phase, stability, difficulty, dueMs, lastReviewMs, reps, lapses, reading) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 5.0, ?, ?, ?, 1, ?)",
                    arrayOf(source, stack, kanji, mode, phase, reps.toDouble(), due, last, reps, reading),
                )
            }
            state(owner, "jlpt-n5", "日", "Quiz", "", "Review", 100, 8, 1000)
            state(owner, "jlpt-n4", "日", "Quiz", "", "Review", 200, 2, 2000)
            state(owner, "jlpt-n3", "日", "Quiz", "", "New", null, 0, 0)
            state(owner, "jlpt-n5", "日", "Draw", "", "Review", 110, 4, 1100)
            state(owner, "jlpt-n5", "日", "KanjiOn", "にち", "Review", 140, 3, 1400)
            state(owner, "jlpt-n4", "日", "KanjiOn", "にち", "Review", 240, 2, 2400)
            state(owner, "jlpt-n4", "日", "KanjiOn", "じつ", "Review", 210, 1, 2100)
            state(owner, "jlpt-n5", "火", "Quiz", "", "Review", null, 0, 3000)
            state(owner, "jlpt-n4", "火", "Quiz", "", "New", null, 0, 0)
            state(owner, "jlpt-n5", "水", "Quiz", "", "Review", 300, 5, 5000)
            state(owner, "jlpt-n4", "水", "Quiz", "", "Review", 300, 7, 7000)
            state(owner, "jlpt-n3", "木", "Quiz", "", "Review", 400, 5, 3000)
            state(owner, "jlpt-n4", "木", "Quiz", "", "Review", 400, 5, 4000)
            state(owner, "all", "日", "Quiz", "", "Review", 250, 7, 2500)
            state(owner, "starter", "日", "Quiz", "", "Review", 260, 6, 2600)
            state(owner, "jlpt-n4", "word", "WordJpEn", "", "Review", 230, 5, 2300)
            state(other, "jlpt-n5", "日", "Quiz", "", "Review", 300, 9, 3000)
            fun log(id: Int, source: String, stack: String, kanji: String, mode: String, at: Int, reading: String = "") {
                sqlite.execSQL(
                    "INSERT INTO review_log (id, sourceId, stack, kanji, mode, grade, atMs, reading, sessionId) " +
                        "VALUES (?, ?, ?, ?, ?, 3, ?, ?, NULL)",
                    arrayOf(id, source, stack, kanji, mode, at, reading),
                )
            }
            log(1, owner, "jlpt-n5", "日", "Quiz", 100)
            log(2, owner, "jlpt-n4", "日", "Quiz", 200)
            log(3, owner, "all", "日", "Quiz", 250)
            log(4, owner, "jlpt-n5", "日", "KanjiOn", 140, "にち")
            log(5, owner, "jlpt-n4", "日", "KanjiOn", 240, "にち")
            log(6, owner, "jlpt-n4", "日", "KanjiOn", 210, "じつ")
            log(7, other, "jlpt-n5", "日", "Quiz", 300)
            log(8, owner, "jlpt-n4", "word", "WordJpEn", 230)
        }
        repeat(2) {
            withDatabase { db ->
                assertEquals(21, db.openHelper.readableDatabase.version)
                val states = db.reviews().allStates()
                val own = states.filter { it.sourceId == owner && it.stack == Stacks.JLPT_STATE }
                assertEquals(7, own.size)
                assertEquals(2000L, own.single { it.kanji == "日" && it.mode == "Quiz" }.dueMs)
                assertEquals(2, own.single { it.kanji == "日" && it.mode == "Quiz" }.reps)
                assertEquals(1100L, own.single { it.mode == "Draw" }.dueMs)
                assertEquals(2400L, own.single { it.reading == "にち" }.dueMs)
                assertEquals(2100L, own.single { it.reading == "じつ" }.dueMs)
                assertEquals("Review", own.single { it.kanji == "火" }.phase)
                assertEquals(7000L, own.single { it.kanji == "水" }.dueMs)
                assertEquals(3000L, own.single { it.kanji == "木" }.dueMs)
                assertEquals(9, states.single { it.sourceId == other && it.stack == Stacks.JLPT_STATE }.reps)
                assertEquals(
                    setOf("all", "starter", "jlpt-n4"),
                    states.filter { it.sourceId == owner && it.stack != Stacks.JLPT_STATE }.map { it.stack }.toSet(),
                )
                assertEquals((1L..8L).toSet(), db.reviews().allLog().map { it.id }.toSet())
                assertEquals(setOf(1L, 2L, 4L, 5L, 6L), db.reviews().unlockLog(owner, Stacks.JLPT_STATE).map { it.id }.toSet())
                assertEquals(3, db.reviews().itemsIntroducedSince(owner, 150))
                assertEquals(1, db.reviews().readingCardsIntroducedSince(owner, Stacks.JLPT_STATE, 150))
                assertTrue(db.reviews().meaningIntroducedSince(owner, Stacks.JLPT_STATE, 150).isEmpty())
                assertTrue(db.reviews().allTransitionChecks().isEmpty())
            }
        }
    }
}
