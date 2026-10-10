package io.github.stopcran.kanji.ui

import androidx.compose.ui.semantics.liveRegion
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel


/** Session key: "kreading:<timestamp>[:extra]". */
@Composable
fun KanjiReadingScreen(sessionKey: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit, onPracticeMore: () -> Unit, vm: KanjiReadingViewModel = serviceViewModel(sessionKey) { KanjiReadingViewModel(it) }) {
    LaunchedEffect(sessionKey) { vm.ensureStarted(sessionKey.endsWith(":extra")) }
    Page("Kanji readings", onBack) {
        when (val s = vm.ui) {
            ReadingUi.Loading -> Text(stringResource(R.string.loading), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            is ReadingUi.Empty -> Text(s.message)
            is ReadingUi.Done -> {
                Text(stringResource(R.string.session_complete), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.of_answers_correct, s.correct, s.answered))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_more)) }
            }
            is ReadingUi.Question -> {
                Text(stringResource(R.string.left, s.remaining), style = MaterialTheme.typography.labelMedium)
                Text(s.card.kanji, fontSize = bigKanjiSize(120), fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.pick_the, s.question.kind.label), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                s.question.options.forEach { o ->
                    OutlinedButton(onClick = { vm.pick(o.key) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) }
                }
                DontKnowButton { vm.pick(NO_ANSWER) }
            }
            is ReadingUi.Answer -> AnswerState(s, vm, onOpenDoc)
        }
    }
}

@Composable
private fun AnswerState(s: ReadingUi.Answer, vm: KanjiReadingViewModel, onOpenDoc: (String) -> Unit) {
    val kind = s.question.kind
    val right = s.question.options.first { it.key == s.question.correctKey }.label
    Text(s.card.kanji, fontSize = bigKanjiSize(96), fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        when {
            s.correct -> "Correct!"
            s.picked == NO_ANSWER -> "No problem — a ${kind.label} of ${s.card.kanji} is “$right”"
            else -> "Not quite — a ${kind.label} of ${s.card.kanji} is “$right”"
        },
        color = if (s.correct) correctTextColor() else wrongTextColor(),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    if (!s.correct && s.pickedBelongsTo.isNotEmpty()) {
        Text("You chose a reading of ${s.pickedBelongsTo.joinToString("、")}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    s.question.options.forEach { o ->
        AnswerOption(o.label, when {
            o.key == s.question.correctKey -> Verdict.Correct
            o.key == s.picked -> Verdict.Wrong
            else -> Verdict.Neutral
        })
    }
    Text("${s.card.title}: ${s.allReadings.joinToString("、")}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) { Text(if (s.correct) "Next" else "Next (will repeat)") }
        if (s.correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text(stringResource(R.string.i_guessed)) }
    }
    ArticleSection(s.correct, "r:${s.card.kanji}:${s.picked}:${s.remaining}", s.card.body, "kanji/${s.card.kanji}.md", onOpenDoc)
}
