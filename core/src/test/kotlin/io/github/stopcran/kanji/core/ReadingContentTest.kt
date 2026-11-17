package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.SafeZip
import io.github.stopcran.kanji.core.readingtext.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ReadingContentTest {
    @Test fun reviewedN5PilotParsesWhenExplicitlyProvided() {
        val path = System.getenv("KANJI_CARDS_READING_PILOT")
        assumeTrue("Set KANJI_CARDS_READING_PILOT for unpublished pilot acceptance", path != null)
        val bytes = File(requireNotNull(path)).readBytes()
        val expected = requireNotNull(System.getenv("KANJI_CARDS_READING_PILOT_SHA256")) { "Pin the reviewed pilot hash" }
        assertEquals(expected, ContentParser.rawSha256(bytes))
        val content = ReadingContent.parse("reading/momotaro-n5.json", bytes.toString(Charsets.UTF_8)).content
        assertEquals(5, content.jlpt)
        assertEquals(8, content.sections.size)
        val count = content.sections.sumOf { it.text.codePointCount(0, it.text.length) }
        assertTrue("User-selected N5 pilot authoring target, not JLPT certification: $count", count in 1_000..1_500)
        assertEquals(setOf(1, 2, 3, 4), content.questions.map { it.tier }.toSet())
        assertTrue((1..4).all { tier -> content.questions.count { it.tier == tier } >= 4 })
        println("Reviewed unpublished pilot: sha256=$expected, codePoints=$count, sections=${content.sections.size}, questions=${content.questions.size}")
    }

    @Test fun reviewedPilotImportsFromTheCompleteIsolatedArchiveWhenExplicitlyProvided() {
        val path = System.getenv("KANJI_CARDS_READING_ARCHIVE")
        assumeTrue("Set KANJI_CARDS_READING_ARCHIVE for whole-archive pilot integration", path != null)
        val archive = File(requireNotNull(path))
        val hash = requireNotNull(System.getenv("KANJI_CARDS_READING_ARCHIVE_SHA256")) { "Pin the isolated archive hash" }
        assertEquals(hash, ContentParser.rawSha256(archive.readBytes()))
        val files = archive.inputStream().use { SafeZip.read(it) }
        assertTrue(files.containsKey("reading/momotaro-n5.json"))
        val imported = ContentParser.parse(files)
        assertTrue(imported.reading.problems.toString(), imported.reading.problems.isEmpty())
        val pilot = imported.reading.records.single { it.content.id == "momotaro-n5" }
        assertEquals(requireNotNull(System.getenv("KANJI_CARDS_READING_PILOT_SHA256")), pilot.hash)
        assertTrue(imported.kanji.isNotEmpty())
        assertTrue(imported.words.isNotEmpty())
        assertTrue(pilot.content.provenance.distribution.reviewed.isEmpty())
        (1..4).forEach { tier ->
            val quiz = ReadingQuiz.capture(pilot.content, pilot.hash, tier, ReadingBookMode.ClosedBook)
            assertEquals(4, quiz.highestCount)
            assertEquals(3, quiz.requiredCorrect)
            assertFalse(quiz.limitedPool)
            assertEquals(4 + tier - 1, quiz.questions.size)
        }
        println("Isolated whole-archive Reading import: sha256=$hash, entries=${files.size}, package=${pilot.hash}")
    }

    @Test fun contentOwnersFixtureUsesTheSharedSchema() {
        val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
        val parsed = ReadingContent.parse("reading/reading-fixture-peach.json", raw)
        assertEquals(2, parsed.content.sections.size)
        assertEquals("桃", parsed.content.sections.first().vocabulary.single().word)
        assertTrue(parsed.content.provenance.distribution.reviewed.isEmpty())
        listOf(
            raw.replace("\"jlpt\": 5", "\"jlpt\": \"5\""),
            raw.replace("\"title\": \"A small peach\"", "\"title\": \"A small peach\", \"unknown\": true"),
            raw.replace("\"word\": \"桃\"", "\"word\": null"),
        ).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { ReadingContent.parse("reading/reading-fixture-peach.json", changed) }
        }

    }

    @Test fun malformedOptionalCatalogCannotDestroyUnrelatedContent() {
        val manifest = Manifest(1, "fixture", emptyMap(), reading = JsonPrimitive("malformed"))
        val parsed = ContentParser.parse(mapOf("manifest.json" to Json.encodeToString(Manifest.serializer(), manifest).toByteArray()))
        assertTrue(parsed.reading.records.isEmpty())
        assertTrue(parsed.reading.problems.single().contains("namespace must be an object"))
        assertTrue(parsed.listeningProblems.isEmpty())
    }

    @Test fun malformedManifestSiblingOrUnreferencedMediaCannotDiscardValidText() {
        val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText().toByteArray()
        val path = "reading/reading-fixture-peach.json"
        val manifest = JsonObject(mapOf(path to JsonPrimitive(ContentParser.sha256(raw)), "reading/broken.json" to JsonPrimitive(42)))
        val media = JsonObject(mapOf("reading-images/unused.webp" to JsonPrimitive(42)))
        val catalog = ReadingContent.read(manifest, media, mapOf(path to raw)) { null }
        assertEquals(1, catalog.records.size)
        assertEquals(2, catalog.problems.size)
        assertTrue(catalog.problems.any { it.startsWith("reading/broken.json:") })
        assertTrue(catalog.problems.any { it.startsWith("reading-images/unused.webp:") })
        assertEquals(1, ReadingContent.read(manifest, JsonPrimitive(false), mapOf(path to raw)) { null }.records.size)
    }

    private val passage = ReadingPackage(
        1, "fixture", 5, "A short parser fixture", "山", "Parser-only original fixture, not level certification.",
        ReadingProvenance("山", ReadingAdaptation("Original test material, not the literary pilot.", "original"),
            listOf("Test authors"), ReadingRights("original-wording", "Original test material."),
            ReadingDistribution(emptyList(), "No jurisdiction reviewed for this fixture.")),
        listOf(ReadingSection("first", "山を見る。", listOf(ReadingOccurrence(0, 1, "山", "やま", "山", "mountain", "山")))),
        listOf(ReadingQuestion("fact", 1, "What is seen?",
            listOf(ReadingOption("mountain", "A mountain"), ReadingOption("sea", "The sea")),
            "mountain", "The text names a mountain.", listOf(ReadingEvidence("first", 0, 1)))),
    )

    @Test fun reviewedOccurrencesAndEvidenceUseCodePoints() {
        val section = ReadingSection("first", "𠮷は山を見る。", listOf(ReadingOccurrence(2, 3, "山", "やま", "山", "mountain")))
        val parsed = parse(passage.copy(sections = listOf(section)))
        assertEquals("山", ReadingContent.slice(parsed.content.sections.single().text, 2, 3))
        assertNull(parsed.content.sections.single().vocabulary.single().word)
    }

    @Test fun rejectsBadReferencesOverlapsAndSpoofedTypes() {
        val first = passage.sections.single()
        val question = passage.questions.single()
        val invalid = listOf(
            passage.copy(questions = listOf(question.copy(correctOptionId = "missing"))),
            passage.copy(questions = listOf(question.copy(evidence = listOf(ReadingEvidence("missing", 0, 1))))),
            passage.copy(sections = listOf(first.copy(vocabulary = first.vocabulary + first.vocabulary))),
            passage.copy(sections = listOf(first.copy(vocabulary = listOf(first.vocabulary.single().copy(text = "海"))))),
            passage.copy(questions = listOf(question.copy(options = question.options.map { it.copy(text = "same") }))),
        )
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { parse(value) } }
        val raw = encode(passage)
        assertThrows(IllegalArgumentException::class.java) {
            ReadingContent.parse("reading/fixture.json", raw.replace("\"jlpt\":5", "\"jlpt\":\"5\""))
        }
        assertThrows(IllegalArgumentException::class.java) { ReadingContent.parse("reading/wrong.json", raw) }
    }

    @Test fun invalidRequiredPictureRejectsOnlyItsPackageAndReportsFailure() {
        val image = byteArrayOf(1, 2, 3)
        val imageHash = ContentParser.rawSha256(image)
        val picture = ReadingPicture("reading-images/fixture.webp", imageHash, "Neutral description")
        val raw = encode(passage.copy(questions = passage.questions.map { it.copy(image = picture) })).toByteArray()
        val entries = mapOf("reading/fixture.json" to ContentParser.sha256(raw))
        val files = mapOf("reading/fixture.json" to raw, picture.path to image)
        val valid = ReadingContent.read(entries, mapOf(picture.path to imageHash), files) { null }
        assertEquals(1, valid.records.size)
        assertArrayEquals(image, valid.images.getValue(picture.path))
        val missing = ReadingContent.read(entries, mapOf(picture.path to imageHash), files - picture.path) { null }
        assertTrue(missing.records.isEmpty())
        assertTrue(missing.problems.single().contains("missing picture"))
        assertTrue(ReadingContent.read(entries, mapOf(picture.path to imageHash), files) { "bad WebP" }.records.isEmpty())
        assertTrue(ReadingContent.read(entries, emptyMap(), files) { null }.records.isEmpty())
    }

    @Test fun rejectsCombiningSequenceSplitsAndUnboundManifestHashes() {
        assertThrows(IllegalArgumentException::class.java) { ReadingContent.slice("か\u3099", 0, 1) }
        val raw = encode(passage).toByteArray()
        val result = ReadingContent.read(mapOf("reading/fixture.json" to "0".repeat(64)), emptyMap(),
            mapOf("reading/fixture.json" to raw)) { null }
        assertTrue(result.records.isEmpty())
        assertTrue(result.problems.single().contains("hash mismatch"))
    }

    @Test fun nonOriginalSourcesNeedEditionEvidenceAndRealAccessDates() {
        val original = passage.provenance
        val adapted = original.copy(adaptation = ReadingAdaptation("Adaptation", "traditional-plot"))
        assertThrows(IllegalArgumentException::class.java) { parse(passage.copy(provenance = adapted)) }
        val source = ReadingSource("昔話", "Named source edition", "https://example.org/source", "2026-02-30", "plot-check")
        assertThrows(IllegalArgumentException::class.java) {
            parse(passage.copy(provenance = adapted.copy(consulted = listOf(source))))
        }
        parse(passage.copy(provenance = adapted.copy(consulted = listOf(source.copy(accessed = "2026-02-28")))))
        assertThrows(IllegalArgumentException::class.java) {
            parse(passage.copy(provenance = adapted.copy(consulted = listOf(source.copy(accessed = "2026-02-28", role = "wording-source")))))
        }
    }

    private fun encode(value: ReadingPackage) = Json.encodeToString(ReadingPackage.serializer(), value)
    private fun parse(value: ReadingPackage) = ReadingContent.parse("reading/${value.id}.json", encode(value))
}
