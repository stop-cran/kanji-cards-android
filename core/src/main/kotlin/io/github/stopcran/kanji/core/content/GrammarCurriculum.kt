package io.github.stopcran.kanji.core.content

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
data class GrammarCurriculumData(val schemaVersion: Int, val chapters: List<GrammarChapter>)

@Serializable
data class GrammarChapter(val id: String, val label: String, val groups: List<String>)

/** Edges point from the foundation to the dependent, not in the authored requires direction. */
data class GrammarLeafEdge(val foundation: String, val dependent: String)

data class GrammarChapterEdge(
    val foundation: String,
    val dependent: String,
    val supportingEdges: List<GrammarLeafEdge>,
)

data class GrammarCurriculumGraph(
    val chapterByGroup: Map<String, String>,
    val unmappedGroups: Set<String>,
    val leafEdges: List<GrammarLeafEdge>,
    val chapterEdges: List<GrammarChapterEdge>,
)

sealed interface GrammarCurriculumState {
    data object PendingRefresh : GrammarCurriculumState
    data object Absent : GrammarCurriculumState
    data class Invalid(val reason: String) : GrammarCurriculumState
    data class Ready(val curriculum: GrammarCurriculumData, val graph: GrammarCurriculumGraph) : GrammarCurriculumState
}

/** Optional navigation metadata; it never changes the authoritative leaf prerequisites or learning state. */
object GrammarCurriculum {
    const val PARSER_EPOCH = 5
    const val PATH = "curriculum/grammar.json"
    private val id = Regex("[a-z0-9-]+")
    private val hash = Regex("[0-9a-f]{64}")

    fun read(manifest: Manifest, files: Map<String, ByteArray>, sets: List<GrammarSet>): GrammarCurriculumState {
        return try {
            require(manifest.files.keys.none { it.startsWith("curriculum/") }) {
                "catalog must be listed only in manifest.curriculum"
            }
            val entries = manifest.curriculum as? JsonObject
            require(entries != null) { "manifest.curriculum must be an object" }
            if (entries.isEmpty()) return GrammarCurriculumState.Absent
            require(entries.keys == setOf(PATH)) { "manifest.curriculum must contain only $PATH" }
            val expected = entries.getValue(PATH) as? JsonPrimitive
            require(expected != null && expected.isString && hash.matches(expected.content)) { "invalid catalog SHA-256" }
            val bytes = files[PATH]
            require(bytes != null) { "listed catalog is missing" }
            require(ContentParser.sha256(bytes) == expected.content) { "catalog hash mismatch" }
            decode(bytes.decodeToString(), sets)
        } catch (e: IllegalArgumentException) {
            GrammarCurriculumState.Invalid("$PATH: ${e.message}")
        }
    }

    fun decode(text: String, sets: List<GrammarSet>): GrammarCurriculumState.Ready {
        val raw = Json.parseToJsonElement(text).jsonObject
        val version = raw["schemaVersion"] as? JsonPrimitive
        require(version != null && !version.isString && version.content == "1") { "unsupported curriculum schema; expected integer 1" }
        val chapters = raw["chapters"] as? JsonArray
        require(chapters != null) { "chapters must be an array" }
        chapters.forEach { element ->
            val chapter = element.jsonObject
            for (key in listOf("id", "label")) {
                val value = chapter[key] as? JsonPrimitive
                require(value != null && value.isString && value.content.isNotBlank()) { "chapter $key must be a nonblank string" }
            }
            val groups = chapter["groups"] as? JsonArray
            require(groups != null) { "chapter groups must be an array" }
            groups.forEach {
                require(it is JsonPrimitive && it.isString) { "chapter groups must be group-ID strings" }
            }
        }
        val data = Json.decodeFromJsonElement(GrammarCurriculumData.serializer(), raw)
        return GrammarCurriculumState.Ready(data, graph(data, sets))
    }

    private fun graph(data: GrammarCurriculumData, sets: List<GrammarSet>): GrammarCurriculumGraph {
        val groups = sets.flatMap { it.introductionGroups }
        val groupIds = groups.map { it.id }.toSet()
        require(groupIds.size == groups.size) { "duplicate source-global group ID" }
        require(GrammarGroups.invalidPrerequisites(groups.associate { it.id to it.requires }).isEmpty()) {
            "invalid leaf prerequisite graph"
        }
        require(data.chapters.map { it.id }.distinct().size == data.chapters.size) { "duplicate chapter ID" }
        val ownership = sortedMapOf<String, String>()
        data.chapters.forEach { chapter ->
            require(id.matches(chapter.id)) { "invalid chapter ID '${chapter.id}'" }
            require(chapter.groups.isNotEmpty()) { "${chapter.id}: chapter needs at least one group" }
            chapter.groups.forEach { group ->
                require(id.matches(group) && group in groupIds) { "${chapter.id}: unknown authored group '$group'" }
                require(ownership.put(group, chapter.id) == null) { "$group: duplicate primary chapter ownership" }
            }
        }
        val leaves = groups.sortedBy { it.id }.flatMap { group ->
            group.requires.sorted().map { GrammarLeafEdge(it, group.id) }
        }
        val supporting = leaves.mapNotNull { edge ->
            val from = ownership[edge.foundation]
            val target = ownership[edge.dependent]
            if (from == null || target == null || from == target) null else (from to target) to edge
        }.groupBy({ it.first }, { it.second })
        val edges = supporting.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (pair, support) ->
            GrammarChapterEdge(pair.first, pair.second, support)
        }
        val incoming = edges.groupBy { it.dependent }
        val requirements = data.chapters.associate { it.id to incoming[it.id].orEmpty().map { edge -> edge.foundation } }
        val unresolved = GrammarGroups.invalidPrerequisites(requirements)
        require(unresolved.isEmpty()) { "cyclic chapter prerequisite chain: ${unresolved.sorted().joinToString()}" }
        return GrammarCurriculumGraph(ownership, (groupIds - ownership.keys).toSortedSet(), leaves, edges)
    }
}
