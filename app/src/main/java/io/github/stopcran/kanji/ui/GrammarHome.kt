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
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.data.GrammarPreferences
import io.github.stopcran.kanji.data.grammarModeState
import io.github.stopcran.kanji.data.grammarSets
import io.github.stopcran.kanji.data.newAllowance
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant

internal class GrammarHome(
    val sourceId: String,
    val preferences: GrammarPreferences,
    val sets: List<GrammarSet>?,
    val mode: ModeState,
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
            app.db.content().observeMeta(source.id).collectLatest {
                val sets = app.db.grammarSets(source.id)
                val now = Instant.now()
                val budget = app.db.newAllowance(app.settings, source.id, now).remaining
                value = GrammarHome(source.id, preferences, sets, app.db.grammarModeState(source.id, sets, budget, now, preferences))
            }
        }
    }
}

@Composable
fun GrammarPage(app: KanjiApp, onBack: () -> Unit, onOpenDoc: (String) -> Unit, a: HomeActions) {
    val home = rememberGrammarHome(app).value
    val preferences = home.preferences
    Page("Grammar", onBack) {
        Text(home.sets?.sumOf { it.exercises.size }?.let { "$it contrast exercises" } ?: "Loading…")
        Text("New grammar: ${preferences.newScope.introductionLabel}")
        GrammarScopeChips(preferences.newScope) { app.settings.setGrammarNewScope(home.sourceId, it) }
        Text(
            "Started exercises keep their reviews at every level. Practice more uses this scope for unseen exercises, outside the daily introduction limit.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!preferences.levelsReady && preferences.newScope != GrammarScope.All) {
            Text(GRAMMAR_LEVEL_SYNC_NEEDED)
        }
        ModeRow("Choose or type the construction", home.mode, onStart = a.onGrammar)
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
private fun GrammarScopeChips(selected: GrammarScope, onSelect: (GrammarScope) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GrammarScope.entries.forEach { scope ->
            FilterChip(selected = selected == scope, onClick = { onSelect(scope) }, label = { Text(scope.label) })
        }
    }
}
