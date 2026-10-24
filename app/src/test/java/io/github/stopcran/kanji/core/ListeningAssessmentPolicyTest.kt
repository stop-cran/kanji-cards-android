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
internal fun assessmentPool(longMs: Long = 20_000): List<AssessmentCandidate> {
    val templates = listOf("open-window", "rainy-class", "borrow-pencil", "park-invitation").map(::taskFixture)
    val short = assessmentAsset("listening-short-tone.ogg")
    return ListeningAssessmentBlueprint.groups.flatMapIndexed { groupIndex, group ->
        (0..group.pool).map { index ->
            val id = "n5-task-fixture-${group.type.wire}-$index"
            val draft = templates[groupIndex].task.copy(id = id, exposureId = id, blueprint = ListeningAssessmentBlueprint.ID,
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
