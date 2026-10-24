package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.content.GrammarCurriculum
import io.github.stopcran.kanji.core.content.GrammarCurriculumData
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.SceneCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RealContentTest {
    @Test
    fun parsesRealContentRepoWhenPresent() {
        val root = File("../../learning-japanese")
        assumeTrue(File(root, "manifest.json").exists())
        val files = root.walkTopDown().filter { it.isFile && !it.path.contains(".git") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
        val parsed = ContentParser.parse(files)
        assertEquals(emptyList<String>(), parsed.problems)
        assertTrue(parsed.kanji.size >= 20)
        assertTrue(parsed.words.size >= 20)
        assertEquals(parsed.kanji.size, parsed.strokes.size)
        assertEquals(10, parsed.counters.size)
        assertEquals(120, parsed.counters.sumOf { it.cards.size })
        assertEquals(20, parsed.counters.sumOf { it.reverse.size })
        val j = Json { ignoreUnknownKeys = true }
        val again = parsed.counters.map { j.decodeFromString(CounterFile.serializer(), j.encodeToString(CounterFile.serializer(), it)) }
        assertEquals(parsed.counters, again)
        val index = io.github.stopcran.kanji.core.counters.CounterIndex(again)
        assertEquals(120, index.ids(io.github.stopcran.kanji.core.counters.CounterMode.Choose).size)
        val serializer = GrammarSet.serializer()
        val declared = j.decodeFromString(Manifest.serializer(), files.getValue("manifest.json").decodeToString())
        assertEquals(declared.images.keys, parsed.images.keys)
        assertEquals(declared.listening.keys, parsed.listening.map { "listening/${it.passage.id}.json" }.toSet())
        assertEquals(declared.audio.values.toSet(), parsed.audio.keys)
        assertTrue(parsed.listening.all { it.ready == (it.passage.audioReview != null) })
        val words = parsed.words.associateBy { it.word }
        for (path in declared.scenes.keys) {
            val expected = SceneCatalog.parse(path, files.getValue(path).decodeToString())
            val imported = words.getValue(expected.word)
            assertEquals(path, expected.scenes, imported.scenes)
            assertEquals(path, expected.scenes.firstOrNull { it.quizEligible }?.image, imported.quizImage)
        }
        val expectedGrammar = declared.files.keys.filter { it.startsWith("grammar/") }.map { path ->
            val text = files.getValue(path).decodeToString()
            val raw = j.parseToJsonElement(text).jsonObject
            val requirements = raw["foundationRequirements"]?.jsonObject?.get("requires")?.jsonObject.orEmpty()
            val set = j.decodeFromString(serializer, text)
            set.copy(introductionGroups = set.introductionGroups.map { group ->
                group.copy(requires = requirements[group.id]?.jsonArray?.map { it.jsonPrimitive.content } ?: group.requires)
            })
        }.sortedBy { it.id }
        assertTrue(expectedGrammar.isNotEmpty())
        assertEquals(expectedGrammar.size, expectedGrammar.map { it.id }.toSet().size)
        val exerciseIds = expectedGrammar.flatMap { it.exercises }.map { it.id }
        assertEquals(exerciseIds.size, exerciseIds.toSet().size)
        assertEquals(expectedGrammar, parsed.grammar.sortedBy { it.id })
        val grammarAgain = parsed.grammar.map { j.decodeFromString(serializer, j.encodeToString(serializer, it)) }
        assertEquals(parsed.grammar, grammarAgain)
        if (declared.curriculum.jsonObject.isEmpty()) {
            assertEquals(GrammarCurriculumState.Absent, parsed.curriculum)
        } else {
            val expected = j.decodeFromString(GrammarCurriculumData.serializer(), files.getValue(GrammarCurriculum.PATH).decodeToString())
            assertCurriculum(expected, parsed.curriculum as GrammarCurriculumState.Ready, expectedGrammar)
        }
    }

    private fun assertCurriculum(expected: GrammarCurriculumData, actual: GrammarCurriculumState.Ready, sets: List<GrammarSet>) {
        assertEquals(expected, actual.curriculum)
        val groups = sets.flatMap { it.introductionGroups }
        val owners = expected.chapters.flatMap { chapter -> chapter.groups.map { it to chapter.id } }.toMap()
        val leaves = groups.flatMap { group -> group.requires.map { it to group.id } }.toSet()
        assertEquals(owners, actual.graph.chapterByGroup)
        assertEquals(groups.map { it.id }.toSet() - owners.keys, actual.graph.unmappedGroups)
        assertEquals(leaves, actual.graph.leafEdges.map { it.foundation to it.dependent }.toSet())
        val chapterEdges = leaves.mapNotNull { leaf ->
            val foundation = owners[leaf.first]
            val dependent = owners[leaf.second]
            if (foundation == null || dependent == null || foundation == dependent) null
            else (foundation to dependent) to leaf
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        assertEquals(chapterEdges, actual.graph.chapterEdges.associate { edge ->
            (edge.foundation to edge.dependent) to edge.supportingEdges.map { it.foundation to it.dependent }.toSet()
        })
    }
}
