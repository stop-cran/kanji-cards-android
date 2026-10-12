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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.describe
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
        Page(mode.title, onBack, scrollToTopOnBack = true) {
            when (val state = vm.ui) {
                KanaStudyUi.Loading -> Text("Loading kana…")
                is KanaStudyUi.Empty -> Text(state.message)
                is KanaStudyUi.Done -> {
                    Text("Session complete", style = MaterialTheme.typography.headlineSmall)
                    Text("${state.correct} correct out of ${state.answered}")
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    OutlinedButton(
                        onClick = { onPracticeMore(script, mode) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Practice more")
                    }
                }
                is KanaStudyUi.QuizQuestion -> QuizQuestion(state, mode, vm)
                is KanaStudyUi.QuizAnswer -> QuizAnswer(state, mode, vm)
                is KanaStudyUi.TypeQuestion -> TypeQuestion(state.card.kana, state.card.romaji, state.remaining, mode, vm)
                is KanaStudyUi.TypeAnswer -> TypeAnswer(state, mode, vm)
                is KanaStudyUi.DrawQuestion -> DrawQuestion(state.card.romaji, state.remaining, vm)
                is KanaStudyUi.DrawChecking -> Text("Checking strokes for ${state.card.kana}…")
                is DrawAnswer -> DrawAnswer(state, vm)
            }
        }
    }
}

@Composable
private fun QuizQuestion(state: KanaStudyUi.QuizQuestion, mode: KanaMode, vm: KanaStudyViewModel) {
    Text("Left: ${state.remaining}", style = MaterialTheme.typography.labelMedium)
    val forward = mode == KanaMode.QuizKanaToRomaji
    Text(
        if (forward) state.card.kana else state.card.romaji,
        fontSize = if (forward) 104.sp else 54.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
    Text(if (forward) "Choose the romaji" else "Choose the kana", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    state.options.forEach { option ->
        OutlinedButton(onClick = { vm.pick(option) }, modifier = Modifier.fillMaxWidth()) {
            Text(option, fontSize = 24.sp)
        }
    }
    OutlinedButton(onClick = { vm.pick(NO_ANSWER) }, modifier = Modifier.fillMaxWidth()) { Text("I don't know") }
}

@Composable
private fun QuizAnswer(state: KanaStudyUi.QuizAnswer, mode: KanaMode, vm: KanaStudyViewModel) {
    val expected = if (mode == KanaMode.QuizKanaToRomaji) state.card.romaji else state.card.kana
    val correct = state.picked == expected
    Text(state.card.kana, fontSize = 80.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        if (correct) "Correct!" else if (state.picked == NO_ANSWER) "It is $expected" else "Not quite — $expected",
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
    Text("Left: $remaining", style = MaterialTheme.typography.labelMedium)
    Text(
        if (forward) kana else romaji,
        fontSize = if (forward) 96.sp else 52.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
    OutlinedTextField(
        value = vm.draft,
        onValueChange = vm::onDraft,
        label = { Text(if (forward) "Type the romaji" else "Type the kana") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = if (forward) KeyboardType.Ascii else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.submit(giveUp = true) }, modifier = Modifier.weight(1f)) { Text("I don't remember") }
        Button(onClick = vm::submit, enabled = vm.draft.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Check") }
    }
}

@Composable
private fun TypeAnswer(state: KanaStudyUi.TypeAnswer, mode: KanaMode, vm: KanaStudyViewModel) {
    val answer = if (mode == KanaMode.TypeKanaToRomaji) state.card.romaji else state.card.kana
    Text(state.card.kana, fontSize = 80.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        if (state.correct) "Correct!" else if (state.typed.isBlank()) "The answer is $answer" else "Not quite — the answer is $answer",
        color = if (state.correct) correctTextColor() else wrongTextColor(),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    Text("Your answer: ${state.typed.ifBlank { "—" }}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    NextButtons(state.correct, vm)
}

@Composable
private fun DrawQuestion(romaji: String, remaining: Int, vm: KanaStudyViewModel) {
    val strokes = vm.ink
    Text("Left: $remaining", style = MaterialTheme.typography.labelMedium)
    Text("Draw the kana for", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(romaji, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    DrawingPad(
        strokes = strokes,
        onStroke = { strokes += it },
        onSize = vm::padSized,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        subject = "kana",
    )
    when (vm.modelState) {
        ModelState.Downloading -> Text("Preparing handwriting recognition…", style = MaterialTheme.typography.bodySmall)
        ModelState.Unavailable -> Text(
            "Handwriting recognition is unavailable; stroke shapes will still be checked.",
            style = MaterialTheme.typography.bodySmall,
        )
        else -> Unit
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
            Text("Undo")
        }
        OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
        Button(
            onClick = { vm.checkDrawing(strokes.toList(), vm.inkSize) },
            enabled = strokes.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) {
            Text("Check")
        }
    }
    OutlinedButton(
        onClick = { vm.checkDrawing(emptyList(), vm.inkSize) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("I don't remember")
    }
}

@Composable
private fun DrawAnswer(state: DrawAnswer, vm: KanaStudyViewModel) {
    val issues = state.outcome == DrawOutcome.Mistakes
    val (headline, color) = when (state.outcome) {
        DrawOutcome.Clean -> "Well written!" to correctTextColor()
        DrawOutcome.Mistakes -> "Recognized, with stroke mistakes" to wrongTextColor()
        DrawOutcome.NotRecognized -> "Not recognized" to wrongTextColor()
    }
    Text(state.card.kana, fontSize = 80.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
        Text("Your drawing", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        Text("Correct strokes", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
    if (issues) state.match.issues.forEach { Text("• ${it.describe()}", color = wrongTextColor()) }
    if (!state.recognizerUsed && state.drawn.isNotEmpty()) {
        Text("Graded by stroke shapes only.", style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = vm::next,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.outcome == DrawOutcome.NotRecognized) "Next (will repeat)" else "Next")
    }
}

@Composable
private fun NextButtons(correct: Boolean, vm: KanaStudyViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::next, modifier = Modifier.weight(1f)) {
            Text(if (correct) "Next" else "Next (will repeat)")
        }
        if (correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text("I guessed") }
    }
}
