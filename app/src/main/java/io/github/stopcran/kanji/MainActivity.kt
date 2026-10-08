package io.github.stopcran.kanji

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.SyncResult
import io.github.stopcran.kanji.data.splitSep
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KanjiApp
        setContent { MaterialTheme { HomeScreen(app) } }
    }
}

@Composable
private fun HomeScreen(app: KanjiApp) {
    val source by app.settings.source.collectAsState()
    val kanji by remember(source) { app.db.content().observeKanji(source.id) }.collectAsState(emptyList())
    val meta by remember(source) { app.db.content().observeMeta(source.id) }.collectAsState(null)
    var url by remember { mutableStateOf(app.settings.repoUrl) }
    var branch by remember { mutableStateOf(app.settings.branch) }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<KanjiEntity?>(null) }
    val scope = rememberCoroutineScope()

    val card = selected
    if (card != null) {
        BackHandler { selected = null }
        Column(Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Button(onClick = { selected = null }) { Text("Back") }
            Text(card.kanji, fontSize = 64.sp)
            Text(card.title, style = MaterialTheme.typography.titleLarge)
            Text("on: " + card.onyomi.splitSep().joinToString(" ") + "   kun: " + card.kunyomi.splitSep().joinToString(" "))
            Text(card.body, modifier = Modifier.padding(top = 12.dp))
        }
        return
    }

    Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(url, { url = it }, label = { Text("Content repo URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(branch, { branch = it }, label = { Text("Branch") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            if (!app.settings.setSource(url, branch)) {
                status = "Invalid repo URL or branch"
                return@Button
            }
            status = "Syncing…"
            scope.launch {
                status = when (val r = app.contentSync.sync(app.settings.source.value, force = true)) {
                    is SyncResult.Updated -> "Synced ${r.kanji} kanji, ${r.words} words" + if (r.problems.isEmpty()) "" else " (${r.problems.size} skipped)"
                    SyncResult.UpToDate -> "Up to date"
                    is SyncResult.Failed -> "Sync failed: ${r.message}"
                }
            }
        }) { Text("Save & sync") }
        Text(status)
        Text("${source.id}: ${kanji.size} kanji" + (meta?.let { ", version ${it.contentVersion}" } ?: ", not synced yet"))
        LazyColumn {
            items(kanji, key = { it.kanji }) { k ->
                Row(Modifier.fillMaxWidth().clickable { selected = k }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(k.kanji, fontSize = 28.sp)
                    Text(k.title)
                }
            }
        }
    }
}
