package io.github.stopcran.kanji.core.readingtext

import io.github.stopcran.kanji.core.content.ContentParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Serializable
data class ReadingPackage(
    val schemaVersion: Int,
    val id: String,
    val jlpt: Int,
    val title: String,
    val japaneseTitle: String,
    val levelRationale: String,
    val provenance: ReadingProvenance,
    val sections: List<ReadingSection>,
    val questions: List<ReadingQuestion>,
)

@Serializable
data class ReadingProvenance(
    val originalTitle: String,
    val adaptation: ReadingAdaptation,
    val credits: List<String>,
    val rights: ReadingRights,
    val distribution: ReadingDistribution,
    val consulted: List<ReadingSource> = emptyList(),
)

@Serializable
data class ReadingAdaptation(val label: String, val basis: String)

@Serializable
data class ReadingRights(val status: String, val note: String)

@Serializable
data class ReadingDistribution(val reviewed: List<String>, val note: String)

@Serializable
data class ReadingSource(
    val originalTitle: String,
    val edition: String,
    val sourceUrl: String,
    val accessed: String,
    val role: String,
    val author: String? = null,
    val translator: String? = null,
    val editor: String? = null,
)

@Serializable
data class ReadingSection(
    val id: String,
    val text: String,
    val vocabulary: List<ReadingOccurrence> = emptyList(),
    val translation: String? = null,
)

@Serializable
data class ReadingOccurrence(
    val start: Int,
    val end: Int,
    val text: String,
    val reading: String,
    val headword: String,
    val gloss: String,
    val word: String? = null,
)

@Serializable
data class ReadingQuestion(
    val id: String,
    val tier: Int,
    val prompt: String,
    val options: List<ReadingOption>,
    val correctOptionId: String,
    val explanation: String,
    val evidence: List<ReadingEvidence>,
    val image: ReadingPicture? = null,
)

@Serializable
data class ReadingOption(val id: String, val text: String)

@Serializable
data class ReadingEvidence(val sectionId: String, val start: Int, val end: Int)

@Serializable
data class ReadingPicture(val path: String, val sha256: String, val description: String)

data class ReadingRecord(val content: ReadingPackage, val json: String, val hash: String)

data class ReadingCatalog(
    val records: List<ReadingRecord> = emptyList(),
    val images: Map<String, ByteArray> = emptyMap(),
    val problems: List<String> = emptyList(),
)

/** Reading uses its own namespace: older readers ignore it without treating its media as Word quiz scenes. */
object ReadingContent {
    const val PARSER_EPOCH = 1
    private val json = Json
    private val id = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val imagePath = Regex("reading-images/[a-z0-9]+(?:-[a-z0-9]+)*\\.webp")
    private val hash = Regex("[a-f0-9]{64}")
    private val numericFields = setOf("schemaVersion", "jlpt", "tier", "start", "end")

    fun parse(path: String, text: String): ReadingRecord {
        val raw = json.parseToJsonElement(text)
        validateNumbers(raw)
        val content = json.decodeFromString(ReadingPackage.serializer(), text)
        require(content.schemaVersion == 1) { "unsupported Reading schema ${content.schemaVersion}" }
        require(validId(content.id) && path == "reading/${content.id}.json") { "package ID must match its flat path" }
        require(content.jlpt in 1..5) { "jlpt must be an integer from 1 to 5" }
        require(content.title.isNotBlank() && content.title.length <= 240) { "invalid title" }
        require(content.japaneseTitle.isNotBlank() && content.japaneseTitle.length <= 240) { "invalid Japanese title" }
        require(content.levelRationale.isNotBlank() && content.levelRationale.length <= 4_000) { "level rationale is required" }
        validateProvenance(content.provenance)
        require(content.sections.size in 1..100) { "expected 1-100 sections" }
        require(content.sections.sumOf { it.text.length } <= 100_000) { "text is too long" }
        unique(content.sections.map { it.id }, "section")
        content.sections.forEach(::validateSection)
        require(content.questions.size in 1..128) { "expected 1-128 questions" }
        unique(content.questions.map { it.id }, "question")
        require(content.questions.any { it.tier == 1 }) { "at least one first-tier question is required" }
        val sections = content.sections.associateBy { it.id }
        content.questions.forEach { question ->
            require(question.tier in 1..4) { "${question.id}: invalid tier" }
            require(question.prompt.isNotBlank() && question.prompt.length <= 2_000) { "${question.id}: invalid prompt" }
            require(question.options.size in 2..6) { "${question.id}: expected 2-6 fixed options" }
            unique(question.options.map { it.id }, "option")
            require(question.options.all { it.text.isNotBlank() && it.text.length <= 1_000 }) { "${question.id}: invalid option" }
            require(question.options.map { it.text.trim() }.distinct().size == question.options.size) { "${question.id}: duplicate option labels" }
            require(question.options.any { it.id == question.correctOptionId }) { "${question.id}: missing correct option" }
            require(question.explanation.isNotBlank() && question.explanation.length <= 4_000) { "${question.id}: missing explanation" }
            require(question.evidence.size in 1..20) { "${question.id}: expected supporting text" }
            question.evidence.forEach { evidence ->
                val section = requireNotNull(sections[evidence.sectionId]) { "${question.id}: unknown evidence section" }
                slice(section.text, evidence.start, evidence.end)
            }
            question.image?.let { picture ->
                require(imagePath.matches(picture.path) && hash.matches(picture.sha256)) { "${question.id}: invalid picture reference" }
                require(picture.description.isNotBlank() && picture.description.length <= 1_000) { "${question.id}: missing picture description" }
            }
        }
        return ReadingRecord(content, text, ContentParser.sha256(text.toByteArray(Charsets.UTF_8)))
    }

    fun read(
        entries: JsonElement,
        imageEntries: JsonElement,
        files: Map<String, ByteArray>,
        imageValidator: (ByteArray) -> String?,
    ): ReadingCatalog {
        val problems = mutableListOf<String>()
        val catalog = read(
            manifestEntries(entries, "reading", problems), manifestEntries(imageEntries, "reading-images", problems), files, imageValidator,
        )
        return catalog.copy(problems = problems + catalog.problems)
    }

    private fun manifestEntries(value: JsonElement, namespace: String, problems: MutableList<String>): Map<String, String> {
        if (value !is JsonObject) {
            problems += "$namespace/: Reading manifest namespace must be an object"
            return emptyMap()
        }
        return value.mapNotNull { (path, item) ->
            if (item is JsonPrimitive && item.isString) path to item.content else {
                problems += "$path: Reading manifest hash must be a string"
                null
            }
        }.toMap()
    }

    fun read(
        entries: Map<String, String>,
        imageEntries: Map<String, String>,
        files: Map<String, ByteArray>,
        imageValidator: (ByteArray) -> String?,
    ): ReadingCatalog {
        val records = mutableListOf<ReadingRecord>()
        val images = mutableMapOf<String, ByteArray>()
        val problems = mutableListOf<String>()
        for ((path, expected) in entries.toSortedMap()) {
            try {
                require(hash.matches(expected)) { "invalid manifest hash" }
                val bytes = requireNotNull(files[path]) { "listed in manifest but missing" }
                require(ContentParser.sha256(bytes) == expected) { "hash mismatch" }
                val record = parse(path, bytes.toString(Charsets.UTF_8))
                val capturedImages = mutableMapOf<String, ByteArray>()
                record.content.questions.mapNotNull { it.image }.forEach { picture ->
                    require(imageEntries[picture.path] == picture.sha256) { "picture is not bound to the manifest" }
                    val image = requireNotNull(files[picture.path]) { "missing picture ${picture.path}" }
                    require(image.size <= ContentParser.MAX_IMAGE_BYTES && ContentParser.rawSha256(image) == picture.sha256) {
                        "picture hash or size is invalid: ${picture.path}"
                    }
                    require(imageValidator(image) == null) { "invalid picture ${picture.path}" }
                    capturedImages[picture.path] = image
                }
                records += record
                images += capturedImages
            } catch (error: IllegalArgumentException) {
                problems += "$path: ${error.message}"
            }
        }
        return ReadingCatalog(records, images, problems)
    }

    fun slice(text: String, start: Int, end: Int): String {
        require(start >= 0 && end > start && end <= text.codePointCount(0, text.length)) { "invalid code-point range" }
        val from = text.offsetByCodePoints(0, start)
        val to = text.offsetByCodePoints(0, end)
        require(!combiningAt(text, from) && !combiningAt(text, to)) { "range splits a combining sequence" }
        return text.substring(from, to)
    }

    private fun validateSection(section: ReadingSection) {
        require(section.text.isNotBlank() && section.text.length <= 10_000) { "${section.id}: invalid section text" }
        section.translation?.let { require(it.isNotBlank() && it.length <= 20_000) { "${section.id}: invalid translation" } }
        require(section.vocabulary.size <= 2_000) { "${section.id}: too many occurrences" }
        var previousEnd = 0
        section.vocabulary.forEach { occurrence ->
            require(occurrence.start >= previousEnd) { "${section.id}: overlapping or unsorted occurrences" }
            require(slice(section.text, occurrence.start, occurrence.end) == occurrence.text) { "${section.id}: occurrence text mismatch" }
            require(listOf(occurrence.text, occurrence.reading, occurrence.headword).all { it.isNotBlank() && it.length <= 160 }) {
                "${section.id}: invalid occurrence form"
            }
            require(occurrence.gloss.isNotBlank() && occurrence.gloss.length <= 1_000) { "${section.id}: invalid occurrence gloss" }
            occurrence.word?.let { word ->
                require(word.isNotBlank() && word.length <= 160 && word !in setOf(".", "..") &&
                    word.none { it == '/' || it == '\\' || it.isISOControl() }) { "${section.id}: invalid word reference" }
            }
            previousEnd = occurrence.end
        }
    }

    private fun validateProvenance(value: ReadingProvenance) {
        require(listOf(value.originalTitle, value.adaptation.label, value.rights.note, value.distribution.note)
            .all { it.isNotBlank() && it.length <= 4_000 }) { "complete provenance and rights are required" }
        require(value.adaptation.basis in setOf("original", "traditional-plot", "public-domain-text")) { "invalid adaptation basis" }
        require(value.rights.status in setOf("original-wording", "public-domain-source-text", "permission-recorded")) { "invalid rights status" }
        require(value.credits.isNotEmpty() && value.credits.size <= 100 && value.credits.all { it.isNotBlank() && it.length <= 4_000 }) {
            "credits are required"
        }
        require(value.distribution.reviewed.size <= 100 && value.distribution.reviewed.all { Regex("[A-Z]{2}").matches(it) }) {
            "reviewed jurisdictions must be two-letter codes; empty is not worldwide clearance"
        }
        require(value.consulted.size <= 100 && (value.adaptation.basis == "original" || value.consulted.isNotEmpty())) {
            "a non-original adaptation requires consulted sources"
        }
        value.consulted.forEach { source ->
            require(listOf(source.originalTitle, source.edition, source.sourceUrl).all { it.isNotBlank() && it.length <= 4_000 }) {
                "incomplete consulted edition"
            }
            require(source.sourceUrl.startsWith("https://")) { "consulted source must be HTTPS" }
            require(source.role in setOf("plot-check", "wording-source")) { "invalid consulted source role" }
            require(value.rights.status != "original-wording" || source.role != "wording-source") {
                "original wording cannot copy a consulted wording source"
            }
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(source.accessed)) { "invalid source access date" }
            try {
                LocalDate.parse(source.accessed)
            } catch (error: DateTimeParseException) {
                throw IllegalArgumentException("invalid source access date", error)
            }
            listOfNotNull(source.author, source.translator, source.editor).forEach {
                require(it.isNotBlank() && it.length <= 1_000) { "invalid source contributor" }
            }
        }
    }

    private fun unique(values: List<String>, kind: String) {
        require(values.all(::validId) && values.toSet().size == values.size) { "invalid or duplicate $kind IDs" }
    }

    private fun validId(value: String) = value.length <= 100 && id.matches(value)

    private fun validateNumbers(value: JsonElement) {
        require(value != JsonNull) { "null fields must be omitted" }
        when (value) {
            is JsonObject -> value.forEach { (key, item) ->
                if (key in numericFields) {
                    require(item is JsonPrimitive && !item.isString && Regex("0|[1-9][0-9]*").matches(item.content)) {
                        "$key must be an unquoted nonnegative integer"
                    }
                }
                validateNumbers(item)
            }
            is JsonArray -> value.forEach(::validateNumbers)
            else -> Unit
        }
    }

    private fun combiningAt(text: String, at: Int): Boolean = at < text.length &&
        Character.getType(text.codePointAt(at)) in setOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
        )
}
