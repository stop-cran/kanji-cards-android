package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.kana.KanaCatalog
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript

@Composable
internal fun KanaPage(
    app: KanjiApp,
    onBack: () -> Unit,
    onStudy: (KanaScript, KanaMode, extra: Boolean) -> Unit,
) {
    val overview = rememberKanaOverview(app)
    var selectedId by rememberSaveable { mutableStateOf(KanaScript.Hiragana.id) }
    val script = KanaScript.entries.first { it.id == selectedId }
    Page("Kana", onBack) {
        Text("${KanaCatalog.all(script).size} ${script.label} cards", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Includes the basic syllabary, voiced and semi-voiced kana, and common yōon combinations. " +
                "A combination such as ちゅ unlocks separately in each mode once both parts (ち and ゆ) have FSRS stability of at least " +
                "${KanaCatalog.UNLOCK_STABILITY_DAYS.toInt()} days in that mode. A voiced or semi-voiced kana such as が or ぱ " +
                "unlocks the same way once its plain kana (か, は) reaches ${KanaCatalog.VOICED_UNLOCK_STABILITY_DAYS.toInt()} days. " +
                "Hiragana and katakana keep separate review schedules. The uncommon ぢ and づ use di/du " +
                "to keep reverse romaji questions distinct; ji/zu are accepted when typing.",
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
