package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.listening.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun taskFixture(name: String = "open-window"): ListeningTaskRecord {
    val id = "n5-task-$name"
    val text = requireNotNull(ListeningTaskContentTest::class.java.getResourceAsStream("/listening-tasks/$id.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    return ListeningTaskContent.parse("listening-tasks/$id.json", text)
}

internal fun taskTone(): ByteArray = requireNotNull(ListeningTaskContentTest::class.java.getResourceAsStream("/listening-test-tone.ogg"))
    .use { it.readBytes() }

internal fun approvedTask(
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

class ListeningTaskContentTest {
    @Test fun allFourOriginalExamplesRetainTypeOrderAndDraftStatus() {
        val records = listOf("open-window", "rainy-class", "borrow-pencil", "park-invitation").map(::taskFixture)
        assertEquals(ListeningTaskType.entries.toList(), records.map { it.task.taskType })
        records.forEach { record ->
            assertFalse(record.ready)
            assertNull(record.estimatedMs)
            assertEquals((1..record.task.taskType.optionCount).toList(), record.task.options.map { it.number })
        }
        assertEquals(listOf(
            "2cc6a18530ee43db05da4b652fd52e7b49756ca7cbe12d57669df2916e0de6e0",
            "cb18354fbfe4f17fee38b3096f2659eaf5f00cc9b571ae1ba118d6713183f687",
            "8dfd632512947247a44f751c34106459e6cd301479e353fae8ab167573de9b3f",
            "e49baee157fb73aa58675e4d59da91dda1c9c8b0028fc2c4ad70ed9d9b1456a7",
        ), records.map { it.contentHash })
    }

    @Test fun strictTypesAndSupportedUnknownFieldsCannotChangePresentationSilently() {
        val draft = taskFixture()
        val raw = Json.parseToJsonElement(draft.json).jsonObject
        listOf(
            raw + ("jlpt" to JsonPrimitive("5")),
            raw + ("policyVersion" to JsonPrimitive("1")),
            raw + ("newGradingRule" to JsonPrimitive(true)),
            raw + ("media" to JsonNull),
            raw + ("options" to JsonArray(raw.getValue("options").jsonArray.mapIndexed { index, option ->
                if (index == 0) JsonObject(option.jsonObject + ("number" to JsonPrimitive("1"))) else option
            })),
        ).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { parse(JsonObject(changed).toString(), draft.task.id) }
        }
    }

    @Test fun fixedSpokenNumberingStageOrderAndStoryEvidenceAreValidated() {
        val task = taskFixture("park-invitation").task
        listOf(
            task.copy(options = task.options.reversed()),
            task.copy(stages = task.stages.reversed()),
            task.copy(evidence = task.stages.first().turns),
            task.copy(visual = TaskVisual("context", "A forbidden quick-response picture.")),
            task.copy(turns = task.turns.map { if (it.id == task.stages.last().turns.single()) it.copy(text = "3. changed") else it }),
        ).forEach { changed -> assertThrows(IllegalArgumentException::class.java) { parse(encode(changed), changed.id) } }
        val focused = taskFixture().task
        assertThrows(IllegalArgumentException::class.java) {
            parse(encode(focused.copy(stages = focused.stages.dropLast(1) + focused.stages.last().copy(turns = focused.stages.first().turns))),
                focused.id)
        }
    }

    @Test fun wholeMediaApprovalBindsEveryStageAndTiming() {
        val record = approvedTask()
        val task = record.task
        assertTrue(record.ready)
        assertEquals(80_000L, record.durationMs)
        assertEquals(185_000L, record.estimatedMs)
        assertEquals(1, record.assetHashes.size)
        val media = requireNotNull(task.media)
        val first = task.stages.first().id
        listOf(
            task.copy(title = "Changed context"),
            task.copy(media = media.copy(audio = media.audio - first)),
            task.copy(media = media.copy(audio = media.audio + (first to media.audio.getValue(first).copy(durationMs = 20_001)))),
            task.copy(approval = requireNotNull(task.approval).copy(reviewedOn = "2026-02-30")),
        ).forEach { changed -> assertThrows(IllegalArgumentException::class.java) { parse(encode(changed), changed.id) } }
        assertFalse(parse(encode(task.copy(approval = null)), task.id).ready)
        val tooLong = task.copy(approval = null, media = media.copy(audio = media.audio.mapValues { it.value.copy(durationMs = 90_000) }))
        assertThrows(IllegalArgumentException::class.java) { parse(encode(tooLong), tooLong.id) }
    }

    @Test fun newNamespacesIgnoreUnknownMediaAndPreserveLegacyCatalog() {
        val record = approvedTask()
        val text = record.json.toByteArray(Charsets.UTF_8)
        val audio = taskTone()
        val path = "listening-tasks/${record.task.id}.json"
        val unsupportedPath = "listening-tasks/future.json"
        val unsupported = """{"schemaVersion":2}""".toByteArray()
        val manifest = Manifest(1, "fixture", emptyMap(),
            listeningTasks = hashes(mapOf(path to ContentParser.sha256(text), unsupportedPath to ContentParser.sha256(unsupported))),
            listeningTaskAudio = hashes(mapOf("task-audio/test-tone.ogg" to ContentParser.rawSha256(audio),
                "task-audio/future.ogg" to "ignored-unreferenced-future-media")),
        )
        val files = mapOf(path to text, unsupportedPath to unsupported, "task-audio/test-tone.ogg" to audio)
        val catalog = ListeningTaskContent.read(manifest, files, emptySet(), emptySet()) { null }
        assertTrue(catalog.problems.toString(), catalog.problems.isEmpty())
        assertTrue(catalog.records.single().ready)
        assertEquals("future", catalog.unsupported.single().id)
        assertTrue(ListeningContent.read(manifest, files).records.isEmpty())
        assertTrue(ListeningContent.read(manifest, files).problems.isEmpty())
        val missing = ListeningTaskContent.read(manifest, files - "task-audio/test-tone.ogg", emptySet(), emptySet()) { null }
        assertEquals(1, missing.problems.size)
        assertEquals(1, missing.unsupported.size)
        val collision = ListeningTaskContent.read(manifest, files, emptySet(), setOf(record.task.id)) { null }
        assertTrue(collision.problems.single().contains("collides"))
    }

    @Test fun malformedNewManifestDoesNotThrowAwayUnrelatedContent() {
        val manifest = Manifest(1, "fixture", emptyMap(), listeningTasks = JsonPrimitive("broken"))
        val parsed = ContentParser.parse(mapOf("manifest.json" to Json.encodeToString(Manifest.serializer(), manifest).toByteArray()))
        assertEquals(1, parsed.listeningTasks.problems.size)
        assertTrue(parsed.listeningProblems.isEmpty())
        assertTrue(parsed.problems.isEmpty())
    }

    @Test fun newOpusEnvelopeDoesNotRelaxLegacyOrContainerIntegrity() {
        val bytes = taskTone()
        assertEquals(20_000L, OpusAudio.durationMs(bytes, 250, 90_000))
        assertThrows(IllegalArgumentException::class.java) { OpusAudio.durationMs(bytes, 250, 19_000) }
        assertThrows(IllegalArgumentException::class.java) { OpusAudio.durationMs(bytes.copyOf(bytes.size - 1), 250, 90_000) }
        assertThrows(IllegalArgumentException::class.java) { OpusAudio.durationMs(bytes, 0, Long.MAX_VALUE) }
        listOf("short" to 250L, "long" to 90_000L).forEach { (name, expected) ->
            val bounded = requireNotNull(javaClass.getResourceAsStream("/listening-$name-tone.ogg")).use { it.readBytes() }
            assertEquals(expected, OpusAudio.durationMs(bounded, 250, 90_000))
            assertThrows(IllegalArgumentException::class.java) { OpusAudio.durationMs(bounded) }
        }
    }

    private fun hashes(values: Map<String, String>) = JsonObject(values.mapValues { JsonPrimitive(it.value) })
    private fun encode(task: ListeningTaskPackage) = Json.encodeToString(ListeningTaskPackage.serializer(), task)
    private fun parse(text: String, id: String) = ListeningTaskContent.parse("listening-tasks/$id.json", text)
}
