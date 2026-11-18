package io.github.stopcran.kanji.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.readingtext.ReadingOccurrence
import io.github.stopcran.kanji.core.readingtext.ReadingBookMode
import io.github.stopcran.kanji.data.READING_TEXT_STACK
import io.github.stopcran.kanji.data.studyMode
import io.github.stopcran.kanji.data.ReadingReader
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun TextReadingBrowser(app: KanjiApp, onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    val source by app.settings.source.collectAsState()
    val texts by remember(source.id) { app.db.reading().observeTexts(source.id) }.collectAsState(null)
    val meta by remember(source.id) { app.db.content().observeMeta(source.id) }.collectAsState(null)
    val openStates by remember(source.id) {
        app.db.reviews().observeStates(source.id, READING_TEXT_STACK, ReadingBookMode.OpenBook.studyMode().name)
    }.collectAsState(null)
    val closedStates by remember(source.id) {
        app.db.reviews().observeStates(source.id, READING_TEXT_STACK, ReadingBookMode.ClosedBook.studyMode().name)
    }.collectAsState(null)
    Page(stringResource(R.string.reading_title), onBack, scrollable = false) {
        if (texts == null) Text(stringResource(R.string.loading))
        meta?.readingProblem?.let { Text(stringResource(R.string.reading_content_warning), color = MaterialTheme.colorScheme.error) }
        if (texts?.isEmpty() == true) Text(stringResource(R.string.reading_empty))
        LazyColumn(state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(texts.orEmpty(), key = { _, row -> row.id }) { _, row ->
                OutlinedButton(onClick = { onOpen(source.id, row.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.reading_browser_entry, row.title, row.jlpt))
                        ReadingQuizSchedule(ReadingBookMode.OpenBook, openStates?.singleOrNull { it.kanji == row.id }, openStates == null)
                        ReadingQuizSchedule(ReadingBookMode.ClosedBook, closedStates?.singleOrNull { it.kanji == row.id }, closedStates == null)
                    }
                }
            }
        }
    }
}

@Composable
fun TextReadingScreen(
    vm: TextReadingViewModel,
    onBack: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onCaptured: (String) -> Unit = {},
    onQuiz: (() -> Unit)? = null,
) {
    val savedState = rememberSaveableStateHolder()
    when (val state = vm.ui) {
        TextReadingUi.Loading -> Page(stringResource(R.string.reading_title), onBack) { Text(stringResource(R.string.loading)) }
        TextReadingUi.Unavailable -> Page(stringResource(R.string.reading_title), onBack) {
            Text(stringResource(R.string.reading_unavailable))
            Button(onClick = vm::load) { Text(stringResource(R.string.content_retry)) }
        }
        is TextReadingUi.Ready -> {
            LaunchedEffect(state.reader.saved.captureId) { onCaptured(state.reader.saved.captureId) }
            savedState.SaveableStateProvider(
                "${state.reader.saved.sourceId}:${state.reader.saved.textId}:${state.reader.saved.captureId}",
            ) { ReadingReaderBody(state.reader, vm, onBack, onOpenDoc, onQuiz) }
        }
    }
}

@Composable
private fun ReadingReaderBody(
    reader: ReadingReader, vm: TextReadingViewModel, onBack: () -> Unit, onOpenDoc: (String) -> Unit, onQuiz: (() -> Unit)?,
) {
    var lookupSection by rememberSaveable { mutableStateOf<Int?>(null) }
    var lookupStart by rememberSaveable { mutableStateOf<Int?>(null) }
    var article by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberLazyListState(reader.saved.sectionIndex, reader.saved.scrollOffset)
    val lookup = lookupSection?.let { reader.content.sections.getOrNull(it) }?.vocabulary?.find { it.start == lookupStart }
    val word = article?.let { reader.words[it] }
    if (word != null) {
        BackHandler { article = null }
        Page("${word.word}  ${word.title}", { article = null }, scrollable = false) {
            LazyMarkdownView(word.body, "words/${word.word}.md", onOpenDoc, Modifier.weight(1f))
        }
    } else {
        LaunchedEffect(scroll, reader.saved.contentHash) {
            snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }
                .distinctUntilChanged().collect { (index, offset) -> vm.position(index, offset) }
        }
        Page(reader.content.title, onBack, scrollable = false, actionsBelowTitle = true, actions = {
            TextButton(onClick = vm::toggleFurigana) {
                Text(stringResource(if (reader.saved.furigana) R.string.reading_hide_furigana else R.string.reading_show_furigana))
            }
        }) {
            if (vm.persistenceFailed) Text(stringResource(R.string.reading_save_failed), color = MaterialTheme.colorScheme.error)
            LazyColumn(state = scroll, verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f).testTag("reading-sections")) {
                item(key = "introduction") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(reader.content.japaneseTitle, style = MaterialTheme.typography.headlineMedium)
                        Text(reader.content.provenance.adaptation.label)
                        Text(stringResource(R.string.reading_tap_help))
                    }
                }
                itemsIndexed(reader.content.sections, key = { _, section -> section.id }) { index, section ->
                    ReadingSectionText(section, reader.saved.furigana) { occurrence ->
                        lookupSection = index
                        lookupStart = occurrence.start
                    }
                }
                item(key = "completion") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (reader.saved.completedAtMs == null) Button(onClick = vm::complete, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.reading_finished))
                        } else Text(stringResource(R.string.reading_complete))
                        if (reader.saved.completedAtMs != null && onQuiz != null) {
                            Button(onClick = onQuiz, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reading_quiz_title)) }
                        }
                        Text(reader.content.provenance.credits.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        Text(reader.content.provenance.rights.note, style = MaterialTheme.typography.bodySmall)
                        Text(reader.content.provenance.distribution.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (lookup != null && article == null) ReadingLookup(
        lookup, lookup.word in reader.words, lookup.word in vm.selectedWords,
        onDismiss = { lookupSection = null; lookupStart = null },
        onArticle = { article = lookup.word; lookupSection = null; lookupStart = null },
        onSelect = { lookup.word?.let(vm::toggleSelectedWord) },
    )
}

@Composable
private fun ReadingLookup(
    occurrence: ReadingOccurrence, hasArticle: Boolean, selected: Boolean,
    onDismiss: () -> Unit, onArticle: () -> Unit, onSelect: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(occurrence.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(occurrence.reading)
                if (occurrence.headword != occurrence.text) Text(occurrence.headword)
                Text(occurrence.gloss)
                if (!hasArticle) Text(stringResource(R.string.reading_no_card))
                if (selected) Text(stringResource(R.string.reading_selected_help))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.grammar_focus_close)) } },
        dismissButton = {
            if (hasArticle) Column {
                TextButton(onClick = onArticle) { Text(stringResource(R.string.grammar_read_word_article)) }
                TextButton(onClick = onSelect) {
                    Text(stringResource(if (selected) R.string.reading_remove_selected else R.string.reading_study_next))
                }
            }
        },
    )
}
