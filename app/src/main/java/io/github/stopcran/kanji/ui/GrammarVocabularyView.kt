package io.github.stopcran.kanji.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarVocabulary
import io.github.stopcran.kanji.data.WordEntity

@Composable
fun GrammarPrompt(vm: GrammarQuizViewModel, exercise: GrammarExercise, answer: String? = null) {
    val rendered = GrammarVocabulary.render(exercise, answer)
    val color = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        with(RareHan) { appendWithRare(rendered.text) }
        rendered.ranges.forEach { range ->
            val style = SpanStyle(
                color = color,
                textDecoration = TextDecoration.Underline,
                fontWeight = if (vm.isFamiliar(range.span)) FontWeight.Normal else FontWeight.SemiBold,
            )
            addLink(
                LinkAnnotation.Clickable("vocabulary:${range.start}", TextLinkStyles(style)) { vm.openLookup(range.span) },
                range.start, range.end,
            )
        }
    }
    Text(text, style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    if (rendered.ranges.isNotEmpty()) {
        Text("Tap underlined words for help. Heavier links: not yet familiar in the app.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = vm::openVocabularyList, modifier = Modifier.fillMaxWidth()) { Text("Vocabulary") }
    }
    exercise.vocabularyProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text(exercise.translation, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}

@Composable
fun GrammarVocabularyHelp(vm: GrammarQuizViewModel) {
    if (vm.vocabularyListOpen) {
        AlertDialog(
            onDismissRequest = vm::closeVocabularyList,
            title = { Text("Vocabulary in this prompt") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    vm.currentExercise?.vocabulary?.spans.orEmpty().forEach { span ->
                        TextButton(onClick = { vm.openLookup(span) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(buildAnnotatedString {
                                with(RareHan) { appendWithRare(span.text) }
                                append(if (vm.isFamiliar(span)) " — look up" else " — not yet familiar; look up")
                            })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeVocabularyList) { Text("Close") } },
        )
    }
    vm.lookup?.let { span ->
        AlertDialog(
            onDismissRequest = vm::closeLookup,
            title = { Text(buildAnnotatedString { with(RareHan) { appendWithRare(span.text) } }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(span.reading, style = MaterialTheme.typography.titleMedium)
                    if (span.headword != span.text) Text("Dictionary form: ${span.headword}")
                    Text(span.gloss, modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        if (vm.isFamiliar(span)) "Familiar in word practice; this reading or meaning may still be new."
                        else "Not yet familiar according to your word-recognition progress.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!vm.lookupHasArticle()) Text("Local explanation; no linked word article.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::closeLookup) { Text("Close") } },
            dismissButton = {
                if (vm.lookupHasArticle()) TextButton(onClick = vm::openLookupArticle) { Text("Read word article") }
            },
        )
    }
}

@Composable
fun GrammarVocabularyArticle(word: WordEntity, onBack: () -> Unit, onOpenDoc: (String) -> Unit) {
    BackHandler(onBack = onBack)
    Page("${word.word}  ${word.title}", onBack, scrollable = false) {
        LazyMarkdownView(word.body, "words/${word.word}.md", onOpenDoc, Modifier.weight(1f))
    }
}
