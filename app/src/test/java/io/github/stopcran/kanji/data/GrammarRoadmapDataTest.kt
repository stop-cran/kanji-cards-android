package io.github.stopcran.kanji.data

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarCurriculum
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GrammarReadiness
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammarFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
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
class GrammarRoadmapDataTest {
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val source get() = settings.source.value
    private val sets = listOf(grammarFixture("p"), grammarFixture("q", requires = listOf("p")), grammarFixture("legacy", authored = false))
    private val partial = """{"schemaVersion":1,"chapters":[{"id":"later","label":"Later","groups":["q"]}]}"""

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        settings = Settings(context)
        settings.setGrammarParserEpoch(source.id, ContentParser.GRAMMAR_PARSER_EPOCH)
        settings.setGrammarBrowseScope(source.id, GrammarScope.N5)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            db.content().insertGrammar(sets.mapIndexed { index, set ->
                GrammarEntity(source.id, set.id, Json.encodeToString(GrammarSet.serializer(), set), index)
            })
            db.content().putMeta(SyncMetaEntity(source.id, "v1", now.toEpochMilli(), 0, grammarCurriculumJson = partial))
        }
    }

    @After
    fun cleanup() = db.close()

    @Test
    fun unmappedAuthoredFoundationSurvivesFilteringAndRemappingWithoutAnyLearningWrites() = runBlocking<Unit> {
        val before = db.grammarRoadmap(settings, source, now)
        val p = before.groups.getValue("p")
        val q = before.groups.getValue("q")
        assertEquals(0, p.matching)
        assertTrue(p.group.authored)
        assertTrue(q.newNeedsFoundation)
        assertEquals(listOf("p"), q.availability.missingPrerequisites)
        assertTrue((before.curriculum as GrammarCurriculumState.Ready).graph.chapterEdges.isEmpty())
        assertFalse(before.groups.values.single { !it.group.authored }.ready)
        val exercise = sets.first().exercises.first()
        db.reviews().recordGrammar(
            ReviewStateEntity(source.id, GRAMMAR_STACK, exercise.id, "Grammar", "Review", 3.0, 5.0,
                now.minusSeconds(60).toEpochMilli(), now.minusSeconds(8640000).toEpochMilli(), 1, 0),
            ReviewLogEntity(0, source.id, GRAMMAR_STACK, exercise.id, "Grammar", 3, now.minusSeconds(8640000).toEpochMilli()),
            GrammarResponseEntity(0, source.id, GRAMMAR_STACK, exercise.id, exercise.target, exercise.relationship, "type", exercise.target, true),
        )
        db.reviews().insertGrammarReadiness(sets.first().introductionGroups.single().facets.map {
            GrammarReadinessEntity(source.id, "p", it.id, GrammarReadiness.POLICY_VERSION, now.minusSeconds(8640000).toEpochMilli())
        })
        val earned = db.grammarRoadmap(settings, source, now)
        assertTrue(earned.groups.getValue("p").ready)
        assertFalse(earned.groups.getValue("p").evidence.familiar)
        assertFalse(earned.groups.getValue("q").newNeedsFoundation)
        val full = """{"schemaVersion":1,"chapters":[{"id":"first","label":"First","groups":["p"]},{"id":"later","label":"Later","groups":["q"]}]}"""
        db.content().putMeta(requireNotNull(db.content().meta(source.id)).copy(grammarCurriculumJson = full))
        val after = db.grammarRoadmap(settings, source, now)
        assertEquals(earned.groups, after.groups)
        assertEquals(1, (after.curriculum as GrammarCurriculumState.Ready).graph.chapterEdges.size)
        assertEquals(1, db.reviews().allStates().size)
        assertEquals(1, db.reviews().allLog().size)
        assertTrue(db.reviews().allSessions().isEmpty())
        assertEquals(2, db.reviews().grammarReadiness(source.id, GrammarReadiness.POLICY_VERSION).size)
        assertNull(db.reviews().grammarFocus(source.id))
        assertNull(db.reviews().grammarReadinessStatus(source.id))
    }

    @Test
    fun invalidOrMissingCatalogKeepsValidGroupsAndOldLevelMetadataIsNotReportedAsZeroMatches() = runBlocking<Unit> {
        val meta = requireNotNull(db.content().meta(source.id))
        db.content().putMeta(meta.copy(grammarCurriculumProblem = "Invalid chapter cycle"))
        val invalid = db.grammarRoadmap(settings, source, now)
        assertTrue(invalid.curriculum is GrammarCurriculumState.Invalid)
        assertEquals(3, invalid.groups.size)
        db.content().putMeta(meta.copy(grammarCurriculumJson = null))
        assertEquals(GrammarCurriculumState.Absent, db.grammarRoadmap(settings, source, now).curriculum)
        settings.setGrammarParserEpoch(source.id, 0)
        val pending = db.grammarRoadmap(settings, source, now)
        assertEquals(GrammarCurriculumState.PendingRefresh, pending.curriculum)
        assertTrue(pending.groups.values.all { it.matching == null })
        assertThrows(IllegalArgumentException::class.java) { GrammarCurriculum.decode(partial, sets.drop(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { db.grammarRoadmap(settings, source.copy(branch = "another"), now) }
        }
    }
}
