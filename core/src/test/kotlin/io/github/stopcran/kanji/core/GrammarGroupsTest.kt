package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarRecommendation
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.Manifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarGroupsTest {
    private fun snapshot(texts: Map<String, String>): io.github.stopcran.kanji.core.content.ParsedContent {
        val files = mapOf("articles/x.md" to "# Fixture\n".toByteArray()) +
            texts.map { (id, text) -> "grammar/$id.json" to text.toByteArray() }.toMap()
        val manifest = Manifest(1, "fixture", files.mapValues { ContentParser.sha256(it.value) })
        return ContentParser.parse(files + ("manifest.json" to Json.encodeToString(Manifest.serializer(), manifest).toByteArray()))
    }

    @Test
    fun newFoundationTransportPreservesLegacyImportWithoutLegacyRollingLocks() {
        val first = grammarFixture()
        val second = grammarFixture("beta")
        val raw = Json.encodeToJsonElement(GrammarSet.serializer(), second).jsonObject
        val text = JsonObject(raw + ("foundationRequirements" to Json.parseToJsonElement(
            """{"schemaVersion":1,"requires":{"beta":["alpha"]}}""",
        ))).toString()
        val parsed = snapshot(mapOf("alpha" to Json.encodeToString(GrammarSet.serializer(), first), "beta" to text))
        assertTrue(parsed.grammarProblems.toString(), parsed.grammarProblems.isEmpty())
        assertEquals(listOf("alpha"), parsed.grammar.single { it.id == "beta" }.introductionGroups.single().requires)
        val oldReader = Json { ignoreUnknownKeys = true }.decodeFromString(GrammarSet.serializer(), text)
        assertEquals(second, oldReader)
        assertTrue(oldReader.introductionGroups.single().requires.isEmpty())
    }

    @Test
    fun foundationPolicyRejectsMisspellingsVersionsTypesOwnershipAndInvalidLeafEdges() {
        val first = grammarFixture()
        val second = grammarFixture("beta")
        val raw = Json.encodeToJsonElement(GrammarSet.serializer(), second).jsonObject
        val invalid = listOf(
            "null", "[]", """{"schemaVersion":"1","requires":{}}""",
            """{"schemaVersion":1.0,"requires":{}}""", """{"schemaVersion":2,"requires":{}}""",
            """{"schemaVersion":1,"require":{}}""", """{"schemaVersion":1,"requires":null}""",
            """{"schemaVersion":1,"requires":{"alpha":[]}}""", """{"schemaVersion":1,"requires":{"beta":null}}""",
            """{"schemaVersion":1,"requires":{"beta":[2]}}""", """{"schemaVersion":1,"requires":{"beta":["beta"]}}""",
            """{"schemaVersion":1,"requires":{"beta":["absent"]}}""",
            """{"schemaVersion":1,"requires":{"beta":["alpha","alpha"]}}""",
        )
        for (policy in invalid) {
            val text = JsonObject(raw + ("foundationRequirements" to Json.parseToJsonElement(policy))).toString()
            val parsed = snapshot(mapOf("alpha" to Json.encodeToString(GrammarSet.serializer(), first), "beta" to text))
            assertEquals(policy, listOf("alpha"), parsed.grammar.map { it.id })
            assertTrue(policy, parsed.grammarProblems.isNotEmpty())
        }
        val legacy = second.copy(introductionGroups = second.introductionGroups.map { it.copy(requires = listOf("alpha")) })
        val both = JsonObject(Json.encodeToJsonElement(GrammarSet.serializer(), legacy).jsonObject +
            ("foundationRequirements" to Json.parseToJsonElement("""{"schemaVersion":1,"requires":{"beta":["alpha"]}}""")))
        assertTrue(snapshot(mapOf("beta" to both.toString())).grammar.isEmpty())
        val cycle = JsonObject(raw + ("foundationRequirements" to Json.parseToJsonElement(
            """{"schemaVersion":1,"requires":{"beta":["alpha"]}}""",
        )))
        assertTrue(snapshot(mapOf(
            "alpha" to Json.encodeToString(GrammarSet.serializer(), grammarFixture(requires = listOf("beta"))),
            "beta" to cycle.toString(),
        )).grammar.isEmpty())
    }

    @Test
    fun groupObjectsAreStrictAndMalformedMetadataCannotBecomeLegacyContent() {
        val set = grammarFixture()
        val raw = Json.encodeToJsonElement(GrammarSet.serializer(), set).jsonObject
        val group = raw.getValue("introductionGroups").jsonArray.single().jsonObject
        val mutations = listOf(
            JsonObject(group + ("id" to JsonPrimitive(3))),
            JsonObject(group + ("label" to JsonPrimitive(false))),
            JsonObject(group + ("require" to JsonArray(emptyList()))),
            JsonObject(group + ("requires" to JsonNull)),
            JsonObject(group + ("requires" to JsonArray(listOf(JsonObject(mapOf("groupId" to JsonPrimitive("beta"))))))),
            JsonObject(group + ("next" to JsonNull)),
            JsonObject(group + ("members" to JsonArray(emptyList()))),
            JsonObject(group + ("facets" to JsonArray(emptyList()))),
        )
        val fields = listOf(JsonNull, JsonPrimitive("groups")) + mutations.map { JsonArray(listOf(it)) }
        for (value in fields) {
            val parsed = snapshot(mapOf(set.id to JsonObject(raw + ("introductionGroups" to value)).toString()))
            assertTrue(parsed.grammar.isEmpty())
            assertTrue(parsed.grammarProblems.toString(), parsed.grammarProblems.isNotEmpty())
            assertEquals(1, parsed.articles.size)
        }
        for (weight in listOf(JsonPrimitive("3"), JsonPrimitive(true), JsonPrimitive(3.0), JsonPrimitive(0), JsonPrimitive(4))) {
            val link = JsonObject(mapOf("groupId" to JsonPrimitive("beta"), "importance" to weight, "reason" to JsonPrimitive("Reason")))
            val changed = JsonObject(group + ("next" to JsonArray(listOf(link))))
            val parsed = snapshot(mapOf(set.id to JsonObject(raw + ("introductionGroups" to JsonArray(listOf(changed)))).toString()))
            assertTrue(parsed.grammarProblems.toString(), parsed.grammar.isEmpty())
        }
    }

    @Test
    fun groupCoverageOwnershipAndLocalTargetsAreValidated() {
        val set = grammarFixture()
        val group = set.introductionGroups.single()
        val invalid = listOf(
            group.copy(members = group.members + group.members.first()),
            group.copy(members = group.members.map { it.copy(exampleKey = "same") }),
            group.copy(members = group.members.map { if (it.exerciseId == "alpha-a-0") it.copy(facetId = "b") else it }),
            group.copy(members = group.members.map { if (it.exerciseId == "alpha-a-0") it.copy(exerciseId = "other-file") else it }),
        )
        for (changed in invalid) {
            val text = Json.encodeToString(GrammarSet.serializer(), set.copy(introductionGroups = listOf(changed)))
            assertTrue(snapshot(mapOf(set.id to text)).grammarProblems.isNotEmpty())
        }
        val valid = snapshot(mapOf(set.id to Json.encodeToString(GrammarSet.serializer(), set)))
        assertEquals(listOf(set), valid.grammar)
        assertTrue(valid.grammarProblems.isEmpty())
    }

    @Test
    fun missingOptionalLinksWarnButDuplicateGroupsAndPrerequisiteChainsRejectFiles() {
        fun parse(vararg sets: GrammarSet) = snapshot(sets.associate { it.id to Json.encodeToString(GrammarSet.serializer(), it) })
        val first = grammarFixture()
        val optional = first.copy(introductionGroups = first.introductionGroups.map {
            it.copy(next = listOf(GrammarRecommendation("absent", 3, "Optional suggestion")))
        })
        val warned = parse(optional)
        assertEquals(1, warned.grammar.size)
        assertTrue(warned.grammar.single().introductionGroups.single().next.isEmpty())
        assertTrue(warned.problems.single().contains("missing optional"))
        assertTrue(warned.grammarProblems.isEmpty())
        val duplicate = grammarFixture("beta").let { it.copy(introductionGroups = it.introductionGroups.map { group -> group.copy(id = "alpha") }) }
        assertTrue(parse(first, duplicate).grammar.isEmpty())
        val missing = grammarFixture(requires = listOf("absent"))
        val dependent = grammarFixture("beta", requires = listOf("alpha"))
        val clean = grammarFixture("clean")
        assertEquals(listOf("clean"), parse(missing, dependent, clean).grammar.map { it.id })
        val cycle = parse(grammarFixture(requires = listOf("beta")), dependent, clean)
        assertEquals(listOf("clean"), cycle.grammar.map { it.id })
        assertEquals(2, cycle.grammarProblems.size)
        val recommendations = listOf(first, grammarFixture("beta")).mapIndexed { index, set ->
            set.copy(introductionGroups = set.introductionGroups.map {
                it.copy(next = listOf(GrammarRecommendation(if (index == 0) "beta" else "alpha", 1, "Comparison")))
            })
        }
        assertEquals(2, parse(*recommendations.toTypedArray()).grammar.size)
    }
}
