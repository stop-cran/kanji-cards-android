package io.github.stopcran.kanji.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.WordSearch

@Composable
fun WordCardsScreen(app: KanjiApp, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val source by app.settings.source.collectAsState()
    val words by remember(source) { app.db.content().observeWordsLite(source.id) }.collectAsState(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(words, query) {
        words.map { it to WordSearch.score(it.word, it.reading, it.title, query) }
            .filter { it.second > 0 }
            .let { list -> if (query.isBlank()) list else list.sortedByDescending { it.second } }
            .map { it.first }
    }
    val count = if (query.isBlank()) "${words.size}" else "${shown.size} of ${words.size}"
    Page("Words ($count)", onBack) {
        OutlinedTextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search word, meaning or reading") },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } },
        )
        if (shown.isEmpty() && words.isNotEmpty()) Text("No words match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown.forEach { w ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(w.word) }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(w.word, fontSize = 22.sp, modifier = Modifier.weight(0.4f))
                Column(Modifier.weight(0.6f)) {
                    Text(w.title)
                    if (w.reading != w.word) Text(w.reading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
