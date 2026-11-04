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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.Services
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
            val new = minOf(allowance, starts.sumOf { it.new })
            when {
                starts.isNotEmpty() -> "${starts.sumOf { it.due }} due, $new new"
                modes.values.any { it == ModeState.PracticeMore } -> "All caught up"
                else -> "Nothing to study yet"
            }
        }
    }

    @Composable
    fun localizedSummary(): String = when {
        cards == null -> stringResource(R.string.loading)
        cards == 0 -> stringResource(R.string.counter_no_cards)
        else -> {
            val starts = modes.values.mapNotNull { it as? ModeState.Start }
            val new = minOf(allowance, starts.sumOf { it.new })
            when {
                starts.isNotEmpty() -> stringResource(R.string.study_due_new, starts.sumOf { it.due }, new)
                modes.values.any { it == ModeState.PracticeMore } -> stringResource(R.string.mode_nothing_due)
                else -> stringResource(R.string.counter_nothing_to_study)
            }
        }
    }
}

@Composable
fun rememberCounterHome(app: KanjiApp, group: CounterGroup = CounterGroup.All): CounterHome =
    rememberCounterHome(app.services, group)

@Composable
internal fun rememberCounterHome(app: Services, group: CounterGroup = CounterGroup.All): CounterHome {
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
                index.files.mapNotNull { f -> f.article?.let { it to app.context.getString(R.string.counter_article_reference) } }.toMap(),
            )
        }
    }
    return state.value
}

@Composable
fun CountersPage(app: KanjiApp, onBack: () -> Unit, a: HomeActions) =
    CountersPage(app.services, onBack, a.onCounters, a.onOpenDoc)

@Composable
internal fun CountersPage(
    app: Services,
    onBack: () -> Unit,
    onCounters: (CounterMode, Boolean, CounterGroup, CounterScope) -> Unit,
    onOpenDoc: (String) -> Unit,
) {
    val context = LocalContext.current
    val source = app.settings.source.collectAsState().value
    val scope = app.settings.counterScopes.collectAsState().value[source.id] ?: CounterScope.N5
    var selected by rememberSaveable(source.id, source.branch) { mutableStateOf(CounterGroup.All.code) }
    val group = CounterGroup.fromCode(selected)
    val home = rememberCounterHome(app, group)
    Page(stringResource(R.string.counter_page_title), onBack, scrollable = home.cards != null) {
        LevelFilter(CounterScope.entries, scope, { app.settings.setCounterScope(source.id, it) }, { context.getString(counterScopeLabel(it)) },
            description = if (scope == CounterScope.N4 || scope == CounterScope.N3)
                stringResource(R.string.level_filter_includes_easier) else null)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CounterGroup.entries.forEach { item ->
                FilterChip(selected = group == item, onClick = { selected = item.code }, label = { Text(stringResource(counterGroupLabel(item))) })
            }
        }
        if (home.cards == null) Text(stringResource(R.string.loading))
        else Text(pluralStringResource(R.plurals.counter_prompts, home.cards, home.cards))
        Text(stringResource(R.string.counter_levels_disclaimer))
        Text(stringResource(R.string.counter_shared_allowance, home.allowance))
        Text(stringResource(R.string.counter_page_guidance))
        home.modes.forEach { (mode, state) ->
            ModeRow(stringResource(counterModeLabel(mode)), state, onStart = { onCounters(mode, it, group, scope) })
        }
        home.articles.forEach { (path, label) ->
            TextButton(onClick = { onOpenDoc(path) }) { Text(label) }
        }
    }
}

private fun counterScopeLabel(scope: CounterScope): Int = when (scope) {
    CounterScope.N5 -> R.string.level_n5
    CounterScope.N4 -> R.string.level_n4
    CounterScope.N3 -> R.string.level_n3
    CounterScope.All -> R.string.counter_scope_all
}

private fun counterGroupLabel(group: CounterGroup): Int = when (group) {
    CounterGroup.All -> R.string.counter_group_all
    CounterGroup.Everyday -> R.string.counter_group_everyday
    CounterGroup.Time -> R.string.counter_group_time
}

internal fun counterModeLabel(mode: CounterMode): Int = when (mode) {
    CounterMode.Choose -> R.string.counter_mode_choose
    CounterMode.Type -> R.string.counter_mode_type
    CounterMode.Noun -> R.string.counter_mode_noun
    CounterMode.Expression -> R.string.counter_mode_expression
    CounterMode.Reading -> R.string.counter_mode_reading
}
