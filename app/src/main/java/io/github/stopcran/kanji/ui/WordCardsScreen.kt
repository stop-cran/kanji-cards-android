package io.github.stopcran.kanji.ui

import androidx.compose.foundation.clickable
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.WordSearch
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.LearningItemKind
import io.github.stopcran.kanji.data.QueueAddResult
import kotlinx.coroutines.launch

@Composable
fun WordCardsScreen(app: KanjiApp, onBack: () -> Unit, onOpen: (String) -> Unit, onOpenDoc: (String) -> Unit = {}) {
    val source by app.settings.source.collectAsState()
    val words by remember(source) { app.db.content().observeWordsLite(source.id) }.collectAsState(emptyList())
    val selectedStack by app.settings.wordStack.collectAsState()
    val queue by remember(source) { app.services.learningQueue.observe(source.id) }.collectAsState(emptyList())
    val jpStates by remember(source, selectedStack) {
        app.db.reviews().observeStates(source.id, selectedStack, StudyMode.WordJpEn.name)
    }.collectAsState(emptyList())
    val queuedIds = remember(queue) { queue.filter { it.kind == LearningItemKind.Word }.mapTo(HashSet()) { it.itemId } }
    val learningIds = remember(jpStates) { jpStates.filter { it.phase != "New" }.mapTo(HashSet()) { it.kanji } }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var queueError by rememberSaveable { mutableStateOf<Int?>(null) }
    val shown = remember(words, query) {
        words.map { it to WordSearch.score(it.word, it.reading, it.title, query) }
            .filter { it.second > 0 }
            .let { list -> if (query.isBlank()) list else list.sortedByDescending { it.second } }
            .map { it.first }
    }
    val count = if (query.isBlank()) "${words.size}" else "${shown.size} of ${words.size}"
    val wide = isExpandedWidth()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Page(stringResource(R.string.all_words_count, count), onBack, scrollable = false, maxWidth = if (wide) 1400.dp else 720.dp, ime = true) {
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
       Column(Modifier.weight(if (wide) 0.4f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
            label = { Text(stringResource(R.string.search_word_meaning_or_reading)) },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text(stringResource(R.string.clear)) } },
        )
        queueError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        if (shown.isEmpty() && words.isNotEmpty()) Text(stringResource(R.string.no_words_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(shown, key = { it.word }) { w ->
                Row(Modifier.fillMaxWidth().clickable { if (wide) selected = w.word else { focusManager.clearFocus(); onOpen(w.word) } }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(w.word, fontSize = 22.sp, modifier = Modifier.weight(0.4f))
                    Column(Modifier.weight(0.6f)) {
                        Text(w.title)
                        if (w.reading != w.word) Text(w.reading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LearningQueueButton(w.word in queuedIds, w.word in learningIds) {
                        if (w.word in queuedIds) {
                            scope.launch {
                                app.services.learningQueue.remove(source.id, LearningItemKind.Word, w.word)
                                queueError = null
                            }
                        } else {
                            scope.launch {
                                queueError = when (app.services.learningQueue.addWord(source.id, selectedStack, w.word, System.currentTimeMillis())) {
                                    QueueAddResult.Added, QueueAddResult.AlreadyQueued -> null
                                    QueueAddResult.AlreadyLearning -> R.string.learning_queue_already_learning_error
                                    QueueAddResult.Missing -> R.string.learning_queue_item_missing_error
                                }
                            }
                        }
                    }
                }
            }
        }
       }
       if (wide) {
        val s = selected
        if (s == null) Text(stringResource(R.string.select_a_word), Modifier.weight(0.6f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else DocBodyRoute(app.services.documents, source.id, "words/$s.md", { onOpenDoc(it) }, Modifier.weight(0.6f))
       }
      }
    }
}
