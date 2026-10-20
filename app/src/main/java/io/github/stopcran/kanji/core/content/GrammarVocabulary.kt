package io.github.stopcran.kanji.core.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
data class GrammarVocabularyData(val schemaVersion: Int, val promptSha256: String, val spans: List<GrammarVocabularySpan>)

/** Half-open Unicode-code-point offsets in the original prompt, before replacing its blank. */
@Serializable
data class GrammarVocabularySpan(
    val start: Int,
    val end: Int,
    val text: String,
    val reading: String,
    val headword: String,
    val gloss: String,
    val word: String? = null,
)

data class GrammarVocabularyRange(val span: GrammarVocabularySpan, val start: Int, val end: Int)
data class GrammarVocabularyText(val text: String, val ranges: List<GrammarVocabularyRange>)

object GrammarVocabulary {
    const val PARSER_EPOCH = 3
    private const val MAX_SPANS = 128
    private const val MAX_FORM = 80
    private const val MAX_GLOSS = 240
    private val json = Json
    private val integer = Regex("-?0|[1-9][0-9]*")
    private val fields = setOf("start", "end", "text", "reading", "headword", "gloss")

    /** Strip optional support before decoding the required exercise, including any spoofed cached diagnostic. */
    fun withoutSupport(raw: JsonObject): JsonObject = JsonObject(raw + (
        "exercises" to JsonArray((raw["exercises"] as? JsonArray).orEmpty().map { item ->
            JsonObject(item.jsonObject - setOf("vocabulary", "vocabularyProblem"))
        })
    ))

    fun attach(set: GrammarSet, raw: JsonObject): GrammarSet {
        val exercises = raw.getValue("exercises") as JsonArray
        return set.copy(exercises = set.exercises.mapIndexed { index, exercise ->
            val input = exercises[index].jsonObject
            if ("vocabulary" in input) decode(exercise, input.getValue("vocabulary")) else exercise
        })
    }

    private fun decode(exercise: GrammarExercise, raw: JsonElement): GrammarExercise = try {
        val obj = raw as? JsonObject ?: errorValue("vocabulary must be an object, or omitted")
        require(obj.keys == setOf("schemaVersion", "promptSha256", "spans")) { "invalid vocabulary fields" }
        require(number(obj["schemaVersion"]) == "1") { "unsupported vocabulary schema" }
        require((obj["promptSha256"] as? JsonPrimitive)?.isString == true) { "prompt hash must be text" }
        val spans = obj["spans"] as? JsonArray ?: errorValue("spans must be an array")
        require(spans.size <= MAX_SPANS) { "too many vocabulary spans" }
        spans.forEach(::validateSpanShape)
        val value = json.decodeFromJsonElement(GrammarVocabularyData.serializer(), raw)
        validate(exercise.prompt, value)
        exercise.copy(vocabulary = value)
    } catch (e: IllegalArgumentException) {
        exercise.copy(vocabulary = null, vocabularyProblem = "Vocabulary hints disabled: ${e.message.orEmpty().take(500)}")
    }

    private fun errorValue(message: String): Nothing = throw IllegalArgumentException(message)

    private fun validateSpanShape(entry: JsonElement) {
        val span = entry as? JsonObject ?: errorValue("span must be an object")
        require(span.keys - "word" == fields) { "invalid span fields" }
        require(number(span["start"]) != null && number(span["end"]) != null) { "offsets must be nonnegative integers" }
        for (key in listOf("text", "reading", "headword", "gloss") + if ("word" in span) listOf("word") else emptyList()) {
            require((span[key] as? JsonPrimitive)?.isString == true) { "$key must be text" }
        }
    }

    private fun number(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { !it.isString && integer.matches(it.content) }?.content

    private fun validate(prompt: String, data: GrammarVocabularyData) {
        require(data.promptSha256 == ContentParser.rawSha256(prompt.toByteArray(Charsets.UTF_8))) { "vocabulary belongs to a different prompt revision" }
        val length = prompt.codePointCount(0, prompt.length)
        val blank = prompt.indexOf("____")
        var previousEnd = 0
        for (span in data.spans) {
            require(span.start >= previousEnd && span.end > span.start && span.end <= length) { "invalid, overlapping or unsorted span" }
            val start = prompt.offsetByCodePoints(0, span.start)
            val end = prompt.offsetByCodePoints(0, span.end)
            require(prompt.substring(start, end) == span.text) { "span text does not match its occurrence" }
            require(end <= blank || start >= blank + 4) { "span overlaps the grammar blank" }
            require(!combiningAt(prompt, start) && !combiningAt(prompt, end)) { "span splits a combining sequence" }
            require(listOf(span.text, span.reading, span.headword).all { it.isNotBlank() && it.codePointCount(0, it.length) <= MAX_FORM }) {
                "text, reading and headword must contain 1-$MAX_FORM characters"
            }
            require(span.gloss.isNotBlank() && span.gloss.codePointCount(0, span.gloss.length) <= MAX_GLOSS) { "invalid gloss length" }
            require(span.word == null || validWord(span.word)) { "invalid word reference" }
            previousEnd = span.end
        }
    }

    private fun validWord(word: String): Boolean =
        word.isNotBlank() && word.codePointCount(0, word.length) <= MAX_FORM && word !in setOf(".", "..") &&
            word.none { it == '/' || it == '\\' || it.isISOControl() }

    private fun combiningAt(text: String, at: Int): Boolean =
        at < text.length && Character.getType(text.codePointAt(at)) in setOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
        )

    fun resolveWords(sets: List<GrammarSet>, words: Set<String>): List<GrammarSet> = sets.map { set ->
        set.copy(exercises = set.exercises.map { exercise ->
            val missing = exercise.vocabulary?.spans.orEmpty().mapNotNull { it.word }.filterNot { it in words }.distinct()
            if (missing.isEmpty()) exercise else exercise.copy(
                vocabulary = exercise.vocabulary?.let { data ->
                    data.copy(spans = data.spans.map { if (it.word in missing) it.copy(word = null) else it })
                },
                vocabularyProblem = "Word articles unavailable; local glosses remain available: ${missing.joinToString(", ").take(400)}",
            )
        })
    }

    /** Convert authored code-point offsets to Compose's UTF-16 offsets after filling the blank. */
    fun render(exercise: GrammarExercise, answer: String? = null): GrammarVocabularyText {
        val replacement = answer ?: "（　）"
        val blank = exercise.prompt.indexOf("____")
        val delta = replacement.length - 4
        val ranges = exercise.vocabulary?.spans.orEmpty().map { span ->
            val start = exercise.prompt.offsetByCodePoints(0, span.start)
            val end = exercise.prompt.offsetByCodePoints(0, span.end)
            GrammarVocabularyRange(span, start + if (start > blank) delta else 0, end + if (start > blank) delta else 0)
        }
        return GrammarVocabularyText(exercise.prompt.replace("____", replacement), ranges)
    }
}
