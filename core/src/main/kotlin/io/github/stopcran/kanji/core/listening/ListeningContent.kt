package io.github.stopcran.kanji.core.listening

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

@Serializable
data class ListeningPassage(
    val schemaVersion: Int,
    val id: String,
    val jlpt: Int,
    val kind: String,
    val title: String,
    val topic: String,
    val levelRationale: String,
    val speakers: List<ListeningSpeaker>,
    val turns: List<ListeningTurn>,
    val questions: List<ListeningQuestion>,
    val vocabulary: List<ListeningWord> = emptyList(),
    val image: ListeningImage? = null,
    val audio: ListeningAudio? = null,
    val audioReview: ListeningApproval? = null,
)

@Serializable
data class ListeningSpeaker(val id: String, val voice: String, val ratePercent: Int)

@Serializable
data class ListeningTurn(val id: String, val speaker: String, val text: String, val translation: String, val spokenText: String? = null)

@Serializable
data class ListeningQuestion(
    val id: String, val mainPoint: Boolean, val prompt: String, val options: List<ListeningOption>,
    val answer: String, val evidence: List<String>, val explanation: String,
)

@Serializable
data class ListeningOption(val id: String, val label: String, val rationale: String)

@Serializable
data class ListeningWord(val surface: String, val reading: String, val gloss: String, val word: String? = null)

@Serializable
data class ListeningImage(val path: String, val sha256: String)

@Serializable
data class ListeningAudio(
    val path: String, val sha256: String, val contentSha256: String, val durationMs: Long, val turns: List<ListeningTiming>,
)

@Serializable
data class ListeningTiming(val id: String, val startMs: Long, val endMs: Long)

@Serializable
data class ListeningApproval(
    val status: String, val audioSha256: String, val contentSha256: String, val reviewedOn: String, val note: String,
)

data class ListeningRecord(val passage: ListeningPassage, val json: String, val contentHash: String) {
    val ready: Boolean get() = passage.audio != null && passage.audioReview != null
    val reason: String? get() = when {
        passage.audio == null -> "Audio has not been generated"
        passage.audioReview == null -> "Awaiting final-recording listening approval"
        else -> null
    }
}

data class ListeningCatalog(val records: List<ListeningRecord>, val audio: Map<String, ByteArray>, val problems: List<String>)

object ListeningContent {
    const val PARSER_EPOCH = 1
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val audioPath = Regex("audio/[a-z0-9]+(?:-[a-z0-9]+)*\\.ogg")
    private val imagePath = Regex("images/[^/\\\\\\s]+\\.webp")

    fun parse(path: String, text: String): ListeningRecord {
        val raw = json.parseToJsonElement(text).jsonObject
        val hash = contentHash(raw)
        val p = json.decodeFromJsonElement(ListeningPassage.serializer(), raw)
        require(p.schemaVersion == 1 && p.jlpt == 5) { "Only listening schema 1 and N5 are supported" }
        validId(p.id)
        require(path == "listening/${p.id}.json") { "Passage ID must match its filename" }
        listOf(p.title, p.topic, p.levelRationale).forEach { plain(it) }
        val turns = validateTurns(p)
        validateQuestions(p.questions, turns)
        validateVocabulary(p.vocabulary)
        p.image?.let { require(imagePath.matches(it.path) && hashPattern.matches(it.sha256)) { "Invalid image path/hash" } }
        validateAudio(p, hash)
        return ListeningRecord(p, text, hash)
    }

    private fun validateTurns(p: ListeningPassage): Set<String> {
        require(p.kind in setOf("dialogue", "monologue")) { "Invalid passage kind" }
        require(p.speakers.size == if (p.kind == "dialogue") 2 else 1) { "Speaker count does not match kind" }
        val speakers = ids(p.speakers.map { it.id })
        p.speakers.forEach {
            require(it.voice.matches(Regex("ja-JP-[A-Za-z]+Neural")) && it.ratePercent in -15..0) { "Invalid Japanese voice/rate" }
        }
        require(p.turns.size in 1..16 && p.turns.map { it.speaker }.toSet() == speakers) { "Invalid turns or speaker references" }
        val turns = ids(p.turns.map { it.id })
        p.turns.forEach { turn ->
            plain(turn.text, 500)
            plain(turn.translation, 500)
            turn.spokenText?.let { plain(it, 500) }
        }
        return turns
    }

    private fun validateQuestions(questions: List<ListeningQuestion>, turns: Set<String>) {
        require(questions.size == 3 && questions.count { it.mainPoint } == 1) { "Three questions and one main point are required" }
        ids(questions.map { it.id })
        questions.forEach { q ->
            plain(q.prompt)
            plain(q.explanation)
            require(q.options.size == 4) { "Four options are required" }
            val options = ids(q.options.map { it.id })
            q.options.forEach { plain(it.label, 300); plain(it.rationale) }
            require(q.options.map { it.label }.distinct().size == 4 && q.answer in options) { "Invalid labels or answer" }
            require(q.evidence.isNotEmpty() && q.evidence.distinct().size == q.evidence.size && q.evidence.all { it in turns }) {
                "Evidence must identify distinct existing turns"
            }
        }
    }

    private fun validateVocabulary(vocabulary: List<ListeningWord>) {
        require(vocabulary.size <= 30) { "Too many vocabulary tips" }
        vocabulary.forEach { word ->
            listOf(word.surface, word.reading, word.gloss).forEach { plain(it, 300) }
            word.word?.let {
                plain(it, 80)
                require(it !in setOf(".", "..") && '/' !in it && '\\' !in it) { "Unsafe word reference" }
            }
        }
    }

    private fun validateAudio(p: ListeningPassage, hash: String) {
        val audio = p.audio
        if (audio == null) {
            require(p.audioReview == null) { "Audio review without audio" }
        } else {
            require(audio.path == "audio/${p.id}.ogg" && hashPattern.matches(audio.sha256)) { "Invalid audio path/hash" }
            require(audio.contentSha256 == hash) { "Audio was generated for a different learning package" }
            require(audio.durationMs in 15_000..60_000) { "Invalid audio duration" }
            require(audio.turns.map { it.id } == p.turns.map { it.id }) { "Audio turn order mismatch" }
            var end = 0L
            audio.turns.forEach { turn ->
                require(turn.startMs >= end && turn.endMs > turn.startMs && turn.endMs <= audio.durationMs) { "Invalid turn timing" }
                end = turn.endMs
            }
            p.audioReview?.let { validateApproval(it, audio, hash) }
        }
    }

    private fun validateApproval(review: ListeningApproval, audio: ListeningAudio, hash: String) {
        require(review.status == "approved" && review.audioSha256 == audio.sha256 && review.contentSha256 == hash) {
            "Invalid or stale audio approval"
        }
        require(review.reviewedOn.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Invalid review date" }
        try {
            LocalDate.parse(review.reviewedOn)
        } catch (e: java.time.format.DateTimeParseException) {
            throw IllegalArgumentException("Invalid review date", e)
        }
        plain(review.note)
    }

    fun resolveReferences(catalog: ListeningCatalog, wordIds: Set<String>, images: Map<String, ByteArray>, manifest: Manifest): ListeningCatalog {
        val problems = catalog.problems.toMutableList()
        val records = catalog.records.filter { record ->
            val image = record.passage.image
            val reason = when {
                image != null && (image.path !in images || manifest.images[image.path] != image.sha256) ->
                    "Listening image is unavailable or changed"
                record.passage.vocabulary.any { it.word != null && it.word !in wordIds } -> "Missing listening word article"
                else -> null
            }
            if (reason != null) problems += "listening/${record.passage.id}.json: $reason"
            reason == null
        }
        return catalog.copy(records = records, problems = problems)
    }

    fun read(manifest: Manifest, files: Map<String, ByteArray>): ListeningCatalog {
        val problems = mutableListOf<String>()
        val audio = mutableMapOf<String, ByteArray>()
        for ((path, hash) in manifest.audio.toSortedMap()) {
            try {
                require(audioPath.matches(path) && hashPattern.matches(hash)) { "Invalid audio manifest entry" }
                val bytes = requireNotNull(files[path]) { "Audio is missing" }
                require(ContentParser.rawSha256(bytes) == hash) { "Audio hash mismatch" }
                OpusAudio.durationMs(bytes)
                audio[hash] = bytes
            } catch (e: IllegalArgumentException) {
                problems += "$path: ${e.message}"
            }
        }
        val records = mutableListOf<ListeningRecord>()
        for ((path, hash) in manifest.listening.toSortedMap()) {
            try {
                require(path.matches(Regex("listening/[a-z0-9]+(?:-[a-z0-9]+)*\\.json")) && hashPattern.matches(hash)) {
                    "Invalid listening manifest entry"
                }
                val bytes = requireNotNull(files[path]) { "Listening document is missing" }
                require(ContentParser.sha256(bytes) == hash) { "Listening document hash mismatch" }
                val text = try {
                    bytes.decodeToString(throwOnInvalidSequence = true)
                } catch (e: java.nio.charset.CharacterCodingException) {
                    throw IllegalArgumentException("Listening document is not valid UTF-8", e)
                }
                val record = parse(path, text)
                record.passage.audio?.let {
                    require(manifest.audio[it.path] == it.sha256) { "Audio is not bound to its manifest entry" }
                    val sound = requireNotNull(audio[it.sha256]) { "Audio is unavailable or invalid" }
                    require(kotlin.math.abs(OpusAudio.durationMs(sound) - it.durationMs) <= 100) { "Audio duration mismatch" }
                }
                records += record
            } catch (e: IllegalArgumentException) {
                problems += "$path: ${e.message}"
            }
        }
        return ListeningCatalog(records, audio, problems)
    }

    fun contentHash(raw: JsonObject): String {
        val payload = JsonObject(raw.filterKeys { it != "audio" && it != "audioReview" })
        return canonicalHash(payload)
    }

    internal fun canonicalHash(value: JsonElement): String =
        ContentParser.rawSha256(canonical(value).toString().toByteArray(Charsets.UTF_8))

    private fun canonical(value: JsonElement, depth: Int = 0): JsonElement {
        require(depth <= 32) { "Listening JSON nesting is too deep" }
        return when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value, depth + 1) })
            is JsonArray -> JsonArray(value.map { canonical(it, depth + 1) })
            is JsonPrimitive -> value.also {
                require(it == JsonNull || it.isString || it.content in setOf("true", "false") ||
                    it.content.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Listening JSON numbers must be integers" }
            }
        }
    }

    private fun validId(id: String) { require(id.length <= 80 && idPattern.matches(id)) { "Invalid listening ID" } }
    private fun ids(values: List<String>): Set<String> {
        values.forEach(::validId)
        require(values.distinct().size == values.size) { "Duplicate listening ID" }
        return values.toSet()
    }
    private fun plain(text: String, max: Int = 2000) {
        require(text.trim().length in 1..max && text.none { it.isISOControl() }) { "Expected bounded nonempty plain text" }
    }
}
