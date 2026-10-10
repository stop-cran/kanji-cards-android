package io.github.stopcran.kanji.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.Defaults
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.SyncResult
import io.github.stopcran.kanji.data.inStack

/** Where the user can go from the home pages; sessions are started by the caller. */
class HomeActions(
    val onSettings: () -> Unit,
    val onCards: () -> Unit,
    val onWordCards: () -> Unit,
    val onKanjiPage: () -> Unit,
    val onWordsPage: () -> Unit,
    val onQuiz: (extra: Boolean) -> Unit,
    val onDraw: (extra: Boolean) -> Unit,
    val onReadings: (extra: Boolean) -> Unit,
    val onWords: (WordDirection, extra: Boolean) -> Unit,
)

/** The main screen and the Kanji / Words pages share one [HomeData], so every count agrees across them. */
@Composable
fun HomePages(app: KanjiApp, route: String, onBack: () -> Unit, actions: HomeActions) {
    val data = rememberHomeData(app)
    val syncStatus by produceState("", data.sourceId) {
        if (app.db.content().meta(data.sourceId) == null) {
            value = "Downloading cards…"
            value = when (val r = app.contentSync.sync(app.settings.source.value)) {
                is SyncResult.Failed -> "Could not download cards: ${r.message}"
                else -> ""
            }
        }
    }
    when (route) {
        "kanji-home" -> KanjiPage(app, data, onBack, actions)
        "words-home" -> WordsPage(app, data, onBack, actions)
        else -> MainPage(app, data, syncStatus, actions)
    }
}

@Composable
private fun MainPage(app: KanjiApp, data: HomeData, syncStatus: String, a: HomeActions) {
    Page("Kanji Cards", actions = { TextButton(onClick = a.onSettings, modifier = Modifier.semantics { contentDescription = "Settings" }) { Text("⚙") } }) {
        if (syncStatus.isNotEmpty()) Text(syncStatus)
        LoadingBar(data)
        val defaultId = remember { RepoSource.parse(Defaults.CONTENT_REPO_URL, Defaults.CONTENT_BRANCH)?.id }
        if (data.sourceId != defaultId) Text("Cards from ${data.sourceId}")
        N4Offer(app, data)
        val kanjiStack = if (data.stacks.size > 1) " · ${data.stack.label}" else ""
        Entry("Kanji", (data.summary(HomeMode.Meaning, HomeMode.Drawing, HomeMode.Readings) ?: "Loading…") + kanjiStack, a.onKanjiPage)
        val wordStack = if (data.wordStacks.size > 1) " · ${data.wordStack.label}" else ""
        Entry("Words", (data.summary(HomeMode.WordJp, HomeMode.WordEn, HomeMode.WordReading) ?: "Loading…") + wordStack, a.onWordsPage)
    }
}

@Composable
private fun KanjiPage(app: KanjiApp, data: HomeData, onBack: () -> Unit, a: HomeActions) {
    Page("Kanji", onBack) {
        LoadingBar(data)
        Text("${data.kanji.size} cards")
        if (data.stacks.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.stacks.forEach { s ->
                    FilterChip(selected = s.id == data.stack.id, onClick = { app.settings.setStack(s.id) }, label = { Text("${s.label} (${data.kanji.inStack(s.id).size})") })
                }
            }
            Text("Each stack keeps its own review schedule.", style = MaterialTheme.typography.bodySmall)
        }
        ModeRow("Meaning quiz", data.mode(HomeMode.Meaning), a.onQuiz)
        ModeRow("Drawing", data.mode(HomeMode.Drawing), a.onDraw)
        ModeRow("Readings (on'yomi and kun'yomi)", data.mode(HomeMode.Readings), a.onReadings)
        OutlinedButton(onClick = a.onCards, enabled = data.kanji.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Browse cards") }
    }
}

@Composable
private fun WordsPage(app: KanjiApp, data: HomeData, onBack: () -> Unit, a: HomeActions) {
    Page("Words", onBack) {
        LoadingBar(data)
        Text("${data.wordCount} words")
        N4Offer(app, data)
        if (data.wordStacks.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.wordStacks.forEach { s ->
                    FilterChip(selected = s.id == data.wordStack.id, onClick = { app.settings.setWordStack(s.id) }, label = { Text(s.label) })
                }
            }
        }
        ModeRow("Japanese → English", data.mode(HomeMode.WordJp)) { a.onWords(WordDirection.JpToEn, it) }
        ModeRow("English → Japanese", data.mode(HomeMode.WordEn)) { a.onWords(WordDirection.EnToJp, it) }
        ModeRow("Reading", data.mode(HomeMode.WordReading)) { a.onWords(WordDirection.Reading, it) }
        OutlinedButton(onClick = a.onWordCards, enabled = data.wordCount > 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Browse words") }
    }
}

@Composable
private fun LoadingBar(data: HomeData) {
    if (!data.loading) return
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text("Loading your cards…", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Entry(title: String, summary: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            Text("›", modifier = Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.headlineMedium)
        }
    }
}

/** One study mode: a title, what is on offer, and a single button whose meaning depends on the state. */
@Composable
private fun ModeRow(title: String, state: ModeState, onStart: (extra: Boolean) -> Unit) {
    val summary = when (state) {
        ModeState.Loading -> "Loading…"
        is ModeState.Start -> "${state.due} due, ${state.new} new today"
        ModeState.PracticeMore -> "Nothing due right now"
        is ModeState.Locked -> state.reason
        is ModeState.Unavailable -> state.reason
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { stateDescription = summary }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(summary, style = MaterialTheme.typography.bodyMedium)
        if (state == ModeState.PracticeMore) {
            OutlinedButton(onClick = { onStart(true) }, modifier = Modifier.fillMaxWidth()) { Text("Practice more") }
        } else {
            Button(onClick = { onStart(false) }, enabled = state.enabled, modifier = Modifier.fillMaxWidth()) { Text("Start") }
        }
    }
}

@Composable
private fun N4Offer(app: KanjiApp, data: HomeData) {
    if (!data.n4Offer) return
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("You know N5 well!", style = MaterialTheme.typography.titleMedium)
            Text("Most N5 kanji and words are solid across quizzes and drawing. Ready to add N4 words?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.settings.setN4Unlocked(true); app.settings.setWordStack(WordStacks.n4.id) }) { Text("Add N4 words") }
                TextButton(onClick = { app.settings.dismissAdvance(System.currentTimeMillis()) }) { Text("Not yet") }
            }
        }
    }
}
