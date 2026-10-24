package io.github.stopcran.kanji.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import io.github.stopcran.kanji.core.grammar.GrammarMapGroup
import io.github.stopcran.kanji.core.grammar.GrammarRoadmap
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.data.GrammarBrowserPreferences
import io.github.stopcran.kanji.data.GrammarRoadmapSnapshot

internal enum class GrammarTopicFilter { All, Unmapped, Compatibility }

internal data class GrammarBrowserContent(val nodes: List<GrammarMapNode>, val edges: List<GrammarLeafEdge>)

internal fun grammarBrowserContent(
    snapshot: GrammarRoadmapSnapshot,
    destination: GrammarMapDestination,
    kind: GrammarTargetKind,
    filter: GrammarTopicFilter = GrammarTopicFilter.All,
): GrammarBrowserContent {
    val catalog = snapshot.curriculum as? GrammarCurriculumState.Ready
    val groups = snapshot.groups
    val nodes = when (kind) {
        GrammarTargetKind.Chapter -> catalog?.curriculum?.chapters.orEmpty().map {
            GrammarMapNode(destination.chapter(it.id), it.label, GrammarMapProgress.of(it.groups.map(groups::getValue)))
        }
        GrammarTargetKind.Set -> snapshot.study.sets.map {
            GrammarMapNode(destination.set(it.id), it.title, GrammarMapProgress.of(snapshot.groupsBySet[it.id].orEmpty()))
        }
        GrammarTargetKind.Group -> browserGroups(snapshot, destination, filter).map {
            GrammarMapNode(destination.group(it.group.id), it.group.label, it.progress)
        }
    }
    val ids = nodes.map { it.id }.toSet()
    val edges = when (kind) {
        GrammarTargetKind.Chapter -> catalog?.graph?.chapterEdges.orEmpty().map { GrammarLeafEdge(it.foundation, it.dependent) }
        GrammarTargetKind.Group -> groups.values.flatMap { group ->
            group.group.requires.map { GrammarLeafEdge(it, group.group.id) }
        }.filter { it.foundation in ids && it.dependent in ids }
        GrammarTargetKind.Set -> emptyList()
    }
    return GrammarBrowserContent(nodes, edges)
}

private fun browserGroups(
    snapshot: GrammarRoadmapSnapshot,
    destination: GrammarMapDestination,
    filter: GrammarTopicFilter,
): List<GrammarMapGroup> {
    val catalog = snapshot.curriculum as? GrammarCurriculumState.Ready
    val scoped = when {
        destination.chapter != null -> catalog?.curriculum?.chapters?.singleOrNull { it.id == destination.chapter }
            ?.groups.orEmpty().map(snapshot.groups::getValue)
        destination.setId != null -> snapshot.groupsBySet[destination.setId].orEmpty()
        else -> snapshot.groups.values.toList()
    }
    return scoped.filter {
        when (filter) {
            GrammarTopicFilter.All -> true
            GrammarTopicFilter.Unmapped -> it.group.authored && catalog?.graph?.chapterByGroup?.containsKey(it.group.id) != true
            GrammarTopicFilter.Compatibility -> !it.group.authored
        }
    }
}

@Composable
internal fun GrammarMapBrowser(
    snapshot: GrammarRoadmapSnapshot,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
    initial: GrammarBrowserPreferences = GrammarBrowserPreferences(),
    onPreferences: ((GrammarBrowserPreferences) -> Unit)? = null,
) {
    val root = destination.target() == null
    var requestedKind by rememberSaveable { mutableStateOf(initial.kind) }
    var diagram by rememberSaveable { mutableStateOf(initial.diagram) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(GrammarTopicFilter.All) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var selectedRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val missingChapters = root && requestedKind == GrammarTargetKind.Chapter && snapshot.curriculum !is GrammarCurriculumState.Ready
    val kind = if (!root || missingChapters) GrammarTargetKind.Group else requestedKind
    fun save() { if (root) onPreferences?.invoke(GrammarBrowserPreferences(requestedKind, diagram)) }
    if (root) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(GrammarTargetKind.Chapter, GrammarTargetKind.Group, GrammarTargetKind.Set).forEach { option ->
                FilterChip(
                    selected = requestedKind == option,
                    onClick = { requestedKind = option; save() },
                    label = { Text(grammarTargetKindLabel(option)) },
                )
            }
        }
    }
    if (missingChapters) Text(stringResource(R.string.grammar_browser_chapter_fallback))
    if (kind == GrammarTargetKind.Set) Text(stringResource(R.string.grammar_browser_set_list))
    OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.grammar_search_topics)) }, singleLine = true)
    Text(stringResource(R.string.grammar_map_browse, snapshot.study.preferences.browseScope.browseLabel), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { tools = !tools }) { Text(stringResource(R.string.grammar_browser_tools)) }
    if (tools) {
        GrammarScopeChips(snapshot.study.preferences.browseScope, actions.browseScope)
        Text(stringResource(R.string.grammar_map_filter_help), style = MaterialTheme.typography.bodySmall)
        if (root && kind == GrammarTargetKind.Group) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GrammarTopicFilter.entries.forEach { option ->
                    FilterChip(filter == option, { filter = option }, label = { Text(topicFilterLabel(option)) })
                }
            }
        }
        GrammarFindNext(snapshot, destination, actions)
    }
    if (query.isNotBlank() || snapshot.study.preferences.browseScope != GrammarScope.All || filter != GrammarTopicFilter.All) {
        TextButton(onClick = { query = ""; filter = GrammarTopicFilter.All; actions.browseScope(GrammarScope.All) }) {
            Text(stringResource(R.string.grammar_browser_reset))
        }
    }
    val showDiagram = diagram && kind != GrammarTargetKind.Set && !missingChapters
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(showDiagram, { diagram = true; save() }, enabled = kind != GrammarTargetKind.Set && !missingChapters,
            label = { Text(stringResource(R.string.grammar_map_diagram)) })
        FilterChip(!showDiagram, { diagram = false; save() }, label = { Text(stringResource(R.string.grammar_map_list)) })
    }
    val effectiveFilter = if (root && kind == GrammarTargetKind.Group) filter else GrammarTopicFilter.All
    val content = remember(snapshot, destination, kind, effectiveFilter) { grammarBrowserContent(snapshot, destination, kind, effectiveFilter) }
    val select: (GrammarMapDestination) -> Unit = { selectedRoute = GrammarRoadmapRoutes.create(it); actions.navigate(it) }
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider("${kind.name}:${effectiveFilter.name}:$showDiagram") {
        GrammarBrowserResults(content, query, snapshot.study.preferences.browseScope, showDiagram, selectedRoute, select)
    }
    GrammarBrowserFoundations(snapshot, destination, kind, content, actions)
}

@Composable
private fun GrammarBrowserResults(
    content: GrammarBrowserContent,
    query: String,
    scope: GrammarScope,
    diagram: Boolean,
    selected: String?,
    onSelect: (GrammarMapDestination) -> Unit,
) {
    val nodes = content.nodes.map {
        it.copy(contextOnly = !(it.label.contains(query, true) || it.id.contains(query, true)) ||
            (scope != GrammarScope.All && it.progress.matching == 0))
    }
    if (diagram && nodes.isNotEmpty()) {
        val layout = remember(content.nodes.map { it.id }, content.edges) { GrammarRoadmap.layout(content.nodes.map { it.id }, content.edges) }
        Text(stringResource(R.string.grammar_map_legend), style = MaterialTheme.typography.bodySmall)
        if (nodes.all { it.contextOnly }) Text(stringResource(R.string.grammar_no_topics_match))
        GrammarRoadmapDiagram(layout, nodes.associateBy { it.id }, selected, onSelect)
    } else {
        GrammarMapList(nodes.filterNot { it.contextOnly }, selected, onSelect)
    }
}

@Composable
private fun GrammarBrowserFoundations(
    snapshot: GrammarRoadmapSnapshot,
    destination: GrammarMapDestination,
    kind: GrammarTargetKind,
    content: GrammarBrowserContent,
    actions: GrammarMapActions,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (kind != GrammarTargetKind.Set) {
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.grammar_map_edge_details)) }
        if (expanded) GrammarMapEdgeDetails(snapshot, destination, actions, if (kind == GrammarTargetKind.Group) content.nodes.map { it.id } else null)
    }
    if (kind == GrammarTargetKind.Group) {
        GrammarMapExternalFoundations(snapshot, content.nodes.map { it.id }, destination, actions)
    }
}

@Composable
private fun GrammarFindNext(snapshot: GrammarRoadmapSnapshot, destination: GrammarMapDestination, actions: GrammarMapActions) {
    val next = snapshot.study.focus?.current?.takeIf { it.blocked == null }?.group?.id
        ?: snapshot.groups.values.filter { it.availability.targetedNew.isNotEmpty() }.minByOrNull { it.group.id }?.group?.id
    OutlinedButton(onClick = { next?.let { actions.navigate(destination.group(it)) } }, enabled = next != null) {
        Text(stringResource(R.string.grammar_map_find_next))
    }
    Text(stringResource(R.string.grammar_practice_scope, snapshot.study.preferences.newScope.introductionLabel), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.grammar_map_find_help), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun grammarTargetKindLabel(kind: GrammarTargetKind): String = stringResource(
    when (kind) {
        GrammarTargetKind.Chapter -> R.string.grammar_chapters
        GrammarTargetKind.Group -> R.string.grammar_groups
        GrammarTargetKind.Set -> R.string.grammar_sets
    },
)

@Composable
private fun topicFilterLabel(filter: GrammarTopicFilter): String = stringResource(
    when (filter) {
        GrammarTopicFilter.All -> R.string.grammar_browser_all_topics
        GrammarTopicFilter.Unmapped -> R.string.grammar_browser_unmapped
        GrammarTopicFilter.Compatibility -> R.string.grammar_browser_compatibility
    },
)

@Composable
internal fun GrammarMapList(
    nodes: List<GrammarMapNode>,
    selectedRoute: String? = null,
    onSelect: (GrammarMapDestination) -> Unit,
) {
    if (nodes.isEmpty()) Text(stringResource(R.string.grammar_no_topics_match))
    key(nodes.map { it.destination }) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("grammar-list"),
            state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(nodes.sortedWith(compareBy({ it.label }, { it.id })), key = { GrammarRoadmapRoutes.create(it.destination) }) { node ->
                GrammarMapCard(node, Modifier.fillMaxWidth(), onSelect, selected = selectedRoute == GrammarRoadmapRoutes.create(node.destination))
            }
        }
    }
}
