package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.material3.Text
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.data.counterIndex
import io.github.stopcran.kanji.data.counterModeStates
import io.github.stopcran.kanji.data.newAllowance
import java.time.Instant

/** Counts for the Counters entry and page; loaded on entry, so they are fresh after every session. */
class CounterHome(val cards: Int?, val modes: Map<CounterMode, ModeState>) {
    fun summary(): String = when {
        cards == null -> "Loading…"
        cards == 0 -> "No cards yet"
        else -> {
            val due = modes.values.sumOf { (it as? ModeState.Start)?.due ?: 0 }
            val new = modes.values.sumOf { (it as? ModeState.Start)?.new ?: 0 }
            "$due due, $new new today"
        }
    }
}

@Composable
fun rememberCounterHome(app: KanjiApp): CounterHome {
    val source = app.settings.source.collectAsState().value
    val state: State<CounterHome> = produceState(CounterHome(null, emptyMap()), source.id) {
        val index = app.db.counterIndex(source.id)
        val now = Instant.now()
        val budget = app.db.newAllowance(app.settings, source.id, now).remaining
        value = CounterHome(index.cards.size, app.db.counterModeStates(source.id, index, budget, now))
    }
    return state.value
}

@Composable
fun CountersPage(app: KanjiApp, onBack: () -> Unit, a: HomeActions) {
    val home = rememberCounterHome(app)
    Page("Counters", onBack) {
        Text(if (home.cards == null) "Loading…" else "${home.cards} sentences")
        CounterMode.entries.forEach { mode ->
            ModeRow(mode.label, home.modes[mode] ?: ModeState.Loading) { a.onCounters(mode, it) }
        }
    }
}
