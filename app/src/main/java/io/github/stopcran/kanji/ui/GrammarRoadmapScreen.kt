package io.github.stopcran.kanji.ui

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import io.github.stopcran.kanji.core.grammar.GrammarRoadmap
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarRoadmapSnapshot
import io.github.stopcran.kanji.data.changeGrammarFocus
import io.github.stopcran.kanji.data.grammarRoadmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
internal fun GrammarRoadmapScreen(app: KanjiApp, route: String, onBack: () -> Unit, push: (String) -> Unit) {
    val source = app.settings.source.collectAsState().value
    val destination = remember(route) {
        try {
            GrammarRoadmapRoutes.parse(route)
        } catch (error: IllegalArgumentException) {
            Log.e("GrammarRoadmap", "Invalid saved roadmap destination", error)
            null
        }
    }
    if (destination == null || !destination.matches(source)) {
        Page(stringResource(R.string.grammar_roadmap), onBack) { Text(stringResource(R.string.grammar_map_source_changed)) }
        return
    }
    val loaded by rememberGrammarMap(app, source)
    val scope = rememberCoroutineScope()
    var focusMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = GrammarMapActions(
        { push(GrammarRoadmapRoutes.create(it)) },
        { push(GrammarPracticeRoutes.create(it)) },
        { push("source-doc:${android.net.Uri.encode(source.id)}:${android.net.Uri.encode(it)}") },
        { id ->
            scope.launch {
                focusMessage = try {
                    require(destination.matches(app.settings.source.value)) { "The selected content source changed." }
                    app.db.changeGrammarFocus(app.settings, source.id, id, Instant.now())
                    app.getString(R.string.grammar_map_focus_selected)
                } catch (error: IllegalArgumentException) {
                    Log.e("GrammarRoadmap", "Cannot select focus", error)
                    error.message ?: app.getString(R.string.grammar_map_focus_error)
                } catch (error: SQLiteException) {
                    Log.e("GrammarRoadmap", "Cannot save focus", error)
                    app.getString(R.string.grammar_map_focus_error)
                }
            }
        },
        { app.settings.setGrammarBrowseScope(source.id, it) },
    )
    GrammarRoadmapPage(loaded, destination, actions, onBack, focusMessage)
}

@Composable
internal fun GrammarRoadmapPage(
    loaded: GrammarMapState,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
    onBack: () -> Unit,
    focusMessage: String? = null,
) {
    // Measuring the short loading placeholder with verticalScroll would clamp restored page position to zero.
    Page(stringResource(R.string.grammar_roadmap), onBack, scrollable = loaded != GrammarMapState.Loading, ime = true) {
        Text(stringResource(R.string.grammar_practice_source, destination.sourceId, destination.branch), style = MaterialTheme.typography.bodySmall)
        when (val state = loaded) {
            GrammarMapState.Loading -> Text(stringResource(R.string.loading))
            is GrammarMapState.Unavailable -> Text(state.message, color = MaterialTheme.colorScheme.error)
            is GrammarMapState.Ready -> GrammarMapContent(state.snapshot, destination, actions, focusMessage)
        }
    }
}

@Composable
private fun rememberGrammarMap(app: KanjiApp, source: RepoSource): State<GrammarMapState> {
    val preferences = app.settings.grammarPreferences.collectAsState().value[source.id]
    val daily = app.settings.dailyNewCards.collectAsState().value
    val week = app.settings.weekPlan.collectAsState().value
    return produceState<GrammarMapState>(GrammarMapState.Loading, source, preferences, daily, week) {
        val changes = combine(
            app.db.content().observeMeta(source.id),
            app.db.reviews().observeStates(source.id, GRAMMAR_STACK, "Grammar"),
            app.db.reviews().observeGrammarFocus(source.id),
            app.db.reviews().observeGrammarReadiness(source.id),
            app.db.reviews().observeGrammarReadinessStatus(source.id),
        ) { _, _, _, _, _ -> Unit }
        val clock = flow { while (true) { emit(Unit); delay(60_000) } }
        changes.combine(clock) { _, _ -> Unit }.collectLatest {
            value = try {
                GrammarMapState.Ready(app.db.grammarRoadmap(app.settings, source, Instant.now()))
            } catch (error: IllegalArgumentException) {
                Log.e("GrammarRoadmap", "Cannot load roadmap", error)
                GrammarMapState.Unavailable(error.message ?: "Roadmap metadata is unavailable.")
            } catch (error: SQLiteException) {
                Log.e("GrammarRoadmap", "Cannot read roadmap", error)
                GrammarMapState.Unavailable("Could not read the roadmap. Return to Grammar and try again.")
            }
        }
    }
}

@Composable
private fun GrammarMapContent(
    snapshot: GrammarRoadmapSnapshot,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
    focusMessage: String?,
) {
    val ready = snapshot.curriculum as? GrammarCurriculumState.Ready
    if (destination.chapter != null || destination.group != null) {
        TextButton(onClick = { actions.navigate(destination.overview()) }) { Text(stringResource(R.string.grammar_map_overview)) }
    }
    snapshot.study.notice?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    GrammarCatalogNotice(snapshot.curriculum)
    if (destination.group != null) {
        val group = snapshot.groups[destination.group]
        if (group == null) Text(stringResource(R.string.grammar_map_removed)) else {
            GrammarMapGroupDetails(snapshot, group, destination, actions, focusMessage)
        }
        return
    }
    val chapter = ready?.curriculum?.chapters?.singleOrNull { it.id == destination.chapter }
    if (destination.chapter != null && chapter == null) {
        Text(stringResource(R.string.grammar_map_removed))
        return
    }
    chapter?.let {
        Text(it.label, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.grammar_map_chapter_help))
        OutlinedButton(onClick = { actions.practice(destination.target(GrammarTargetKind.Chapter, it.id)) }) {
            Text(stringResource(R.string.grammar_practice_chapter))
        }
    }
    GrammarMapBrowser(snapshot, destination, actions, actions.browseScope)
}

@Composable
private fun GrammarCatalogNotice(curriculum: GrammarCurriculumState) {
    when (curriculum) {
        is GrammarCurriculumState.Ready -> Unit
        is GrammarCurriculumState.Invalid -> Text(curriculum.reason, color = MaterialTheme.colorScheme.error)
        GrammarCurriculumState.PendingRefresh -> Text(stringResource(R.string.grammar_chapter_sync_needed))
        GrammarCurriculumState.Absent -> Text(stringResource(R.string.grammar_no_chapters))
    }
}

@Composable
private fun GrammarMapBrowser(
    snapshot: GrammarRoadmapSnapshot,
    destination: GrammarMapDestination,
    actions: GrammarMapActions,
    onBrowseScope: (GrammarScope) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var list by rememberSaveable { mutableStateOf(false) }
    var edgesVisible by rememberSaveable { mutableStateOf(false) }
    var toolsVisible by rememberSaveable { mutableStateOf(false) }
    var selectedRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val select: (GrammarMapDestination) -> Unit = {
        selectedRoute = GrammarRoadmapRoutes.create(it)
        actions.navigate(it)
    }
    val ready = snapshot.curriculum as? GrammarCurriculumState.Ready
    val groups = snapshot.groups
    val groupNodes = groups.values.map {
        GrammarMapNode(destination.group(it.group.id), it.group.label, it.progress)
    }
    val chapterNodes = ready?.curriculum?.chapters.orEmpty().map {
        GrammarMapNode(destination.chapter(it.id), it.label, GrammarMapProgress.of(it.groups.map(groups::getValue)))
    }
    val chapter = ready?.curriculum?.chapters?.singleOrNull { it.id == destination.chapter }
    val nodes = if (chapter == null) chapterNodes else groupNodes.filter { it.id in chapter.groups }
    val edges = if (chapter == null) ready?.graph?.chapterEdges.orEmpty().map { GrammarLeafEdge(it.foundation, it.dependent) }
        else ready?.graph?.leafEdges.orEmpty().filter { it.foundation in chapter.groups && it.dependent in chapter.groups }
    val layout = remember(nodes.map { it.id }, edges) { GrammarRoadmap.layout(nodes.map { it.id }, edges) }
    Text(stringResource(R.string.grammar_map_browse, snapshot.study.preferences.browseScope.browseLabel), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { toolsVisible = !toolsVisible }) { Text(stringResource(R.string.grammar_map_tools)) }
    if (toolsVisible) {
        GrammarScopeChips(snapshot.study.preferences.browseScope, onBrowseScope)
        Text(stringResource(R.string.grammar_map_filter_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.grammar_search_topics)) }, singleLine = true)
        val next = snapshot.study.focus?.current?.takeIf { it.blocked == null }?.group?.id
            ?: groups.values.filter { it.availability.targetedNew.isNotEmpty() }.minByOrNull { it.group.id }?.group?.id
        OutlinedButton(onClick = { next?.let { actions.navigate(destination.group(it)) } }, enabled = next != null) {
            Text(stringResource(R.string.grammar_map_find_next))
        }
        Text(stringResource(R.string.grammar_map_find_help), style = MaterialTheme.typography.bodySmall)
    }
    if (query.isNotBlank()) {
        Text(stringResource(R.string.grammar_map_search_active, query))
        TextButton(onClick = { query = "" }) { Text(stringResource(R.string.grammar_map_clear_search)) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!list, { list = false }, label = { Text(stringResource(R.string.grammar_map_diagram)) })
        FilterChip(list, { list = true }, label = { Text(stringResource(R.string.grammar_map_list)) })
    }
    if (query.isNotBlank()) {
        Text(stringResource(R.string.grammar_map_search_all))
        GrammarMapList((chapterNodes + groupNodes).filter {
            it.label.contains(query, true) || it.id.contains(query, true)
        }, selectedRoute, select)
    } else if (list || nodes.isEmpty()) {
        GrammarMapList(nodes, selectedRoute, select)
    } else {
        Text(stringResource(R.string.grammar_map_legend), style = MaterialTheme.typography.bodySmall)
        GrammarRoadmapDiagram(layout, nodes.associateBy { it.id }, selectedRoute, select)
    }
    TextButton(onClick = { edgesVisible = !edgesVisible }) { Text(stringResource(R.string.grammar_map_edge_details)) }
    if (edgesVisible) GrammarMapEdgeDetails(snapshot, destination, actions)
    if (chapter == null) {
        val unmapped = groupNodes.filter { groups.getValue(it.id).group.authored && ready?.graph?.chapterByGroup?.containsKey(it.id) != true }
        val compatibility = groupNodes.filter { !groups.getValue(it.id).group.authored }
        GrammarMapExtraTopics(
            if (ready == null) R.string.grammar_map_uncatalogued_topics else R.string.grammar_map_unmapped_topics, unmapped, selectedRoute, select,
        )
        GrammarMapExtraTopics(R.string.grammar_map_compatibility_topics, compatibility, selectedRoute, select)
    } else {
        GrammarMapExternalFoundations(snapshot, chapter.groups, destination, actions)
    }
}

@Composable
private fun GrammarMapExtraTopics(
    title: Int,
    nodes: List<GrammarMapNode>,
    selectedRoute: String?,
    onSelect: (GrammarMapDestination) -> Unit,
) {
    if (nodes.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(title, nodes.size)) }
    if (expanded) GrammarMapList(nodes, selectedRoute, onSelect)
}

@Composable
internal fun GrammarMapList(
    nodes: List<GrammarMapNode>,
    selectedRoute: String? = null,
    onSelect: (GrammarMapDestination) -> Unit,
) {
    if (nodes.isEmpty()) Text(stringResource(R.string.grammar_no_topics_match))
    key(nodes.map { it.destination }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(nodes.sortedWith(compareBy({ it.label }, { it.id })), key = { "${it.destination.chapter != null}:${it.id}" }) { node ->
                GrammarMapCard(node, Modifier.fillMaxWidth(), onSelect, selected = selectedRoute == GrammarRoadmapRoutes.create(node.destination))
            }
        }
    }
}
