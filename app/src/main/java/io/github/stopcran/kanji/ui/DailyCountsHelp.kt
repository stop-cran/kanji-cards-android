package io.github.stopcran.kanji.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.PacingNote

@Composable
internal fun DailyCountsHelp(kana: NewAllowance? = null, content: NewAllowance? = null) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.daily_counts_hide else R.string.daily_counts_show))
    }
    if (!expanded) return
    Text(stringResource(R.string.daily_counts_kana))
    AllowanceStatus(R.string.kana_allowance_remaining, kana)
    Text(stringResource(R.string.daily_counts_content))
    AllowanceStatus(R.string.content_allowance_remaining, content)
    Text(stringResource(R.string.daily_counts_opportunities))
    Text(stringResource(R.string.daily_counts_listening))
    Text(stringResource(R.string.daily_counts_reviews))
}

@Composable
private fun AllowanceStatus(label: Int, allowance: NewAllowance?) {
    if (allowance == null) return
    Text(stringResource(label, allowance.remaining, allowance.total), style = MaterialTheme.typography.titleSmall)
    val explanation = when {
        allowance.total == 0 && allowance.note in setOf(PacingNote.None, PacingNote.LightDay) ->
            stringResource(R.string.daily_counts_disabled)
        allowance.remaining == 0 && allowance.total > 0 ->
            stringResource(R.string.daily_counts_used)
        else -> pacingExplanation(allowance)
    }
    explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
