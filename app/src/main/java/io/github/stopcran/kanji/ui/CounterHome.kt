package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.data.counterIndex
import io.github.stopcran.kanji.data.counterModeStates
import io.github.stopcran.kanji.data.newAllowance
import java.time.Instant
import kotlinx.coroutines.flow.collectLatest

/** Counts for the Counters entry and page; loaded on entry, so they are fresh after every session. */
class CounterHome(
    val cards: Int?, val modes: Map<CounterMode, ModeState>, val allowance: Int = 0,
    val articles: Map<String, String> = emptyMap(),
) {
    fun summary(): String = when {
        cards == null -> "Loading…"
        cards == 0 -> "No cards yet"
        else -> {
            val starts = modes.values.mapNotNull { it as? ModeState.Start }
            when {
                starts.isNotEmpty() -> "${starts.sumOf { it.due }} due; $allowance new-item allowance"
                modes.values.any { it == ModeState.PracticeMore } -> "All caught up"
                else -> "Nothing to study yet"
            }
        }
    }
}

@Composable
fun rememberCounterHome(app: KanjiApp, group: CounterGroup = CounterGroup.All): CounterHome {
    val source = app.settings.source.collectAsState().value
    val scope = app.settings.counterScopes.collectAsState().value[source.id] ?: CounterScope.N5
    val daily = app.settings.dailyNewCards.collectAsState().value
    val week = app.settings.weekPlan.collectAsState().value
    val state: State<CounterHome> = produceState(CounterHome(null, emptyMap()), source.id, group to scope, daily to week) {
        value = CounterHome(null, emptyMap())
        app.db.content().observeMeta(source.id).collectLatest {
            val index = app.db.counterIndex(source.id).inScope(group, scope)
            val now = Instant.now()
            val budget = app.db.newAllowance(app.settings, source.id, now).remaining
            value = CounterHome(
                index.cards.size + index.expressions.size, app.db.counterModeStates(source.id, index, budget, now), budget,
                index.files.mapNotNull { f -> f.article?.let { it to "Counting expressions: reference" } }.toMap(),
            )
        }
    }
    return state.value
}

@Composable
fun CountersPage(app: KanjiApp, onBack: () -> Unit, a: HomeActions) {
    val source = app.settings.source.collectAsState().value
    val scope = app.settings.counterScopes.collectAsState().value[source.id] ?: CounterScope.N5
    var selected by rememberSaveable(source.id, source.branch) { mutableStateOf(CounterGroup.All.code) }
    val group = CounterGroup.fromCode(selected)
    val home = rememberCounterHome(app, group)
    Page("Numbers & counters", onBack, scrollable = home.cards != null) {
        LevelFilter(CounterScope.entries, scope, { app.settings.setCounterScope(source.id, it) }, { it.label },
            description = if (scope == CounterScope.N4)
                stringResource(R.string.level_filter_includes_easier) else null)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CounterGroup.entries.forEach { item ->
                FilterChip(selected = group == item, onClick = { selected = item.code }, label = { Text(item.label) })
            }
        }
        Text(if (home.cards == null) "Loading…" else "${home.cards} prompts in this selection")
        Text("Levels are learning targets, not an official JLPT syllabus. Filters keep the same review history.")
        Text(stringResource(R.string.counter_shared_allowance, home.allowance))
        Text("Choose a counter, write a complete expression, or recall its complete kana reading. Each skill has its own review history.")
        home.modes.forEach { (mode, state) ->
            ModeRow(mode.label, state, onStart = { a.onCounters(mode, it, group, scope) })
        }
        home.articles.forEach { (path, label) ->
            TextButton(onClick = { a.onOpenDoc(path) }) { Text(label) }
        }
    }
}
