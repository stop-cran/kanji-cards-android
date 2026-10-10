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
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.splitSep

private val originTags = setOf("wago", "kango", "gairaigo")

/** Session key: "wquiz:<timestamp>:<jp|en|rd>[:extra]". */
@Composable
fun WordQuizScreen(sessionKey: String, onBack: () -> Unit, onOpenDoc: (String) -> Unit, onPracticeMore: () -> Unit, vm: WordQuizViewModel = serviceViewModel(sessionKey) { WordQuizViewModel(it) }) {
    val parts = sessionKey.split(':')
    val direction = when (parts.getOrNull(2)) {
        "en" -> WordDirection.EnToJp
        "rd" -> WordDirection.Reading
        else -> WordDirection.JpToEn
    }
    LaunchedEffect(sessionKey) { vm.ensureStarted(direction, sessionKey.endsWith(":extra")) }
    Page(
        when (direction) {
            WordDirection.JpToEn -> "Words: Japanese → English"
            WordDirection.EnToJp -> "Words: English → Japanese"
            WordDirection.Reading -> "Words: reading"
        },
        onBack,
    ) {
        when (val s = vm.ui) {
            WordQuizUi.Loading -> Text(stringResource(R.string.loading), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            is WordQuizUi.Empty -> Text(s.message)
            is WordQuizUi.Done -> {
                Text(stringResource(R.string.session_complete), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.of_answers_correct, s.correct, s.answered))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
                OutlinedButton(onClick = onPracticeMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_more)) }
            }
            is WordQuizUi.Question -> {
                Text(stringResource(R.string.left, s.remaining), style = MaterialTheme.typography.labelMedium)
                Prompt(s.word, s.direction, s.font, big = true)
                Text(
                    when (s.direction) {
                        WordDirection.JpToEn -> "What does it mean?"
                        WordDirection.EnToJp -> "Which word is it?"
                        WordDirection.Reading -> "How is it read?"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                s.options.forEach { o ->
                    OutlinedButton(onClick = { vm.pick(o.word) }, modifier = Modifier.fillMaxWidth()) { Text(o.label) }
                }
                DontKnowButton { vm.pick(NO_ANSWER) }
                if (s.direction == WordDirection.JpToEn) VoiceButton(s.options.map { it.label }, { i, heard -> vm.pick(s.options[i].word, heard) })
            }
            is WordQuizUi.Answer -> Answer(s, vm, onOpenDoc)
        }
    }
}

@Composable
private fun Prompt(word: WordEntity, direction: WordDirection, font: io.github.stopcran.kanji.core.srs.KanjiFont, big: Boolean) {
    if (direction != WordDirection.EnToJp || !big) {
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
    Text(stringResource(R.string.value_dash_detail, s.word.reading, s.word.title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(
        when {
            s.correct -> "Correct!"
            s.picked == NO_ANSWER -> "No problem — here it is"
            else -> "Not quite"
        },
        color = if (s.correct) correctTextColor() else wrongTextColor(),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
    s.pickedWord?.takeIf { !s.correct && s.direction != WordDirection.Reading }?.let {
        Text(stringResource(R.string.you_chose, it.word, it.reading, it.title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    s.options.forEach { o ->
        AnswerOption(o.label, when {
            o.word == s.key -> Verdict.Correct
            o.word == s.picked -> Verdict.Wrong
            else -> Verdict.Neutral
        })
    }
    s.heard?.let { VoiceHeard(it, vm::retry) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Button(onClick = { vm.next() }, modifier = Modifier.weight(1f)) { Text(if (s.correct) "Next" else "Next (will repeat)") }
        if (s.correct) OutlinedButton(onClick = { vm.next(guessed = true) }) { Text(stringResource(R.string.i_guessed)) }
    }
    ArticleSection(s.correct, "w:${s.word.word}:${s.picked}:${s.remaining}", s.word.body, "words/${s.word.word}.md", onOpenDoc)
}
