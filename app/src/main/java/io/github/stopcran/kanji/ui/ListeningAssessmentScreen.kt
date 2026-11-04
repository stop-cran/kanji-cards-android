package io.github.stopcran.kanji.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.listening.*

@Composable
fun ListeningAssessmentScreen(id: String, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    val vm = serviceViewModel(id) { ListeningAssessmentViewModel(it, id) }
    ListeningAssessmentView(vm, onBack, onOpenDoc)
}

@Composable
internal fun ListeningAssessmentView(vm: ListeningAssessmentViewModel, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    AssessmentLifecycle(vm)
    var ending by remember { mutableStateOf(false) }
    var article by rememberSaveable { mutableStateOf<String?>(null) }
    val current = vm.snapshot
    val state = current?.state
    val task = vm.displayTask
    val scroll = key(current?.row?.id, state?.index, state?.phase, vm.detail?.id) { rememberScrollState() }
    val leave: () -> Unit = {
        when {
            article != null -> article = null
            vm.detail != null -> vm.hideDetail()
            current?.result != null -> vm.close(onBack)
            else -> { vm.pause("Left assessment", leave = true); onBack() }
        }
    }
    BackHandler(onBack = leave)
    val body = vm.words[article]
    if (body != null && current != null) {
        Page(requireNotNull(article), { article = null }, scrollable = false) {
            LazyMarkdownView(body, "words/$article.md", { onOpenDoc(current.row.sourceId, it) }, Modifier.weight(1f))
        }
        return
    }
    Page(stringResource(R.string.listening_diagnostic_title), leave, scrollable = false, maxWidth = 1100.dp) {
        CapturedListeningLanguageNotice(vm.capturedLanguage)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val unavailable = vm.error != null || !vm.ready
        if (current?.row?.closedAtMs != null) {
            Text(stringResource(R.string.listening_assessment_closed))
            Button(onClick = onBack) { Text(stringResource(R.string.listening_assessment_closed_back)) }
        } else if (current?.result != null && vm.detail == null) {
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListeningAssessmentResults(requireNotNull(current.result), vm::showDetail)
                Button(onClick = { vm.close(onBack) }, enabled = !vm.busy) { Text(stringResource(R.string.listening_done)) }
            }
        } else if (unavailable || current == null || task == null) {
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.error == null) Text(stringResource(R.string.listening_loading_captured_section))
                else {
                    Text(stringResource(R.string.listening_assessment_error_generic), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.listening_technical_not_wrong))
                    OutlinedButton(onClick = vm::retry, enabled = !vm.busy) { Text(stringResource(R.string.listening_retry_captured)) }
                    if (current != null) TextButton(onClick = vm::end, enabled = !vm.busy) {
                        Text(stringResource(R.string.listening_end_partial_results))
                    }
                    TextButton(onClick = { vm.close(onBack, discardUnreadable = true) }, enabled = !vm.busy) {
                        Text(stringResource(R.string.listening_discard_unreadable))
                    }
                }
            }
        } else {
            val hideEvidence = state?.phase in setOf(AssessmentPhase.Ready, AssessmentPhase.Instruction) && !vm.help
            val picture = vm.picture.takeUnless { hideEvidence }
            val evidenceId = requireNotNull(vm.displayRow).id
            val evidence = Modifier.onGloballyPositioned {
                if (!it.boundsInWindow().isEmpty) {
                    vm.evidenceVisible(evidenceId, current.state.generation, current.state.phase, vm.help || vm.detail != null)
                }
            }
            key(evidenceId, current.state.phase) {
                ListeningTaskLayout(picture, scroll, pictureView = { modifier ->
                    picture?.let {
                        ListeningTaskPicture(it, task.visual?.role == "required", task.choiceMode, modifier, evidence,
                            onEnlarge = { vm.pause("Picture enlargement") },
                            enlargementLabel = if (current.result == null) R.string.listening_task_picture_enlarge_interrupt
                                else R.string.listening_task_picture_enlarge)
                    }
                }) {
                    if (vm.detail != null) {
                        current.result?.let { result ->
                            val index = result.responses.indexOfFirst { it.encounterId == vm.detail?.id }
                            if (index >= 0) Text(stringResource(
                                R.string.listening_result_item_type,
                                listeningAssessmentItemLabel(result, index),
                                task.taskType.label(),
                            ))
                        }
                        Text(task.title, style = MaterialTheme.typography.titleLarge)
                        ListeningTaskFeedbackBody(task, current.result?.responses?.find { it.encounterId == vm.detail?.id }?.answer,
                            evidence, vm.words, { vm.pause("Reading captured word"); article = it }, { vm.replay(it) }, !vm.busy && !vm.playing)
                        TextButton(onClick = vm::hideDetail) { Text(stringResource(R.string.listening_back_to_results)) }
                    } else {
                        ListeningAssessmentItem(vm, evidence)
                        TextButton(onClick = { vm.pause("End confirmation"); ending = true }, enabled = !vm.busy) {
                            Text(stringResource(R.string.listening_end_partial_results))
                        }
                    }
                }
            }
        }
    }
    if (ending) AlertDialog(onDismissRequest = { ending = false }, title = { Text(stringResource(R.string.listening_end_section_title)) },
        text = { Text(stringResource(R.string.listening_end_section_message)) },
        confirmButton = { TextButton(onClick = { ending = false; vm.end() }) { Text(stringResource(R.string.listening_end_section)) } },
        dismissButton = { TextButton(onClick = { ending = false }) { Text(stringResource(R.string.listening_keep_interrupted_session)) } })
}

@Composable
private fun ListeningAssessmentItem(vm: ListeningAssessmentViewModel, evidence: Modifier) {
    val current = requireNotNull(vm.snapshot)
    val state = current.state
    val task = current.record.task
    val example = task.usage == TaskUsage.Example
    Text(task.taskType.label(), style = MaterialTheme.typography.titleLarge)
    Text(if (example) stringResource(R.string.listening_worked_example_unscored_score) else
        stringResource(
            R.string.listening_scored_item_progress,
            current.form.items.take(state.index).count { !it.example } + 1,
            current.form.items.count { !it.example },
        ))
    if (state.paused) {
        val interruptions = listeningInterruptionList(state.interruptions)
        Text(stringResource(R.string.listening_assessment_paused, interruptions))
        Button(onClick = vm::continuePractice, enabled = !vm.busy && !vm.playing) {
            Text(stringResource(R.string.listening_continue_interrupted_practice))
        }
        OutlinedButton(onClick = vm::showHelp, enabled = !vm.busy && !vm.playing) {
            Text(stringResource(R.string.listening_help_transcript_translation))
        }
        OutlinedButton(onClick = { vm.replay(state.stageIndex, slower = true) }, enabled = !vm.busy && !vm.playing) {
            Text(stringResource(R.string.listening_replay_stage_slower_help))
        }
    } else if (state.phase == AssessmentPhase.Ready) {
        Text(stringResource(R.string.listening_fixed_form_begin))
        Button(onClick = vm::begin, enabled = !vm.busy) { Text(stringResource(R.string.listening_begin_section)) }
    }
    when (state.phase) {
        AssessmentPhase.Instruction -> task.stages.first().turns.forEach { id -> Text(task.turns.single { it.id == id }.text) }
        AssessmentPhase.Preview -> Text(stringResource(R.string.listening_preview_time, ((state.remainingMs ?: 0) + 999) / 1_000))
        AssessmentPhase.Audio -> Text(stringResource(R.string.listening_item_audio, task.stages[state.stageIndex].label(task)))
        AssessmentPhase.Answer -> Text(stringResource(R.string.listening_answer_window, ((state.remainingMs ?: 0) + 999) / 1_000))
        AssessmentPhase.ExampleFeedback -> {
            Text(stringResource(R.string.listening_example_answer_time, ((state.remainingMs ?: 0) + 999) / 1_000))
            val correct = task.options.single { it.id == task.answer }
            Text(stringResource(R.string.listening_correct_answer_numbered, correct.number, correct.label))
            Text(task.explanation, Modifier.onGloballyPositioned {
                if (!it.boundsInWindow().isEmpty) {
                    vm.evidenceVisible(current.encounter.id, state.generation, state.phase, textHelp = true, explanation = true)
                }
            })
        }
        else -> Unit
    }
    if (state.phase !in setOf(AssessmentPhase.Ready, AssessmentPhase.Instruction, AssessmentPhase.ExampleFeedback)) {
        ListeningTaskChoices(task, state.answer, state.phase == AssessmentPhase.Answer && !state.paused && !state.skipped && !vm.busy,
            vm.help, { vm.answer(it) }, evidence)
        if (state.phase == AssessmentPhase.Answer) {
            if (state.skipped) Text(stringResource(R.string.listening_skipped_locked))
            else OutlinedButton(onClick = { vm.answer(null) }, enabled = !state.paused && !vm.busy) {
                Text(stringResource(R.string.listening_skip_lock))
            }
        }
    }
    if (!state.paused && state.phase != AssessmentPhase.Ready) {
        Button(onClick = { vm.pause() }, enabled = !vm.busy) { Text(stringResource(R.string.listening_pause_interrupts)) }
    }
    Text(stringResource(if (state.interrupted) R.string.listening_interrupted_never_full else R.string.listening_single_play_diagnostic))
    Text(stringResource(
        R.string.listening_assessment_source_detail,
        current.form.jlpt,
        current.row.sourceId,
        current.row.branch,
    ), style = MaterialTheme.typography.bodySmall)
    if (vm.help) {
        task.visual?.let { Text(it.description, evidence) }
        ListeningTaskTranscript(task, evidence, { vm.replay(it) }, !vm.busy && !vm.playing)
    }
}

@Composable
internal fun ListeningAssessmentResults(result: AssessmentResult, detail: (Int) -> Unit) {
    Text(stringResource(if (result.completedFullForm) R.string.listening_assessment_full_completed
        else R.string.listening_assessment_partial_interrupted), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.listening_assessment_level_blueprint, result.jlpt, result.blueprint), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.listening_assessment_correct_assessed, result.correct, result.assessed), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(
        R.string.listening_assessment_counts,
        result.unanswered,
        result.notAssessed,
        result.planned,
        result.examples,
    ))
    Text(stringResource(R.string.listening_assessment_no_srs))
    Text(stringResource(R.string.listening_no_official_score))
    if (result.interrupted) {
        val interruptions = listeningInterruptionList(result.interruptions)
        Text(stringResource(R.string.listening_interruption_list, interruptions))
    }
    ListeningTaskType.entries.forEach { type ->
        val responses = result.scored.filter { it.type == type }
        Text(stringResource(
            R.string.listening_assessment_type_score,
            type.label(),
            responses.count { it.outcome == AssessmentOutcome.Correct },
            responses.count { it.outcome != AssessmentOutcome.NotAssessed },
        ))
    }
    result.responses.forEachIndexed { index, response ->
        HorizontalDivider()
        val outcome = stringResource(if (response.outcome == AssessmentOutcome.NotAssessed) R.string.listening_not_assessed else
            if (response.outcome == AssessmentOutcome.Correct) R.string.listening_result_correct else R.string.listening_result_incorrect)
        Text(stringResource(R.string.listening_result_item_type_outcome, listeningAssessmentItemLabel(result, index), response.type.label(), outcome))
        val familiar = stringResource(if (response.familiar) R.string.listening_familiar_at_capture else R.string.listening_unseen_at_capture)
        val exposure = stringResource(if (response.exposed) R.string.listening_exposed_during_section else R.string.listening_not_exposed_during_section)
        val picture = stringResource(if (response.visual) R.string.listening_picture_yes else R.string.listening_picture_no)
        Text(stringResource(
            R.string.listening_result_familiar_exposed_visual,
            familiar, exposure, picture,
        ))
        if (response.assistance.isNotEmpty()) {
            Text(stringResource(R.string.listening_help_used, listeningAssistanceList(response.assistance)))
        }
        if (response.interrupted) Text(stringResource(R.string.listening_finalized_interrupted))
        TextButton(onClick = { detail(index) }) { Text(stringResource(R.string.listening_feedback_captured_transcript)) }
    }
}

@Composable
private fun listeningAssessmentItemLabel(result: AssessmentResult, index: Int): String =
    if (result.responses[index].example) stringResource(R.string.listening_result_example_item)
    else stringResource(R.string.listening_result_scored_item, result.responses.take(index).count { !it.example } + 1, result.planned)

@Composable
private fun listeningInterruptionLabel(value: String): String = stringResource(when (value) {
    "App paused or backgrounded" -> R.string.listening_interrupt_app_background
    "Window focus lost" -> R.string.listening_interrupt_window_focus
    "Headphones disconnected" -> R.string.listening_interrupt_headphones
    "Left assessment screen" -> R.string.listening_interrupt_left_screen
    "Left assessment" -> R.string.listening_interrupt_left_screen
    "Picture enlargement" -> R.string.listening_interrupt_picture_enlarged
    "End confirmation" -> R.string.listening_interrupt_end_confirmation
    "Reading captured word" -> R.string.listening_interrupt_read_article
    else -> R.string.listening_interrupt_other
})

@Composable
private fun listeningInterruptionList(values: Iterable<String>): String {
    val labels = mutableListOf<String>()
    for (value in values) labels += listeningInterruptionLabel(value)
    return labels.joinToString()
}

@Composable
private fun AssessmentLifecycle(vm: ListeningAssessmentViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.load() }
    DisposableEffect(vm, lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.pause("App paused or backgrounded", leave = true)
            if (event == Lifecycle.Event.ON_RESUME) vm.load()
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) vm.pause("Window focus lost") }
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { vm.pause("Headphones disconnected") }
        }
        lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(noisy, filter)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            context.unregisterReceiver(noisy)
            vm.pause("Left assessment screen", leave = true)
        }
    }
}
