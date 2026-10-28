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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterVerdict
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterScope

/** Session key: "cquiz:<timestamp>:<mode>:<group>:<scope>[:extra]"; old routes use All levels. */
@Composable
fun CounterQuizScreen(
    sessionKey: String,
    onBack: () -> Unit,
    onPracticeMore: () -> Unit,
    onOpenDoc: (String, String) -> Unit,
    vm: CounterQuizViewModel = serviceViewModel(sessionKey) { CounterQuizViewModel(it) },
) {
    val mode = CounterMode.fromCode(sessionKey.split(':').getOrNull(2))
    val extra = sessionKey.endsWith(":extra")
    LaunchedEffect(sessionKey) {
        vm.ensureStarted(mode, extra, CounterGroup.fromRoute(sessionKey), CounterScope.fromRoute(sessionKey))
    }
    Page("Numbers: ${mode.label}", onBack,
        ime = mode in listOf(CounterMode.Type, CounterMode.Expression, CounterMode.Reading), scrollToTopOnBack = true) {
        if (extra) {
            Text("Extra practice updates review history and scheduling.", style = MaterialTheme.typography.bodySmall)
        }
        when (val s = vm.ui) {
            CounterUi.Loading -> Text(stringResource(R.string.loading), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            is CounterUi.Empty -> Text(s.message)
            is CounterUi.Done -> {
                Text(stringResource(R.string.session_complete), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.of_answers_correct, s.correct, s.answered))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_more)) }
            }
            is CounterUi.Choose -> {
                SessionRemaining(s.remaining)
                Sentence(vm, s.card, filled = false)
                Text("Which counter fits?", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                s.options.forEach { o -> OutlinedButton(onClick = { vm.pick(o) }, modifier = Modifier.fillMaxWidth()) { Text(vm.index.counterLabel(o)) } }
                DontKnowButton { vm.pick(NO_ANSWER) }
            }
            is CounterUi.ChooseAnswer -> {
                Result(correct = s.correct, gaveUp = s.picked == NO_ANSWER)
                Sentence(vm, s.card, filled = true)
                s.options.forEach { o ->
                    AnswerOption(vm.index.counterLabel(o), when {
                        o == s.file.counter -> Verdict.Correct
                        o == s.picked -> Verdict.Wrong
                        else -> Verdict.Neutral
                    })
                }
                Details(s.card, s.file.title)
                NextRow(s.correct, canGuess = true, vm)
            }
            is CounterUi.TypeQuestion -> TypeQuestion(s, vm)
            is CounterUi.ExpressionQuestion -> {
                SessionRemaining(s.remaining)
                Text(s.item.cue, style = MaterialTheme.typography.titleMedium)
                if (s.reading) Text(s.item.written, fontSize = 32.sp)
                Text(if (s.reading) "Type the complete reading in kana." else "Type the complete Japanese written expression, not just its counter.")
                CounterInput(vm)
            }
            is CounterUi.ExpressionAnswer -> {
                Result(s.correct, s.gaveUp)
                Text(s.item.cue)
                Text("${s.item.written} (${s.item.reading})", fontSize = 28.sp)
                if (!s.gaveUp) Text("You typed ${s.typed}")
                val answers = if (s.reading) s.item.readingAnswers else s.item.writtenAnswers
                Text("Accepted: ${answers.joinToString(" / ")}")
                Text(s.item.explanation)
                vm.index.article(s.item.id)?.let { path ->
                    TextButton(onClick = { onOpenDoc(vm.sourceId, path) }) { Text("Read the explanation") }
                }
                NextRow(s.correct, canGuess = false, vm)
            }
            is CounterUi.TypeAnswer -> {
                val verdict = s.result.verdict
                Text(
                    when {
                        s.gaveUp -> "No problem — here it is"
                        verdict == CounterVerdict.Good -> "Correct!"
                        verdict == CounterVerdict.Yellow -> "Acceptable, but not the targeted counter"
                        else -> "Not quite"
                    },
                    color = if (verdict == CounterVerdict.Wrong) wrongTextColor() else correctTextColor(),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
                Sentence(vm, s.card, filled = true)
                if (!s.gaveUp) Text("You typed ${s.typed}", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                s.result.reason?.let { Text(it, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                Details(s.card, s.file.title)
                NextRow(s.result.ok, canGuess = false, vm)
            }
            is CounterUi.NounQuestion -> {
                SessionRemaining(s.remaining)
                Text(s.file.counter, fontSize = 72.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(s.file.reading, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("Which of these can it count?", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                s.options.forEach { o -> OutlinedButton(onClick = { vm.pick(o.id) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) } }
                DontKnowButton { vm.pick(NO_ANSWER) }
            }
            is CounterUi.NounAnswer -> {
                Result(correct = s.correct, gaveUp = s.picked == NO_ANSWER)
                Text(
                    vm.index.counterLabel(s.file.counter), style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                s.options.forEach { o ->
                    AnswerOption(o.label, when {
                        o.id == s.answer.id -> Verdict.Correct
                        o.id == s.picked -> Verdict.Wrong
                        else -> Verdict.Neutral
                    })
                }
                Sentence(vm, s.answer, filled = true)
                Details(s.answer, s.file.title)
                NextRow(s.correct, canGuess = true, vm)
            }
        }
    }
}

@Composable
private fun Result(correct: Boolean, gaveUp: Boolean) {
    Text(
        when {
            correct -> "Correct!"
            gaveUp -> "No problem — here it is"
            else -> "Not quite"
        },
        color = if (correct) correctTextColor() else wrongTextColor(),
        style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
}

/** The sentence; the blank shows as a gap until the answer, then as the full counted phrase. Hints list readings of the words in it. */
@Composable
private fun Sentence(vm: CounterQuizViewModel, card: CounterCard, filled: Boolean) {
    Text(
        if (filled) vm.index.sentence(card) else card.text.replace("{}", "（　）"),
        fontSize = 28.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    val hints = vm.index.glossary(card).joinToString("  ·  ") { (w, v) -> "$w ${v.reading} ${v.gloss}".replace(Regex(" +"), " ").trim() }
    if (hints.isNotEmpty()) Text(hints, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
private fun Details(card: CounterCard, title: String) {
    Text(card.en, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Text(
        "${card.phrase} (${card.reading}) — $title", style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
    card.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
}

@Composable
private fun NextRow(ok: Boolean, canGuess: Boolean, vm: CounterQuizViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.next)) }
        if (ok && canGuess) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text(stringResource(R.string.i_guessed)) }
    }
}

@Composable
private fun TypeQuestion(s: CounterUi.TypeQuestion, vm: CounterQuizViewModel) {
    SessionRemaining(s.remaining)
    Sentence(vm, s.card, filled = false)
    Text("Type the number and counter, e.g. 三本, or just the counter", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    CounterInput(vm)
}

@Composable
private fun CounterInput(vm: CounterQuizViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(vm.ui) { focus.requestFocus() }
    OutlinedTextField(
        value = vm.draft,
        onValueChange = vm::onDraft,
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.submit() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Button(onClick = { vm.submit() }, enabled = vm.draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Check") }
    DontKnowButton { vm.submit(giveUp = true) }
}
