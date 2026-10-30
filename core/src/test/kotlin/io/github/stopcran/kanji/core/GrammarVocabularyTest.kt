package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.GrammarVocabulary
import io.github.stopcran.kanji.core.content.GrammarVocabularyData
import io.github.stopcran.kanji.core.content.GrammarVocabularySpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarVocabularyTest {
    private val prompt = "𠮷野の生物は____。生物を食べる。"
    private val data = GrammarVocabularyData(1, ContentParser.rawSha256(prompt.toByteArray()), listOf(
        GrammarVocabularySpan(0, 2, "𠮷野", "よしの", "𠮷野", "Yoshino"),
        GrammarVocabularySpan(3, 5, "生物", "せいぶつ", "生物", "living organisms", "生物"),
        GrammarVocabularySpan(11, 13, "生物", "なまもの", "生物", "raw food", "生物"),
    ))

    private fun parse(
        metadata: JsonElement? = Json.encodeToJsonElement(GrammarVocabularyData.serializer(), data),
        sourcePrompt: String = prompt,
    ): GrammarSet {
        val base = grammarFixture().let { set ->
            set.copy(exercises = set.exercises.mapIndexed { i, e -> if (i == 0) e.copy(prompt = sourcePrompt) else e })
        }
        val raw = Json.encodeToJsonElement(GrammarSet.serializer(), base).jsonObject
        val exercises = raw.getValue("exercises").jsonArray.mapIndexed { i, value ->
            if (i != 0 || metadata == null) value else JsonObject(value.jsonObject + ("vocabulary" to metadata))
        }
        return ContentParser.parseGrammar("grammar/alpha.json", JsonObject(raw + ("exercises" to JsonArray(exercises))).toString())
    }

    @Test
    fun repeatedReadingsAndSupplementaryCharactersKeepTheirExactOccurrencesWhenBlankIsFilled() {
        val exercise = parse().exercises.first()
        assertEquals(data, exercise.vocabulary)
        assertNull(exercise.vocabularyProblem)
        for (answer in listOf(null, "もの", "そういうものだった")) {
            val rendered = GrammarVocabulary.render(exercise, answer)
            assertEquals(0, rendered.ranges.first().start)
            assertEquals(3, rendered.ranges.first().end)
            assertEquals(listOf("𠮷野", "生物", "生物"), rendered.ranges.map { rendered.text.substring(it.start, it.end) })
            assertEquals(listOf("よしの", "せいぶつ", "なまもの"), rendered.ranges.map { it.span.reading })
        }
    }

    @Test
    fun malformedOptionalSupportIsDiagnosedWithoutLosingExercisesOrGroups() {
        val raw = Json.encodeToJsonElement(GrammarVocabularyData.serializer(), data).jsonObject
        val bad = listOf(
            JsonNull, JsonPrimitive("not metadata"), JsonObject(raw + ("schemaVersion" to JsonPrimitive("1"))),
            Json.encodeToJsonElement(GrammarVocabularyData.serializer(), data.copy(promptSha256 = "stale")),
            Json.encodeToJsonElement(GrammarVocabularyData.serializer(), data.copy(spans = data.spans.reversed())),
            Json.encodeToJsonElement(GrammarVocabularyData.serializer(), data.copy(spans = listOf(data.spans[0].copy(end = 1)))),
            Json.encodeToJsonElement(GrammarVocabularyData.serializer(), data.copy(spans = listOf(data.spans[0].copy(start = 6, end = 10, text = "____")))),
            JsonObject(raw + ("spans" to JsonArray(listOf(JsonObject(
                raw.getValue("spans").jsonArray.first().jsonObject + ("start" to JsonPrimitive("0")),
            ))))),
        )
        val plain = parse(null)
        assertNull(plain.exercises.first().vocabularyProblem)
        for (metadata in bad) {
            val parsed = parse(metadata)
            assertNotNull("$metadata", parsed.exercises.first().vocabularyProblem)
            assertEquals(plain, parsed.copy(exercises = parsed.exercises.map { it.copy(vocabularyProblem = null) }))
            val reopened = Json.decodeFromString(GrammarSet.serializer(), Json.encodeToString(GrammarSet.serializer(), parsed))
            assertEquals(parsed, reopened)
        }
    }

    @Test
    fun integerZeroAllowsItsNegativeSpellingButNotFractionsBooleansOrStrings() {
        val raw = Json.encodeToJsonElement(GrammarVocabularyData.serializer(), data).jsonObject
        for (value in listOf("-0", "0.0", "true", "\"0\"")) {
            val spans = raw.getValue("spans").jsonArray.mapIndexed { i, span ->
                if (i == 0) JsonObject(span.jsonObject + ("start" to Json.parseToJsonElement(value))) else span
            }
            val exercise = parse(JsonObject(raw + ("spans" to JsonArray(spans)))).exercises.first()
            if (value == "-0") {
                assertEquals(data, exercise.vocabulary)
            } else {
                assertNull(exercise.vocabulary)
                assertNotNull(exercise.vocabularyProblem)
            }
        }
    }

    @Test
    fun combiningSequencesAreKeptWholeAndPromptHashesDoNotNormalizeThem() {
        val decomposed = "か\u3099は____。"
        val span = GrammarVocabularySpan(0, 2, "か\u3099", "が", "が", "Synthetic vocabulary fixture")
        val hints = GrammarVocabularyData(1, ContentParser.rawSha256(decomposed.toByteArray()), listOf(span))
        fun annotated(value: GrammarVocabularyData, text: String = decomposed) =
            parse(Json.encodeToJsonElement(GrammarVocabularyData.serializer(), value), text).exercises.first()
        assertEquals(hints, annotated(hints).vocabulary)
        assertNotNull(annotated(hints, "がは____。").vocabularyProblem)
        for ((start, end) in listOf(0 to 1, 1 to 2)) {
            val split = span.copy(start = start, end = end, text = decomposed.substring(start, end))
            assertNotNull(annotated(hints.copy(spans = listOf(split))).vocabularyProblem)
        }
    }

    @Test
    fun unavailableReferencesKeepLocalGlossesButCannotLinkOrClaimDictionaryFamiliarity() {
        val set = parse()
        assertEquals(set, GrammarVocabulary.resolveWords(listOf(set), setOf("生物")).single())
        val resolved = GrammarVocabulary.resolveWords(listOf(set), emptySet()).single()
        val exercise = resolved.exercises.first()
        assertNotNull(exercise.vocabularyProblem)
        assertTrue(exercise.vocabulary!!.spans.all { it.word == null })
        assertEquals(data.spans.map { it.gloss }, exercise.vocabulary.spans.map { it.gloss })
        assertEquals(set.exercises.map { it.acceptedAnswers }, resolved.exercises.map { it.acceptedAnswers })
        assertFalse(exercise.vocabulary.spans.isEmpty())
    }
}
