package io.github.stopcran.kanji.ui

import android.net.Uri
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class GrammarMapDestination(
    val sourceId: String,
    val branch: String,
    val chapter: String? = null,
    val group: String? = null,
) {
    init {
        require(RepoSource.parse("https://github.com/$sourceId", branch)?.id == sourceId) { "Invalid roadmap source." }
        require(chapter == null || group == null) { "Ambiguous roadmap destination." }
        listOfNotNull(chapter, group).forEach {
            require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) { "Invalid roadmap destination." }
        }
    }

    fun overview() = copy(chapter = null, group = null)
    fun chapter(id: String) = copy(chapter = id, group = null)
    fun group(id: String) = copy(chapter = null, group = id)
    fun target(kind: GrammarTargetKind, id: String) = GrammarPracticeTarget(sourceId, branch, kind, id)
    fun matches(source: RepoSource) = source.id == sourceId && source.branch == branch
}

internal object GrammarRoadmapRoutes {
    const val PREFIX = "grammar-map:"
    fun create(destination: GrammarMapDestination): String =
        PREFIX + Uri.encode(Json.encodeToString(GrammarMapDestination.serializer(), destination))

    fun parse(route: String): GrammarMapDestination {
        require(route.startsWith(PREFIX)) { "Invalid roadmap route." }
        return Json.decodeFromString(GrammarMapDestination.serializer(), Uri.decode(route.removePrefix(PREFIX)))
    }
}
