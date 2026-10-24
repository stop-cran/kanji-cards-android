package io.github.stopcran.kanji.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.stopcran.kanji.core.listening.*

@Composable
fun ListeningTaskScreen(id: String, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    val vm = serviceViewModel(id) { ListeningTaskViewModel(it, id) }
    ListeningTaskEncounterView(vm, onBack, onOpenDoc)
}

@Composable
internal fun ListeningTaskEncounterView(vm: ListeningTaskViewModel, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { vm.load() }
    DisposableEffect(vm, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.pause(release = true)
            if (event == Lifecycle.Event.ON_START) vm.load()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.pause(release = true) }
    }
    var discard by remember { mutableStateOf(false) }
    var article by rememberSaveable { mutableStateOf<String?>(null) }
    val pages = rememberSaveableStateHolder()
    val row = vm.encounter
    val task = vm.task
    val state = vm.attempt
    val words = vm.vocabularyBodies
    val scroll = key(row?.id, state?.phase) { rememberScrollState() }
    fun leave() {
        if (article != null) article = null
        else if (row?.kind == "study" || row?.reviewLogId != null) vm.close(false, onBack)
        else { vm.pause(release = true); onBack() }
    }
    BackHandler(onBack = ::leave)
    val body = words[article]
    if (body != null && row != null) {
        pages.SaveableStateProvider("article:$article") {
            Page(requireNotNull(article), { article = null }, scrollable = false) {
                LazyMarkdownView(body, "words/$article.md", { onOpenDoc(row.sourceId, it) }, Modifier.weight(1f))
            }
        }
        return
    }
    pages.SaveableStateProvider("task:${row?.id}:${state?.phase}") {
        Page(task?.title ?: "Japanese listening task", ::leave, scrollable = false, maxWidth = 1100.dp) {
            if (vm.busy && !vm.playing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                row?.closedAtMs != null -> {
                    Text("This encounter is complete.")
                    Button(onClick = onBack) { Text("Back to Listening") }
                }
                vm.error != null -> ListeningTaskUnavailable(vm) { discard = true }
                row == null || task == null || state == null || !vm.ready -> {
                    Text("Loading the captured task...")
                    if (row != null) TextButton(onClick = { discard = true }, enabled = !vm.busy) { Text("Discard encounter") }
                }
                else -> {
                    val feedback = row.kind == "study" || row.reviewLogId != null
                    val textHelp = "text-help" in state.assistance
                    ListeningTaskLayout(vm, vm.picture.takeUnless { state.describedStudy }, task.visual?.role == "required", scroll) {
                        Text(task.taskType.label, style = MaterialTheme.typography.titleMedium)
                        Text(task.taskType.objective)
                        Text("${if (row.kind == "study") "Unscored study" else "Guided practice"} - ${row.sourceId} (${row.branch})",
                            style = MaterialTheme.typography.bodySmall)
                        if (state.describedStudy) {
                            Text("Described study is unscored; it is not an equivalent scored nonvisual presentation.")
                            Text(requireNotNull(task.visual).description, Modifier.listeningEvidence(vm))
                        } else if (task.visual?.role == "required" && !feedback) {
                            Text("This task requires its picture to score. For nonvisual study, " +
                                "discard without a grade and choose Described study in Listening.")
                        }
                        if (!feedback) {
                            ListeningTaskControls(vm)
                            ListeningTaskChoices(vm, textHelp)
                            ListeningTaskAnswerActions(vm)
                            if (textHelp) ListeningTaskTranscript(vm)
                            else OutlinedButton(onClick = vm::help, enabled = !vm.busy) { Text("Show transcript and translation (help)") }
                        } else {
                            ListeningTaskFeedback(vm) { word -> vm.pause(); article = word }
                            ListeningTaskControls(vm)
                            Button(onClick = { vm.close(false, onBack) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                        }
                        if (row.reviewLogId == null) TextButton(onClick = { discard = true }, enabled = !vm.busy) { Text("Discard encounter") }
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text("Discard this encounter?") },
        text = { Text("No failure is graded. Actual exposure remains recorded; a new scored encounter waits 24 hours. Unscored study remains available.") },
        confirmButton = { TextButton(onClick = { discard = false; vm.close(true, onBack) }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep") } },
    )
}

@Composable
private fun ColumnScope.ListeningTaskUnavailable(vm: ListeningTaskViewModel, discard: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(requireNotNull(vm.error), color = MaterialTheme.colorScheme.error)
        Text("Technical unavailability is not a wrong answer. Retry the captured evidence or explicitly discard the attempt.")
        OutlinedButton(onClick = vm::retry, enabled = !vm.busy) { Text("Retry captured evidence") }
        if (vm.encounter != null) TextButton(onClick = discard, enabled = !vm.busy) { Text("Discard encounter") }
    }
}

@Composable
private fun ColumnScope.ListeningTaskLayout(
    vm: ListeningTaskViewModel, picture: ImageBitmap?, required: Boolean, scroll: ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val pictureHeight = (maxHeight * 0.4f).coerceAtMost(260.dp)
        if (picture != null && maxWidth > maxHeight && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ListeningTaskPicture(vm, picture, required, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picture != null) ListeningTaskPicture(vm, picture, required, Modifier.fillMaxWidth().height(pictureHeight))
                Column(Modifier.weight(1f).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        }
    }
}

@Composable
private fun ListeningTaskControls(vm: ListeningTaskViewModel) {
    val task = requireNotNull(vm.task)
    val state = requireNotNull(vm.attempt)
    val complete = task.stages.all { it.id in state.completedStages }
    val whole = complete && !state.wholeReplayInProgress && state.positionMs == 0L
    val enabled = !vm.busy && vm.error == null
    Text("Stage ${state.stageIndex + 1}/${task.stages.size}: ${task.stages[state.stageIndex].label(task)}")
    Text("${state.completedStages.size}/${task.stages.size} required stages complete", style = MaterialTheme.typography.bodySmall)
    if (state.stageIndex == 0) {
        task.stages.first().turns.forEach { id -> Text(task.turns.single { it.id == id }.text) }
    }
    if (vm.playing) Button(onClick = { vm.pause() }) { Text("Pause") }
    else Button(onClick = { vm.play(wholeReplay = whole) }, enabled = enabled) {
        Text(if (whole) "Replay whole task" else if (state.positionMs > 0) "Resume audio" else "Play remaining stages")
    }
    OutlinedButton(onClick = { vm.play(wholeReplay = whole, slower = true) }, enabled = enabled) { Text("Play more slowly (help)") }
    if (!complete && state.positionMs > 0) {
        OutlinedButton(onClick = { vm.play(stage = state.stageIndex) }, enabled = enabled) { Text("Restart this stage (help)") }
    }
    if (complete && !state.wholeReplayInProgress && state.positionMs == 0L) {
        TextButton(onClick = { vm.play(stage = 0) }, enabled = enabled) { Text("Replay instructions") }
    }
    Text("One complete whole replay is normal. Partial/targeted replay, further replay, slowing, text help or guessing limits a correct result to Hard.",
        style = MaterialTheme.typography.bodySmall)
    (state.technicalProblem ?: state.pictureProblem)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ListeningTaskChoices(vm: ListeningTaskViewModel, reveal: Boolean) {
    val task = requireNotNull(vm.task)
    val state = requireNotNull(vm.attempt)
    val row = requireNotNull(vm.encounter)
    val answerable = state.phase == "answer" && row.kind == "graded" && row.reviewLogId == null && vm.error == null && !vm.busy
    Text("Choices", style = MaterialTheme.typography.titleMedium)
    if (task.choiceMode == TaskChoiceMode.Audio && !reveal) {
        Text("The three choices are spoken in number order. Choose a number after hearing all required stages.")
    }
    task.options.forEach { option ->
        val label = if (task.choiceMode == TaskChoiceMode.Text || reveal) "${option.number}. ${option.label}" else "${option.number}"
        val evidence = if (task.choiceMode == TaskChoiceMode.Text || reveal) Modifier.listeningEvidence(vm) else Modifier
        if (answerable) {
            FilterChip(selected = state.answer == option.id, onClick = { vm.answer(option.id) },
                label = { Text(label) }, modifier = evidence.fillMaxWidth())
        } else Text(label, modifier = evidence, style = MaterialTheme.typography.bodyLarge)
    }

}

internal fun Modifier.listeningEvidence(vm: ListeningTaskViewModel): Modifier = onGloballyPositioned {
    if (!it.boundsInWindow().isEmpty) vm.evidenceVisible()
}

@Composable
private fun ListeningTaskAnswerActions(vm: ListeningTaskViewModel) {
    val state = requireNotNull(vm.attempt)
    val available = !vm.busy && vm.error == null && state.technicalProblem == null && state.pictureProblem == null
    if (state.assistance.isNotEmpty()) Text("Help used: ${state.assistance.joinToString()}")
    if (state.phase == "answer") {
        Button(onClick = { vm.submit() }, enabled = available && state.answer != null, modifier = Modifier.fillMaxWidth()) { Text("Check answer") }
        OutlinedButton(onClick = { vm.submit(guessed = true) }, enabled = available && state.answer != null) { Text("Check - I guessed") }
    } else Text("Complete every required stage before choosing an answer.")
    TextButton(onClick = { vm.submit(giveUp = true) }, enabled = available && vm.encounter?.exposedAtMs != null) {
        Text("I cannot follow this (Again)")
    }
}
