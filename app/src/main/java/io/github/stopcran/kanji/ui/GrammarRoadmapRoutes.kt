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
    val setId: String? = null,
) {
    init {
        require(RepoSource.parse("https://github.com/$sourceId", branch)?.id == sourceId) { "Invalid roadmap source." }
        require(listOfNotNull(chapter, group, setId).size <= 1) { "Ambiguous Grammar destination." }
        listOfNotNull(chapter, group, setId).forEach {
            require(it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl)) { "Invalid roadmap destination." }
        }
    }

    fun overview() = copy(chapter = null, group = null, setId = null)
    fun chapter(id: String) = copy(chapter = id, group = null, setId = null)
    fun group(id: String) = copy(chapter = null, group = id, setId = null)
    fun set(id: String) = copy(chapter = null, group = null, setId = id)
    fun target(kind: GrammarTargetKind, id: String) = GrammarPracticeTarget(sourceId, branch, kind, id)
    fun target(): GrammarPracticeTarget? = when {
        chapter != null -> target(GrammarTargetKind.Chapter, chapter)
        group != null -> target(GrammarTargetKind.Group, group)
        setId != null -> target(GrammarTargetKind.Set, setId)
        else -> null
    }
    fun matches(source: RepoSource) = source.id == sourceId && source.branch == branch

    companion object {
        fun from(target: GrammarPracticeTarget): GrammarMapDestination {
            val root = GrammarMapDestination(target.sourceId, target.branch)
            return when (target.kind) {
                GrammarTargetKind.Chapter -> root.chapter(target.id)
                GrammarTargetKind.Group -> root.group(target.id)
                GrammarTargetKind.Set -> root.set(target.id)
            }
        }
    }
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
