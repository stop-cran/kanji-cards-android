package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.*
import kotlinx.serialization.json.Json

fun listeningPassage(id: String = "example") = ListeningPassage(
        1, id, 5, "monologue", "A message", "routine", "Short basic sentences.",
        listOf(ListeningSpeaker("a", "ja-JP-NanamiNeural", -10)),
        listOf(ListeningTurn("one", "a", "こんにちは。", "Hello.")),
        (0..2).map { number ->
            ListeningQuestion("q-$number", number == 0, "Question $number?",
                (0..3).map { ListeningOption("a-$it", "Choice $it", "Fixture rationale.") },
                "a-0", listOf("one"), "Fixture explanation.")
        },
    )

fun approvedListening(id: String = "example"): ListeningRecord {
    val p = listeningPassage(id)
    val hash = ListeningContent.parse("listening/$id.json", Json.encodeToString(ListeningPassage.serializer(), p)).contentHash
    val bytes = requireNotNull(FixtureResources::class.java.getResourceAsStream("/listening-test-tone.ogg")).use { it.readBytes() }
    val audioHash = ContentParser.rawSha256(bytes)
    val approved = p.copy(
        audio = ListeningAudio("audio/$id.ogg", audioHash, hash, 20_000, listOf(ListeningTiming("one", 250, 19_500))),
        audioReview = ListeningApproval("approved", audioHash, hash, "2026-10-22", "Synthetic test fixture only; not learning content."),
    )
    return ListeningContent.parse("listening/$id.json", Json.encodeToString(ListeningPassage.serializer(), approved))
}
