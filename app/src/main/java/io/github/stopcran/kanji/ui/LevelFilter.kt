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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun <T> LevelFilter(
    choices: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(selected = selected == choice, onClick = { onSelect(choice) }, enabled = enabled,
                label = { Text(label(choice), maxLines = 1) })
        }
    }
    description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
