package io.github.stopcran.kanji.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.Advancement
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQueues
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.inWordStack
import io.github.stopcran.kanji.data.levels
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import io.github.stopcran.kanji.data.inStack
import io.github.stopcran.kanji.data.stacks
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
fun HomeScreen(app: KanjiApp, onSettings: () -> Unit, onCards: () -> Unit, onQuiz: (Boolean) -> Unit, onDraw: (Boolean) -> Unit, onWords: (WordDirection, Boolean) -> Unit) {
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

    val stackId by app.settings.stack.collectAsState()
    val stacks = remember(kanji) { kanji.stacks() }
    val stack = Stacks.find(stackId, stacks)
    val inStack = remember(kanji, stack) { kanji.inStack(stack.id) }

    val quizQueue = rememberQueue(app, source.id, stack.id, inStack.map { it.kanji }, StudyMode.Quiz, dailyNew)
    val drawQueue = rememberQueue(app, source.id, stack.id, inStack.filter { it.strokesJson != null }.map { it.kanji }, StudyMode.Draw, dailyNew)

    val n4Unlocked by app.settings.n4Unlocked.collectAsState()
    val dismissedMs by app.settings.advanceDismissedMs.collectAsState()
    val words by remember(source) { app.db.content().observeWords(source.id) }.collectAsState(emptyList())
    val wordStacks = WordStacks.offered(n4Unlocked)
    val wordStackId by app.settings.wordStack.collectAsState()
    val wordStack = WordStacks.find(wordStackId, wordStacks)
    val levels = remember(kanji) { kanji.levels() }
    val wordsInStack = remember(words, levels, wordStack) { words.inWordStack(wordStack, levels) }
    val wordIds = wordsInStack.map { it.word }
    val jpStates = rememberStates(app, source.id, wordStack.id, StudyMode.WordJpEn)
    val enStates = rememberStates(app, source.id, wordStack.id, StudyMode.WordEnJp)
    val startOfDay = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val jpIntroduced by produceState(0, source.id, wordStack.id, jpStates.size) { value = app.db.reviews().newCardsIntroducedSince(source.id, wordStack.id, StudyMode.WordJpEn.name, startOfDay) }
    val enIntroduced by produceState(0, source.id, wordStack.id, enStates.size) { value = app.db.reviews().newCardsIntroducedSince(source.id, wordStack.id, StudyMode.WordEnJp.name, startOfDay) }
    val now = Instant.now()
    val jpQueue = WordQueues.build(WordDirection.JpToEn, wordIds, jpStates, enStates, now, dailyNew - jpIntroduced)
    val enQueue = WordQueues.build(WordDirection.EnToJp, wordIds, enStates, jpStates, now, dailyNew - enIntroduced)

    val quizStates = rememberStates(app, source.id, stack.id, StudyMode.Quiz)
    val drawStates = rememberStates(app, source.id, stack.id, StudyMode.Draw)
    val n5Kanji = inStack.filter { it.jlpt == 5 }
    val n5Words = remember(words, levels) { words.inWordStack(WordStacks.n5, levels) }
    val ready = remember(quizStates, drawStates, jpStates, enStates, n5Kanji, n5Words, wordStack) {
        Advancement.ready(
            listOf(
                n5Kanji.map { quizStates[it.kanji] },
                n5Kanji.filter { it.strokesJson != null }.map { drawStates[it.kanji] },
                if (wordStack.id == WordStacks.n5.id) n5Words.map { jpStates[it.word] } else emptyList(),
                if (wordStack.id == WordStacks.n5.id) n5Words.map { enStates[it.word] } else emptyList(),
            ),
            Instant.now(),
        )
    }

    Page("Kanji Cards", actions = { TextButton(onClick = onSettings) { Text("⚙") } }) {
        if (syncStatus.isNotEmpty()) Text(syncStatus)
        Text("${kanji.size} cards from ${source.id}")
        if (stacks.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stacks.forEach { s ->
                    FilterChip(selected = s.id == stack.id, onClick = { app.settings.setStack(s.id) }, label = { Text("${s.label} (${kanji.inStack(s.id).size})") })
                }
            }
            Text("Each stack keeps its own review schedule.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Meaning quiz: ${quizQueue.count { !it.isNew }} due, ${quizQueue.count { it.isNew }} new today")
        Button(onClick = { onQuiz(false) }, enabled = quizQueue.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start meaning quiz") }
        OutlinedButton(onClick = { onQuiz(true) }, enabled = inStack.size >= 2, modifier = Modifier.fillMaxWidth()) { Text("Extra practice") }
        Text("Drawing: ${drawQueue.count { !it.isNew }} due, ${drawQueue.count { it.isNew }} new today", modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { onDraw(false) }, enabled = drawQueue.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start drawing") }
        OutlinedButton(onClick = { onDraw(true) }, enabled = inStack.any { it.strokesJson != null }, modifier = Modifier.fillMaxWidth()) { Text("Extra drawing practice") }
        if (Advancement.shouldOffer(ready, n4Unlocked, dismissedMs, System.currentTimeMillis())) {
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
        Text("Words (${wordsInStack.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        if (wordStacks.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wordStacks.forEach { s ->
                    FilterChip(selected = s.id == wordStack.id, onClick = { app.settings.setWordStack(s.id) }, label = { Text(s.label) })
                }
            }
        }
        Text("Japanese → English: ${jpQueue.count { !it.isNew }} due, ${jpQueue.count { it.isNew }} new today")
        Button(onClick = { onWords(WordDirection.JpToEn, false) }, enabled = jpQueue.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start Japanese → English") }
        Text("English → Japanese: ${enQueue.count { !it.isNew }} due (unlocks as you learn words)")
        Button(onClick = { onWords(WordDirection.EnToJp, false) }, enabled = enQueue.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Start English → Japanese") }
        OutlinedButton(onClick = { onWords(WordDirection.JpToEn, true) }, enabled = wordsInStack.size >= 2, modifier = Modifier.fillMaxWidth()) { Text("Extra word practice") }
        OutlinedButton(onClick = onCards, enabled = kanji.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Browse cards") }
    }
}

@Composable
private fun rememberStates(app: KanjiApp, sourceId: String, stack: String, mode: StudyMode): Map<String, SrsState> {
    val states by remember(sourceId, stack, mode) { app.db.reviews().observeStates(sourceId, stack, mode.name) }.collectAsState(emptyList<ReviewStateEntity>())
    return remember(states) { states.associate { it.kanji to it.toSrs() } }
}

/** Cards that would be offered now for [mode]: due first, then new ones within today's remaining budget. */
@Composable
private fun rememberQueue(app: KanjiApp, sourceId: String, stack: String, ids: List<String>, mode: StudyMode, dailyNew: Int): List<QueueItem> {
    val states by remember(sourceId, stack, mode) { app.db.reviews().observeStates(sourceId, stack, mode.name) }.collectAsState(emptyList<ReviewStateEntity>())
    val startOfDay = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val introduced by produceState(0, sourceId, stack, states.size) { value = app.db.reviews().newCardsIntroducedSince(sourceId, stack, mode.name, startOfDay) }
    return QueueBuilder.build(ids, states.associate { it.kanji to it.toSrs() }, Instant.now(), dailyNew - introduced)
}