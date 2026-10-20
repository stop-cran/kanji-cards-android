package io.github.stopcran.kanji.core.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object SceneCatalog {
    const val PARSER_EPOCH = 1
    private val pathPattern = Regex("scenes/[^/\\\\\\s]+\\.json")
    private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(path: String, text: String): WordSceneFile {
        require(pathPattern.matches(path)) { "not a flat scenes/<word>.json path" }
        val raw = json.parseToJsonElement(text).jsonObject
        val version = raw["schemaVersion"] as? JsonPrimitive
        require(version != null && !version.isString && version.content == "1") {
            "schemaVersion must be the integer 1"
        }
        require((raw["word"] as? JsonPrimitive)?.isString == true) { "word must be a string" }
        raw["scenes"]?.jsonArray?.forEach { item ->
            val scene = item.jsonObject
            for (field in listOf("id", "sense", "image", "description")) {
                require((scene[field] as? JsonPrimitive)?.isString == true) { "$field must be a string" }
            }
            val eligible = scene["quizEligible"] as? JsonPrimitive
            require(eligible != null && !eligible.isString && eligible.booleanOrNull != null) {
                "quizEligible must be an explicit boolean"
            }
        }
        val catalog = json.decodeFromJsonElement(WordSceneFile.serializer(), raw)
        require(path == "scenes/${catalog.word}.json") { "word must match the file name" }
        require(catalog.scenes.isNotEmpty()) { "scenes must not be empty" }
        val ids = hashSetOf<String>()
        val images = hashSetOf<String>()
        for (scene in catalog.scenes) {
            require(idPattern.matches(scene.id) && ids.add(scene.id)) { "invalid or repeated scene ID '${scene.id}'" }
            require(scene.sense.isNotBlank() && scene.description.isNotBlank()) { "${scene.id}: missing sense or description" }
            require(ContentParser.IMAGE_PATH.matches(scene.image)) { "${scene.id}: not a flat images/<name>.webp path" }
            require(images.add(scene.image)) { "${scene.id}: repeated image '${scene.image}'" }
        }
        return catalog
    }

    private fun readCatalogs(
        manifest: Manifest,
        files: Map<String, ByteArray>,
        knownWords: Map<String, WordArticle>,
        problems: MutableList<String>,
    ): Map<String, WordSceneFile> {
        val catalogs = mutableMapOf<String, WordSceneFile>()
        for ((path, hash) in manifest.scenes.toSortedMap()) {
            if (path in manifest.files) continue // Already reported; never fall back around a rejected catalog.
            try {
                require(pathPattern.matches(path)) { "not a flat scenes/<word>.json path" }
                val bytes = requireNotNull(files[path]) { "listed in manifest but missing" }
                require(ContentParser.sha256(bytes) == hash) { "hash mismatch" }
                val catalog = parse(path, bytes.decodeToString())
                val word = requireNotNull(knownWords[catalog.word]) { "word '${catalog.word}' is missing" }
                if (word.image != null) {
                    val legacy = catalog.scenes.find { it.image == word.image }
                    require(legacy != null && legacy.description == word.imageScene && legacy.quizEligible) {
                        "legacy image must match a quiz-eligible catalog scene and its description"
                    }
                }
                catalogs[catalog.word] = catalog
            } catch (e: IllegalArgumentException) {
                problems += "$path: ${e.message}"
            }
        }
        return catalogs
    }

    internal fun resolve(
        manifest: Manifest,
        files: Map<String, ByteArray>,
        words: MutableList<WordArticle>,
        images: Map<String, ByteArray>,
        problems: MutableList<String>,
    ): Map<String, ByteArray> {
        val catalogs = readCatalogs(manifest, files, words.associateBy { it.word }, problems)
        val owners = mutableMapOf<String, String>()
        for (i in words.indices) {
            val word = words[i]
            val path = "scenes/${word.word}.json"
            val declared = path in manifest.scenes || path in manifest.files
            val candidates = if (declared) catalogs[word.word]?.scenes.orEmpty() else listOfNotNull(
                word.image?.let { WordScene("legacy", "Illustrated usage", it, word.imageScene.orEmpty(), true) },
            )
            val available = (candidates.map { it.image } + listOfNotNull(word.image)).distinct().filterTo(hashSetOf()) { image ->
                val reason = when {
                    image !in images -> "image '$image' is missing or failed verification"
                    owners[image] != null && owners[image] != word.word -> "image '$image' is already used by ${owners[image]}"
                    else -> null
                }
                if (reason != null) problems += "${if (declared) path else "words/${word.word}.md"}: $reason"
                else owners[image] = word.word
                reason == null
            }
            val scenes = candidates.filter { it.image in available }
            val primary = word.image?.takeIf { it in available } ?: scenes.firstOrNull()?.image
            val description = if (primary == word.image) word.imageScene else scenes.firstOrNull()?.description
            words[i] = word.copy(image = primary, imageScene = if (primary != null) description else null, scenes = scenes)
        }
        return images.filterKeys { it in owners }
    }
}
