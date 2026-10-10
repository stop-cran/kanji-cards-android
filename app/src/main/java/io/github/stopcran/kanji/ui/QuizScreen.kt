package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.stopcran.kanji.data.splitSep

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

@Composable
fun QuizScreen(sessionKey: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit, onPracticeMore: () -> Unit, vm: QuizViewModel = viewModel(key = sessionKey)) {
    LaunchedEffect(sessionKey) { vm.ensureStarted(sessionKey.endsWith(":extra")) }
    Page("Quiz", onBack) {
        when (val s = vm.ui) {
            QuizUi.Loading -> Text("Loading…")
            is QuizUi.Empty -> Text(s.message)
            is QuizUi.Done -> {
                Text("Session complete", style = MaterialTheme.typography.headlineSmall)
                Text("${s.correct} of ${s.answered} answers correct.")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text("Practice more") }
            }
            is QuizUi.Study -> StudyState(s, vm, onOpenDoc)
            is QuizUi.Question -> {
                Text("${s.remaining} left", style = MaterialTheme.typography.labelMedium)
                Text(s.card.kanji, fontSize = 120.sp, fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("What does it mean?", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                s.options.forEach { o ->
                    OutlinedButton(onClick = { vm.pick(o.kanji) }, modifier = Modifier.fillMaxWidth()) { Text(o.title) }
                }
                VoiceButton(s.options.map { it.title }, { i, heard -> vm.pick(s.options[i].kanji, heard) })
            }
            is QuizUi.Answer -> AnswerState(s, vm, onOpenDoc)
        }
    }
}

@Composable
private fun StudyState(s: QuizUi.Study, vm: QuizViewModel, onOpenDoc: (String) -> Unit) {
    Text("New kanji · ${s.remaining} left", style = MaterialTheme.typography.labelMedium)
    Text(s.card.kanji, fontSize = 120.sp, fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(s.card.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    val readings = listOf(s.card.onyomi.splitSep().take(3), s.card.kunyomi.splitSep().take(3)).filter { it.isNotEmpty() }.joinToString("  ·  ") { it.joinToString("  ") }
    if (readings.isNotEmpty()) Text(readings, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("Take a moment with it, then you will be asked what it means.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Button(onClick = vm::startQuestion, modifier = Modifier.fillMaxWidth()) { Text("Got it — quiz me") }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    MarkdownView(s.card.body, "kanji/${s.card.kanji}.md", onOpenDoc)
}

@Composable
private fun AnswerState(s: QuizUi.Answer, vm: QuizViewModel, onOpenDoc: (String) -> Unit) {
    Text(s.card.kanji, fontSize = 96.sp, fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        if (s.correct) "Correct!" else "Not quite — it means “${s.card.title}”",
        color = if (s.correct) Green else Red,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    s.options.forEach { o ->
        AnswerOption(o.title, when {
            o.kanji == s.card.kanji -> Verdict.Correct
            o.kanji == s.picked -> Verdict.Wrong
            else -> Verdict.Neutral
        })
    }
    s.heard?.let { VoiceHeard(it, vm::retry) }
    Text("${s.card.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) { Text(if (s.correct) "Next" else "Next (will repeat)") }
        if (s.correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text("I guessed") }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    MarkdownView(s.card.body, "kanji/${s.card.kanji}.md", onOpenDoc)
}
