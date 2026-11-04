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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.stopcran.kanji.R
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
    val leave: () -> Unit = {
        if (article != null) article = null
        else if (row?.kind == "study" || row?.reviewLogId != null) vm.close(false, onBack)
        else { vm.pause(release = true); onBack() }
    }
    BackHandler(onBack = leave)
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
        Page(task?.title ?: stringResource(R.string.listening_task_default_title), leave, scrollable = false, maxWidth = 1100.dp) {
            CapturedListeningLanguageNotice(vm.capturedLanguage)
            if (vm.busy && !vm.playing) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                row?.closedAtMs != null -> {
                    Text(stringResource(R.string.listening_this_task_complete))
                    Button(onClick = onBack) { Text(stringResource(R.string.listening_back_to_listening)) }
                }
                vm.error != null -> ListeningTaskUnavailable(vm) { discard = true }
                row == null || task == null || state == null || !vm.ready -> {
                    Text(stringResource(R.string.listening_loading_captured_task))
                    if (row != null) TextButton(onClick = { discard = true }, enabled = !vm.busy) { Text(stringResource(R.string.listening_discard_encounter)) }
                }
                else -> {
                    val feedback = row.kind == "study" || row.reviewLogId != null
                    val textHelp = "text-help" in state.assistance
                    ListeningTaskLayout(vm.picture.takeUnless { state.describedStudy }, scroll, pictureView = { modifier ->
                        vm.picture?.let { ListeningTaskPicture(vm, it, task.visual?.role == "required", modifier) }
                    }) {
                        Text(task.taskType.label(), style = MaterialTheme.typography.titleMedium)
                        Text(task.taskType.objective())
                        Text(stringResource(
                            R.string.listening_task_study_status,
                            task.jlpt,
                            stringResource(if (row.kind == "study") R.string.listening_unscored_study else R.string.listening_guided_practice),
                            row.sourceId,
                            row.branch,
                        ),
                            style = MaterialTheme.typography.bodySmall)
                        if (state.describedStudy) {
                            Text(stringResource(R.string.listening_described_study_not_equivalent))
                            Text(requireNotNull(task.visual).description, Modifier.listeningEvidence(vm))
                        } else if (task.visual?.role == "required" && !feedback) {
                            Text(stringResource(R.string.listening_required_picture_score))
                        }
                        if (!feedback) {
                            ListeningTaskControls(vm)
                            ListeningTaskChoices(vm, textHelp)
                            ListeningTaskAnswerActions(vm)
                            if (textHelp) ListeningTaskTranscript(vm)
                            else OutlinedButton(onClick = vm::help, enabled = !vm.busy) {
                                Text(stringResource(R.string.listening_help_transcript_translation))
                            }
                        } else {
                            ListeningTaskFeedback(vm) { word -> vm.pause(); article = word }
                            ListeningTaskControls(vm)
                        }
                        if (row.reviewLogId == null) TextButton(onClick = { discard = true }, enabled = !vm.busy) {
                            Text(stringResource(R.string.listening_discard_encounter))
                        }
                    }
                    if (feedback) {
                        Button(onClick = { vm.close(false, onBack) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.listening_done))
                        }
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.listening_discard_this_encounter)) },
        text = { Text(stringResource(R.string.listening_discard_no_failure)) },
        confirmButton = { TextButton(onClick = { discard = false; vm.close(true, onBack) }) { Text(stringResource(R.string.listening_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.listening_keep)) } },
    )
}

@Composable
private fun ColumnScope.ListeningTaskUnavailable(vm: ListeningTaskViewModel, discard: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.listening_task_error_generic), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.listening_technical_unavailable))
        OutlinedButton(onClick = vm::retry, enabled = !vm.busy) { Text(stringResource(R.string.listening_retry_captured)) }
        if (vm.encounter != null) TextButton(onClick = discard, enabled = !vm.busy) {
            Text(stringResource(R.string.listening_discard_encounter))
        }
    }
}

@Composable
internal fun ColumnScope.ListeningTaskLayout(
    picture: ImageBitmap?, scroll: ScrollState, pictureView: @Composable (Modifier) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val pictureHeight = (maxHeight * 0.4f).coerceAtMost(260.dp)
        if (picture != null && maxWidth > maxHeight && maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pictureView(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (picture != null) pictureView(Modifier.fillMaxWidth().height(pictureHeight))
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
    Text(stringResource(R.string.listening_stage_progress, state.stageIndex + 1, task.stages.size, task.stages[state.stageIndex].label(task)))
    Text(stringResource(R.string.listening_stage_complete_count, state.completedStages.size, task.stages.size),
        style = MaterialTheme.typography.bodySmall)
    if (state.stageIndex == 0) {
        task.stages.first().turns.forEach { id -> Text(task.turns.single { it.id == id }.text) }
    }
    if (vm.playing) Button(onClick = { vm.pause() }) { Text(stringResource(R.string.listening_pause)) }
    else Button(onClick = { vm.play(wholeReplay = whole) }, enabled = enabled) {
        Text(stringResource(if (whole) R.string.listening_replay_whole_task else
            if (state.positionMs > 0) R.string.listening_resume_audio else R.string.listening_play_stage))
    }
    OutlinedButton(onClick = { vm.play(wholeReplay = whole, slower = true) }, enabled = enabled) {
        Text(stringResource(R.string.listening_play_slow_help))
    }
    if (!complete && state.positionMs > 0) {
        OutlinedButton(onClick = { vm.play(stage = state.stageIndex) }, enabled = enabled) {
            Text(stringResource(R.string.listening_restart_audio))
        }
    }
    if (complete && !state.wholeReplayInProgress && state.positionMs == 0L) {
        TextButton(onClick = { vm.play(stage = 0) }, enabled = enabled) {
            Text(stringResource(R.string.listening_replay_instructions))
        }
    }
    Text(stringResource(R.string.listening_playback_policy),
        style = MaterialTheme.typography.bodySmall)
    state.technicalProblem?.let { Text(stringResource(R.string.listening_audio_technical_problem), color = MaterialTheme.colorScheme.error) }
    state.pictureProblem?.let { Text(stringResource(R.string.listening_picture_technical_problem), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ListeningTaskChoices(vm: ListeningTaskViewModel, reveal: Boolean) {
    val task = requireNotNull(vm.task)
    val state = requireNotNull(vm.attempt)
    val row = requireNotNull(vm.encounter)
    val answerable = state.phase == "answer" && row.kind == "graded" && row.reviewLogId == null && vm.error == null && !vm.busy
    ListeningTaskChoices(task, state.answer, answerable, reveal, vm::answer, Modifier.listeningEvidence(vm))
}

@Composable
internal fun ListeningTaskChoices(
    task: ListeningTaskPackage, answer: String?, answerable: Boolean, reveal: Boolean,
    onAnswer: (String) -> Unit, evidence: Modifier,
) {
    Text(stringResource(R.string.listening_choices), style = MaterialTheme.typography.titleMedium)
    if (task.choiceMode == TaskChoiceMode.Audio && !reveal) {
        Text(stringResource(R.string.listening_audio_choice_order))
    }
    task.options.forEach { option ->
        val label = if (task.choiceMode == TaskChoiceMode.Text || reveal) "${option.number}. ${option.label}" else "${option.number}"
        val visible = if (task.choiceMode == TaskChoiceMode.Text || reveal) evidence else Modifier
        if (answerable) {
            FilterChip(selected = answer == option.id, onClick = { onAnswer(option.id) },
                label = { Text(label) }, modifier = visible.fillMaxWidth())
        } else Text(label, modifier = visible, style = MaterialTheme.typography.bodyLarge)
    }

}

internal fun Modifier.listeningEvidence(vm: ListeningTaskViewModel): Modifier = onGloballyPositioned {
    if (!it.boundsInWindow().isEmpty) vm.evidenceVisible()
}

@Composable
private fun ListeningTaskAnswerActions(vm: ListeningTaskViewModel) {
    val state = requireNotNull(vm.attempt)
    val available = !vm.busy && vm.error == null && state.technicalProblem == null && state.pictureProblem == null
    if (state.assistance.isNotEmpty()) Text(stringResource(
        R.string.listening_help_used,
        listeningAssistanceList(state.assistance),
    ))
    if (state.phase == "answer") {
        Button(onClick = { vm.submit() }, enabled = available && state.answer != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.check_answer))
        }
        OutlinedButton(onClick = { vm.submit(guessed = true) }, enabled = available && state.answer != null) {
            Text(stringResource(R.string.check_guessed))
        }
    } else Text(stringResource(R.string.listening_complete_stages_before_answer))
    TextButton(onClick = { vm.submit(giveUp = true) }, enabled = available && vm.encounter?.exposedAtMs != null) {
        Text(stringResource(R.string.listening_cannot_follow))
    }
}
