package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

internal fun listeningPassage(id: String = "example") = ListeningPassage(
        1, id, 5, "monologue", "A message", "routine", "Short basic sentences.",
        listOf(ListeningSpeaker("a", "ja-JP-NanamiNeural", -10)),
        listOf(ListeningTurn("one", "a", "こんにちは。", "Hello.")),
        (0..2).map { number ->
            ListeningQuestion("q-$number", number == 0, "Question $number?",
                (0..3).map { ListeningOption("a-$it", "Choice $it", "Fixture rationale.") },
                "a-0", listOf("one"), "Fixture explanation.")
        },
    )

internal fun approvedListening(id: String = "example"): ListeningRecord {
    val p = listeningPassage(id)
    val hash = ListeningContent.parse("listening/$id.json", Json.encodeToString(ListeningPassage.serializer(), p)).contentHash
    val bytes = requireNotNull(ListeningTest::class.java.getResourceAsStream("/listening-test-tone.ogg")).use { it.readBytes() }
    val audioHash = ContentParser.rawSha256(bytes)
    val approved = p.copy(
        audio = ListeningAudio("audio/$id.ogg", audioHash, hash, 20_000, listOf(ListeningTiming("one", 250, 19_500))),
        audioReview = ListeningApproval("approved", audioHash, hash, "2026-10-22", "Synthetic test fixture only; not learning content."),
    )
    return ListeningContent.parse("listening/$id.json", Json.encodeToString(ListeningPassage.serializer(), approved))
}

class ListeningTest {
    @Test fun gradesAggregateMainPointAndAssistanceWithoutPromotingFailure() {
        val p = listeningPassage()
        val good = p.questions.associate { it.id to it.answer }
        assertEquals(Grade.Good, ListeningPolicy.grade(p, good, false).grade)
        assertEquals(Grade.Hard, ListeningPolicy.grade(p, good, true).grade)
        assertEquals(Grade.Hard, ListeningPolicy.grade(p, good + ("q-2" to "a-1"), false).grade)
        assertEquals(Grade.Again, ListeningPolicy.grade(p, good + ("q-0" to "a-1"), true).grade)
        assertEquals(Grade.Again, ListeningPolicy.grade(p, emptyMap(), false, giveUp = true).grade)
        assertThrows(IllegalArgumentException::class.java) { ListeningPolicy.grade(p, emptyMap(), false) }
    }

    @Test fun pacingRespectsTimeCountSharedBudgetWeekAndBacklog() {
        assertEquals(ListeningAllowance(2, 300_000), ListeningPolicy.allowance(5, DayLevel.Full, 0, 0, 0))
        assertEquals(ListeningAllowance(1, 150_000), ListeningPolicy.allowance(5, DayLevel.Light, 0, 0, 0))
        assertEquals(ListeningAllowance(0, 0), ListeningPolicy.allowance(5, DayLevel.Off, 0, 0, 0))
        assertEquals(0, ListeningPolicy.allowance(0, DayLevel.Full, 0, 0, 0).count)
        assertEquals(ListeningAllowance(1, 0), ListeningPolicy.allowance(5, DayLevel.Full, 1, 160_000, 450_000))
        assertEquals(0, ListeningPolicy.allowance(5, DayLevel.Full, 0, 0, 600_000).milliseconds)
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertEquals(now.plusSeconds(86_400), ListeningPolicy.scheduler.review(SrsState(), Grade.Again, now).due)
        assertEquals(now.plusSeconds(600), Fsrs().review(SrsState(), Grade.Again, now).due)
    }

    @Test fun canonicalHashAgreesWithPythonAndExcludesOnlyGeneratedFields() {
        val raw = Json.parseToJsonElement("""{"z":["日本語",{"b":2,"a":true}],"audio":{},"a":null,"audioReview":null}""").jsonObject
        assertEquals("3086a530afd0eb4dfe5523f94d04199542cc3d83f74e3ec4a78ffe6cd63350f1", ListeningContent.contentHash(raw))
        val changed = Json.parseToJsonElement("""{"z":["日本語",{"b":3,"a":true}],"a":null}""").jsonObject
        assertNotEquals(ListeningContent.contentHash(raw), ListeningContent.contentHash(changed))
    }

    @Test fun finalAudioMustBeCompleteAndHashBoundToTheApprovedQuestions() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/listening-test-tone.ogg")).use { it.readBytes() }
        assertEquals(20_000, OpusAudio.durationMs(bytes))
        val corrupt = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { OpusAudio.durationMs(corrupt) }
        assertThrows(IllegalArgumentException::class.java) { OpusAudio.durationMs(bytes.copyOf(bytes.size - 1)) }
        val p = listeningPassage()
        val draft = Json.encodeToString(ListeningPassage.serializer(), p)
        val hash = ListeningContent.parse("listening/example.json", draft).contentHash
        val audioHash = ContentParser.rawSha256(bytes)
        val rendered = p.copy(audio = ListeningAudio("audio/example.ogg", audioHash, hash, 20_000,
            listOf(ListeningTiming("one", 250, 19_500))))
        val approved = rendered.copy(audioReview = ListeningApproval("approved", audioHash, hash, "2026-10-22", "Test declaration only."))
        fun encode(value: ListeningPassage) = Json.encodeToString(ListeningPassage.serializer(), value)
        assertFalse(ListeningContent.parse("listening/example.json", encode(rendered)).ready)
        assertTrue(ListeningContent.parse("listening/example.json", encode(approved)).ready)
        assertThrows(IllegalArgumentException::class.java) {
            ListeningContent.parse("listening/example.json", encode(approved.copy(title = "Changed context")))
        }
        val text = encode(approved).toByteArray()
        val manifest = Manifest(1, "test", emptyMap(), listening = mapOf("listening/example.json" to ContentParser.sha256(text)),
            audio = mapOf("audio/example.ogg" to audioHash))
        assertTrue(ListeningContent.read(manifest, mapOf("listening/example.json" to text, "audio/example.ogg" to bytes)).records.single().ready)
        assertTrue(ListeningContent.read(manifest, mapOf("listening/example.json" to text)).records.isEmpty())
    }
}
