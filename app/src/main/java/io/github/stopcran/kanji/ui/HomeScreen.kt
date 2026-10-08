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
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.SyncResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(app: KanjiApp, onSettings: () -> Unit, onCards: () -> Unit, onQuiz: (Boolean) -> Unit) {
    val source by app.settings.source.collectAsState()
    val dailyNew by app.settings.dailyNewCards.collectAsState()
    val kanji by remember(source) { app.db.content().observeKanji(source.id) }.collectAsState(emptyList())
    val states by remember(source) { app.db.reviews().observeStates(source.id, StudyMode.Quiz.name) }.collectAsState(emptyList<ReviewStateEntity>())
    val syncStatus by produceState("", source) {
        if (app.db.content().meta(source.id) == null) {
            value = "Downloading cards…"
            value = when (val r = app.contentSync.sync(source)) {
                is SyncResult.Failed -> "Could not download cards: ${r.message}"
                else -> ""
            }
        }
    }

    val startOfDay = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val introduced by produceState(0, source, states.size) { value = app.db.reviews().newCardsIntroducedSince(source.id, StudyMode.Quiz.name, startOfDay) }
    val srs = states.associate {
        it.kanji to SrsState(CardPhase.valueOf(it.phase), it.stability, it.difficulty, Instant.ofEpochMilli(it.dueMs), it.lastReviewMs?.let(Instant::ofEpochMilli), it.reps, it.lapses)
    }
    val queue = QueueBuilder.build(kanji.map { it.kanji }, srs, Instant.now(), dailyNew - introduced)
    val due = queue.count { !it.isNew }
    val fresh = queue.count { it.isNew }

    Page("Kanji Cards", actions = { TextButton(onClick = onSettings) { Text("⚙") } }) {
        if (syncStatus.isNotEmpty()) Text(syncStatus)
        Text("${kanji.size} cards from ${source.id}")
        Text("Meaning quiz: $due due, $fresh new today")
        Button(onClick = { onQuiz(false) }, enabled = queue.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start meaning quiz") }
        OutlinedButton(onClick = { onQuiz(true) }, enabled = kanji.size >= 2, modifier = Modifier.fillMaxWidth()) { Text("Extra practice") }
        OutlinedButton(onClick = onCards, enabled = kanji.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Browse cards") }
    }
}
