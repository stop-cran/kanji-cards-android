package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.QueueItem
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.SyncResult
import io.github.stopcran.kanji.data.toSrs
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(app: KanjiApp, onSettings: () -> Unit, onCards: () -> Unit, onQuiz: (Boolean) -> Unit, onDraw: (Boolean) -> Unit) {
    val source by app.settings.source.collectAsState()
    val dailyNew by app.settings.dailyNewCards.collectAsState()
    val kanji by remember(source) { app.db.content().observeKanji(source.id) }.collectAsState(emptyList())
    val syncStatus by produceState("", source) {
        if (app.db.content().meta(source.id) == null) {
            value = "Downloading cards…"
            value = when (val r = app.contentSync.sync(source)) {
                is SyncResult.Failed -> "Could not download cards: ${r.message}"
                else -> ""
            }
        }
    }

    val quizQueue = rememberQueue(app, source.id, kanji.map { it.kanji }, StudyMode.Quiz, dailyNew)
    val drawQueue = rememberQueue(app, source.id, kanji.filter { it.strokesJson != null }.map { it.kanji }, StudyMode.Draw, dailyNew)

    Page("Kanji Cards", actions = { TextButton(onClick = onSettings) { Text("⚙") } }) {
        if (syncStatus.isNotEmpty()) Text(syncStatus)
        Text("${kanji.size} cards from ${source.id}")
        Text("Meaning quiz: ${quizQueue.count { !it.isNew }} due, ${quizQueue.count { it.isNew }} new today")
        Button(onClick = { onQuiz(false) }, enabled = quizQueue.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start meaning quiz") }
        OutlinedButton(onClick = { onQuiz(true) }, enabled = kanji.size >= 2, modifier = Modifier.fillMaxWidth()) { Text("Extra practice") }
        Text("Drawing: ${drawQueue.count { !it.isNew }} due, ${drawQueue.count { it.isNew }} new today", modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { onDraw(false) }, enabled = drawQueue.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start drawing") }
        OutlinedButton(onClick = { onDraw(true) }, enabled = kanji.any { it.strokesJson != null }, modifier = Modifier.fillMaxWidth()) { Text("Extra drawing practice") }
        OutlinedButton(onClick = onCards, enabled = kanji.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Browse cards") }
    }
}

/** Cards that would be offered now for [mode]: due first, then new ones within today's remaining budget. */
@Composable
private fun rememberQueue(app: KanjiApp, sourceId: String, ids: List<String>, mode: StudyMode, dailyNew: Int): List<QueueItem> {
    val states by remember(sourceId, mode) { app.db.reviews().observeStates(sourceId, mode.name) }.collectAsState(emptyList<ReviewStateEntity>())
    val startOfDay = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val introduced by produceState(0, sourceId, states.size) { value = app.db.reviews().newCardsIntroducedSince(sourceId, mode.name, startOfDay) }
    return QueueBuilder.build(ids, states.associate { it.kanji to it.toSrs() }, Instant.now(), dailyNew - introduced)
}