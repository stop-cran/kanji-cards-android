package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.grammar.GrammarMapGroup
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.data.GrammarRoadmapSnapshot

@Composable
internal fun GrammarMapGroupDetails(
    snapshot: GrammarRoadmapSnapshot,
    item: GrammarMapGroup,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
    focusMessage: String? = null,
) {
    val group = item.group
    val curriculum = snapshot.curriculum as? GrammarCurriculumState.Ready
    val chapterId = curriculum?.graph?.chapterByGroup?.get(group.id)
    if (chapterId != null) {
        TextButton(onClick = { actions.navigate(destination.chapter(chapterId)) }) {
            Text(curriculum.curriculum.chapters.single { it.id == chapterId }.label)
        }
    } else {
        Text(stringResource(when {
            !group.authored -> R.string.grammar_map_compatibility
            curriculum == null -> R.string.grammar_map_uncatalogued
            else -> R.string.grammar_map_unmapped
        }))
    }
    Text(group.label, style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(when {
            !group.authored -> R.string.grammar_map_compatibility
            item.ready -> R.string.grammar_map_established
            item.readinessProblem != null -> R.string.grammar_map_readiness_unavailable
            item.availability.started.isEmpty() -> R.string.grammar_map_unseen
            else -> R.string.grammar_map_learning
        }),
    )
    GrammarMapProgressText(item.progress)
    Text(stringResource(R.string.grammar_map_evidence_help), style = MaterialTheme.typography.bodySmall)
    GrammarMapNewAccess(snapshot, item)
    OutlinedButton(onClick = { actions.practice(destination.target(GrammarTargetKind.Group, group.id)) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.grammar_practice_group))
    }
    val candidate = snapshot.study.focus?.candidates?.singleOrNull { it.group.id == group.id }
    OutlinedButton(
        onClick = { actions.focus(group.id) }, enabled = candidate != null && candidate.blocked == null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.grammar_map_make_focus)) }
    focusMessage?.let { Text(it) }
    if (!snapshot.study.preferences.guided) Text(stringResource(R.string.grammar_map_enable_focus))
    candidate?.blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    item.articles.forEach { (path, label) ->
        TextButton(onClick = { actions.article(path) }) { Text(stringResource(R.string.grammar_map_read_article, label)) }
    }
    if (group.requires.isNotEmpty()) {
        Text(stringResource(R.string.grammar_map_foundations), style = MaterialTheme.typography.titleMedium)
        group.requires.forEach { id -> GrammarMapFoundationLink(snapshot, id, destination, actions) }
    }
    if (group.authored) {
        group.facets.forEach { facet ->
            val evidence = item.evidence.facets.getValue(facet.id)
            val total = group.members.filter { it.facetId == facet.id }.map { it.exampleKey }.distinct().size
            Text(
                stringResource(R.string.grammar_map_facet, facet.label, evidence.startedKeys, total, evidence.positiveKeys, evidence.typedPositives),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item.evidence.diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (group.next.isNotEmpty()) {
        Text(stringResource(R.string.grammar_map_recommendations), style = MaterialTheme.typography.titleMedium)
        group.next.forEach { link ->
            TextButton(onClick = { actions.navigate(destination.group(link.groupId)) }) {
                Text(snapshot.groups.getValue(link.groupId).group.label)
            }
            Text(link.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GrammarMapNewAccess(snapshot: GrammarRoadmapSnapshot, item: GrammarMapGroup) {
    val available = item.availability
    val unseen = available.inScopeUnseen.size + available.outOfScopeUnseen.size
    if (unseen == 0) Text(stringResource(R.string.grammar_map_no_unseen))
    if (available.outOfScopeUnseen.isNotEmpty()) {
        Text(stringResource(R.string.grammar_map_out_of_scope, available.outOfScopeUnseen.size))
    }
    available.newMetadataProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (available.missingCoherentFacets.isNotEmpty() && unseen > 0) {
        Text(stringResource(R.string.grammar_map_coherence, available.missingCoherentFacets.joinToString()))
    }
    if (available.dailyAllowance == 0) Text(stringResource(R.string.grammar_map_no_allowance))
    snapshot.study.readiness.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun GrammarMapFoundationLink(
    snapshot: GrammarRoadmapSnapshot,
    id: String,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
) {
    val item = snapshot.groups.getValue(id)
    val catalog = snapshot.curriculum as? GrammarCurriculumState.Ready
    val chapterId = catalog?.graph?.chapterByGroup?.get(id)
    val chapter = catalog?.curriculum?.chapters?.singleOrNull { it.id == chapterId }?.label
        ?: stringResource(if (catalog == null) R.string.grammar_map_uncatalogued else R.string.grammar_map_unmapped_foundation)
    TextButton(onClick = { actions.navigate(destination.group(id)) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(item.group.label)
            Text(chapter, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(when {
                    item.ready -> R.string.grammar_map_established
                    item.readinessProblem != null -> R.string.grammar_map_readiness_unavailable
                    else -> R.string.grammar_map_readiness_needed
                }),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun GrammarMapExternalFoundations(
    snapshot: GrammarRoadmapSnapshot,
    groupIds: List<String>,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
) {
    val edges = groupIds.flatMap { id ->
        snapshot.groups.getValue(id).group.requires.filter { it !in groupIds }.map { GrammarLeafEdge(it, id) }
    }
    if (edges.isEmpty()) return
    Text(stringResource(R.string.grammar_map_external), style = MaterialTheme.typography.titleMedium)
    GrammarMapEdges(snapshot, edges, destination, actions)
}

@Composable
internal fun GrammarMapEdgeDetails(snapshot: GrammarRoadmapSnapshot, destination: GrammarMapDestination, actions: GrammarMapActions) {
    val catalog = snapshot.curriculum as? GrammarCurriculumState.Ready
    val ownership = catalog?.graph?.chapterByGroup.orEmpty()
    val children = catalog?.curriculum?.chapters?.singleOrNull { it.id == destination.chapter }?.groups.orEmpty()
    val edges = snapshot.groups.values.flatMap { item -> item.group.requires.map { GrammarLeafEdge(it, item.group.id) } }.filter {
        if (destination.chapter == null) ownership[it.foundation] != ownership[it.dependent] ||
            it.foundation !in ownership || it.dependent !in ownership
        else it.dependent in children && it.foundation in children
    }
    Text(stringResource(R.string.grammar_map_edge_help), style = MaterialTheme.typography.bodySmall)
    if (edges.isEmpty()) Text(stringResource(R.string.grammar_map_no_edges))
    GrammarMapEdges(snapshot, edges, destination, actions)
}

@Composable
private fun GrammarMapEdges(
    snapshot: GrammarRoadmapSnapshot,
    edges: List<GrammarLeafEdge>,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(edges.sortedWith(compareBy({ it.foundation }, { it.dependent })), key = { "${it.foundation}:${it.dependent}" }) { edge ->
            GrammarMapFoundationLink(snapshot, edge.foundation, destination, actions)
            TextButton(onClick = { actions.navigate(destination.group(edge.dependent)) }) {
                Text(stringResource(R.string.grammar_map_required_by, snapshot.groups.getValue(edge.dependent).group.label))
            }
        }
    }
}
