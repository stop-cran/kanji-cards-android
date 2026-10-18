package io.github.stopcran.kanji.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GRAMMAR_LEVEL_SYNC_NEEDED
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarFocusDecision
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.data.GrammarPreferences
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.changeGrammarFocus
import io.github.stopcran.kanji.data.changeGrammarGuided
import io.github.stopcran.kanji.data.changeGrammarScope
import io.github.stopcran.kanji.data.grammarStudy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant

internal class GrammarHome(
    val sourceId: String,
    val preferences: GrammarPreferences,
    val sets: List<GrammarSet>?,
    val mode: ModeState,
    val focus: GrammarFocusDecision? = null,
    val notice: String? = null,
) {
    fun summary(): String = when {
        sets == null -> "Loading…"
        sets.isEmpty() -> "No exercises yet"
        mode is ModeState.Start -> "${mode.due} due, ${mode.new} new today"
        mode == ModeState.PracticeMore -> "All caught up"
        mode is ModeState.Locked -> mode.reason
        mode is ModeState.Unavailable -> mode.reason
        else -> "Nothing to study yet"
    }
}

@Composable
internal fun rememberGrammarHome(app: KanjiApp): State<GrammarHome> {
    val source = app.settings.source.collectAsState().value
    val preferences = app.settings.grammarPreferences.collectAsState().value[source.id] ?: GrammarPreferences()
    val dailyNew = app.settings.dailyNewCards.collectAsState().value
    val weekPlan = app.settings.weekPlan.collectAsState().value
    return key(source.id, preferences, dailyNew, weekPlan) {
        produceState(GrammarHome(source.id, preferences, null, ModeState.Loading)) {
            combine(
                app.db.content().observeMeta(source.id),
                app.db.reviews().observeStates(source.id, GRAMMAR_STACK, "Grammar"),
                app.db.reviews().observeGrammarFocus(source.id),
            ) { _, _, _ -> Unit }.collectLatest {
                val study = app.db.grammarStudy(app.settings, source.id, Instant.now())
                value = GrammarHome(source.id, study.preferences, study.sets, study.mode, study.focus, study.notice)
            }
        }
    }
}

@Composable
fun GrammarPage(app: KanjiApp, onBack: () -> Unit, onOpenDoc: (String) -> Unit, a: HomeActions) {
    val home = rememberGrammarHome(app).value
    val preferences = home.preferences
    val coroutineScope = rememberCoroutineScope()
    Page("Grammar", onBack) {
        Text(home.sets?.sumOf { it.exercises.size }?.let { "$it contrast exercises" } ?: "Loading…")
        Text("New grammar: ${preferences.newScope.introductionLabel}")
        GrammarScopeChips(preferences.newScope) {
            coroutineScope.launch { app.db.changeGrammarScope(app.settings, home.sourceId, it) }
        }
        Text(
            "Started exercises keep their reviews at every level. Practice more uses this scope for unseen exercises, outside the daily introduction limit.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!preferences.levelsReady && preferences.newScope != GrammarScope.All) {
            Text(GRAMMAR_LEVEL_SYNC_NEEDED)
        }
        ModeRow("Choose or type the construction", home.mode, onStart = a.onGrammar)
        GrammarFocusControls(app, home)
        Text("Browse: ${preferences.browseScope.browseLabel}")
        GrammarScopeChips(preferences.browseScope) { app.settings.setGrammarBrowseScope(home.sourceId, it) }
        if (home.sets.isNullOrEmpty()) {
            Text("Grammar exercises are loaded from the selected content repository.")
        } else if (!preferences.levelsReady && preferences.browseScope != GrammarScope.All) {
            Text("Sync needed before browsing by level or Unclassified (settings).")
        } else {
            val matching = home.sets.map { set ->
                set to set.exercises.count { preferences.browseScope.matches(it.jlpt, cumulative = false) }
            }.filter { it.second > 0 }
            Text("${matching.sumOf { it.second }} matching exercises in ${matching.size} sets")
            matching.forEach { (set, count) ->
                Text(set.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text("$count of ${set.exercises.size} exercises match", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.TextButton(
                    onClick = { onOpenDoc(set.article) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Read the article") }
            }
        }
    }
}

@Composable
private fun GrammarFocusControls(app: KanjiApp, home: GrammarHome) {
    val scope = rememberCoroutineScope()
    var choosing by remember(home.sourceId) { mutableStateOf(false) }
    var error by remember(home.sourceId) { mutableStateOf<String?>(null) }
    fun choose(id: String) {
        scope.launch {
            error = null
            try {
                app.db.changeGrammarFocus(app.settings, home.sourceId, id, Instant.now())
                choosing = false
            } catch (e: IllegalArgumentException) {
                error = e.message ?: "Focus could not be changed."
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Focused introductions (pilot)", modifier = Modifier.weight(1f))
        Switch(
            checked = home.preferences.guided,
            onCheckedChange = { enabled ->
                scope.launch { app.db.changeGrammarGuided(app.settings, home.sourceId, enabled) }
            },
        )
    }
    if (!home.preferences.guided) return
    Text("New material stays on one contrast. Familiarity is not mastery. Practice more remains unguided.", style = MaterialTheme.typography.bodySmall)
    home.notice?.let { Text(it) }
    val focus = home.focus
    val current = focus?.current
    if (current != null) {
        Text(if (focus.continuing) "Focus: ${current.group.label}" else "Next focus (not committed): ${current.group.label}")
        current.group.facets.forEach { facet ->
            val evidence = current.evidence.facets.getValue(facet.id)
            val introduced = focus.activation?.introduced.orEmpty().count { it.facetId == facet.id }
            Text(
                "${facet.label}: $introduced/2 new this activation; ${evidence.startedKeys} started contexts; " +
                    "${evidence.positiveKeys} positive contexts, ${evidence.typedPositives} typed, ${evidence.spanMs / 3_600_000}h span.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            when {
                !current.group.authored -> "Legacy contrast: no familiarity or prerequisite claim. Focus releases at its quota or exhaustion."
                current.evidence.familiar -> "Supported familiarity (not mastery)."
                else -> "Familiarity not yet supported."
            },
        )
        current.evidence.diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (focus != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choosing = true }) { Text("Change focus") }
            OutlinedButton(onClick = { current?.let { choose(it.group.id) } }, enabled = current != null && current.blocked == null) {
                Text(if (focus.continuing) "Revisit" else "Use this focus")
            }
        }
    }
    error?.let { Text(it) }
    if (choosing && focus != null) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Choose a focus") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    val alternatives = focus.candidates.filter { !focus.continuing || it.group.id != current?.group?.id }
                    if (alternatives.none { it.blocked == null }) {
                        Text("No alternative focus qualifies. Revisit the current group or adjust the scope.")
                    }
                    alternatives.forEach { candidate ->
                        TextButton(onClick = { choose(candidate.group.id) }, enabled = candidate.blocked == null) {
                            Text(candidate.group.label)
                        }
                        candidate.blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    error?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun GrammarScopeChips(selected: GrammarScope, onSelect: (GrammarScope) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GrammarScope.entries.forEach { scope ->
            FilterChip(selected = selected == scope, onClick = { onSelect(scope) }, label = { Text(scope.label) })
        }
    }
}
