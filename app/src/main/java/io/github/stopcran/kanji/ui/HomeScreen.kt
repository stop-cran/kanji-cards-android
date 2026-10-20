package io.github.stopcran.kanji.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.Defaults
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.srs.PacingNote
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.SyncResult
import io.github.stopcran.kanji.data.inStack

/** Where the user can go from the home pages; sessions are started by the caller. */
@Suppress("LongParameterList")
class HomeActions(
    val onSettings: () -> Unit,
    val onCards: () -> Unit,
    val onWordCards: () -> Unit,
    val onKanjiPage: () -> Unit,
    val onWordsPage: () -> Unit,
    val onCountersPage: () -> Unit,
    val onGrammarPage: () -> Unit,
    val onOpenDoc: (String) -> Unit,
    val onKanaPage: () -> Unit,
    val onKanaStudy: (KanaScript, KanaMode, extra: Boolean) -> Unit,
    val onCounters: (io.github.stopcran.kanji.core.counters.CounterMode, extra: Boolean) -> Unit,
    val onGrammar: (extra: Boolean) -> Unit,
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
        "counters-home" -> CountersPage(app, onBack, actions)
        "grammar-home" -> GrammarPage(app, onBack, actions.onOpenDoc, actions)
        "kana-home" -> KanaPage(app, onBack, actions.onKanaStudy)
        else -> MainPage(app, data, syncStatus, actions)
    }
}

@Composable
private fun MainPage(app: KanjiApp, data: HomeData, syncStatus: String, a: HomeActions) {
    val kana = rememberKanaOverview(app)
    Page("Ensō Nihongo", actions = { IconButton(onClick = a.onSettings) { Image(painterResource(R.drawable.settings_brush), "Settings", Modifier.size(27.dp)) } }) {
        if (syncStatus.isNotEmpty()) Text(syncStatus)
        LoadingBar(data)
        val defaultId = remember { RepoSource.parse(Defaults.CONTENT_REPO_URL, Defaults.CONTENT_BRANCH)?.id }
        if (data.sourceId != defaultId) Text(stringResource(R.string.cards_from, data.sourceId))
        N4Offer(app, data)
        PacingHint(data)
        Entry("Kana", kana.summary(), R.drawable.kana_sumi, a.onKanaPage)
        Entry("Kanji", data.summary(HomeMode.Meaning, HomeMode.Drawing, HomeMode.Readings) ?: "Loading…", ArtSet.current.kanji, a.onKanjiPage)
        val words = data.summary(
            HomeMode.WordJp, HomeMode.WordEn, HomeMode.WordReading, HomeMode.WordType, HomeMode.WordPicture, HomeMode.WordToPicture,
        ) ?: "Loading…"
        Entry("Words", words, ArtSet.current.words, a.onWordsPage)
        val counters = rememberCounterHome(app)
        Entry("Counters", counters.summary(), ArtSet.current.counters, a.onCountersPage)
        val grammar = rememberGrammarHome(app).value
        Entry("Grammar", grammar.summary(), null, a.onGrammarPage)
    }
}

@Composable
private fun KanjiPage(app: KanjiApp, data: HomeData, onBack: () -> Unit, a: HomeActions) {
    Page("Kanji", onBack) {
        LoadingBar(data)
        Text(stringResource(R.string.cards, data.kanji.size))
        if (data.stacks.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.stacks.forEach { s ->
                    FilterChip(selected = s.id == data.stack.id, onClick = { app.settings.setStack(s.id) }, label = { Text(stringResource(R.string.label_with_count, s.label, data.kanji.inStack(s.id).size)) })
                }
            }
            Text(stringResource(R.string.each_stack_keeps_its_own), style = MaterialTheme.typography.bodySmall)
        }
        ModeRow("Meaning quiz", data.mode(HomeMode.Meaning), onStart = a.onQuiz)
        ModeRow("Drawing", data.mode(HomeMode.Drawing), onStart = a.onDraw)
        ModeRow("Readings (on'yomi and kun'yomi)", data.mode(HomeMode.Readings), onStart = a.onReadings)
        OutlinedButton(onClick = a.onCards, enabled = data.kanji.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.browse_cards)) }
    }
}

@Composable
private fun WordsPage(app: KanjiApp, data: HomeData, onBack: () -> Unit, a: HomeActions) {
    Page("Words", onBack) {
        LoadingBar(data)
        Text(data.wordStack.label + " · " + stringResource(R.string.words, data.wordCount))
        N4Offer(app, data)
        if (data.wordStacks.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                data.wordStacks.forEach { s ->
                    FilterChip(selected = s.id == data.wordStack.id, onClick = { app.settings.setWordStack(s.id) }, label = { Text(s.label) })
                }
            }
        }
        ModeRow("Japanese → English", data.mode(HomeMode.WordJp), onStart = { a.onWords(WordDirection.JpToEn, it) })
        ModeRow("English → Japanese", data.mode(HomeMode.WordEn), onStart = { a.onWords(WordDirection.EnToJp, it) })
        ModeRow("Reading", data.mode(HomeMode.WordReading), onStart = { a.onWords(WordDirection.Reading, it) })
        ModeRow("Type it", data.mode(HomeMode.WordType), onStart = { a.onWords(WordDirection.Type, it) })
        if (data.pictureWordCount > 0) {
            ModeRow("Picture → word", data.mode(HomeMode.WordPicture), onStart = { a.onWords(WordDirection.Picture, it) })
            ModeRow("Word → picture", data.mode(HomeMode.WordToPicture), onStart = { a.onWords(WordDirection.WordToPicture, it) })
        }
        OutlinedButton(onClick = a.onWordCards, enabled = data.wordCount > 0, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.browse_words)) }
    }
}

@Composable
private fun LoadingBar(data: HomeData) {
    if (!data.loading) return
    LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(stringResource(R.string.loading_your_cards), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Entry(title: String, summary: String, art: Int?, onClick: () -> Unit) {
    ArtCard(title, summary, art, onClick)
}

/** One study mode: a title, what is on offer, and a single button whose meaning depends on the state. */
@Composable
internal fun ModeRow(
    title: String,
    state: ModeState,
    supportingText: String? = null,
    onStart: (extra: Boolean) -> Unit,
) {
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
        supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state == ModeState.PracticeMore) {
            OutlinedButton(onClick = { onStart(true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_more)) }
        } else {
            Button(onClick = { onStart(false) }, enabled = state.enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.start)) }
        }
    }
}

/** Explains a reduced or empty new-item allowance; reviews are never limited, the backlog case only warns. */
@Composable
private fun PacingHint(data: HomeData) {
    data.week?.let { w ->
        val tail = if (w.studied >= w.goal) " — goal reached" else ""
        Text(stringResource(R.string.this_week_of_study_days, w.studied, w.goal, tail), style = MaterialTheme.typography.bodySmall)
    }
    val p = data.pacing
    data.focus?.takeIf { it.full }?.let {
        Text("Finish your current ${it.occupancy} kanji first: new kanji come in as they are learned in every mode.", style = MaterialTheme.typography.bodySmall)
    }
    if (p == null) return
    val text = when (p.note) {
        PacingNote.None -> return
        PacingNote.RestDay -> "Rest day in your weekly rhythm: no new items today, reviews are still available."
        PacingNote.LightDay -> "Light day: ${p.total} new items today."
        PacingNote.BacklogReduced -> "${p.due} reviews are waiting, so new items are reduced to ${p.total} today."
        PacingNote.BacklogPaused -> "${p.due} reviews are waiting, so new items are paused. A short review session now keeps things from piling up."
    }
    Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun N4Offer(app: KanjiApp, data: HomeData) {
    if (!data.n4Offer) return
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.you_know_n5_well), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.most_n5_kanji_and_words))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.settings.setN4Unlocked(true); app.settings.setWordStack(WordStacks.n4.id) }) { Text(stringResource(R.string.add_n4_words)) }
                TextButton(onClick = { app.settings.dismissAdvance(System.currentTimeMillis()) }) { Text(stringResource(R.string.not_yet)) }
            }
        }
    }
}
