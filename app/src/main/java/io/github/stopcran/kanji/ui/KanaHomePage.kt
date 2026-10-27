package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.kana.KanaCatalog
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript

@Composable
internal fun KanaPage(
    app: KanjiApp,
    onBack: () -> Unit,
    onStudy: (KanaScript, KanaMode, extra: Boolean) -> Unit,
) {
    KanaPage(rememberKanaOverview(app), onBack, onStudy)
}

@Composable
internal fun KanaPage(
    overview: KanaOverview,
    onBack: () -> Unit,
    onStudy: (KanaScript, KanaMode, extra: Boolean) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf(KanaScript.Hiragana.id) }
    val script = KanaScript.entries.first { it.id == selectedId }
    Page("Kana", onBack) {
        Text("${KanaCatalog.all(script).size} ${script.label} cards", style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.kana_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KanaScript.entries.forEach { option ->
                FilterChip(
                    selected = option == script,
                    onClick = { selectedId = option.id },
                    label = { Text(option.label) },
                )
            }
        }
        KanaProgressionHelp()
        KanaMode.entries.forEach { mode ->
            val locked = overview.lockedCount(script, mode)
            val lockSummary = when (locked) {
                null -> "Checking locked kana…"
                0 -> "No kana locked"
                1 -> "1 kana locked"
                else -> "$locked kana locked"
            }
            ModeRow(
                mode.title,
                overview.modeState(script, mode),
                onStart = { extra -> onStudy(script, mode, extra) },
                supportingText = lockSummary,
            )
        }
    }
}

@Composable
private fun KanaProgressionHelp() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.kana_help_hide else R.string.kana_help_show))
    }
    if (!expanded) return
    Text(stringResource(R.string.kana_help_combinations, KanaCatalog.UNLOCK_STABILITY_DAYS.toInt()))
    Text(stringResource(R.string.kana_help_voiced, KanaCatalog.VOICED_UNLOCK_STABILITY_DAYS.toInt()))
    Text(stringResource(R.string.kana_help_stability))
    Text(stringResource(R.string.kana_help_scope))
    Text(stringResource(R.string.kana_help_allowance))
    Text(stringResource(R.string.kana_help_typing))
}
