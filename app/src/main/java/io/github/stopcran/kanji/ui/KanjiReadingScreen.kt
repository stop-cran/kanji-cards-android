package io.github.stopcran.kanji.ui

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

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

/** Session key: "kreading:<timestamp>[:extra]". */
@Composable
fun KanjiReadingScreen(sessionKey: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit, onPracticeMore: () -> Unit, vm: KanjiReadingViewModel = serviceViewModel(sessionKey) { KanjiReadingViewModel(it) }) {
    LaunchedEffect(sessionKey) { vm.ensureStarted(sessionKey.endsWith(":extra")) }
    Page("Kanji readings", onBack) {
        when (val s = vm.ui) {
            ReadingUi.Loading -> Text("Loading…")
            is ReadingUi.Empty -> Text(s.message)
            is ReadingUi.Done -> {
                Text("Session complete", style = MaterialTheme.typography.headlineSmall)
                Text("${s.correct} of ${s.answered} answers correct.")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text("Practice more") }
            }
            is ReadingUi.Question -> {
                Text("${s.remaining} left", style = MaterialTheme.typography.labelMedium)
                Text(s.card.kanji, fontSize = 120.sp, fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("Pick the ${s.question.kind.label}", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
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
    Text(s.card.kanji, fontSize = 96.sp, fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        when {
            s.correct -> "Correct!"
            s.picked == NO_ANSWER -> "No problem — a ${kind.label} of ${s.card.kanji} is “$right”"
            else -> "Not quite — a ${kind.label} of ${s.card.kanji} is “$right”"
        },
        color = if (s.correct) Green else Red,
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
        if (s.correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text("I guessed") }
    }
    ArticleSection(s.correct, "r:${s.card.kanji}:${s.picked}:${s.remaining}", s.card.body, "kanji/${s.card.kanji}.md", onOpenDoc)
}
