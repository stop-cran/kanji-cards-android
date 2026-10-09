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
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.splitSep

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)
private val originTags = setOf("wago", "kango", "gairaigo")

/** Session key: "wquiz:<timestamp>:<jp|en>[:extra]". */
@Composable
fun WordQuizScreen(sessionKey: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit, onPracticeMore: () -> Unit, vm: WordQuizViewModel = viewModel(key = sessionKey)) {
    val parts = sessionKey.split(':')
    val direction = if (parts.getOrNull(2) == "en") WordDirection.EnToJp else WordDirection.JpToEn
    LaunchedEffect(sessionKey) { vm.ensureStarted(direction, sessionKey.endsWith(":extra")) }
    Page(if (direction == WordDirection.JpToEn) "Words: Japanese → English" else "Words: English → Japanese", onBack) {
        when (val s = vm.ui) {
            WordQuizUi.Loading -> Text("Loading…")
            is WordQuizUi.Empty -> Text(s.message)
            is WordQuizUi.Done -> {
                Text("Session complete", style = MaterialTheme.typography.headlineSmall)
                Text("${s.correct} of ${s.answered} answers correct.")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text("Practice more") }
            }
            is WordQuizUi.Question -> {
                Text("${s.remaining} left", style = MaterialTheme.typography.labelMedium)
                Prompt(s.word, s.direction, s.font, big = true)
                Text(
                    if (s.direction == WordDirection.JpToEn) "What does it mean?" else "Which word is it?",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                s.options.forEach { o ->
                    OutlinedButton(onClick = { vm.pick(o.word) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) }
                }
                if (s.direction == WordDirection.JpToEn) VoiceButton(s.options.map { it.label }, { i, heard -> vm.pick(s.options[i].word, heard) })
            }
            is WordQuizUi.Answer -> Answer(s, vm, onOpenDoc)
        }
    }
}

@Composable
private fun Prompt(word: WordEntity, direction: WordDirection, font: io.github.stopcran.kanji.core.srs.KanjiFont, big: Boolean) {
    if (direction == WordDirection.JpToEn || !big) {
        Text(word.word, fontSize = if (big) 64.sp else 48.sp, fontFamily = font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    } else {
        Text(word.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        val hint = (listOfNotNull(word.type) + word.tags.splitSep()).filter { it !in originTags }.distinct().joinToString(" · ")
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Answer(s: WordQuizUi.Answer, vm: WordQuizViewModel, onOpenDoc: (String) -> Unit) {
    Text(s.word.word, fontSize = 56.sp, fontFamily = s.font.family(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("${s.word.reading} — ${s.word.title}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        if (s.correct) "Correct!" else "Not quite",
        color = if (s.correct) Green else Red,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    s.options.forEach { o ->
        val color = when {
            o.word == s.word.word -> Green
            o.word == s.picked -> Red
            else -> MaterialTheme.colorScheme.outline
        }
        val neutral = color == MaterialTheme.colorScheme.outline
        Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = color.copy(alpha = if (neutral) 0.15f else 0.9f),
                disabledContentColor = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
            ),
        ) { Text(o.label) }
    }
    s.heard?.let { VoiceHeard(it, vm::retry) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) { Text(if (s.correct) "Next" else "Next (will repeat)") }
        if (s.correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text("I guessed") }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    MarkdownView(s.word.body, "words/${s.word.word}.md", onOpenDoc)
}
