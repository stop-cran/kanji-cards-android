package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarCurriculum
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GrammarAvailability
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarPractice
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

class GrammarPracticeTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val alpha = grammarFixture("alpha", examples = 2)
    private val beta = grammarFixture("beta", examples = 2, requires = listOf("alpha"))
    private val combined = alpha.copy(
        id = "combined", title = "Two learning groups", exercises = alpha.exercises + beta.exercises,
        introductionGroups = alpha.introductionGroups + beta.introductionGroups,
    )
    private val other = grammarFixture("other", examples = 2, authored = false)
    private val sets = listOf(combined, other)

    private fun target(kind: GrammarTargetKind = GrammarTargetKind.Group, id: String = "alpha") =
        GrammarPracticeTarget("owner/repo", "main", kind, id)

    @Suppress("LongParameterList")
    private fun preview(
        target: GrammarPracticeTarget = target(),
        sets: List<GrammarSet> = this.sets,
        states: Map<String, SrsState> = emptyMap(),
        scope: GrammarScope = GrammarScope.All,
        problem: String? = null,
        curriculum: GrammarCurriculumState = GrammarCurriculumState.Absent,
        limit: Int = 10,
    ) = GrammarPractice.preview(
        target, sets, curriculum,
        GrammarAvailability.evaluate(
            GrammarFocus.groups(sets), sets.flatMap { it.exercises }.associateBy { it.id }, states,
            scope, true, emptySet(), 0, now, newMetadataProblem = problem,
        ),
        "accepted-version", ContentParser.GRAMMAR_PARSER_EPOCH, scope, limit,
    )

    @Test
    fun exactGroupSetAndChapterMembershipNeverPadsWithUnrelatedDueItems() {
        GrammarPractice.validateSnapshot(sets)
        val states = mapOf(
            "other-a-0" to SrsState(CardPhase.Review, due = now.minusSeconds(86400)),
            "alpha-a-0" to SrsState(CardPhase.Review, due = now.minusSeconds(1)),
            "alpha-a-1" to SrsState(CardPhase.Review, due = now.plusSeconds(86400)),
        )
        val group = preview(states = states)
        assertEquals(alpha.exercises.map { it.id }, group.ids(false))
        assertEquals(4, group.initialCount(false))
        val queue = QueueBuilder.extra(group.ids(false), states, now, limit = group.limit, noise = 0.0)
        assertEquals("alpha-a-0", queue.first().kanji)
        assertEquals("alpha-a-1", queue.last().kanji)
        assertEquals(group.ids(false).toSet(), queue.map { it.kanji }.toSet())
        val set = preview(target(GrammarTargetKind.Set, "combined"), states = states)
        assertEquals(alpha.exercises.map { it.id }, set.ids(false))
        assertEquals(combined.exercises.map { it.id }, set.ids(true))
        val catalog = GrammarCurriculum.decode(
            """{"schemaVersion":1,"chapters":[{"id":"chapter","label":"Chapter","groups":["beta","alpha"]}]}""", sets,
        )
        val chapter = preview(target(GrammarTargetKind.Chapter, "chapter"), curriculum = catalog, limit = 3)
        assertEquals(alpha.exercises.map { it.id }, chapter.ids(false))
        assertEquals(combined.exercises.map { it.id }.toSet(), chapter.ids(true).toSet())
        assertEquals(3, chapter.initialCount(true))
        assertFalse(chapter.ids(true).any { it.startsWith("other-") })
        assertEquals(setOf("alpha"), chapter.foundations.keys)
    }

    @Test
    fun realMixedLevelGroupDoesNotInheritTheGuidedCoherenceLock() {
        val file = Paths.get("..", "..", "learning-japanese", "grammar", "conditionals-core.json").toFile()
        assumeTrue("Sibling content checkout required", file.exists())
        val set = ContentParser.parseGrammar("grammar/conditionals-core.json", file.readText())
        val own = set.introductionGroups.single { it.id == "conditionals-forms" }.members.map { member ->
            set.exercises.single { it.id == member.exerciseId }
        }
        val started = own.first { it.jlpt == 4 }.id
        val result = preview(
            target(id = "conditionals-forms"), sets = listOf(set),
            states = mapOf(started to SrsState(CardPhase.Review, due = now.minusSeconds(1))), scope = GrammarScope.N5,
        )
        assertTrue(result.ids(false).contains(started))
        assertTrue(result.included(false).filterNot { it.started }.all { it.exercise.jlpt == 5 })
        assertTrue(result.members.any { it.overridable && it.exercise.jlpt == 4 })
        assertEquals(own.map { it.id }.toSet(), result.ids(true).toSet())
    }

    @Test
    fun metadataProblemsCannotBeOverriddenButValidStartedReviewsRemainAvailable() {
        val result = preview(
            target(id = "beta"),
            states = mapOf("beta-a-0" to SrsState(CardPhase.Review, due = now.minusSeconds(1))),
            scope = GrammarScope.N5, problem = "Prerequisite metadata needs a sync.",
        )
        assertEquals(listOf("beta-a-0"), result.ids(false))
        assertEquals(result.ids(false), result.ids(true))
        assertEquals(3, result.members.count { it.problem != null })
        assertTrue(result.members.none { it.overridable })
    }

    @Test
    fun unavailableTargetsAndInvalidCachedGradingNeverBecomeGeneralPractice() {
        assertThrows(IllegalArgumentException::class.java) { preview(target(id = "missing")) }
        assertThrows(IllegalArgumentException::class.java) { preview(target(GrammarTargetKind.Set, "missing")) }
        for (state in listOf(
            GrammarCurriculumState.Absent, GrammarCurriculumState.PendingRefresh, GrammarCurriculumState.Invalid("Bad catalog"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { preview(target(GrammarTargetKind.Chapter, "chapter"), curriculum = state) }
        }
        val bad = alpha.copy(exercises = alpha.exercises.mapIndexed { index, exercise ->
            if (index == 0) exercise.copy(acceptedAnswers = emptyList()) else exercise
        })
        assertThrows(IllegalArgumentException::class.java) { GrammarPractice.validateSnapshot(listOf(bad)) }
        assertThrows(IllegalArgumentException::class.java) { GrammarPractice.validateSnapshot(listOf(beta)) }
    }
}
