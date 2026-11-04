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
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarVocabulary
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.R

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
        Text(stringResource(R.string.grammar_tap_vocabulary_help),
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = vm::openVocabularyList, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.grammar_vocabulary)) }
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
            title = { Text(stringResource(R.string.grammar_vocabulary_prompt_title)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    val lookUp = stringResource(R.string.grammar_look_up)
                    val notFamiliar = stringResource(R.string.grammar_not_familiar_look_up)
                    vm.currentExercise?.vocabulary?.spans.orEmpty().forEach { span ->
                        TextButton(onClick = { vm.openLookup(span) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(buildAnnotatedString {
                                with(RareHan) { appendWithRare(span.text) }
                                append(if (vm.isFamiliar(span)) " $lookUp" else " $notFamiliar")
                            })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeVocabularyList) { Text(stringResource(R.string.grammar_focus_close)) } },
        )
    }
    vm.lookup?.let { span ->
        AlertDialog(
            onDismissRequest = vm::closeLookup,
            title = { Text(buildAnnotatedString { with(RareHan) { appendWithRare(span.text) } }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(span.reading, style = MaterialTheme.typography.titleMedium)
                    if (span.headword != span.text) Text(stringResource(R.string.grammar_dictionary_form, span.headword))
                    Text(span.gloss, modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        if (vm.isFamiliar(span)) stringResource(R.string.grammar_familiar_word_practice)
                        else stringResource(R.string.grammar_not_familiar_word_progress),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!vm.lookupHasArticle()) Text(stringResource(R.string.grammar_local_explanation), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::closeLookup) { Text(stringResource(R.string.grammar_focus_close)) } },
            dismissButton = {
                if (vm.lookupHasArticle()) TextButton(onClick = vm::openLookupArticle) { Text(stringResource(R.string.grammar_read_word_article)) }
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
