package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun GrammarQuizScreen(
    sessionKey: String,
    onBack: () -> Unit,
    onOpenDoc: (String, String) -> Unit,
    onPracticeMore: () -> Unit,
    vm: GrammarQuizViewModel = serviceViewModel(sessionKey) { GrammarQuizViewModel(it) },
) {
    LaunchedEffect(sessionKey) { vm.ensureStarted(sessionKey.endsWith(":extra")) }
    val holder = rememberSaveableStateHolder()
    val article = vm.lookupArticle
    holder.SaveableStateProvider(if (article == null) "question" else "vocabulary:${article.word}") {
        if (article != null) {
            GrammarVocabularyArticle(article, vm::closeLookupArticle) { onOpenDoc(vm.sourceId, it) }
        } else {
            GrammarSessionBody(vm, onBack, onOpenDoc, onPracticeMore)
        }
    }
}

@Composable
private fun GrammarSessionBody(
    vm: GrammarQuizViewModel,
    onBack: () -> Unit,
    onOpenDoc: (String, String) -> Unit,
    onPracticeMore: () -> Unit,
) {
    GrammarVocabularyHelp(vm)
    Page("Grammar", onBack, ime = true, scrollToTopOnBack = true) {
        vm.focusNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when (val state = vm.ui) {
            GrammarUi.Loading -> Text("Loading…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            is GrammarUi.Empty -> Text(state.message)
            is GrammarUi.Done -> {
                Text("Session complete", style = MaterialTheme.typography.headlineSmall)
                Text("${state.correct} of ${state.answered} answers correct")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text("Practice more") }
            }
            is GrammarUi.Choose -> {
                GrammarRemaining(state.remaining)
                GrammarPrompt(vm, state.exercise)
                Text(
                    if (state.choices.correctOptionIds.size > 1) "Choose any one of the correct answers."
                    else "Choose the construction that best fits the context.",
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
                state.options.forEach { option ->
                    OutlinedButton(onClick = { vm.pick(option.id, state) }, modifier = Modifier.fillMaxWidth()) { Text(option.label) }
                }
                DontKnowButton { vm.pick(null, state) }
            }
            is GrammarUi.Type -> {
                GrammarRemaining(state.remaining)
                GrammarPrompt(vm, state.exercise)
                Text("Type the construction (not the whole sentence).", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                TypeAnswer(vm)
            }
            is GrammarUi.Answer -> {
                Text(
                    if (state.correct) "Correct!" else "Not quite",
                    color = if (state.correct) correctTextColor() else wrongTextColor(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                GrammarPrompt(vm, state.exercise, state.completion)
                if (state.response.isNotEmpty()) {
                    Text("Your answer: ${state.responseLabel}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                GrammarOptionFeedback(state)
                Text(state.exercise.evidence, style = MaterialTheme.typography.bodyMedium)
                Text(state.exercise.explanation, style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { onOpenDoc(vm.sourceId, vm.articlePath(state.exercise.id)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Read the grammar article")
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Button(onClick = { vm.next(origin = state) }, modifier = Modifier.weight(1f)) { Text(if (state.correct) "Next" else "Next (will repeat)") }
                    if (state.correct && state.type == "choose") {
                        OutlinedButton(onClick = { vm.next(guessed = true, origin = state) }) { Text("I guessed") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GrammarOptionFeedback(state: GrammarUi.Answer) {
    state.options.forEach { option ->
        val correct = option.id in state.correctOptionIds
        val selectedWrong = !correct && state.type == "choose" && option.id == state.response
        val prefix = when {
            correct -> "✓ Correct: "
            selectedWrong -> "✗ Your answer: "
            else -> ""
        }
        val color = when {
            correct -> correctTextColor()
            selectedWrong -> wrongTextColor()
            else -> MaterialTheme.colorScheme.onSurface
        }
        Text("$prefix${option.label} — ${option.rationale}", color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TypeAnswer(vm: GrammarQuizViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = vm.draft,
        onValueChange = vm::onDraft,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.check() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { vm.check() }, enabled = vm.draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Check") }
    DontKnowButton { vm.check(giveUp = true) }
}

@Composable
private fun GrammarRemaining(count: Int) {
    Text("Questions remaining: $count", style = MaterialTheme.typography.labelMedium)
}
