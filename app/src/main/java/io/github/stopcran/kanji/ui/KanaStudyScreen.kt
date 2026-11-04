package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.ui.KanaStudyUi.DrawAnswer

@Composable
fun KanaStudyScreen(
    sessionKey: String,
    onBack: () -> Unit,
    onPracticeMore: (KanaScript, KanaMode) -> Unit,
    vm: KanaStudyViewModel = serviceViewModel(sessionKey) { KanaStudyViewModel(it) },
) {
    val parts = sessionKey.split(":")
    val script = KanaScript.entries.first { it.id == parts[1] }
    val mode = KanaMode.valueOf(parts[2])
    LaunchedEffect(sessionKey) { vm.ensureStarted(script, mode, sessionKey.endsWith(":extra")) }
    val app = LocalContext.current.applicationContext as KanjiApp
    val brush by app.settings.brush.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalBrush provides brush, LocalBrushLook provides vm.look) {
        Page(stringResource(kanaModeLabel(mode)), onBack, ime = mode == KanaMode.TypeKanaToRomaji || mode == KanaMode.TypeRomajiToKana,
            scrollToTopOnBack = true) {
            when (val state = vm.ui) {
                KanaStudyUi.Loading -> Text(stringResource(R.string.kana_loading))
                is KanaStudyUi.Empty -> Text(stringResource(when (state.reason) {
                    KanaEmptyReason.NoPractice -> R.string.kana_no_practice
                    KanaEmptyReason.PracticeMore -> R.string.kana_nothing_due_practice_more
                    KanaEmptyReason.NothingDue -> R.string.kana_nothing_due
                }))
                is KanaStudyUi.Done -> {
                    Text(stringResource(R.string.session_complete), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.of_answers_correct, state.correct, state.answered))
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                    OutlinedButton(
                        onClick = { onPracticeMore(script, mode) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.practice_more))
                    }
                }
                is KanaStudyUi.QuizQuestion -> QuizQuestion(state, mode, vm)
                is KanaStudyUi.QuizAnswer -> QuizAnswer(state, mode, vm)
                is KanaStudyUi.TypeQuestion -> TypeQuestion(state.card.kana, state.card.romaji, state.remaining, mode, vm)
                is KanaStudyUi.TypeAnswer -> TypeAnswer(state, mode, vm)
                is KanaStudyUi.DrawQuestion -> DrawQuestion(state, vm)
                is KanaStudyUi.DrawChecking -> Text(stringResource(R.string.kana_checking_strokes, state.card.kana))
                is DrawAnswer -> DrawAnswer(state, vm)
            }
        }
    }
}

@Composable
private fun QuizQuestion(state: KanaStudyUi.QuizQuestion, mode: KanaMode, vm: KanaStudyViewModel) {
    SessionRemaining(state.remaining)
    val forward = mode == KanaMode.QuizKanaToRomaji
    Text(
        if (forward) state.card.kana else state.card.romaji,
        fontSize = if (forward) 104.sp else 54.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
    Text(
        stringResource(if (forward) R.string.kana_choose_romaji else R.string.kana_choose_kana),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    state.options.forEach { option ->
        OutlinedButton(onClick = { vm.pick(option) }, modifier = Modifier.fillMaxWidth()) {
            Text(option, fontSize = 24.sp)
        }
    }
    OutlinedButton(onClick = { vm.pick(NO_ANSWER) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.kana_dont_know)) }
}

@Composable
private fun QuizAnswer(state: KanaStudyUi.QuizAnswer, mode: KanaMode, vm: KanaStudyViewModel) {
    val expected = if (mode == KanaMode.QuizKanaToRomaji) state.card.romaji else state.card.kana
    val correct = state.picked == expected
    Text(state.card.kana, fontSize = 80.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        if (correct) stringResource(R.string.kana_correct) else if (state.picked == NO_ANSWER) {
            stringResource(R.string.kana_answer_is, expected)
        } else stringResource(R.string.kana_not_quite_answer, expected),
        color = if (correct) correctTextColor() else wrongTextColor(),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    state.options.forEach { option ->
        val answer = option == expected
        Text(
            option,
            color = when {
                answer -> correctTextColor()
                option == state.picked -> wrongTextColor()
                else -> MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
    NextButtons(correct, vm)
}

@Composable
private fun TypeQuestion(kana: String, romaji: String, remaining: Int, mode: KanaMode, vm: KanaStudyViewModel) {
    val forward = mode == KanaMode.TypeKanaToRomaji
    val focus = remember { FocusRequester() }
    LaunchedEffect(kana, mode) { focus.requestFocus() }
    SessionRemaining(remaining)
    Text(
        if (forward) kana else romaji,
        fontSize = if (forward) 96.sp else 52.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
    OutlinedTextField(
        value = vm.draft,
        onValueChange = vm::onDraft,
        label = { Text(stringResource(if (forward) R.string.kana_mode_type_kana_romaji else R.string.kana_mode_type_romaji_kana)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = if (forward) KeyboardType.Ascii else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.submit(giveUp = true) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.kana_remember)) }
        Button(onClick = vm::submit, enabled = vm.draft.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.check)) }
    }
}

@Composable
private fun TypeAnswer(state: KanaStudyUi.TypeAnswer, mode: KanaMode, vm: KanaStudyViewModel) {
    val answer = if (mode == KanaMode.TypeKanaToRomaji) state.card.romaji else state.card.kana
    Text(state.card.kana, fontSize = 80.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        if (state.correct) stringResource(R.string.kana_correct) else if (state.typed.isBlank()) {
            stringResource(R.string.kana_answer_is, answer)
        } else stringResource(R.string.kana_not_quite_answer, answer),
        color = if (state.correct) correctTextColor() else wrongTextColor(),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text(stringResource(R.string.kana_answer_feedback, state.typed.ifBlank { "—" }), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    NextButtons(state.correct, vm)
}

@Composable
private fun DrawQuestion(state: KanaStudyUi.DrawQuestion, vm: KanaStudyViewModel) {
    val strokes = vm.ink
    SessionRemaining(state.remaining)
    Text(
        stringResource(if (state.trace) R.string.kana_trace_prompt else R.string.kana_draw_prompt),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(state.card.romaji, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (state.trace) {
        Text(stringResource(R.string.tracing_practice_notice), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    DrawingPad(
        strokes = strokes,
        onStroke = { strokes += it },
        onSize = vm::padSized,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ghost = if (state.trace) vm.reference(state.card) else null,
        subject = R.string.drawing_subject_kana,
    )
    when (vm.modelState) {
        ModelState.Downloading -> Text(stringResource(R.string.kana_prepare_recognition), style = MaterialTheme.typography.bodySmall)
        ModelState.Unavailable -> Text(
            stringResource(R.string.kana_recognition_unavailable),
            style = MaterialTheme.typography.bodySmall,
        )
        else -> Unit
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
            Text(stringResource(R.string.undo))
        }
        OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.clear)) }
        Button(
            onClick = { vm.checkDrawing(strokes.toList(), vm.inkSize) },
            enabled = strokes.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.check))
        }
    }
    if (!state.trace) {
        OutlinedButton(
            onClick = { vm.checkDrawing(emptyList(), vm.inkSize) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.kana_remember))
        }
    }
}

@Composable
private fun DrawAnswer(state: DrawAnswer, vm: KanaStudyViewModel) {
    val issues = state.outcome == DrawOutcome.Mistakes
    val (headline, color) = when (state.outcome) {
        DrawOutcome.Clean -> stringResource(R.string.kana_well_written) to correctTextColor()
        DrawOutcome.Mistakes -> stringResource(R.string.kana_recognized_mistakes) to wrongTextColor()
        DrawOutcome.NotRecognized -> stringResource(R.string.kana_not_recognized) to wrongTextColor()
    }
    Text(state.card.kana, fontSize = 80.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (state.trace) {
        Text(
            stringResource(R.string.kana_practice_only),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(headline, color = color, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Overlay(Modifier.weight(1f)) {
            val flagged = if (issues) state.match.issues.mapNotNull { it.drawnIndex }.toSet() else emptySet()
            YourDrawing(state.drawn, state.canvasPx, flagged, state.outcome == DrawOutcome.NotRecognized)
        }
        Overlay(Modifier.weight(1f)) {
            Reference(state.reference, if (issues) state.match.flaggedRefStrokes else emptySet(), numbers = true)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.kana_your_drawing), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        Text(stringResource(R.string.kana_correct_strokes), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
    if (issues) state.match.issues.forEach { StrokeIssueText(it) }
    if (!state.recognizerUsed && state.drawn.isNotEmpty()) {
        Text(stringResource(R.string.kana_shapes_only), style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = vm::next,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.next))
    }
}

@Composable
private fun NextButtons(correct: Boolean, vm: KanaStudyViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::next, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.next))
        }
        if (correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text(stringResource(R.string.i_guessed)) }
    }
}

internal fun kanaModeLabel(mode: KanaMode): Int = when (mode) {
    KanaMode.QuizKanaToRomaji -> R.string.kana_mode_quiz_kana_romaji
    KanaMode.QuizRomajiToKana -> R.string.kana_mode_quiz_romaji_kana
    KanaMode.Draw -> R.string.kana_mode_draw
    KanaMode.TypeKanaToRomaji -> R.string.kana_mode_type_kana_romaji
    KanaMode.TypeRomajiToKana -> R.string.kana_mode_type_romaji_kana
}

internal fun kanaScriptLabel(script: KanaScript): Int = when (script) {
    KanaScript.Hiragana -> R.string.kana_script_hiragana
    KanaScript.Katakana -> R.string.kana_script_katakana
}
