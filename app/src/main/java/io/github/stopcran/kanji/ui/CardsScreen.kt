package io.github.stopcran.kanji.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
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
import io.github.stopcran.kanji.core.content.CardSearch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.data.splitSep

@Composable
fun CardsScreen(app: KanjiApp, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val source by app.settings.source.collectAsState()
    val kanji by remember(source) { app.db.content().observeKanji(source.id) }.collectAsState(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(kanji, query) {
        kanji.map { it to CardSearch.score(it.kanji, it.title, it.onyomi.splitSep(), it.kunyomi.splitSep(), query) }
            .filter { it.second > 0 }
            .let { list -> if (query.isBlank()) list else list.sortedWith(compareByDescending<Pair<*, Int>> { it.second }.thenBy { (it.first as io.github.stopcran.kanji.data.KanjiEntity).title.length }) }
            .map { it.first }
    }
    val count = if (query.isBlank()) "${kanji.size}" else "${shown.size} of ${kanji.size}"
    Page("Cards ($count)", onBack) {
        OutlinedTextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search kanji, meaning or reading") },
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } },
        )
        if (shown.isEmpty() && kanji.isNotEmpty()) Text("No cards match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown.forEach { k ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(k.kanji) }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(k.kanji, fontSize = 28.sp)
                Column {
                    Text(k.title)
                    val readings = (k.onyomi.splitSep() + k.kunyomi.splitSep()).joinToString("  ")
                    if (readings.isNotEmpty()) Text(readings, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
