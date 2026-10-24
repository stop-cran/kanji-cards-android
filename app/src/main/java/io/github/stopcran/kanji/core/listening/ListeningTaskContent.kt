package io.github.stopcran.kanji.core.listening

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

object ListeningTaskContent {
    const val PARSER_EPOCH = 1
    const val POLICY_VERSION = 1
    const val BLUEPRINT = "n5-section-v1"
    private val json = Json
    private val fullJson = Json { encodeDefaults = true }
    private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private data class TaskManifest(val tasks: Map<String, String>, val audio: Map<String, String>, val images: Map<String, String>)

    fun contentHash(raw: JsonObject): String =
        ListeningContent.canonicalHash(JsonObject(raw.filterKeys { it != "media" && it != "approval" }))

    fun mediaHash(media: TaskMedia): String =
        ListeningContent.canonicalHash(json.encodeToJsonElement(TaskMedia.serializer(), media))

    fun parse(path: String, text: String): ListeningTaskRecord {
        val raw = json.parseToJsonElement(text).jsonObject
        require(unsupportedReason(raw) == null) { "Unsupported listening task version or type" }
        val p = json.decodeFromJsonElement(ListeningTaskPackage.serializer(), raw)
        strictTypes(raw, fullJson.encodeToJsonElement(ListeningTaskPackage.serializer(), p))
        require(path == "listening-tasks/${p.id}.json") { "Task ID must match its filename" }
        validate(p)
        val hash = contentHash(raw)
        val media = p.media
        val mediaHash = if (media == null) null else {
            validateMedia(p, media, hash)
            ListeningContent.canonicalHash(requireNotNull(raw["media"]))
        }
        p.approval?.let { approval ->
            require(media != null && approval.status == "approved" && approval.contentSha256 == hash &&
                approval.mediaSha256 == mediaHash) { "Invalid or stale complete-task approval" }
            require(approval.reviewedOn.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Invalid approval date" }
            try {
                LocalDate.parse(approval.reviewedOn)
            } catch (e: java.time.format.DateTimeParseException) {
                throw IllegalArgumentException("Invalid approval date", e)
            }
            plain(approval.note)
        }
        return ListeningTaskRecord(p, text, hash, mediaHash)
    }

    private fun unsupportedReason(raw: JsonObject): String? {
        fun version(key: String): Int {
            val value = raw[key] as? JsonPrimitive
            require(value != null && !value.isString && (value.intOrNull ?: 0) > 0) { "Invalid $key" }
            return requireNotNull(value.intOrNull)
        }
        val schema = version("schemaVersion")
        if (schema != 1) return "This listening task requires a newer app"
        val policy = version("policyVersion")
        if (policy != POLICY_VERSION) return "This listening task requires a newer app"
        val type = raw["taskType"] as? JsonPrimitive
        require(type?.isString == true && type.content.isNotBlank()) { "Invalid taskType" }
        return if (ListeningTaskType.entries.none { it.wire == type.content }) {
            "This listening task requires a newer app"
        } else null
    }

    private fun validate(p: ListeningTaskPackage) {
        validId(p.id)
        validId(p.exposureId)
        require(p.jlpt == 5 && p.kind in setOf("dialogue", "monologue")) { "Only N5 dialogue/monologue tasks are supported" }
        require(p.blueprint == null || p.blueprint == BLUEPRINT) { "Unsupported assessment blueprint" }
        listOf(p.title, p.topic, p.levelRationale, p.explanation).forEach { plain(it) }
        validateTurns(p)
        validateStages(p)
        validateOptions(p)
        require(p.vocabulary.size <= 30) { "Too many vocabulary tips" }
        p.vocabulary.forEach { word ->
            listOf(word.surface, word.reading, word.gloss).forEach { plain(it, 300) }
            word.word?.let {
                plain(it, 80)
                require(it !in setOf(".", "..") && '/' !in it && '\\' !in it) { "Unsafe word reference" }
            }
        }
    }

    private fun validateTurns(p: ListeningTaskPackage) {
        require(p.speakers.size in 2..4 && p.turns.size in 1..32) { "Invalid speaker/turn count" }
        val speakers = ids(p.speakers.map { it.id })
        ids(p.turns.map { it.id })
        require(p.speakers.count { it.role == "narrator" } == 1 &&
            p.speakers.count { it.role == "character" } == if (p.kind == "dialogue") 2 else 1) { "Invalid narrator/character roles" }
        p.speakers.forEach {
            require(it.role in setOf("narrator", "character") && it.voice.matches(Regex("ja-JP-[A-Za-z]+Neural")) &&
                it.ratePercent in -15..0) { "Invalid voice/rate/role" }
        }
        val used = p.turns.mapTo(hashSetOf()) { it.speaker }
        require(used.all { it in speakers } && p.speakers.filter { it.role == "character" }.all { it.id in used }) {
            "Invalid or unused character speakers"
        }
        p.turns.forEach {
            plain(it.text, 500)
            plain(it.translation, 500)
            it.spokenText?.let { spoken -> plain(spoken, 500) }
        }
    }

    private fun validateStages(p: ListeningTaskPackage) {
        val expected = when (p.taskType) {
            ListeningTaskType.TaskBased, ListeningTaskType.KeyPoint ->
                listOf(TaskStageRole.Instruction, TaskStageRole.Focus, TaskStageRole.Passage, TaskStageRole.FocusRepeat)
            ListeningTaskType.VerbalExpression -> listOf(TaskStageRole.Instruction, TaskStageRole.Situation) + List(3) { TaskStageRole.Choice }
            ListeningTaskType.QuickResponse -> listOf(TaskStageRole.Instruction, TaskStageRole.Utterance) + List(3) { TaskStageRole.Choice }
        }
        require(p.stages.map { it.role } == expected) { "Invalid task stage sequence" }
        ids(p.stages.map { it.id })
        val turns = p.turns.mapTo(hashSetOf()) { it.id }
        p.stages.forEach { stage ->
            require(stage.turns.isNotEmpty() && stage.turns.distinct().size == stage.turns.size &&
                stage.turns.all { it in turns }) { "Invalid stage turn references" }
            require((stage.role == TaskStageRole.Choice) == (stage.optionId != null)) { "Only choices reference an option" }
        }
        require(p.stages.flatMap { it.turns }.toSet() == turns) { "Unused task turns" }
        if (!p.taskType.audioChoices) require(p.stages[1].turns == p.stages.last().turns) { "Repeated focus must match initial focus" }
        val storyTurns = p.stages.filter { it.role in setOf(TaskStageRole.Passage, TaskStageRole.Situation, TaskStageRole.Utterance) }
            .flatMap { it.turns }.toSet()
        require(p.evidence.isNotEmpty() && p.evidence.distinct().size == p.evidence.size &&
            p.evidence.all { it in turns } && p.evidence.any { it in storyTurns }) { "Evidence must include story turns" }
    }

    private fun validateOptions(p: ListeningTaskPackage) {
        require(p.options.size == p.taskType.optionCount && p.options.map { it.number } == (1..p.taskType.optionCount).toList()) {
            "Invalid fixed option count/numbering"
        }
        val options = ids(p.options.map { it.id })
        require(p.answer in options && p.options.map { it.label }.distinct().size == options.size) { "Invalid answer or duplicate choices" }
        p.options.forEach { plain(it.label, 500); plain(it.translation); plain(it.rationale) }
        require((p.choiceMode == TaskChoiceMode.Audio) == p.taskType.audioChoices) { "Choice modality does not match task type" }
        if (p.taskType.audioChoices) {
            p.stages.takeLast(3).zip(p.options).forEach { (stage, option) ->
                require(stage.optionId == option.id && stage.turns.size == 1) { "Spoken choice order mismatch" }
                val turn = p.turns.single { it.id == stage.turns.single() }
                require(turn.text == "${option.number}\u3002${option.label}") { "Spoken number/text does not match its option" }
            }
        }
        p.visual?.let { require(it.role in setOf("context", "required")) { "Invalid visual role" }; plain(it.description) }
        if (p.choiceMode == TaskChoiceMode.Visual || p.taskType == ListeningTaskType.VerbalExpression) {
            require(p.visual?.role == "required") { "This task needs required visual evidence" }
        }
        require(p.taskType != ListeningTaskType.QuickResponse || p.visual == null) { "Quick response has no picture" }
    }

    private fun validateMedia(p: ListeningTaskPackage, media: TaskMedia, hash: String) {
        require(media.contentSha256 == hash && media.audio.keys == p.stages.mapTo(hashSetOf()) { it.id }) { "Incomplete or stale task media" }
        require((media.image != null) == (p.visual != null)) { "Declared visual media is missing or unexpected" }
        require(media.audio.values.sumOf { it.durationMs } in 1..150_000) { "Task audio exceeds its total envelope" }
        p.stages.forEach { stage ->
            val audio = media.audio.getValue(stage.id)
            require(audio.path.matches(Regex("task-audio/[a-z0-9]+(?:-[a-z0-9]+)*\\.ogg")) &&
                hashPattern.matches(audio.sha256) && audio.durationMs in 250..90_000) { "Invalid task audio path/hash/duration" }
            require(audio.turns.map { it.id } == stage.turns) { "Recorded turn order does not match stage" }
            var end = 0L
            audio.turns.forEach {
                require(it.startMs >= end && it.endMs > it.startMs && it.endMs <= audio.durationMs) { "Invalid task audio timing" }
                end = it.endMs
            }
        }
        media.image?.let {
            require(it.path.matches(Regex("task-images/[a-z0-9]+(?:-[a-z0-9]+)*\\.webp")) && hashPattern.matches(it.sha256)) {
                "Invalid task picture path/hash"
            }
        }
    }

    fun read(
        manifest: Manifest, files: Map<String, ByteArray>, wordIds: Set<String>, legacyIds: Set<String>,
        imageValidator: (ByteArray) -> String?,
    ): ListeningTaskCatalog {
        val maps = try {
            TaskManifest(manifestMap(manifest.listeningTasks), manifestMap(manifest.listeningTaskAudio), manifestMap(manifest.listeningTaskImages))
        } catch (e: IllegalArgumentException) {
            return ListeningTaskCatalog(problems = listOf("Listening task manifest: ${e.message}"))
        }
        val records = mutableListOf<ListeningTaskRecord>()
        val unsupported = mutableListOf<UnsupportedListeningTask>()
        val assets = mutableMapOf<String, ByteArray>()
        val problems = mutableListOf<String>()
        maps.tasks.toSortedMap().forEach { (path, hash) ->
            try {
                require(path.matches(Regex("listening-tasks/[a-z0-9]+(?:-[a-z0-9]+)*\\.json")) && hashPattern.matches(hash)) {
                    "Invalid task manifest entry"
                }
                val id = path.removePrefix("listening-tasks/").removeSuffix(".json")
                require(id !in legacyIds) { "Scored ID collides with a legacy passage" }
                val bytes = requireNotNull(files[path]) { "Task document is missing" }
                require(ContentParser.sha256(bytes) == hash) { "Task document hash mismatch" }
                val text = bytes.decodeToString(throwOnInvalidSequence = true)
                val raw = json.parseToJsonElement(text).jsonObject
                val unavailable = unsupportedReason(raw)
                if (unavailable != null) {
                    unsupported += UnsupportedListeningTask(id, unavailable)
                } else {
                    val record = parse(path, text)
                    require(record.task.vocabulary.all { it.word == null || it.word in wordIds }) { "Missing task word article" }
                    validateAssets(record, maps, files, assets, imageValidator)
                    records += record
                }
            } catch (e: IllegalArgumentException) {
                problems += "$path: ${e.message}"
            } catch (e: java.nio.charset.CharacterCodingException) {
                problems += "$path: Task document is not valid UTF-8 (${e.message})"
            }
        }
        return ListeningTaskCatalog(records, unsupported, assets, problems)
    }

    private fun validateAssets(
        record: ListeningTaskRecord, manifest: TaskManifest, files: Map<String, ByteArray>,
        assets: MutableMap<String, ByteArray>, imageValidator: (ByteArray) -> String?,
    ) {
        record.task.media?.audio?.values?.forEach { audio ->
            require(manifest.audio[audio.path] == audio.sha256) { "Task audio is not bound to the manifest" }
            val bytes = requireNotNull(files[audio.path]) { "Task audio is missing" }
            require(ContentParser.rawSha256(bytes) == audio.sha256 &&
                kotlin.math.abs(OpusAudio.durationMs(bytes, 250, 90_000) - audio.durationMs) <= 100) { "Task audio failed integrity/duration checks" }
            assets[audio.sha256] = bytes
        }
        record.task.media?.image?.let { image ->
            require(manifest.images[image.path] == image.sha256) { "Task image is not bound to the manifest" }
            val bytes = requireNotNull(files[image.path]) { "Task image is missing" }
            require(bytes.size <= ContentParser.MAX_IMAGE_BYTES && ContentParser.hasWebpEnvelope(bytes) &&
                ContentParser.rawSha256(bytes) == image.sha256) { "Task picture failed integrity checks" }
            require(imageValidator(bytes) == null) { "Task picture could not be decoded within its limits" }
            assets[image.sha256] = bytes
        }
    }

    private fun manifestMap(value: JsonElement): Map<String, String> {
        require(value is JsonObject) { "Expected a task path-to-hash map" }
        return value.mapValues { (_, hash) ->
            require(hash is JsonPrimitive && hash.isString) { "Expected a task path-to-hash string" }
            hash.content
        }
    }

    private fun strictTypes(raw: JsonElement, typed: JsonElement) {
        when (raw) {
            JsonNull -> throw IllegalArgumentException("Optional task fields must be omitted, not null")
            is JsonObject -> {
                require(typed is JsonObject) { "Expected a task object" }
                raw.forEach { (key, value) -> strictTypes(value, typed.getValue(key)) }
            }
            is JsonArray -> {
                require(typed is JsonArray && raw.size == typed.size) { "Expected a task array" }
                raw.zip(typed).forEach { (value, expected) -> strictTypes(value, expected) }
            }
            is JsonPrimitive -> require(raw == typed) { "Invalid task primitive type" }
        }
    }

    private fun validId(value: String) = require(idPattern.matches(value)) { "Invalid task identifier" }
    private fun ids(values: List<String>): Set<String> {
        values.forEach(::validId)
        require(values.distinct().size == values.size) { "Duplicate task identifiers" }
        return values.toSet()
    }
    private fun plain(value: String, max: Int = 2_000) {
        require(value.isNotBlank() && value.length <= max && '<' !in value && '>' !in value &&
            value.none { it.code < 32 && it !in "\n\t" }) { "Invalid plain task text" }
    }
}
