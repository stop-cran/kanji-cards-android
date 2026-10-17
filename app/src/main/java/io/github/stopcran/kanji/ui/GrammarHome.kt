package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.data.grammarModeState
import io.github.stopcran.kanji.data.grammarSets
import io.github.stopcran.kanji.data.newAllowance
import java.time.Instant

internal class GrammarHome(val sets: List<GrammarSet>?, val mode: ModeState) {
    fun summary(): String = when {
        sets == null -> "Loading…"
        sets.isEmpty() -> "No exercises yet"
        mode is ModeState.Start -> "${mode.due} due, ${mode.new} new today"
        mode == ModeState.PracticeMore -> "All caught up"
        else -> "Nothing to study yet"
    }
}

@Composable
internal fun rememberGrammarHome(app: KanjiApp): State<GrammarHome> {
    val source = app.settings.source.collectAsState().value
    return produceState(GrammarHome(null, ModeState.Loading), source.id) {
        val sets = app.db.grammarSets(source.id)
        val budget = app.db.newAllowance(app.settings, source.id, Instant.now()).remaining
        value = GrammarHome(sets, app.db.grammarModeState(source.id, sets, budget, Instant.now()))
    }
}

@Composable
fun GrammarPage(app: KanjiApp, onBack: () -> Unit, onOpenDoc: (String) -> Unit, a: HomeActions) {
    val home = rememberGrammarHome(app).value
    Page("Grammar", onBack) {
        Text(home.sets?.sumOf { it.exercises.size }?.let { "$it contrast exercises" } ?: "Loading…")
        if (home.sets.isNullOrEmpty()) {
            Text("Grammar exercises are loaded from the selected content repository.")
        } else {
            home.sets.forEach { set ->
                Text(set.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                androidx.compose.material3.TextButton(
                    onClick = { onOpenDoc(set.article) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Read the article") }
            }
            ModeRow("Choose or type the construction", home.mode, onStart = a.onGrammar)
        }
    }
}
