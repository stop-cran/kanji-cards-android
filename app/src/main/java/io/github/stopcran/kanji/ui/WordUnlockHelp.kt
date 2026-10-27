package io.github.stopcran.kanji.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordGate
import java.text.NumberFormat

@Composable
internal fun WordUnlockHelp() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.word_unlock_hide else R.string.word_unlock_show))
    }
    if (!expanded) return
    Text(stringResource(R.string.word_unlock_path))
    Text(stringResource(
        R.string.word_unlock_rule, WordGate.MIN_FORWARD_REPS,
        NumberFormat.getNumberInstance().format(WordGate.MIN_FORWARD_STABILITY),
    ))
    Text(stringResource(R.string.word_unlock_stability))
    Text(stringResource(R.string.word_unlock_pool))
    Text(stringResource(R.string.word_unlock_scope))
}

@Composable
internal fun WordModeRow(
    data: HomeData, title: String, mode: HomeMode, direction: WordDirection, onStart: (Boolean) -> Unit,
) {
    val readiness = data.wordReadiness[direction]
    val state = data.mode(mode)
    val explained = if (state is ModeState.Locked && direction.gated) {
        ModeState.Locked(stringResource(R.string.word_unlock_practise, direction.gate.label))
    } else state
    val detail = readiness?.takeIf { direction.gated }?.let {
        stringResource(R.string.word_unlock_count, it.unlocked, it.candidates)
    }
    ModeRow(title, explained, detail, onStart)
}
