package io.github.stopcran.kanji.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.data.splitSep

@Composable
fun CardsScreen(app: KanjiApp, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val source by app.settings.source.collectAsState()
    val kanji by remember(source) { app.db.content().observeKanji(source.id) }.collectAsState(emptyList())
    Page("Cards (${kanji.size})", onBack) {
        kanji.forEach { k ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(k.kanji) }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(k.kanji, fontSize = 28.sp)
                Text(k.title)
            }
        }
    }
}
