package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.srs.NewAllowance

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeHelp(
    guideVisible: Boolean,
    onGuideVisibility: (Boolean) -> Unit,
    onKana: () -> Unit,
    onWords: () -> Unit,
    onGrammar: () -> Unit,
    kana: NewAllowance?,
    content: NewAllowance?,
) {
    var countsExpanded by rememberSaveable { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!guideVisible) StartingGuide(false, onGuideVisibility, onKana, onWords, onGrammar)
        DailyCountsHelpToggle(countsExpanded) { countsExpanded = !countsExpanded }
    }
    if (guideVisible) StartingGuide(true, onGuideVisibility, onKana, onWords, onGrammar)
    if (countsExpanded) DailyCountsHelpContent(kana, content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StartingGuide(visible: Boolean, onVisibility: (Boolean) -> Unit, onKana: () -> Unit, onWords: () -> Unit, onGrammar: () -> Unit) {
    if (!visible) {
        TextButton(onClick = { onVisibility(true) }) {
            Text(stringResource(R.string.starting_guide_title))
        }
        return
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.starting_guide_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onVisibility(false) }) {
                    Text(stringResource(R.string.starting_guide_hide))
                }
            }
            Text(stringResource(R.string.starting_guide_choices))
            Text(stringResource(R.string.starting_guide_recording))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onKana) { Text(stringResource(R.string.starting_guide_kana)) }
                TextButton(onClick = onWords) { Text(stringResource(R.string.starting_guide_words)) }
                TextButton(onClick = onGrammar) { Text(stringResource(R.string.starting_guide_grammar)) }
            }
        }
    }
}
