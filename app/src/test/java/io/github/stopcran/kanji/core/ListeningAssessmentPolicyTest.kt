package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

internal fun assessmentAsset(name: String): ByteArray =
    requireNotNull(ListeningAssessmentPolicyTest::class.java.getResourceAsStream("/$name")).use { it.readBytes() }

internal fun assessmentMedia(record: ListeningTaskRecord, media: TaskMedia): ListeningTaskRecord {
    val task = record.task.copy(media = media, approval = requireNotNull(record.task.approval).copy(mediaSha256 = ListeningTaskContent.mediaHash(media)))
    return ListeningTaskContent.parse("listening-tasks/${task.id}.json", Json.encodeToString(task))
}

/** Repeated synthetic scripts exercise identity and protocol, not real story independence or listener approval. */
internal fun assessmentPool(longMs: Long = 20_000, profile: ListeningAssessmentProfile = ListeningAssessmentProfile.N5): List<AssessmentCandidate> {
    val templates = listOf("open-window", "rainy-class", "borrow-pencil", "park-invitation").map(::taskFixture)
    val short = assessmentAsset("listening-short-tone.ogg")
    return profile.groups.flatMapIndexed { groupIndex, group ->
        (0..group.pool).map { index ->
            val id = "n${profile.jlpt}-task-fixture-${group.type.wire}-$index"
            val draft = templates[groupIndex].task.copy(id = id, exposureId = id, blueprint = profile.id,
                schemaVersion = profile.schemaVersion, jlpt = profile.jlpt,
                usage = if (index == 0) TaskUsage.Example else TaskUsage.Scored, vocabulary = emptyList())
            val original = approvedTask(draft, durationMs = longMs,
                image = draft.visual?.let { assessmentAsset("listening-test-picture.webp") })
            val media = requireNotNull(original.task.media)
            val revised = media.copy(audio = media.audio.mapValues { (stageId, audio) ->
                val stage = draft.stages.single { it.id == stageId }
                if (stage.role != TaskStageRole.Choice) audio else TaskAudio(
                    "task-audio/short-tone.ogg", ContentParser.rawSha256(short), 250,
                    stage.turns.mapIndexed { turnIndex, turn ->
                        ListeningTiming(turn, turnIndex * 250L / stage.turns.size, (turnIndex + 1) * 250L / stage.turns.size)
                    })
            })
            AssessmentCandidate(assessmentMedia(original, revised))
        }
    }
}

class ListeningAssessmentPolicyTest {
    private val now = 100_000_000L

    @Test fun formHasFixedGroupsIndependentExamplesAndExactOccurrenceTiming() {
        val pool = assessmentPool()
        val selection = ListeningAssessmentBlueprint.select(pool, now, 123)
        assertTrue(selection.problems.toString(), selection.available)
        assertEquals(1_739_750L, selection.durationMs)
        assertEquals(28, selection.items.size)
        assertEquals(28, selection.items.map { it.record.task.exposureId }.distinct().size)
        assertEquals(listOf(8, 7, 6, 7), ListeningTaskType.entries.map { type -> selection.items.count { it.record.task.taskType == type } })
        assertEquals(listOf(0, 8, 15, 21), selection.items.indices.filter { selection.items[it].record.task.usage == TaskUsage.Example })
        assertEquals(selection, ListeningAssessmentBlueprint.select(pool.reversed(), now, 123))
        assertTrue((1L..8L).map { seed -> ListeningAssessmentBlueprint.select(pool, now, seed).items }.distinct().size > 1)
    }

    @Test fun gatesDoNotRelaxExamplesFamiliarityCooldownAccessibilityOrPoolSize() {
        val pool = assessmentPool()
        val type = ListeningTaskType.TaskBased
        val seen = pool.map { if (it.record.task.taskType == type) it.copy(lastExposureMs = now - ListeningPolicy.COOLDOWN_MS) else it }
        assertFalse(ListeningAssessmentBlueprint.select(seen, now, 1).available)
        assertTrue(ListeningAssessmentBlueprint.select(seen, now, 1, includeFamiliar = true).available)
        assertFalse(ListeningAssessmentBlueprint.select(seen, now - 1, 1, includeFamiliar = true).available)
        assertFalse(ListeningAssessmentBlueprint.select(pool, now, 1, visualAccess = false).available)
        assertFalse(ListeningAssessmentBlueprint.select(pool.drop(1), now, 1).available)
        assertFalse(ListeningAssessmentBlueprint.select(pool.filterNot { it.record.task.id.endsWith("task-based-8") }, now, 1).available)
        val broken = pool.map { if (it.record.task.taskType == type) it.copy(problem = "Missing media") else it }
        assertEquals(9, ListeningAssessmentBlueprint.select(broken, now, 1).groups.first().unavailable)
        assertFalse(ListeningAssessmentBlueprint.select(pool + pool.first(), now, 1).available)
    }

    @Test fun exactSelectedFormChecksInclusiveDurationEnvelope() {
        listOf(1_559_999L, 1_560_000L, 2_040_000L, 2_040_001L).forEach { target ->
            val pool = assessmentPool(if (target < 2_000_000) 17_000 else 24_800)
            val original = ListeningAssessmentBlueprint.select(pool, now, 1).durationMs!!
            val example = pool.first().record
            val media = requireNotNull(example.task.media)
            val instruction = example.task.stages.first().id
            val audio = media.audio.getValue(instruction)
            val revised = media.copy(audio = media.audio + (instruction to audio.copy(durationMs = audio.durationMs + target - original)))
            val selection = ListeningAssessmentBlueprint.select(listOf(AssessmentCandidate(assessmentMedia(example, revised))) + pool.drop(1), now, 1)
            assertEquals(target, selection.durationMs)
            assertEquals(target in 1_560_000L..2_040_000L, selection.available)
        }
    }

    @Test fun n4FormsUseOnlyTheirLevelAndReuseExamplesAfterCooldownWithoutReusingScoredStories() {
        val profile = ListeningAssessmentProfile.N4
        val n4 = assessmentPool(profile = profile)
        val pool = assessmentPool() + n4
        val first = profile.select(pool, now, 123)
        assertTrue(first.problems.toString(), first.available)
        assertEquals(32, first.items.size)
        assertEquals(listOf(9, 8, 6, 9), ListeningTaskType.entries.map { type -> first.items.count { it.record.task.taskType == type } })
        assertEquals(listOf(0, 9, 17, 23), first.items.indices.filter { first.items[it].record.task.usage == TaskUsage.Example })
        assertTrue(first.items.all { it.record.task.schemaVersion == 2 && it.record.task.jlpt == 4 })
        assertEquals(1_961_250L, first.durationMs)
        assertEquals(AssessmentDurationRange(1_961_250, 1_961_250), first.durationRange)
        assertEquals(550_000L, first.items.sumOf {
            val task = it.record.task
            ListeningAssessmentBlueprint.previewMs(task) + ListeningAssessmentBlueprint.answerMs(task) +
                if (task.usage == TaskUsage.Example) 10_000L else 0L
        })
        val ids = first.items.map { it.record.task.id }.toSet()
        val exposed = pool.map { if (it.record.task.id in ids) it.copy(lastExposureMs = now) else it }
        assertFalse(profile.select(exposed, now + ListeningPolicy.COOLDOWN_MS - 1, 456).available)
        val second = profile.select(exposed, now + ListeningPolicy.COOLDOWN_MS, 456)
        assertTrue(second.problems.toString(), second.available)
        assertEquals(0, second.familiarCount)
        assertEquals(4, second.items.count { it.lastExposureMs != null && it.record.task.usage == TaskUsage.Example })
        assertTrue(second.items.filter { it.record.task.usage == TaskUsage.Scored }.none { it.record.task.id in ids })
        val secondIds = second.items.map { it.record.task.id }.toSet()
        val bothExposed = exposed.map {
            if (it.record.task.id in secondIds) it.copy(lastExposureMs = now + ListeningPolicy.COOLDOWN_MS) else it
        }
        assertFalse(profile.select(bothExposed, now + 2 * ListeningPolicy.COOLDOWN_MS, 789).available)
        assertEquals(28, profile.select(bothExposed, now + 2 * ListeningPolicy.COOLDOWN_MS, 789, includeFamiliar = true).familiarCount)
        assertFalse(profile.select(pool.filterNot { it.record.task.id == n4[1].record.task.id }, now, 123).available)
        assertFalse(profile.select(pool, now, 123, visualAccess = false).available)
    }

    @Test fun n4RequiresExactApprovalAndIndependentCrossLevelStoryIdentities() {
        val profile = ListeningAssessmentProfile.N4
        val pool = assessmentPool(profile = profile)
        val unpublished = pool.map { candidate ->
            val task = candidate.record.task.copy(approval = null)
            AssessmentCandidate(ListeningTaskContent.parse("listening-tasks/${task.id}.json", Json.encodeToString(task)))
        }
        assertFalse(profile.select(unpublished, now, 1).available)
        val n5 = assessmentPool()
        val shared = approvedTask(pool.first().record.task.copy(exposureId = n5.first().record.task.exposureId),
            durationMs = 20_000, image = pool.first().record.task.visual?.let { assessmentAsset("listening-test-picture.webp") })
        val selection = profile.select(n5 + pool.drop(1) + AssessmentCandidate(shared), now, 1)
        assertFalse(selection.available)
        assertTrue(selection.problems.any { "another level" in it })
        assertTrue(ListeningAssessmentBlueprint.select(n5.map { it.copy(sharesLegacyStoryId = true) }, now, 1).available)
    }

    @Test fun n4RejectsUnselectedDurationOutliersInsteadOfAllowingALuckySeed() {
        val profile = ListeningAssessmentProfile.N4
        val pool = assessmentPool(profile = profile)
        val chosen = profile.select(pool, now, 123).items.map { it.record.task.id }.toSet()
        val changed = pool.map { candidate ->
            val task = candidate.record.task
            if (task.taskType != ListeningTaskType.QuickResponse || task.id in chosen) candidate else {
                val media = requireNotNull(task.media)
                val utterance = task.stages[1].id
                val revised = media.copy(audio = media.audio + (utterance to media.audio.getValue(utterance).copy(durationMs = 90_000)))
                AssessmentCandidate(assessmentMedia(candidate.record, revised))
            }
        }
        val selection = profile.select(changed, now, 123)
        assertFalse(selection.available)
        assertEquals(1_961_250L, selection.durationMs)
        assertEquals(AssessmentDurationRange(1_961_250, 2_521_250), selection.durationRange)
        assertTrue(selection.problems.any { "longest permitted" in it })
        assertEquals(chosen, selection.items.map { it.record.task.id }.toSet())
    }

    @Test fun n4DurationEnvelopeIsInclusiveToTheMillisecond() {
        val profile = ListeningAssessmentProfile.N4
        listOf(1_859_999L, 1_860_000L, 2_340_000L, 2_340_001L).forEach { target ->
            val pool = assessmentPool(if (target < 2_000_000) 18_000 else 25_000, profile)
            val original = profile.select(pool, now, 1).durationMs!!
            val example = pool.first().record
            val media = requireNotNull(example.task.media)
            val instruction = example.task.stages.first().id
            val audio = media.audio.getValue(instruction)
            val revised = media.copy(audio = media.audio + (instruction to audio.copy(durationMs = audio.durationMs + target - original)))
            val selection = profile.select(listOf(AssessmentCandidate(assessmentMedia(example, revised))) + pool.drop(1), now, 1)
            assertEquals(AssessmentDurationRange(target, target), selection.durationRange)
            assertEquals(target in 1_860_000L..2_340_000L, selection.available)
        }
    }

    @Test fun previewWaitsForVisibilityAndAnswersCannotAdvanceEarlyOrChangeAfterSkip() {
        val task = assessmentPool()[1].record.task
        var state = ListeningAssessmentPolicy.beginItem(AssessmentState(), task)
        assertEquals(AssessmentPhase.Preview, state.phase)
        assertNull(state.deadlineMs)
        state = ListeningAssessmentPolicy.windowVisible(state, 100)
        assertEquals(5_100L, state.deadlineMs)
        assertFalse(ListeningAssessmentPolicy.expired(state, 5_099))
        assertTrue(ListeningAssessmentPolicy.expired(state, 5_100))
        state = state.copy(phase = AssessmentPhase.Audio, stageIndex = task.stages.lastIndex, deadlineMs = null)
        state = ListeningAssessmentPolicy.audioCompleted(state, task, 10_000)
        state = ListeningAssessmentPolicy.choose(state, task, task.answer, 24_999)
        assertEquals(25_000L, state.deadlineMs)
        state = ListeningAssessmentPolicy.skip(state, 24_999)
        assertEquals(25_000L, state.deadlineMs)
        assertEquals(AssessmentOutcome.Unanswered, ListeningAssessmentPolicy.outcome(task, state, true))
        assertEquals(AssessmentOutcome.NotAssessed, ListeningAssessmentPolicy.outcome(task, state, false))
        assertThrows(IllegalArgumentException::class.java) { ListeningAssessmentPolicy.choose(state, task, task.answer, 24_999) }
    }

    @Test fun interruptionFreezesRemainingTimeAndRestorationNeverTrustsOldClock() {
        val task = assessmentPool()[1].record.task
        val state = AssessmentState(phase = AssessmentPhase.Answer, deadlineMs = 15_000, remainingMs = 15_000, answer = task.answer)
        val paused = ListeningAssessmentPolicy.interrupt(state, 4_000, "Headphones removed")
        assertEquals(11_000L, paused.remainingMs)
        val resumed = ListeningAssessmentPolicy.continuePractice(paused, task, 99_000)
        assertEquals(110_000L, resumed.deadlineMs)
        assertTrue(resumed.interrupted)
        assertEquals(task.answer, resumed.answer)
        val checkpoint = ListeningAssessmentPolicy.checkpoint(state, 5_000)
        val restored = ListeningAssessmentPolicy.interrupt(checkpoint, 9_000_000, "Process death", restoring = true)
        assertEquals(10_000L, restored.remainingMs)
        assertNull(restored.deadlineMs)
        assertEquals(10_100L, ListeningAssessmentPolicy.continuePractice(restored, task, 100).deadlineMs)
    }
}
