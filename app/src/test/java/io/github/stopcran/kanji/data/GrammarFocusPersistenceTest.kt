package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.grammarFixture
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GrammarCursor
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GrammarFocusPersistenceTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val databaseName = "grammar-focus-test.db"
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private val source get() = settings.source.value.id
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    private fun open() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).allowMainThreadQueries().build()

    @Before
    fun setUp() = runBlocking {
        context.deleteDatabase(databaseName)
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        settings = Settings(context)
        settings.setWeekPlan("FFFFFFF")
        settings.setGrammarParserEpoch(source, GRAMMAR_GROUP_EPOCH)
        db = open()
        db.changeGrammarGuided(settings, source, true)
        install(grammarFixture(), grammarFixture("beta"))
    }

    @After
    fun cleanUp() {
        db.close()
        context.deleteDatabase(databaseName)
    }

    private suspend fun install(vararg sets: GrammarSet) {
        db.content().insertGrammar(sets.mapIndexed { index, set ->
            GrammarEntity(source, set.id, Json.encodeToString(GrammarSet.serializer(), set), index)
        })
    }

    private suspend fun cursor() = db.reviews().grammarFocus(source)?.let { Json.decodeFromString(GrammarCursor.serializer(), it.json) }
    private suspend fun study(reserve: Boolean = false) = db.grammarStudy(settings, source, now, reserve)

    private suspend fun answer(study: GrammarStudy, index: Int = 0): String? {
        val id = requireNotNull(study.ticket).selected[index].exerciseId
        val exercise = study.sets.flatMap { it.exercises }.single { it.id == id }
        val state = Fsrs().review(study.states[id] ?: SrsState(), Grade.Good, now)
        return db.recordGrammarAnswer(
            settings, state.toEntity(source, GRAMMAR_STACK, id, StudyMode.Grammar),
            ReviewLogEntity(0, source, GRAMMAR_STACK, id, "Grammar", 3, now.toEpochMilli()),
            GrammarResponseEntity(0, source, GRAMMAR_STACK, id, exercise.target, exercise.relationship, "choose", exercise.answer, true),
            study.ticket,
        )
    }

    @Test
    fun previewsZeroBudgetAndExtraPracticeNeverReserveOrConsumeAutomaticSlots() = runBlocking {
        repeat(3) { assertEquals(4, study().focus!!.selected.size) }
        assertNull(cursor())
        settings.setDailyNewCards(0)
        assertTrue(study(reserve = true).focus!!.selected.isEmpty())
        assertNull(cursor())
        val extra = db.grammarStudy(settings, source, now, reserve = true, extra = true)
        assertEquals(12, extra.extraIds.size)
        assertNull(extra.ticket)
        assertNull(cursor())
    }

    @Test
    fun firstIntroductionAndQuotaPersistAcrossReopenWithoutExportingTheCursor() = runBlocking {
        val reserved = study(reserve = true)
        assertEquals(1L, cursor()!!.automaticOrdinal)
        assertFalse(cursor()!!.active!!.committed)
        db.close()
        db = open()
        val resumed = study(reserve = true)
        assertEquals(reserved.focus!!.activation!!.groupId, resumed.focus!!.activation!!.groupId)
        assertNotEquals(reserved.ticket!!.revision, resumed.ticket!!.revision)
        answer(resumed)
        assertEquals(2L, cursor()!!.automaticOrdinal)
        assertEquals(1, cursor()!!.active!!.introduced.size)
        val persisted = cursor()
        db.close()
        db = open()
        assertEquals(persisted, cursor())
        assertEquals(3, study().focus!!.selected.size)
        val bytes = ByteArrayOutputStream()
        ProgressExport.write(db, settings, bytes)
        val names = mutableSetOf<String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(
            setOf("export_manifest.json", "review_state.json", "review_log.json", "grammar_responses.json",
                "study_sessions.json", "listening_encounters.json", "settings.json"),
            names,
        )
    }

    @Test
    fun staleSessionAnswersPreserveSrsButCannotMutateANewerManualFocus() = runBlocking {
        val old = study(reserve = true)
        val other = if (old.ticket!!.groupId == "alpha") "beta" else "alpha"
        db.changeGrammarFocus(settings, source, other, now)
        val newer = db.reviews().grammarFocus(source)
        answer(old)
        assertEquals(newer, db.reviews().grammarFocus(source))
        assertEquals(1, db.reviews().allLog().size)
        assertEquals(1, db.reviews().allGrammarResponses().size)
        assertEquals(1, db.reviews().allStates().size)
        val manual = study(reserve = true)
        answer(manual)
        assertEquals(1L, cursor()!!.automaticOrdinal)
        assertEquals(1, cursor()!!.active!!.introduced.size)
    }

    @Test
    fun failedCursorWriteRollsBackTheWholeAnswerTransaction() = runBlocking {
        val reserved = study(reserve = true)
        val before = db.reviews().grammarFocus(source)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_focus_update BEFORE INSERT ON grammar_focus BEGIN SELECT RAISE(ABORT, 'synthetic cursor failure'); END",
        )
        try {
            answer(reserved)
            fail("The synthetic database failure must surface")
        } catch (expected: android.database.sqlite.SQLiteException) {
            assertTrue(expected.message.orEmpty().contains("synthetic cursor failure"))
        }
        assertTrue(db.reviews().allStates().isEmpty())
        assertTrue(db.reviews().allLog().isEmpty())
        assertTrue(db.reviews().allGrammarResponses().isEmpty())
        assertEquals(before, db.reviews().grammarFocus(source))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_focus_update")
        answer(reserved)
        assertEquals(2L, cursor()!!.automaticOrdinal)
        assertEquals(1, db.reviews().allLog().size)
    }

    @Test
    fun scopeAndContentChangesInvalidateSessionTokensWithoutResettingHistory() = runBlocking {
        val old = study(reserve = true)
        db.changeGrammarScope(settings, source, GrammarScope.N4)
        val afterScope = db.reviews().grammarFocus(source)
        answer(old)
        assertEquals(afterScope, db.reviews().grammarFocus(source))
        val newer = study(reserve = true)
        val rows = db.content().grammar(source)
        db.content().replaceContent(source, ContentRows(emptyList(), emptyList(), emptyList(), grammar = rows), SyncMetaEntity(source, "v2", 2, 0))
        val afterSync = db.reviews().grammarFocus(source)
        answer(newer)
        assertEquals(afterSync, db.reviews().grammarFocus(source))
        assertEquals(2, db.reviews().allStates().size)
        val history = db.reviews().allLog()
        db.changeGrammarGuided(settings, source, false)
        assertNull(cursor()!!.active)
        assertEquals(history, db.reviews().allLog())
        db.changeGrammarGuided(settings, source, true)
        assertEquals(1L, cursor()!!.automaticOrdinal)
    }

    @Test
    fun pendingMetadataAndMalformedCursorOrContentBlockOnlyGuidedNewIntake() = runBlocking {
        settings.setGrammarParserEpoch(source, GRAMMAR_LEVEL_EPOCH)
        assertTrue(study().ordinaryIds.isEmpty())
        assertTrue(study().notice!!.contains("Sync needed"))
        assertEquals(12, db.grammarStudy(settings, source, now, extra = true).extraIds.size)
        settings.setGrammarParserEpoch(source, GRAMMAR_GROUP_EPOCH)
        db.reviews().putGrammarFocus(GrammarFocusEntity(source, 0, """{"automaticOrdinal":-1}"""))
        assertTrue(study().ordinaryIds.isEmpty())
        assertTrue(study().notice!!.contains("Stored Grammar focus is invalid"))
        assertEquals("""{"automaticOrdinal":-1}""", db.reviews().grammarFocus(source)!!.json)
        db.content().putMeta(SyncMetaEntity(source, "partial", 1, 1, "grammar/broken.json: invalid requires"))
        assertTrue(study().notice!!.contains("content errors"))
        db.changeGrammarGuided(settings, source, false)
        assertEquals(12, study().ordinaryIds.size)
    }

    @Test
    fun dueOnlySessionsKeepReviewsAndReleaseIneligibleFocusWithoutConsumingAnActivation() = runBlocking {
        val reserved = study(reserve = true)
        answer(reserved)
        val before = cursor()!!
        db.changeGrammarScope(settings, source, GrammarScope.N5)
        val dueOnly = db.grammarStudy(settings, source, now.plusSeconds(172_800), reserve = true)
        assertEquals(listOf(reserved.ticket!!.selected.first().exerciseId), dueOnly.ordinaryIds)
        assertTrue(dueOnly.focus!!.selected.isEmpty())
        assertNull(dueOnly.ticket)
        assertEquals(before.automaticOrdinal, cursor()!!.automaticOrdinal)
        assertEquals(before.selectionNonce, cursor()!!.selectionNonce)
        assertNull(cursor()!!.active)
    }

    @Test
    fun sourceIsolationAndNewSessionReservationPreventCrossSessionQuotaWrites() = runBlocking {
        val old = study(reserve = true)
        val current = study(reserve = true)
        val reserved = db.reviews().grammarFocus(source)
        answer(old)
        assertEquals(reserved, db.reviews().grammarFocus(source))
        answer(current, index = 1)
        assertEquals(2L, cursor()!!.automaticOrdinal)
        assertEquals(1, cursor()!!.active!!.introduced.size)
        val firstSource = db.reviews().grammarFocus(source)
        val other = "other/repo"
        settings.setGrammarParserEpoch(other, GRAMMAR_GROUP_EPOCH)
        db.content().insertGrammar(db.content().grammar(source).map { it.copy(sourceId = other) })
        db.changeGrammarGuided(settings, other, true)
        val otherStudy = db.grammarStudy(settings, other, now, reserve = true)
        assertTrue(otherStudy.states.isEmpty())
        assertEquals(1L, Json.decodeFromString(GrammarCursor.serializer(), db.reviews().grammarFocus(other)!!.json).automaticOrdinal)
        assertEquals(firstSource, db.reviews().grammarFocus(source))
        db.changeGrammarScope(settings, other, GrammarScope.N5)
        db.changeGrammarGuided(settings, other, false)
        assertEquals(firstSource, db.reviews().grammarFocus(source))
        assertEquals(GrammarScope.All, settings.grammarFor(source).newScope)
    }
}
