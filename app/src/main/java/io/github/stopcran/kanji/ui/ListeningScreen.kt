package io.github.stopcran.kanji.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.withTransaction
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.data.ListeningOverview
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.Grade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun rememberListeningHome(app: KanjiApp): State<ListeningHomeState> {
    return rememberListeningHome(app.services)
}

@Composable
internal fun rememberListeningHome(services: Services): State<ListeningHomeState> {
    val source by services.settings.source.collectAsState()
    val budget by services.settings.dailyNewCards.collectAsState()
    val week by services.settings.weekPlan.collectAsState()
    return key(source.id, budget, week) {
        produceState(ListeningHomeState()) {
            listeningAction({ value = ListeningHomeState(error = it) }) {
                combine(listOf(
                    services.db.listening().observePassages(source.id).map { Unit }, services.db.listening().observeOpen().map { Unit },
                    services.db.listeningTasks().observeTasks(source.id).map { Unit }, services.db.listeningTasks().observeOpen().map { Unit },
                    services.db.listeningTasks().observeSync(source.id).map { Unit },
                    services.db.listeningTasks().observeActiveAssessment(source.id).map { Unit },
                    services.db.reviews().observeStates(source.id, LISTENING_STACK, LISTENING_MODE).map { Unit },
                    flow { while (true) { emit(Unit); delay(30_000) } },
                )) { Unit }.collectLatest {
                    value = services.db.withTransaction {
                        ListeningHomeState(services.listening.overview(source.id),
                            queue = services.listeningTasks.overview(source.id),
                            resumes = services.db.listening().open() + services.db.listeningTasks().open(),
                            assessmentPending = services.db.listeningTasks().activeAssessment(source.id) != null,
                            taskNotice = services.db.listeningTasks().sync(source.id)?.problem)
                    }
                }
            }
        }
    }
}

@Composable
fun ListeningPage(app: KanjiApp, onBack: () -> Unit, onEncounter: (String) -> Unit) {
    ListeningBrowser(app.services, onBack, onEncounter)
}

@Composable
internal fun ListeningStartButton(data: ListeningOverview, busy: Boolean, onStart: (String, Boolean) -> Unit) {
    val scheduled = data.due.firstOrNull() ?: data.fresh.firstOrNull()
    val extra = scheduled == null && data.extra.isNotEmpty()
    val next = scheduled ?: data.extra.firstOrNull()
    val enabled = next != null && data.active == null && !busy
    if (extra) {
        OutlinedButton(onClick = { next?.let { onStart(it, true) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.practice_more))
        }
        Text(stringResource(R.string.listening_extra_practice), style = MaterialTheme.typography.bodySmall)
    } else {
        Button(onClick = { next?.let { onStart(it, false) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Start listening")
        }
    }
}

@Composable
fun ListeningScreen(encounterId: String, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    val vm = serviceViewModel(encounterId) { ListeningViewModel(it, encounterId) }
    LaunchedEffect(encounterId) { vm.load() }
    ListeningEncounterView(vm, onBack, onOpenDoc)
}

@Composable
internal fun ListeningEncounterView(vm: ListeningViewModel, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vm, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.pause(release = true)
            if (event == Lifecycle.Event.ON_START) vm.load()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.pause(release = true) }
    }
    var discard by remember { mutableStateOf(false) }
    var vocabulary by rememberSaveable { mutableStateOf<String?>(null) }
    var article by rememberSaveable { mutableStateOf<String?>(null) }
    val row = vm.encounter
    val passage = vm.passage
    val attempt = vm.attempt
    fun leave() {
        if (row?.kind == "study" || row?.reviewLogId != null) vm.close(false, onBack)
        else { vm.pause(release = true); onBack() }
    }
    BackHandler { if (article != null) article = null else leave() }
    val capturedArticle = article?.let { attempt?.vocabularyBodies?.get(it) }
    if (capturedArticle != null && row != null) {
        Page(requireNotNull(article), { article = null }, scrollable = false) {
            LazyMarkdownView(capturedArticle, "words/$article.md", { onOpenDoc(row.sourceId, it) }, Modifier.weight(1f))
        }
        return
    }
    key(row?.id, attempt?.phase) { Page(passage?.title ?: "Listening", ::leave) {
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            Text("No failure is graded for a playback or storage error. The encounter remains resumable.")
            Button(onClick = vm::retry, enabled = !vm.busy) { Text("Retry") }
        }
        if (row == null || passage == null || attempt == null) {
            if (vm.error == null) Text("Loading the captured recording...")
        } else if (row.closedAtMs != null) {
            Text("This encounter is complete.")
            Button(onClick = onBack) { Text("Back") }
        } else {
            val study = row.kind == "study"
            val feedback = study || row.reviewLogId != null
            val ready = !vm.busy && vm.error == null
            Text("${if (study) "Unscored study" else "N5 listening"} - ${row.sourceId}", style = MaterialTheme.typography.bodySmall)
            vm.picture?.let { SceneBitmap(it, passage.topic) }
            Text("${vm.positionMs / 1_000}s / ${requireNotNull(passage.audio).durationMs / 1_000}s")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.playing) Button(onClick = { vm.pause() }) { Text("Pause") }
                else Button(onClick = { vm.play(restart = vm.positionMs >= passage.audio.durationMs) }, enabled = ready) {
                    Text(if (vm.positionMs >= passage.audio.durationMs) "Replay" else if (vm.positionMs > 0) "Resume audio" else "Play")
                }
                OutlinedButton(onClick = { vm.play(restart = true) }, enabled = ready && !vm.playing && attempt.starts > 0) { Text("From beginning") }
            }
            OutlinedButton(onClick = { vm.play(restart = vm.positionMs >= passage.audio.durationMs, slower = true) },
                enabled = ready && !vm.playing) { Text("Play more slowly (help)") }
            if (!feedback) {
                Text("The recording already uses a mild N5 pace. A third full start or extra slowing counts as help.")
                if (attempt.assistance.isNotEmpty()) Text("Help used: ${attempt.assistance.joinToString()}")
                if (attempt.phase == "questions") {
                    passage.questions.forEach { question ->
                        Text(question.prompt, style = MaterialTheme.typography.titleMedium)
                        attempt.optionOrder.getValue(question.id).forEach { id ->
                            val option = question.options.single { it.id == id }
                            FilterChip(selected = attempt.answers[question.id] == id,
                                onClick = { vm.answer(question.id, id) }, enabled = ready,
                                label = { Text(option.label) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Button(onClick = { vm.submit() }, enabled = ready && attempt.answers.size == 3,
                        modifier = Modifier.fillMaxWidth()) { Text("Check all answers") }
                    OutlinedButton(onClick = { vm.submit(guessed = true) }, enabled = ready && attempt.answers.size == 3,
                        modifier = Modifier.fillMaxWidth()) { Text("Check - I guessed") }
                } else Text("Questions appear after the first complete playback. Pausing or leaving preserves this encounter.")
                TextButton(onClick = { vm.submit(giveUp = true) }, enabled = ready && row.exposedAtMs != null) { Text("I cannot follow this (Again)") }
            } else {
                vm.response?.let { result ->
                    val percent = (requireNotNull(result.correct) * 100.0 / 3).roundToInt()
                    Text("${result.correct}/3 ($percent%) - ${Grade.entries.single { it.value == result.grade }.name}",
                        style = MaterialTheme.typography.headlineSmall)
                    Text(if (row.firstEncounter) "First encounter in this source" else "Previously encountered passage")
                    Text("This measures this passage, not your ability to understand unseen speech.", style = MaterialTheme.typography.bodySmall)
                    if (result.assistance.isNotEmpty()) Text("Help used: ${result.assistance.joinToString()}")
                }
                passage.questions.forEach { question ->
                    HorizontalDivider()
                    Text(question.prompt, style = MaterialTheme.typography.titleMedium)
                    (attempt.optionOrder.getValue(question.id)).forEach { id ->
                        val option = question.options.single { it.id == id }
                        val correct = id == question.answer
                        val selected = vm.response?.answers?.get(question.id) == id
                        Text("${if (correct) "Correct: " else if (selected) "Your answer: " else ""}${option.label}",
                            color = if (selected && !correct) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(option.rationale, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(question.explanation)
                    Text("Evidence: ${question.evidence.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Transcript and translation", style = MaterialTheme.typography.titleLarge)
                passage.turns.forEach { turn ->
                    Text("${turn.speaker}: ${turn.text}", style = MaterialTheme.typography.titleMedium)
                    Text(turn.translation)
                    TextButton(onClick = { vm.play(turnId = turn.id) }, enabled = ready && !vm.playing) { Text("Replay ${turn.id}") }
                }
                if (passage.vocabulary.isNotEmpty()) Text("Vocabulary", style = MaterialTheme.typography.titleLarge)
                passage.vocabulary.forEach { word ->
                    TextButton(onClick = { vocabulary = word.surface }, modifier = Modifier.fillMaxWidth()) {
                        Text("${word.surface} - ${word.reading}")
                    }
                }
                Button(onClick = { vm.close(false, onBack) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
            if (row.reviewLogId == null) TextButton(onClick = { discard = true }, enabled = !vm.busy) { Text("Discard encounter") }
        }
    } }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("Discard this encounter?") },
        text = { Text("No failure will be graded. If you heard or studied it, a fresh scored encounter waits 24 hours after your last exposure.") },
        confirmButton = { TextButton(onClick = { discard = false; vm.close(true, onBack) }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep") } },
    )
    passage?.vocabulary?.find { it.surface == vocabulary }?.let { word ->
        AlertDialog(onDismissRequest = { vocabulary = null }, title = { Text(word.surface) },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                Text(word.reading); Text(word.gloss)
            } },
            confirmButton = { TextButton(onClick = { vocabulary = null }) { Text("Close") } },
            dismissButton = {
                if (word.word in attempt?.vocabularyBodies.orEmpty()) TextButton(onClick = {
                    vm.pause(); article = word.word; vocabulary = null
                }) { Text("Read word article") }
            })
    }
}
