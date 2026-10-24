package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.core.grammarFixture
import io.github.stopcran.kanji.core.familiarEvents
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GrammarEvent
import io.github.stopcran.kanji.core.grammar.GrammarReadiness
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GrammarReadinessPersistenceTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val name = "grammar-readiness-test.db"
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private val source get() = settings.source.value.id
    private val now = Instant.parse("2026-10-18T12:00:00Z")
    private val set = grammarFixture()
    private val dependent = grammarFixture("beta", requires = listOf("alpha"))
    @Volatile private var pageBarrier: Pair<CountDownLatch, CountDownLatch>? = null
    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries()
        .setQueryCallback({ sql, _ ->
            if (sql.contains("FROM review_log l") && sql.contains("LIMIT")) pageBarrier?.let { barrier ->
                pageBarrier = null
                barrier.first.countDown()
                check(barrier.second.await(30, TimeUnit.SECONDS)) { "Timed out waiting to resume the history page" }
            }
        }, Executor { it.run() })
        .build()

    @Before
    fun setup() = runBlocking {
        context.deleteDatabase(name)
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        settings = Settings(context)
        settings.setWeekPlan("FFFFFFF")
        settings.setGrammarParserEpoch(source, ContentParser.GRAMMAR_PARSER_EPOCH)
        db = open()
        install(source)
    }

    @After
    fun cleanup() {
        db.close()
        context.deleteDatabase(name)
    }

    private suspend fun install(owner: String) {
        db.content().insertGrammar(listOf(set, dependent).mapIndexed { index, item ->
            GrammarEntity(owner, item.id, Json.encodeToString(GrammarSet.serializer(), item), index)
        })
    }

    private suspend fun record(event: GrammarEvent, live: Boolean = false, owner: String = source, evaluationNow: Instant = now) {
        val state = SrsState(CardPhase.Review, due = now.minusSeconds(1)).toEntity(owner, GRAMMAR_STACK, event.exerciseId, StudyMode.Grammar)
        val log = ReviewLogEntity(0, owner, GRAMMAR_STACK, event.exerciseId, "Grammar", event.grade, event.atMs)
        val detail = GrammarResponseEntity(
            0, if (event.identityMatches) owner else "wrong/source", GRAMMAR_STACK, event.exerciseId,
            requireNotNull(event.constructionId), requireNotNull(event.relationshipId), requireNotNull(event.promptType), "a", requireNotNull(event.correct),
        )
        if (live) db.recordGrammarAnswer(settings, state, log, detail, null, evaluationNow) else db.reviews().recordGrammar(state, log, detail)
    }

    @Test
    fun historicalBackfillIsPagedIdempotentSourceIsolatedAndRecoverableAfterDerivedStateLoss() = runBlocking {
        val old = familiarEvents(set, now.minus(Duration.ofDays(100)))
        db.withTransaction {
            old.forEach { record(it) }
            repeat(1100) { record(old.first().copy(exerciseId = "removed", atMs = now.minusSeconds(100).toEpochMilli())) }
            record(old.first().copy(grade = 1, correct = false, atMs = now.toEpochMilli()))
        }
        val logs = db.reviews().allLog()
        val states = db.reviews().allStates()
        db.changeGrammarGuided(settings, source, true)
        assertTrue(db.grammarStudy(settings, source, now).readiness.facets.isEmpty())
        assertNull(db.reviews().grammarReadinessStatus(source))
        db.rebuildGrammarReadiness(source, now)
        val earned = db.reviews().grammarReadiness(source, 1)
        assertEquals(setOf("a", "b"), earned.map { it.facetId }.toSet())
        assertFalse(db.grammarStudy(settings, source, now).readiness.rebuilding)
        assertNull(db.grammarStudy(settings, source, now).focus!!.candidates.single { it.group.id == "beta" }.blocked)
        assertEquals(logs, db.reviews().allLog())
        assertEquals(states, db.reviews().allStates())
        assertNull(db.reviews().grammarFocus(source))
        db.close()
        db = open()
        db.rebuildGrammarReadiness(source, now.plus(Duration.ofDays(50)))
        assertEquals(earned, db.reviews().grammarReadiness(source, 1))
        db.openHelper.writableDatabase.execSQL("DELETE FROM grammar_readiness WHERE facetId = 'a'")
        assertTrue(db.reviews().grammarReadinessStatus(source)!!.complete)
        db.rebuildGrammarReadiness(source, now)
        assertEquals(earned, db.reviews().grammarReadiness(source, 1))
        install("other/repo")
        db.rebuildGrammarReadiness("other/repo", now)
        assertTrue(db.grammarReadiness("other/repo", listOf(set, dependent)).readyGroups.isEmpty())
    }

    @Test
    fun aResetAtAQueryPageBoundaryCannotBeBypassedBySameTimestampPositives() = runBlocking {
        val events = familiarEvents(set, now).filter { it.constructionId == "a" }.sortedBy { it.atMs }
        db.withTransaction {
            repeat(999) { record(events.first()) }
            record(events.first().copy(grade = 2))
            record(events.first())
            record(events.last())
        }
        db.rebuildGrammarReadiness(source, now)
        assertTrue(db.reviews().grammarReadiness(source, GrammarReadiness.POLICY_VERSION).isEmpty())
        assertTrue(db.reviews().grammarReadinessStatus(source)!!.complete)
    }

    @Test
    fun liveUnguidedAnswersEarnAtomicallyWithoutAFocusTicketAndInvalidJoinsDoNot() = runBlocking {
        val good = familiarEvents(set, now).sortedBy { it.atMs }
        good.forEach { record(it.copy(identityMatches = false), live = true) }
        assertTrue(db.reviews().grammarReadiness(source, 1).isEmpty())
        good.forEach { record(it, live = true) }
        assertEquals(setOf("alpha"), db.grammarReadiness(source, listOf(set, dependent)).readyGroups)
        assertNull(db.reviews().grammarFocus(source))
        val earned = db.reviews().grammarReadiness(source, 1)
        record(good.first().copy(grade = 2, atMs = now.toEpochMilli()), live = true)
        assertEquals(earned, db.reviews().grammarReadiness(source, 1))
    }

    @Test
    fun liveResetQueuedDuringAPageCannotBeOverwrittenByThatRebuild() = runBlocking {
        val good = familiarEvents(set, now).filter { it.constructionId == "a" }.sortedBy { it.atMs }
        for (beforeCursor in listOf(false, true)) {
            val owner = "fixture/race-$beforeCursor"
            install(owner)
            db.withTransaction {
                record(good.first(), owner = owner)
                if (beforeCursor) repeat(1000) {
                    record(good.first().copy(exerciseId = "removed", atMs = good.first().atMs + 1), owner = owner)
                }
                record(good.last(), owner = owner)
            }
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            pageBarrier = entered to resume
            val rebuild = async(Dispatchers.Default) { db.rebuildGrammarReadiness(owner, now) }
            try {
                assertTrue("Rebuild must reach its paged history query", entered.await(30, TimeUnit.SECONDS))
                val failure = (if (beforeCursor) good.first() else good.last()).copy(grade = 2)
                // UNDISPATCHED reaches Room's serialized transaction queue before returning to this test.
                val reset = async(start = CoroutineStart.UNDISPATCHED) { record(failure, live = true, owner = owner) }
                assertFalse(reset.isCompleted)
                resume.countDown()
                withTimeout(30_000) { rebuild.await(); reset.await() }
            } finally {
                resume.countDown()
            }
            assertTrue("beforeCursor=$beforeCursor", db.grammarReadiness(owner, listOf(set, dependent)).facets.isEmpty())
            db.rebuildGrammarReadiness(owner, now)
            assertTrue(db.grammarReadiness(owner, listOf(set, dependent)).facets.isEmpty())
        }
    }

    @Test
    fun advancingTheClockCannotExpireEvidenceUsedToReconcileADelayedTimestamp() = runBlocking {
        val good = familiarEvents(set, now).filter { it.constructionId == "a" }.sortedBy { it.atMs }
        val first = good.first().copy(atMs = now.minus(Duration.ofDays(20)).toEpochMilli())
        val last = good.last().copy(atMs = now.toEpochMilli())
        for (delayDays in listOf(2L, 30L)) {
            val owner = "fixture/delayed-$delayDays"
            install(owner)
            record(first, live = true, owner = owner)
            record(last, live = true, owner = owner)
            val earned = db.reviews().grammarReadiness(owner, 1)
            assertEquals(now.toEpochMilli(), earned.single().attainedAtMs)
            val later = now.plus(Duration.ofDays(delayDays))
            record(last, live = true, owner = owner, evaluationNow = later)
            assertEquals(earned, db.reviews().grammarReadiness(owner, 1))
            db.openHelper.writableDatabase.execSQL("DELETE FROM grammar_readiness WHERE sourceId = ?", arrayOf(owner))
            db.rebuildGrammarReadiness(owner, later)
            assertEquals(earned, db.reviews().grammarReadiness(owner, 1))
            record(last.copy(grade = 2), live = true, owner = owner, evaluationNow = later)
            assertTrue(db.reviews().grammarReadiness(owner, 1).isEmpty())
            db.rebuildGrammarReadiness(owner, later)
            assertTrue(db.reviews().grammarReadiness(owner, 1).isEmpty())
        }
    }

    @Test
    fun delayedResetsKeepTheActualLaterProofRatherThanAnInvalidOldAttainmentTimestamp() = runBlocking {
        val good = familiarEvents(set, now).filter { it.constructionId == "a" }.sortedBy { it.atMs }
        val earlier = good.map { it.copy(atMs = it.atMs - Duration.ofDays(3).toMillis()) }
        (earlier + good).forEach { record(it, live = true) }
        assertEquals(earlier.last().atMs, db.reviews().grammarReadiness(source, 1).single().attainedAtMs)
        record(earlier.last().copy(grade = 2), live = true)
        assertEquals(good.last().atMs, db.reviews().grammarReadiness(source, 1).single().attainedAtMs)
        record(good.last().copy(grade = 2), live = true)
        assertTrue(db.grammarReadiness(source, listOf(set, dependent)).facets.isEmpty())
        db.rebuildGrammarReadiness(source, now)
        assertTrue(db.grammarReadiness(source, listOf(set, dependent)).facets.isEmpty())
    }

    @Test
    fun futureAnswersCannotMakeTheirOwnTimestampTheQualificationClock() = runBlocking {
        val good = familiarEvents(set, now).filter { it.constructionId == "a" }.sortedBy { it.atMs }
        record(good.first(), live = true)
        record(good.last().copy(atMs = now.toEpochMilli() + 1), live = true)
        assertTrue(db.grammarReadiness(source, listOf(set, dependent)).facets.isEmpty())
        db.rebuildGrammarReadiness(source, now)
        assertTrue(db.grammarReadiness(source, listOf(set, dependent)).facets.isEmpty())
    }

    @Test
    fun liveTimestampBatchesAgreeWithRebuildWithoutRevokingEarlierAttainment() = runBlocking {
        val good = familiarEvents(set, now).filter { it.constructionId == "a" }.sortedBy { it.atMs }
        for (scenario in listOf("positive-first", "reset-first", "later-reset")) {
            val owner = "fixture/$scenario"
            install(owner)
            val reset = good.last().copy(grade = 2, atMs = good.last().atMs + if (scenario == "later-reset") 1 else 0)
            val events = if (scenario == "reset-first") listOf(good.first(), reset, good.last()) else good + reset
            events.forEach { record(it, live = true, owner = owner) }
            val live = db.grammarReadiness(owner, listOf(set, dependent)).facets
            assertEquals(scenario, scenario == "later-reset", live.isNotEmpty())
            db.openHelper.writableDatabase.execSQL("DELETE FROM grammar_readiness WHERE sourceId = ?", arrayOf(owner))
            db.rebuildGrammarReadiness(owner, now)
            assertEquals(scenario, live, db.grammarReadiness(owner, listOf(set, dependent)).facets)
        }
    }

    @Test
    fun rebuildFailureIsExplicitAndRetryDoesNotInventCreditOrRewriteHistory() = runBlocking {
        familiarEvents(set, now).forEach { record(it) }
        val logs = db.reviews().allLog()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_backfill BEFORE INSERT ON grammar_readiness BEGIN SELECT RAISE(ABORT, 'synthetic rebuild failure'); END",
        )
        try {
            db.rebuildGrammarReadiness(source, now)
            fail("The rebuild must report storage failure")
        } catch (expected: android.database.sqlite.SQLiteException) {
            assertTrue(expected.message.orEmpty().contains("synthetic rebuild failure"))
        }
        val status = db.reviews().grammarReadinessStatus(source)!!
        assertFalse(status.complete)
        assertTrue(status.problem!!.contains("synthetic rebuild failure"))
        assertTrue(db.reviews().grammarReadiness(source, 1).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_backfill")
        db.rebuildGrammarReadiness(source, now)
        assertEquals(setOf("alpha"), db.grammarReadiness(source, listOf(set, dependent)).readyGroups)
        assertEquals(logs, db.reviews().allLog())
    }

    @Test
    fun changedContentMappingAbandonsTheOldScanBeforeSavingMilestones() = runBlocking {
        familiarEvents(set, now).forEach { record(it) }
        val changed = set.copy(introductionGroups = set.introductionGroups.map { group ->
            group.copy(members = group.members.map { it.copy(exampleKey = "new-${it.exampleKey}") })
        })
        val json = Json.encodeToString(GrammarSet.serializer(), changed).replace("'", "''")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER replace_mapping AFTER INSERT ON grammar_readiness_status WHEN NEW.complete = 0 " +
                "BEGIN UPDATE grammar_sets SET json = '$json' WHERE sourceId = NEW.sourceId AND id = 'alpha'; END",
        )
        db.rebuildGrammarReadiness(source, now)
        assertTrue(db.reviews().grammarReadiness(source, 1).isEmpty())
        assertFalse(db.reviews().grammarReadinessStatus(source)!!.complete)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER replace_mapping")
        db.rebuildGrammarReadiness(source, now)
        assertEquals(setOf("alpha"), db.grammarReadiness(source, listOf(changed, dependent)).readyGroups)
    }

    @Test
    fun aMilestoneWriteFailureRollsBackTheCanonicalAnswerAsWell() = runBlocking {
        val good = familiarEvents(set, now).filter { it.constructionId == "a" }.sortedBy { it.atMs }
        record(good.first(), live = true)
        val logs = db.reviews().allLog()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_readiness BEFORE INSERT ON grammar_readiness BEGIN SELECT RAISE(ABORT, 'synthetic readiness failure'); END",
        )
        try {
            record(good.last(), live = true)
            fail("The transaction must surface a failed milestone write")
        } catch (expected: android.database.sqlite.SQLiteException) {
            assertTrue(expected.message.orEmpty().contains("synthetic readiness failure"))
        }
        assertEquals(logs, db.reviews().allLog())
        assertEquals(1, db.reviews().allGrammarResponses().size)
        assertTrue(db.reviews().grammarReadiness(source, 1).isEmpty())
    }
}
