package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarChapter
import io.github.stopcran.kanji.core.content.GrammarCurriculum
import io.github.stopcran.kanji.core.content.GrammarCurriculumData
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.content.GrammarRecommendation
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.Manifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarCurriculumTest {
    private val sets = listOf(grammarFixture(), grammarFixture("beta", requires = listOf("alpha")))
    private val partial = GrammarCurriculumData(1, listOf(GrammarChapter("later", "Later", listOf("beta"))))

    private fun catalog(data: GrammarCurriculumData) = Json.encodeToString(GrammarCurriculumData.serializer(), data)

    private fun parse(
        text: String? = catalog(partial),
        groups: List<GrammarSet> = sets,
        declaration: JsonElement? = null,
        omitCatalog: Boolean = false,
        inLegacyFiles: Boolean = false,
    ): io.github.stopcran.kanji.core.content.ParsedContent {
        val legacy = mapOf("articles/x.md" to "# Fixture\n".toByteArray()) +
            groups.associate { "grammar/${it.id}.json" to Json.encodeToString(GrammarSet.serializer(), it).toByteArray() }
        val bytes = text?.toByteArray()
        val files = legacy + if (bytes == null || omitCatalog) emptyMap() else mapOf(GrammarCurriculum.PATH to bytes)
        val hashes = (if (inLegacyFiles) files else legacy).mapValues { ContentParser.sha256(it.value) }
        val namespace = declaration ?: if (bytes == null) JsonObject(emptyMap()) else
            JsonObject(mapOf(GrammarCurriculum.PATH to JsonPrimitive(ContentParser.sha256(bytes))))
        val manifest = Manifest(1, "fixture", hashes, curriculum = namespace)
        return ContentParser.parse(files + ("manifest.json" to Json.encodeToString(Manifest.serializer(), manifest).toByteArray()))
    }

    @Test
    fun partialMappingRetainsAuthoredFoundationsAndLaterMappingOnlyChangesProjection() {
        val legacy = grammarFixture("legacy", authored = false)
        val source = sets + legacy
        val before = parse(groups = source)
        val ready = before.curriculum as GrammarCurriculumState.Ready
        assertEquals(source, before.grammar)
        assertEquals(setOf("alpha"), ready.graph.unmappedGroups)
        assertEquals(listOf(GrammarLeafEdge("alpha", "beta")), ready.graph.leafEdges)
        assertTrue(ready.graph.chapterEdges.isEmpty())

        val complete = partial.copy(chapters = partial.chapters + GrammarChapter("first", "First", listOf("alpha")))
        val after = parse(catalog(complete), source)
        val edge = (after.curriculum as GrammarCurriculumState.Ready).graph.chapterEdges.single()
        assertEquals("first", edge.foundation)
        assertEquals("later", edge.dependent)
        assertEquals(ready.graph.leafEdges, edge.supportingEdges)
        assertEquals(before.grammar, after.grammar)
        val internal = parse(catalog(GrammarCurriculumData(1, listOf(GrammarChapter("both", "Both", listOf("beta", "alpha"))))))
        assertTrue((internal.curriculum as GrammarCurriculumState.Ready).graph.chapterEdges.isEmpty())
        assertEquals(before.grammar, parse(text = null, groups = source).grammar)
    }

    @Test
    fun collapsedCycleIsOnlyARoadmapFailureButMissingLeafPrerequisitesStillRejectTheirFiles() {
        val chain = sets + grammarFixture("gamma", requires = listOf("beta"))
        val collapsed = GrammarCurriculumData(1, listOf(
            GrammarChapter("outside", "Outside", listOf("alpha", "gamma")),
            GrammarChapter("middle", "Middle", listOf("beta")),
        ))
        val parsed = parse(catalog(collapsed), chain)
        assertTrue((parsed.curriculum as GrammarCurriculumState.Invalid).reason.contains("cyclic chapter"))
        assertEquals(chain, parsed.grammar)
        assertTrue(parsed.grammarProblems.isEmpty())

        val missing = parse(groups = listOf(grammarFixture("beta", requires = listOf("alpha"))))
        assertTrue(missing.curriculum is GrammarCurriculumState.Invalid)
        assertTrue(missing.grammar.isEmpty())
        assertTrue(missing.grammarProblems.single().contains("missing/cyclic prerequisite"))
    }

    @Test
    fun directEdgesKeepAllSupportingPairsAndNeverInventATransitiveChapterArrow() {
        val source = listOf(
            grammarFixture(), grammarFixture("beta"),
            grammarFixture("gamma", requires = listOf("alpha", "beta")),
            grammarFixture("delta", requires = listOf("gamma")),
        )
        val all = GrammarCurriculumData(1, listOf(
            GrammarChapter("first", "First", listOf("alpha", "beta")),
            GrammarChapter("middle", "Middle", listOf("gamma")),
            GrammarChapter("last", "Last", listOf("delta")),
        ))
        val full = (parse(catalog(all), source).curriculum as GrammarCurriculumState.Ready).graph
        assertEquals(2, full.chapterEdges.size)
        assertEquals(listOf(GrammarLeafEdge("alpha", "gamma"), GrammarLeafEdge("beta", "gamma")),
            full.chapterEdges.first { it.foundation == "first" }.supportingEdges)
        val partial = (parse(catalog(all.copy(chapters = all.chapters.filter { it.id != "middle" })), source)
            .curriculum as GrammarCurriculumState.Ready).graph
        assertTrue(partial.chapterEdges.isEmpty())
        assertEquals(full.leafEdges, partial.leafEdges)
        val reversed = parse(catalog(all.copy(chapters = all.chapters.reversed())), source.reversed())
        assertEquals(full, (reversed.curriculum as GrammarCurriculumState.Ready).graph)
    }

    @Test
    fun malformedOptionalMetadataIsDiagnosedWithoutRejectingExercises() {
        val invalid = listOf(
            """{"schemaVersion":1}""",
            """{"schemaVersion":"1","chapters":[]}""",
            """{"schemaVersion":1.0,"chapters":[]}""",
            """{"schemaVersion":2,"chapters":[]}""",
            """{"schemaVersion":1,"chapters":null}""",
            """{"schemaVersion":1,"chapters":[],"requires":[]}""",
            """{"schemaVersion":1,"chapters":[{"id":"x","label":"X"}]}""",
            """{"schemaVersion":1,"chapters":[{"id":"x","label":"X","groups":[]}]}""",
            """{"schemaVersion":1,"chapters":[{"id":3,"label":"X","groups":["beta"]}]}""",
            """{"schemaVersion":1,"chapters":[{"id":"x","label":true,"groups":["beta"]}]}""",
            """{"schemaVersion":1,"chapters":[{"id":"x","label":"X","groups":["beta"],"requires":[]}]}""",
            """{"schemaVersion":1,"chapters":[{"id":"x","label":"X","groups":["missing"]}]}""",
            """{"schemaVersion":1,"chapters":[{"id":"x","label":"X","groups":["beta","beta"]}]}""",
            catalog(GrammarCurriculumData(1, listOf(
                GrammarChapter("x", "X", listOf("alpha")), GrammarChapter("x", "Again", listOf("beta")),
            ))),
            catalog(GrammarCurriculumData(1, listOf(
                GrammarChapter("x", "X", listOf("beta")), GrammarChapter("y", "Y", listOf("beta")),
            ))),
        )
        invalid.forEach { text ->
            val parsed = parse(text)
            assertTrue(text, parsed.curriculum is GrammarCurriculumState.Invalid)
            assertEquals(sets, parsed.grammar)
            assertTrue(parsed.grammarProblems.isEmpty())
            assertTrue(parsed.problems.isNotEmpty())
        }
        assertTrue(parse(text = null).curriculum is GrammarCurriculumState.Absent)
        val empty = parse("""{"schemaVersion":1,"chapters":[]}""").curriculum as GrammarCurriculumState.Ready
        assertEquals(setOf("alpha", "beta"), empty.graph.unmappedGroups)
    }

    @Test
    fun catalogTransportFailuresStaySeparateFromGrammarFailures() {
        val invalid = listOf(
            parse(declaration = JsonNull),
            parse(declaration = JsonPrimitive("catalog")),
            parse(declaration = JsonObject(mapOf("../grammar.json" to JsonPrimitive("0".repeat(64))))),
            parse(declaration = JsonObject(mapOf(GrammarCurriculum.PATH to JsonPrimitive(1)))),
            parse(declaration = JsonObject(mapOf(GrammarCurriculum.PATH to JsonPrimitive("0".repeat(64))))),
            parse(omitCatalog = true),
            parse(inLegacyFiles = true),
        )
        invalid.forEach { parsed ->
            assertTrue(parsed.curriculum.toString(), parsed.curriculum is GrammarCurriculumState.Invalid)
            assertEquals(sets, parsed.grammar)
            assertTrue(parsed.grammarProblems.isEmpty())
        }
        val crlf = catalog(partial).replace("\"chapters\"", "\r\n\"chapters\"")
        assertTrue(parse(crlf).curriculum is GrammarCurriculumState.Ready)
    }

    @Test
    fun recommendationCyclesNeverBecomeChapterEdges() {
        val source = listOf(grammarFixture(), grammarFixture("beta")).mapIndexed { index, set ->
            set.copy(introductionGroups = set.introductionGroups.map {
                it.copy(next = listOf(GrammarRecommendation(if (index == 0) "beta" else "alpha", 3, "Helpful")))
            })
        }
        val parsed = parse(groups = source)
        assertEquals(source, parsed.grammar)
        val graph = (parsed.curriculum as GrammarCurriculumState.Ready).graph
        assertTrue(graph.leafEdges.isEmpty())
        assertTrue(graph.chapterEdges.isEmpty())
    }
}
