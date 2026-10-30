package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*
import kotlinx.serialization.json.*

fun taskFixture(name: String = "open-window"): ListeningTaskRecord {
    val id = "n5-task-$name"
    val text = requireNotNull(FixtureResources::class.java.getResourceAsStream("/listening-tasks/$id.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    return ListeningTaskContent.parse("listening-tasks/$id.json", text)
}

fun taskTone(): ByteArray = requireNotNull(FixtureResources::class.java.getResourceAsStream("/listening-test-tone.ogg"))
    .use { it.readBytes() }

fun approvedTask(
    packageDraft: ListeningTaskPackage = taskFixture().task, tone: ByteArray = taskTone(), durationMs: Long = 20_000,
    image: ByteArray? = null,
): ListeningTaskRecord {
    val draft = ListeningTaskContent.parse("listening-tasks/${packageDraft.id}.json",
        Json.encodeToString(ListeningTaskPackage.serializer(), packageDraft.copy(media = null, approval = null)))
    val hash = ContentParser.rawSha256(tone)
    val media = TaskMedia(draft.contentHash, draft.task.stages.associate { stage ->
        stage.id to TaskAudio("task-audio/test-tone.ogg", hash, durationMs, stage.turns.mapIndexed { index, id ->
            val chunk = durationMs / stage.turns.size
            ListeningTiming(id, index * chunk, (index + 1) * chunk)
        })
    }, image?.let { ListeningImage("task-images/test-picture.webp", ContentParser.rawSha256(it)) })
    val task = draft.task.copy(media = media, approval = TaskApproval(
        "approved", draft.contentHash, ListeningTaskContent.mediaHash(media), "2026-10-24",
        "Synthetic integration fixture only, not learning content or actual listening approval.",
    ))
    return ListeningTaskContent.parse("listening-tasks/${task.id}.json", Json.encodeToString(ListeningTaskPackage.serializer(), task))
}
