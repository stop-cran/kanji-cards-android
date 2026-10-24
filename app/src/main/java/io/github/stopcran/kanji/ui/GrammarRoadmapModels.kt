package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.data.GrammarRoadmapSnapshot

internal sealed interface GrammarMapState {
    data object Loading : GrammarMapState
    data class Ready(val snapshot: GrammarRoadmapSnapshot) : GrammarMapState
    data class Unavailable(val message: String) : GrammarMapState
}

internal data class GrammarMapNode(
    val destination: GrammarMapDestination,
    val label: String,
    val progress: GrammarMapProgress,
    val contextOnly: Boolean = false,
) {
    val id: String get() = requireNotNull(destination.chapter ?: destination.group ?: destination.setId)
}

internal class GrammarMapActions(
    val navigate: (GrammarMapDestination) -> Unit,
    val article: (String) -> Unit,
    val focus: (String) -> Unit,
    val browseScope: (GrammarScope) -> Unit,
)
