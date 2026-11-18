package io.github.stopcran.kanji.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.srs.StudyProgress

@Composable
internal fun <T> LevelFilter(
    choices: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    description: String? = null,
    enabled: Boolean = true,
    progress: (T) -> StudyProgress? = { null },
    skillLabel: @Composable (String) -> String = { it },
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            val selectedChoice = selected == choice
            val itemProgress = progress(choice)
            val total = itemProgress?.total
            val accessibleProgress = itemProgress?.let { value ->
                val count = value.total
                val percent = count.percent
                if (percent == null) {
                    stringResource(R.string.level_progress_unavailable, label(choice))
                } else {
                    stringResource(
                        R.string.level_progress_accessibility,
                        label(choice),
                        percent,
                        count.solid,
                        count.total,
                    )
                }
            }
            val shape = FilterChipDefaults.shape
            val colors = MaterialTheme.colorScheme
            Box(
                Modifier
                    .clip(shape)
                    .drawBehind {
                        drawRect(if (selectedChoice) colors.secondaryContainer else colors.surfaceVariant)
                        if (total != null && total.total > 0) {
                            val tint = if (selectedChoice) colors.onSecondaryContainer else colors.onSurface
                            drawRect(
                                tint.copy(alpha = 0.12f),
                                size = androidx.compose.ui.geometry.Size(size.width * total.fraction, size.height),
                            )
                        }
                    },
            ) {
                FilterChip(
                    selected = selectedChoice,
                    onClick = { onSelect(choice) },
                    enabled = enabled,
                    modifier = if (accessibleProgress == null) Modifier else Modifier.semantics {
                        contentDescription = accessibleProgress
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        selectedContainerColor = Color.Transparent,
                    ),
                    label = { Text(label(choice), maxLines = 1) },
                )
            }
        }
    }
    description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    val selectedProgress = progress(selected)
    selectedProgress?.let { value ->
        val total = value.total
        val percent = total.percent
        if (percent == null) {
            Text(stringResource(R.string.level_progress_unavailable, label(selected)), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                stringResource(R.string.level_progress_summary, percent, total.solid, total.total),
                style = MaterialTheme.typography.bodySmall,
            )
            value.skills.forEach { (skill, count) ->
                val percent = count.percent ?: return@forEach
                Text(
                    stringResource(R.string.level_progress_skill, skillLabel(skill), percent, count.solid, count.total),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
