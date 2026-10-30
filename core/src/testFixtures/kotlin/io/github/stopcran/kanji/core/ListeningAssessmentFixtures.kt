package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun assessmentAsset(name: String): ByteArray =
    requireNotNull(FixtureResources::class.java.getResourceAsStream("/$name")).use { it.readBytes() }

fun assessmentMedia(record: ListeningTaskRecord, media: TaskMedia): ListeningTaskRecord {
    val task = record.task.copy(media = media, approval = requireNotNull(record.task.approval).copy(mediaSha256 = ListeningTaskContent.mediaHash(media)))
    return ListeningTaskContent.parse("listening-tasks/${task.id}.json", Json.encodeToString(task))
}

/** Repeated synthetic scripts exercise identity and protocol, not real story independence or listener approval. */
fun assessmentPool(longMs: Long = 20_000, profile: ListeningAssessmentProfile = ListeningAssessmentProfile.N5): List<AssessmentCandidate> {
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
