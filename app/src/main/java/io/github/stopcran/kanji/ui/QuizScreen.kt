package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
fun QuizScreen(sessionKey: String, onBack: () -> Unit, vm: QuizViewModel = viewModel(key = sessionKey)) {
    Page("Quiz", onBack) {
        when (val s = vm.ui) {
            QuizUi.Loading -> Text("Loading…")
            is QuizUi.Empty -> Text(s.message)
            is QuizUi.Done -> {
                Text("Session complete", style = MaterialTheme.typography.headlineSmall)
                Text("${s.correct} of ${s.answered} answers correct.")
                Button(onClick = onBack) { Text("Done") }
            }
            is QuizUi.Question -> {
                Text("${s.remaining} left", style = MaterialTheme.typography.labelMedium)
                Text(s.card.kanji, fontSize = 120.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text("What does it mean?", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                s.options.forEach { o ->
                    OutlinedButton(onClick = { vm.pick(o.kanji) }, modifier = Modifier.fillMaxWidth()) { Text(o.title) }
                }
            }
            is QuizUi.Answer -> AnswerState(s, vm)
        }
    }
}

@Composable
private fun AnswerState(s: QuizUi.Answer, vm: QuizViewModel) {
    var details by rememberSaveable(s.card.kanji) { mutableStateOf(false) }
    Text(s.card.kanji, fontSize = 96.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        if (s.correct) "Correct!" else "Not quite — it means “${s.card.title}”",
        color = if (s.correct) Green else Red,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    s.options.forEach { o ->
        val color = when {
            o.kanji == s.card.kanji -> Green
            o.kanji == s.picked -> Red
            else -> MaterialTheme.colorScheme.outline
        }
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(disabledContainerColor = color.copy(alpha = if (color == MaterialTheme.colorScheme.outline) 0.15f else 0.9f), disabledContentColor = if (color == MaterialTheme.colorScheme.outline) MaterialTheme.colorScheme.onSurfaceVariant else Color.White),
        ) { Text(o.title) }
    }
    Text("on: ${s.card.onyomi.splitSep().joinToString(" ")}   kun: ${s.card.kunyomi.splitSep().joinToString(" ")}", modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) { Text(if (s.correct) "Next" else "Next (will repeat)") }
        if (s.correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text("I guessed") }
    }
    OutlinedButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth()) { Text(if (details) "Hide details" else "Details") }
    if (details) Text(s.card.body)
}
