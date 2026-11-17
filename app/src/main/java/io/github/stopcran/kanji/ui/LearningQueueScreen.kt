package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.data.LearningItemKind
import io.github.stopcran.kanji.data.LearningQueueEntity
import kotlinx.coroutines.launch

@Composable
fun LearningQueueScreen(app: KanjiApp, onBack: () -> Unit, onOpenDoc: (String) -> Unit) {
    val source by app.settings.source.collectAsState()
    val queued by remember(source) { app.services.learningQueue.observe(source.id) }.collectAsState(emptyList())
    val kanji by remember(source) { app.db.content().observeKanjiLite(source.id) }.collectAsState(emptyList())
    val words by remember(source) { app.db.content().observeWordsLite(source.id) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val kanjiById = remember(kanji) { kanji.associateBy { it.kanji } }
    val wordById = remember(words) { words.associateBy { it.word } }
    val queuedKanji = queued.filter { it.kind == LearningItemKind.Kanji }
    val queuedWords = queued.filter { it.kind == LearningItemKind.Word }

    Page(stringResource(R.string.learning_queue), onBack, scrollable = false) {
        if (queued.isEmpty()) {
            Text(stringResource(R.string.learning_queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.learning_queue_kanji), style = MaterialTheme.typography.titleMedium) }
                if (queuedKanji.isEmpty()) item { EmptyQueueSection() }
                items(queuedKanji, key = { "${it.kind}:${it.itemId}" }) { item ->
                    QueueEntry(
                        item = item,
                        title = kanjiById[item.itemId]?.let { "${it.kanji} · ${it.title}" },
                        onOpen = { onOpenDoc("kanji/${item.itemId}.md") },
                        onMoveUp = { scope.launch { app.services.learningQueue.move(source.id, item.kind, item.itemId, -1) } },
                        onMoveDown = { scope.launch { app.services.learningQueue.move(source.id, item.kind, item.itemId, 1) } },
                        onRemove = { scope.launch { app.services.learningQueue.remove(source.id, item.kind, item.itemId) } },
                        first = item == queuedKanji.firstOrNull(),
                        last = item == queuedKanji.lastOrNull(),
                    )
                }
                item { Text(stringResource(R.string.learning_queue_words), style = MaterialTheme.typography.titleMedium) }
                if (queuedWords.isEmpty()) item { EmptyQueueSection() }
                items(queuedWords, key = { "${it.kind}:${it.itemId}" }) { item ->
                    QueueEntry(
                        item = item,
                        title = wordById[item.itemId]?.let { "${it.word} · ${it.title}" },
                        onOpen = { onOpenDoc("words/${item.itemId}.md") },
                        onMoveUp = { scope.launch { app.services.learningQueue.move(source.id, item.kind, item.itemId, -1) } },
                        onMoveDown = { scope.launch { app.services.learningQueue.move(source.id, item.kind, item.itemId, 1) } },
                        onRemove = { scope.launch { app.services.learningQueue.remove(source.id, item.kind, item.itemId) } },
                        first = item == queuedWords.firstOrNull(),
                        last = item == queuedWords.lastOrNull(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyQueueSection() {
    Text(stringResource(R.string.learning_queue_empty_section), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun QueueEntry(
    item: LearningQueueEntity,
    title: String?,
    onOpen: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    first: Boolean,
    last: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = onOpen, enabled = title != null) {
            Text(title ?: "${item.itemId} · ${stringResource(R.string.learning_queue_unavailable)}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onMoveUp, enabled = !first) { Text(stringResource(R.string.learning_queue_move_up)) }
            TextButton(onClick = onMoveDown, enabled = !last) { Text(stringResource(R.string.learning_queue_move_down)) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.learning_queue_remove)) }
        }
    }
}

@Composable
fun LearningQueueButton(queued: Boolean, learning: Boolean, onClick: () -> Unit) {
    val label = when {
        queued -> R.string.learning_queue_remove
        learning -> R.string.learning_queue_in_learning
        else -> R.string.learning_queue_add
    }
    TextButton(onClick = onClick, enabled = !learning || queued) { Text(stringResource(label)) }
}
